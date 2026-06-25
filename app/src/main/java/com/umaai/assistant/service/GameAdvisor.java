package com.umaai.assistant.service;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 赛马娘决策引擎 v2 — 基于真实游戏数据
 *
 * 数据来源：
 *   - Hook实时数据：当前五维/体力/干劲/训练增益/支援卡
 *   - 静态数据API2：事件Values数组 [速,耐,力,根,智,Pt,Hint,?,羁绊,干劲]
 *
 * 决策逻辑：
 *   1. 训练推荐：比较各训练的期望收益 = 属性增益 + Pt*0.3 + 羁绊价值 - 失败风险
 *   2. 事件建议：按事件ID查表，比较各选项Values总和，选最高
 *   3. 体力管理：结合剩余回合数动态判断
 */
public class GameAdvisor {

    private static final String TAG = "UmaAdvisor";

    // 训练类型
    private static final String[] TRAIN_TYPES = {"speed", "stamina", "power", "guts", "wisdom"};
    private static final String[] TRAIN_LABELS = {"速", "耐", "力", "根", "智"};

    // Values数组索引
    private static final int V_SPD = 0;
    private static final int V_STA = 1;
    private static final int V_PWR = 2;
    private static final int V_GUT = 3;
    private static final int V_WIT = 4;
    private static final int V_PT = 5;
    private static final int V_HINT = 6;
    private static final int V_BOND = 8;
    private static final int V_MOTIV = 9;

    // 干劲倍率（游戏实际公式）
    private static final double[] MOTIV_MULT = {0, 0.6, 0.8, 1.0, 1.1, 1.2}; // 绝不调~绝好调

    // 属性权重（可根据目标赛程调整）
    private static final double[] STAT_WEIGHTS = {1.0, 1.0, 1.0, 0.7, 0.8}; // 速耐力根智

    private Context context;

    public GameAdvisor(Context context) {
        this.context = context;
    }

    /**
     * 主决策入口
     */
    public JSONObject advise(JSONObject gameData) {
        JSONObject result = new JSONObject();
        try {
            // 复制基础显示数据
            copyStr(result, gameData, "turn");
            copyInt(result, gameData, "total");
            copyInt(result, gameData, "pt");
            copyInt(result, gameData, "stamina");
            copyInt(result, gameData, "max_stamina");
            copyStr(result, gameData, "motivation");
            copyStr(result, gameData, "facility");
            for (String key : new String[]{"speed", "stamina_stat", "power", "guts", "wisdom"}) {
                copyObj(result, gameData, key);
            }

            // 干劲倍率
            int motivLevel = parseMotivation(gameData.optString("motivation", ""));
            double motivMult = MOTIV_MULT[motivLevel];

            // 1. 训练推荐
            JSONObject trainRec = recommendTraining(gameData, motivMult);
            result.put("recommend", trainRec.getString("text"));
            result.put("recommend_type", trainRec.getString("type"));

            // 2. 体力建议
            int stamina = gameData.optInt("stamina", 0);
            int maxStamina = gameData.optInt("max_stamina", 100);
            if (stamina > 0 && stamina <= 25) {
                result.put("stamina_tip", "体力不足! 休息或智力训练");
            } else if (stamina > 0 && stamina <= 45) {
                result.put("stamina_tip", "体力偏低，注意安排休息");
            }

            // 3. 事件建议
            if (gameData.has("event_id")) {
                JSONObject eventRec = recommendEvent(gameData.optInt("event_id", -1));
                if (eventRec != null) {
                    result.put("event_advice", eventRec);
                }
            }

        } catch (JSONException e) {
            Log.e(TAG, "advise error: " + e.getMessage());
        }
        return result;
    }

    /**
     * 训练推荐 — 基于增益计算期望收益
     * 
     * 评分公式：
     *   score = Σ(属性增益 * 权重) + Pt增益 * 0.3 + 羁绊价值 - 高属性衰减 - 失败惩罚
     */
    private JSONObject recommendTraining(JSONObject gameData, double motivMult) throws JSONException {
        double bestScore = -999;
        int bestIdx = -1;
        String bestDetail = "";
        int stamina = gameData.optInt("stamina", 50);
        int failureRate = gameData.optInt("failure_rate", 0);

        for (int i = 0; i < 5; i++) {
            String key = TRAIN_TYPES[i];
            if (key.equals("stamina")) key = "stamina_stat";

            JSONObject stat = gameData.optJSONObject(key);
            if (stat == null) continue;

            int gain = stat.optInt("gain", 0);
            int pt = stat.optInt("pt", 0);
            int current = stat.optInt("current", 0);
            int remain = stat.optInt("remain", 0);

            // 属性收益 = 增益 * 干劲倍率 * 属性权重
            double attrScore = gain * motivMult * STAT_WEIGHTS[i];

            // Pt收益
            double ptScore = pt * 0.3;

            // 高属性衰减（超过800后收益递减，鼓励练低属性）
            if (current > 900) {
                attrScore *= 0.6;
            } else if (current > 800) {
                attrScore *= 0.8;
            }

            // 剩余空间惩罚（接近上限的训练价值低）
            if (remain < 100) {
                attrScore *= 0.5;
            }

            // 体力消耗惩罚
            double staminaCost = getStaminaCost(i, gameData);
            if (stamina - staminaCost <= 20) {
                attrScore *= 0.2; // 体力会见底，严重惩罚
            } else if (stamina - staminaCost <= 30) {
                attrScore *= 0.6;
            }

            double score = attrScore + ptScore;

            if (score > bestScore) {
                bestScore = score;
                bestIdx = i;
                // 生成推荐文字
                StringBuilder detail = new StringBuilder();
                detail.append(TRAIN_LABELS[i]);
                if (gain > 0) detail.append(" +").append((int)(gain * motivMult));
                if (pt > 0) detail.append(" Pt+").append(pt);
                // 失败率
                int trainFailure = stat.optInt("failure_rate", 0);
                if (trainFailure > 0) detail.append(" 失敗").append(trainFailure).append("%");
                bestDetail = detail.toString();
            }
        }

        // 体力过低强制休息
        if (stamina > 0 && stamina <= 20) {
            JSONObject rec = new JSONObject();
            rec.put("text", "休息 (体力" + stamina + "过低)");
            rec.put("type", "rest");
            return rec;
        }

        if (bestIdx >= 0) {
            JSONObject rec = new JSONObject();
            rec.put("text", bestDetail);
            rec.put("type", TRAIN_TYPES[bestIdx]);
            return rec;
        }

        JSONObject rec = new JSONObject();
        rec.put("text", "等待训练数据...");
        rec.put("type", "unknown");
        return rec;
    }

