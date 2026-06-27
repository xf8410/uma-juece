package com.umaai.assistant.service;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * 赛马娘训练评分引擎 v2 — 对应插件v3.13.0全字段版
 *
 * 核心评分维度：
 *   1. 属性收益（软上限约束 + 预留空间因子）
 *   2. 体力价值（分段评估）
 *   3. 失败率惩罚
 *   4. 彩圈加成
 *   5. 羁绊价值（从evaluation数据计算）
 *   6. 没带卡惩罚（从heads判断）
 *   7. 训练类型偏好
 *   8. 训练等级加成（从training_levels数据）
 *   9. 角色状态惩罚（生病等）
 *
 * 不含剧本专属逻辑（温泉挖掘/超回复/友人外出等），
 * 新剧本上线后再加对应分支。
 */
public class TrainingEvaluator {

    private static final String TAG = "TrainEval";

    // ========================================================================
    // 常量 — 来自 umaai-rs handwritten_evaluator.rs
    // ========================================================================

    /** 属性权重 [速, 耐, 力, 根, 智] — 有卡时 */
    private static final double[] STATUS_WEIGHTS = {7.0, 8.0, 8.0, 8.0, 6.0};

    /** 没带卡的属性权重 */
    private static final double ABSENT_WEIGHT = 2.0;

    /** 训练类型偏好 [速训, 耐训, 力训, 根训, 智训] */
    private static final double[] TRAIN_TYPE_BONUS = {20.0, 10.0, 30.0, 30.0, 20.0};

    /** 控属性预留空间因子 */
    private static final double RESERVE_STATUS_FACTOR = 40.0;

    /** 羁绊基础价值 */
    private static final double JIBAN_VALUE = 12.0;

    /** 体力价值因子（游戏开始时） */
    private static final double VITAL_FACTOR_START = 3.5;

    /** 体力价值因子（游戏结束时） */
    private static final double VITAL_FACTOR_END = 2.0;

    /** 小失败惩罚值 */
    private static final double SMALL_FAIL_VALUE = -1500.0;

    /** 大失败惩罚值 */
    private static final double BIG_FAIL_VALUE = -1800.0;

    /** 彩圈加成系数 */
    private static final double SHINING_BONUS = 35.0;

    /** 最终事件预估属性加成 (URA3=45 + 最终事件=30 + URA2=20 + URA1=20) */
    private static final int FINAL_BONUS = 115;

    /** 属性上限（游戏硬上限） */
    private static final int STATUS_CAP = 1200;

    /** 训练等级加成因子（每级加成） */
    private static final double TRAIN_LEVEL_BONUS = 8.0;

    /** 病気/状态惩罚 */
    private static final double STATE_PENALTY = -500.0;

    // ========================================================================
    // 训练名称映射 — 对齐插件v3.13.0输出格式
    // ========================================================================

    /** 插件trainings[].name值（小写英文，用于匹配） */
    private static final String[] TRAIN_KEYS = {"speed", "stamina", "power", "guts", "wisdom"};

    /** 浮窗显示标签 */
    private static final String[] TRAIN_LABELS = {"速", "耐", "力", "根", "智"};

    /** 颜色 */
    private static final int[] TRAIN_COLORS = {
        0xFF4FC3F7,  // 速-青
        0xFF66BB6A,  // 耐-绿
        0xFFEF5350,  // 力-赤
        0xFFFFA726,  // 根-橙
        0xFFAB47BC   // 智-紫
    };

    /**
     * 插件gains键名映射 — 插件输出PascalCase
     * 插件输出: Speed/Stamina/Power/Guts/Wiz/HP/SkillPt/Motivation
     * stats键: speed/stamina/power/guts/wiz/vital/skill_point (lowercase)
     */
    private static final String[] GAIN_STAT_KEYS = {"Speed", "Stamina", "Power", "Guts", "Wiz"};
    private static final String GAIN_HP_KEY = "HP";
    private static final String GAIN_PT_KEY = "SkillPt";

    /** command_id → trainIdx 映射 (101=速,102=耐,103=根,105=力,106=智) */
    private static final Map<Integer, Integer> CMD_TO_IDX = new HashMap<>();
    static {
        CMD_TO_IDX.put(101, 0); // Speed
        CMD_TO_IDX.put(102, 1); // Stamina
        CMD_TO_IDX.put(103, 3); // Guts
        CMD_TO_IDX.put(105, 2); // Power
        CMD_TO_IDX.put(106, 4); // Wisdom
    }

