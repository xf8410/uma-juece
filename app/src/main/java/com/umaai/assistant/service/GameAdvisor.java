package com.umaai.assistant.service;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 赛马娘决策引擎
 * 结合实时游戏数据（Hook截获）+ 静态游戏数据（远程API缓存）
 * 生成训练推荐和事件建议
 *
 * 决策逻辑：
 * 1. 训练推荐：根据当前五维+体力+支援卡分布，算各训练期望收益
 * 2. 事件建议：匹配事件ID查表，返回最优选项
 * 3. 休息/外出/比赛：根据体力阈值和当前状态判断
 */
public class GameAdvisor {

    private static final String TAG = "UmaAdvisor";

    // 训练类型映射
    private static final String[] TRAIN_TYPES = {"speed", "stamina", "power", "guts", "wisdom"};
    private static final String[] TRAIN_NAMES = {"速度", "耐力", "力量", "根性", "智力"};
    private static final String[] TRAIN_NAMES_JA = {"スピード", "スタミナ", "パワー", "根性", "賢さ"};
    private static final int[] TRAIN_COLORS = {0xFF4488FF, 0xFFFF4444, 0xFFFF8800, 0xFFFF66AA, 0xFFFFDD00};

    // 体力阈值
    private static final int STAMINA_REST_THRESHOLD = 30; // 低于此值建议休息
    private static final int STAMINA_LOW = 50; // 低于此值注意体力

    private Context context;

    public GameAdvisor(Context context) {
        this.context = context;
    }

    /**
     * 根据实时游戏数据生成推荐
     * @param gameData Hook截获的游戏JSON数据
     * @return 推荐结果，可直接推送到小黑板
     */
    public JSONObject advise(JSONObject gameData) {
        JSONObject result = new JSONObject();
        try {
            // 1. 训练推荐
            JSONObject trainRec = recommendTraining(gameData);
            result.put("recommend", trainRec.getString("text"));
            result.put("recommend_type", trainRec.getString("type"));

            // 2. 体力建议
            int stamina = gameData.optInt("stamina", 0);
            int maxStamina = gameData.optInt("max_stamina", 100);
            if (stamina > 0) {
                String staminaTip = getStaminaTip(stamina, maxStamina);
                if (!staminaTip.isEmpty()) {
                    result.put("stamina_tip", staminaTip);
                }
            }

            // 3. 事件建议（如果有事件ID）
            if (gameData.has("event_id")) {
                JSONObject eventRec = recommendEvent(gameData.getInt("event_id"));
                if (eventRec != null) {
                    result.put("event_advice", eventRec);
                }
            }

            // 4. 复制原始数据（小黑板显示用）
            copyIfHas(result, gameData, "turn");
            copyIfHas(result, gameData, "total");
            copyIfHas(result, gameData, "pt");
            copyIfHas(result, gameData, "stamina");
            copyIfHas(result, gameData, "max_stamina");
            copyIfHas(result, gameData, "motivation");
            copyIfHas(result, gameData, "facility");
            for (String key : new String[]{"speed", "stamina_stat", "power", "guts", "wisdom"}) {
                copyIfHas(result, gameData, key);
            }

        } catch (JSONException e) {
            Log.e(TAG, "advise error: " + e.getMessage());
        }
        return result;
    }

    /**
     * 训练推荐逻辑
     * 策略：优先选增益最高的训练，考虑体力消耗
     */
    private JSONObject recommendTraining(JSONObject gameData) throws JSONException {
        double bestScore = -1;
        int bestIdx = -1;
        String bestDetail = "";

        int stamina = gameData.optInt("stamina", 50);

        for (int i = 0; i < 5; i++) {
            String key = TRAIN_TYPES[i];
            if (key.equals("stamina")) key = "stamina_stat"; // JSON key特殊

            JSONObject stat = gameData.optJSONObject(key);
            if (stat == null) continue;

            int gain = stat.optInt("gain", 0);
            int pt = stat.optInt("pt", 0);
            int current = stat.optInt("current", 0);

            // 评分：属性增益*1.0 + Pt增益*0.3 - 当前属性越高收益递减
            double score = gain * 1.0 + pt * 0.3;
            if (current > 800) score *= 0.8; // 高属性训练收益递减

            // 体力不足时惩罚
            if (stamina < STAMINA_REST_THRESHOLD && i != 4) { // 智力训练消耗少
                score *= 0.3;
            }

            if (score > bestScore) {
                bestScore = score;
                bestIdx = i;
                bestDetail = TRAIN_NAMES[i] + " +" + gain + " Pt" + pt;
            }
        }

        // 体力过低建议休息
        if (stamina > 0 && stamina <= STAMINA_REST_THRESHOLD) {
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
        rec.put("text", "数据不足");
        rec.put("type", "unknown");
        return rec;
    }

    /**
     * 体力建议
     */
    private String getStaminaTip(int stamina, int maxStamina) {
        if (stamina <= STAMINA_REST_THRESHOLD) {
            return "体力不足! 建议休息";
        } else if (stamina <= STAMINA_LOW) {
            return "体力偏低，注意安排休息";
        }
        return "";
    }

    /**
     * 事件建议：查静态事件数据
     */
    private JSONObject recommendEvent(int eventId) {
        String eventsJson = RemoteDataLoader.getCachedData(context, RemoteDataLoader.KEY_EVENTS);
        if (eventsJson == null) return null;

        try {
            JSONArray events = new JSONArray(eventsJson);
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.getJSONObject(i);
                if (event.optInt("id", -1) == eventId) {
                    JSONObject advice = new JSONObject();
                    advice.put("event_name", event.optString("name", ""));
                    advice.put("best_choice", event.optString("best_choice", ""));
                    advice.put("choices", event.optJSONArray("choices"));
                    return advice;
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "Event lookup failed: " + e.getMessage());
        }
        return null;
    }

    private void copyIfHas(JSONObject dest, JSONObject src, String key) throws JSONException {
        if (src.has(key)) {
            dest.put(key, src.get(key));
        }
    }
}
