package com.umaai.assistant.service;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 赛马娘训练评分引擎 — 基于umaai-rs手写逻辑的URA通用版
 *
 * 核心评分维度：
 *   1. 属性收益（软上限约束 + 预留空间因子）
 *   2. 体力价值（分段评估）
 *   3. 失败率惩罚
 *   4. 彩圈加成
 *   5. 羁绊价值
 *   6. 没带卡惩罚
 *   7. 训练类型偏好
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

    // ========================================================================
    // 训练名称映射
    // ========================================================================

    private static final String[] TRAIN_KEYS = {"speed", "stamina", "power", "guts", "wisdom"};
    private static final String[] TRAIN_LABELS = {"速", "耐", "力", "根", "智"};
    private static final int[] TRAIN_COLORS = {
        0xFF4FC3F7,  // 速-青
        0xFF66BB6A,  // 耐-绿
        0xFFEF5350,  // 力-赤
        0xFFFFA726,  // 根-橙
        0xFFAB47BC   // 智-紫
    };

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

            double bestScore = Double.NEGATIVE_INFINITY;
            int bestIdx = -1;
            String bestDetail = "";

            for (int i = 0; i < 5; i++) {
                double score = evaluateTraining(summary, stats, trainings, i, vital, maxVital, turn, maxTurn);
                result.allScores[i] = score;

                if (score > bestScore) {
                    bestScore = score;
                    bestIdx = i;
                    bestDetail = buildDetail(summary, trainings, i);
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
                                     int turn, int maxTurn) throws JSONException {
        double score = 0.0;

        JSONObject trData = getTrainingData(trainings, trainIdx);
        if (trData == null) {
            return -500.0;
        }

        JSONObject gains = trData.optJSONObject("gains");
        if (gains == null || gains.length() == 0) {
            return -500.0;
        }

        // --- 1. 属性收益（软上限约束）---
        int[] currentStats = new int[5];
        currentStats[0] = stats.optInt("speed", 0);
        currentStats[1] = stats.optInt("stamina", 0);
        currentStats[2] = stats.optInt("power", 0);
        currentStats[3] = stats.optInt("guts", 0);
        currentStats[4] = stats.optInt("wisdom", 0);

        int[] gainStats = new int[5];
        gainStats[0] = gains.optInt("speed", 0);
        gainStats[1] = gains.optInt("stamina", 0);
        gainStats[2] = gains.optInt("power", 0);
        gainStats[3] = gains.optInt("guts", 0);
        gainStats[4] = gains.optInt("wisdom", 0);
        int ptGain = gains.optInt("skill_point", 0);

        score = calcStatusGain(currentStats, gainStats, ptGain, turn, maxTurn, trainIdx);

        // --- 2. 体力价值评估 ---
        double vitalFactor = calcVitalFactor(turn, maxTurn);
        double vitalBefore = vitalEvaluation(vital, maxVital);
        int staminaCost = estimateStaminaCost(trainIdx, gains);
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
        int friendHeads = trData.optInt("friend_heads", 0);
        if (heads > 0) {
            score += heads * JIBAN_VALUE;
            score += friendHeads * 40.0;
        }

        // --- 6. 没带卡惩罚 ---
        boolean hasCard = trData.optBoolean("has_card", true);
        if (!hasCard) {
            score += -100.0;
        }

        // --- 7. 训练类型偏好 ---
        score += TRAIN_TYPE_BONUS[trainIdx];

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

    private int estimateStaminaCost(int trainIdx, JSONObject gains) {
        if (gains != null) {
            int vitalChange = gains.optInt("vital", 0);
            if (vitalChange < 0) {
                return -vitalChange;
            }
        }
        switch (trainIdx) {
            case 0: case 1: case 2: case 3: return 22;
            case 4: return 12;
            default: return 20;
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

    private String buildDetail(JSONObject summary, JSONArray trainings, int trainIdx) {
        StringBuilder sb = new StringBuilder(TRAIN_LABELS[trainIdx]);
        JSONObject trData = getTrainingData(trainings, trainIdx);
        if (trData != null) {
            JSONObject gains = trData.optJSONObject("gains");
            if (gains != null) {
                String[] gainKeys = {"speed", "stamina", "power", "guts", "wisdom", "skill_point"};
                String[] gainLabels = {"速", "耐", "力", "根", "智", "Pt"};
                for (int j = 0; j < gainKeys.length; j++) {
                    int v = gains.optInt(gainKeys[j], 0);
                    if (v > 0) {
                        sb.append(" ").append(gainLabels[j]).append("+").append(v);
                    }
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
        }
        return sb.toString();
    }
}