    // ========================================================================
    // 评分结果
    // ========================================================================

    public static class EvalResult {
        public String bestType;
        public String bestLabel;
        public String bestDetail;
        public int bestColor;
        public double bestScore;
        public double[] allScores;
    }

    // ========================================================================
    // 主评分入口
    // ========================================================================

    public EvalResult evaluate(JSONObject summary) {
        EvalResult result = new EvalResult();
        result.allScores = new double[5];

        try {
            JSONObject stats = summary.getJSONObject("stats");
            JSONArray trainings = summary.optJSONArray("trainings");

            int vital = stats.optInt("vital", 50);
            int maxVital = stats.optInt("max_vital", 100);
            int month = summary.optInt("month", 1);
            int half = summary.optInt("half", 1);
            int turn = (month - 1) * 2 + half;
            int maxTurn = 12;
            int state = stats.optInt("state", 0);

            // 解析训练等级
            Map<Integer, Integer> trainLevels = parseTrainingLevels(summary.optJSONArray("training_levels"));

            // 解析羁绊评价
            double avgEvaluation = calcAvgEvaluation(summary.optJSONArray("evaluation"));

            double bestScore = Double.NEGATIVE_INFINITY;
            int bestIdx = -1;
            String bestDetail = "";

            for (int i = 0; i < 5; i++) {
                double score = evaluateTraining(summary, stats, trainings, i,
                        vital, maxVital, turn, maxTurn, state,
                        trainLevels, avgEvaluation);
                result.allScores[i] = score;

                if (score > bestScore) {
                    bestScore = score;
                    bestIdx = i;
                    bestDetail = buildDetail(summary, trainings, i, trainLevels);
                }
            }

            // 体力极低强制休息
            if (vital > 0 && vital <= 20) {
                result.bestType = "rest";
                result.bestLabel = "休息";
                result.bestDetail = "体力" + vital + "过低";
                result.bestColor = 0xFFFF4444;
                result.bestScore = 0;
                return result;
            }

            // 生病时建议休息（但不强制）
            if (state != 0 && bestScore < -300) {
                result.bestType = "rest";
                result.bestLabel = "休息";
                result.bestDetail = "状态不佳建议休息";
                result.bestColor = 0xFFFF4444;
                result.bestScore = 0;
                return result;
            }

            if (bestIdx >= 0) {
                result.bestType = TRAIN_KEYS[bestIdx];
                result.bestLabel = TRAIN_LABELS[bestIdx];
                result.bestDetail = bestDetail;
                result.bestColor = TRAIN_COLORS[bestIdx];
                result.bestScore = bestScore;
            } else {
                result.bestType = "unknown";
                result.bestLabel = "?";
                result.bestDetail = "等待训练数据";
                result.bestColor = 0xFF888888;
                result.bestScore = 0;
            }

        } catch (JSONException e) {
            Log.e(TAG, "evaluate error: " + e.getMessage());
            result.bestType = "error";
            result.bestLabel = "!";
            result.bestDetail = e.getMessage();
            result.bestColor = 0xFFFF4444;
            result.bestScore = 0;
        }

        return result;
    }

    // ========================================================================
    // 单个训练评分
    // ========================================================================