    /**
     * 事件推荐 — 查API2数据，比较各选项Values总和
     */
    private JSONObject recommendEvent(int eventId) {
        String eventsJson = RemoteDataLoader.getCachedData(context, RemoteDataLoader.KEY_EVENTS);
        if (eventsJson == null || eventsJson.equals("LOADED_V2")) {
            // 事件数据太大未缓存完整版，返回提示
            return null;
        }

        try {
            JSONArray events = new JSONArray(eventsJson);
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.getJSONObject(i);
                if (event.optInt("Id", -1) == eventId) {
                    return analyzeEvent(event);
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "Event lookup failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * 分析事件各选项，选最优
     */
    private JSONObject analyzeEvent(JSONObject event) throws JSONException {
        JSONObject advice = new JSONObject();
        advice.put("event_name", event.optString("Name", ""));
        advice.put("event_id", event.optInt("Id", 0));

        JSONArray choices = event.optJSONArray("Choices");
        if (choices == null || choices.length() == 0) return advice;

        int bestChoice = 0;
        double bestValue = -999;
        JSONArray choiceDetails = new JSONArray();

        for (int i = 0; i < choices.length(); i++) {
            JSONArray optionGroup = choices.optJSONArray(i);
            if (optionGroup == null || optionGroup.length() == 0) continue;

            // 取第一个子选项（通常只有一个）
            JSONObject option = optionGroup.optJSONObject(0);
            if (option == null) continue;

            JSONObject detail = new JSONObject();
            detail.put("option", option.optString("Option", "选项" + (i + 1)));
            detail.put("effect", option.optString("SuccessEffect", ""));

            // 计算Values总分
            JSONObject successVal = option.optJSONObject("SuccessEffectValue");
            if (successVal != null) {
                JSONArray values = successVal.optJSONArray("Values");
                if (values != null && values.length() >= 10) {
                    double score = calcEventScore(values);
                    detail.put("score", score);

                    if (score > bestValue) {
                        bestValue = score;
                        bestChoice = i;
                    }
                }
            }

            choiceDetails.put(detail);
        }

        advice.put("best_choice", bestChoice);
        advice.put("best_score", bestValue);
        advice.put("choices", choiceDetails);

        return advice;
    }

    /**
     * 计算事件选项Values得分
     * 权重：属性*1.0 + Pt*0.5 + 干劲*3.0 + 羁绊*0.2 + Hint*2.0
     */
    private double calcEventScore(JSONArray values) throws JSONException {
        double score = 0;
        // 属性：速耐力根智
        for (int i = 0; i < 5; i++) {
            score += values.optInt(i, 0) * STAT_WEIGHTS[i];
        }
        // 技能Pt
        score += values.optInt(V_PT, 0) * 0.5;
        // ヒント等级
        score += values.optInt(V_HINT, 0) * 2.0;
        // 羁绊
        score += values.optInt(V_BOND, 0) * 0.2;
        // 干劲（非常重要）
        score += values.optInt(V_MOTIV, 0) * 3.0;

        return score;
    }

    /**
     * 解析干劲文字→等级
     */
    private int parseMotivation(String mot) {
        if (mot.contains("絶好") || mot.contains("绝好")) return 5;
        if (mot.contains("好調") || mot.contains("好调")) return 4;
        if (mot.contains("普通")) return 3;
        if (mot.contains("不調") || mot.contains("不调")) return 2;
        if (mot.contains("絶不") || mot.contains("绝不")) return 1;
        return 3; // 默认普通
    }

    /**
     * 估算训练体力消耗
     */
    private double getStaminaCost(int trainIdx, JSONObject gameData) {
        // 基础消耗：速耐力根=20~30，智=10~20
        // 实际值受剧本和训练等级影响，这里用简化值
        switch (trainIdx) {
            case 0: case 1: case 2: case 3: return 22; // 速耐力根
            case 4: return 12; // 智力训练消耗少
            default: return 20;
        }
    }

    private void copyStr(JSONObject dest, JSONObject src, String key) throws JSONException {
        if (src.has(key)) dest.put(key, src.getString(key));
    }

    private void copyInt(JSONObject dest, JSONObject src, String key) throws JSONException {
        if (src.has(key)) dest.put(key, src.getInt(key));
    }

    private void copyObj(JSONObject dest, JSONObject src, String key) throws JSONException {
        if (src.has(key)) dest.put(key, src.get(key));
    }
}