    private double evaluateTraining(JSONObject summary, JSONObject stats,
                                     JSONArray trainings, int trainIdx,
                                     int vital, int maxVital,
                                     int turn, int maxTurn, int state,
                                     Map<Integer, Integer> trainLevels,
                                     double avgEvaluation) throws JSONException {
        double score = 0.0;

        JSONObject trData = getTrainingData(trainings, trainIdx);
        if (trData == null) {
            return -500.0;
        }

        // ★ is_enable检查：训练不可用时跳过
        int isEnable = trData.optInt("is_enable", 1);
        if (isEnable == 0) {
            return -9999.0;
        }

        JSONObject gains = trData.optJSONObject("gains");
        if (gains == null || gains.length() == 0) {
            return -500.0;
        }

        // --- 0. 角色状态惩罚（生病等） ---
        if (state != 0) {
            score += STATE_PENALTY;
        }

        // --- 1. 属性收益（软上限约束）---
        int[] currentStats = new int[5];
        currentStats[0] = stats.optInt("speed", 0);
        currentStats[1] = stats.optInt("stamina", 0);
        currentStats[2] = stats.optInt("power", 0);
        currentStats[3] = stats.optInt("guts", 0);
        currentStats[4] = stats.optInt("wisdom", 0);

        int[] gainStats = new int[5];
        for (int i = 0; i < 5; i++) {
            gainStats[i] = gains.optInt(GAIN_STAT_KEYS[i], 0);
        }
        int ptGain = gains.optInt(GAIN_PT_KEY, 0);

        score = calcStatusGain(currentStats, gainStats, ptGain, turn, maxTurn, trainIdx);

        // --- 2. 体力价值评估 ---
        double vitalFactor = calcVitalFactor(turn, maxTurn);
        double vitalBefore = vitalEvaluation(vital, maxVital);
        int staminaCost = readStaminaCost(gains);
        int vitalAfter = Math.min(maxVital, Math.max(0, vital - staminaCost));
        double vitalAfterValue = vitalEvaluation(vitalAfter, maxVital);
        score += vitalFactor * (vitalAfterValue - vitalBefore);

        // --- 3. 失败率惩罚 ---
        int failRate = trData.optInt("failure_rate", 0);
        if (failRate > 0) {
            double bigFailProb = (failRate < 20) ? 0.0 : (double) failRate;
            double failValueAvg = 0.01 * bigFailProb * BIG_FAIL_VALUE
                    + (1.0 - 0.01 * bigFailProb) * SMALL_FAIL_VALUE;
            score = 0.01 * failRate * failValueAvg + (1.0 - 0.01 * failRate) * score;
        }

        // --- 4. 彩圈加成 ---
        int shiningCount = trData.optInt("shining", 0);
        if (shiningCount > 0) {
            score *= 1.0 + shiningCount * 0.15;
            score += shiningCount * SHINING_BONUS;
        }

        // --- 5. 羁绊价值 ---
        int heads = trData.optInt("heads", 0);
        if (heads > 0) {
            score += heads * JIBAN_VALUE;
            // 用平均羁绊值加成（羁绊越高价值越大）
            if (avgEvaluation > 0) {
                score += heads * (avgEvaluation / 100.0) * 8.0;
            }
        }

        // --- 6. 没带卡惩罚 ---
        if (heads == 0) {
            score += -100.0;
        }

        // --- 7. 训练类型偏好 ---
        score += TRAIN_TYPE_BONUS[trainIdx];

        // --- 8. 训练等级加成 ---
        int level = getTrainLevel(trainLevels, trainIdx);
        if (level > 1) {
            score += (level - 1) * TRAIN_LEVEL_BONUS;
        }

        return score;
    }

    // ========================================================================
    // 属性收益计算（软上限约束）
    // ========================================================================

    private double calcStatusGain(int[] currentStats, int[] gainStats, int ptGain,
                                   int turn, int maxTurn, int trainIdx) {
        int remainTurn = Math.max(0, maxTurn - turn - 1);
        double totalTurn = (double) maxTurn;
        double reserve = RESERVE_STATUS_FACTOR * remainTurn * (1.0 - remainTurn / (totalTurn * 2.0));

        double total = 0.0;

        for (int i = 0; i < 5; i++) {
            int limit = STATUS_CAP;
            int remain = limit - currentStats[i] - FINAL_BONUS;
            int gain = gainStats[i];

            double s0 = statusSoftFunction(-remain, reserve);
            double s1 = statusSoftFunction(gain - remain, reserve);

            double weight;
            if (i == trainIdx && gainStats[i] == 0) {
                weight = ABSENT_WEIGHT;
            } else {
                weight = STATUS_WEIGHTS[i];
            }

            total += weight * (s1 - s0);
        }

        total += ptGain * 0.5;

        return total;
    }

    /**
     * 属性上限软约束函数
     */
    private double statusSoftFunction(double x, double reserve) {
        if (reserve <= 0.0) {
            return Math.min(x, 0.0);
        }
        if (x >= 0.0) {
            return 0.0;
        } else if (x > -reserve) {
            return -x * x / (2.0 * reserve);
        } else {
            return x + 0.25 * reserve;
        }
    }

    // ========================================================================
    // 体力评估
    // ========================================================================

    private double vitalEvaluation(int vital, int maxVital) {
        if (vital <= 50) {
            return 2.0 * vital;
        } else if (vital <= 70) {
            return 1.5 * (vital - 50) + vitalEvaluation(50, maxVital);
        } else if (vital <= maxVital) {
            return 1.0 * (vital - 70) + vitalEvaluation(70, maxVital);
        } else {
            return vitalEvaluation(maxVital, maxVital);
        }
    }

    private double calcVitalFactor(int turn, int maxTurn) {
        return VITAL_FACTOR_START + ((double) turn / maxTurn) * (VITAL_FACTOR_END - VITAL_FACTOR_START);
    }

    /**
     * 从gains读取消耗体力 — 插件键名为"HP"，负值表示消耗
     */
    private int readStaminaCost(JSONObject gains) {
        if (gains != null) {
            int hpChange = gains.optInt(GAIN_HP_KEY, 0);
            if (hpChange < 0) {
                return -hpChange;
            }
        }
        return 20; // fallback
    }

    // ========================================================================
    // 新字段解析
    // ========================================================================

    /**
     * 解析training_levels数组 → Map<command_id, level>
     */
    private Map<Integer, Integer> parseTrainingLevels(JSONArray trainingLevels) {
        Map<Integer, Integer> map = new HashMap<>();
        if (trainingLevels == null) return map;
        try {
            for (int i = 0; i < trainingLevels.length(); i++) {
                JSONObject tl = trainingLevels.getJSONObject(i);
                int cmdId = tl.optInt("command_id", 0);
                int level = tl.optInt("level", 0);
                if (cmdId > 0) {
                    map.put(cmdId, level);
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "parseTrainingLevels error: " + e.getMessage());
        }
        return map;
    }

    /**
     * 获取指定训练的等级
     */
    private int getTrainLevel(Map<Integer, Integer> trainLevels, int trainIdx) {
        // trainIdx → command_id 反查
        int cmdId = 0;
        for (Map.Entry<Integer, Integer> e : CMD_TO_IDX.entrySet()) {
            if (e.getValue() == trainIdx) {
                cmdId = e.getKey();
                break;
            }
        }
        return trainLevels.getOrDefault(cmdId, 1);
    }

    /**
     * 计算平均羁绊评价
     */
    private double calcAvgEvaluation(JSONArray evaluation) {
        if (evaluation == null || evaluation.length() == 0) return 0;
        try {
            double sum = 0;
            int count = 0;
            for (int i = 0; i < evaluation.length(); i++) {
                JSONObject ev = evaluation.getJSONObject(i);
                int eval = ev.optInt("evaluation", 0);
                int isAppear = ev.optInt("is_appear", 0);
                if (isAppear != 0 && eval > 0) {
                    sum += eval;
                    count++;
                }
            }
            return count > 0 ? sum / count : 0;
        } catch (JSONException e) {
            return 0;
        }
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    private JSONObject getTrainingData(JSONArray trainings, int trainIdx) {
        if (trainings == null) return null;
        String targetName = TRAIN_KEYS[trainIdx];
        for (int i = 0; i < trainings.length(); i++) {
            try {
                JSONObject tr = trainings.getJSONObject(i);
                String name = tr.optString("name", "");
                if (name.equalsIgnoreCase(targetName)) {
                    return tr;
                }
            } catch (JSONException e) {
                // skip
            }
        }
        return null;
    }

    private String buildDetail(JSONObject summary, JSONArray trainings, int trainIdx,
                               Map<Integer, Integer> trainLevels) {
        StringBuilder sb = new StringBuilder(TRAIN_LABELS[trainIdx]);
        JSONObject trData = getTrainingData(trainings, trainIdx);
        if (trData != null) {
            // 训练等级
            int level = getTrainLevel(trainLevels, trainIdx);
            if (level > 1) {
                sb.append("Lv").append(level);
            }

            JSONObject gains = trData.optJSONObject("gains");
            if (gains != null) {
                String[] gainKeys = {"Speed", "Stamina", "Power", "Guts", "Wiz"};
                String[] gainLabels = {"速", "耐", "力", "根", "智"};
                for (int j = 0; j < gainKeys.length; j++) {
                    int v = gains.optInt(gainKeys[j], 0);
                    if (v > 0) {
                        sb.append(" ").append(gainLabels[j]).append("+").append(v);
                    }
                }
                int pt = gains.optInt(GAIN_PT_KEY, 0);
                if (pt > 0) {
                    sb.append(" Pt+").append(pt);
                }
            }
            int fail = trData.optInt("failure_rate", 0);
            if (fail > 0) {
                sb.append(" 失敗").append(fail).append("%");
            }
            int shining = trData.optInt("shining", 0);
            if (shining > 0) {
                sb.append(" ★×").append(shining);
            }
            int heads = trData.optInt("heads", 0);
            if (heads > 0) {
                sb.append(" 頭").append(heads);
            }
        }
        return sb.toString();
    }
}
