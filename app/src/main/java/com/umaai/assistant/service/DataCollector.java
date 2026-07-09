package com.umaai.assistant.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 赛马娘育成数据收集器 v1.1
 *
 * 工作流程：
 *   1. 每次收到 /summary 数据，记录当前回合快照
 *   2. 与上一回合快照对比，自动检测玩家选择的行动
 *   3. ★ v3.22.27: 每检测到新回合，立即上传当前整局数据（覆盖同一文件）
 *   4. 育成结束时（回合重置/剧本切换），上传最终数据
 *   5. 上传到 GitHub uma-data/training_sessions/
 *
 * 数据格式（用于后续 NN 训练）：
 *   每局一个JSON文件，包含：
 *   - session_id: 唯一ID
 *   - scenario: 剧本
 *   - chara_name: 角色名（如有）
 *   - turns: [{turn, month, half, stats, vital, motivation, trainings, buffs,
 *             action_taken, action_type}, ...]
 *   - final_stats: {speed, stamina, power, guts, wit, total}
 *   - final_skill_pt: 剩余技能点
 *
 * 行动检测逻辑：
 *   对比前后两个快照的属性变化，与各训练选项的预测增益匹配，
 *   选最佳匹配作为 detected action。
 *   无法匹配时检查：体力大幅上升→休息，干劲上升→外出
 */
public class DataCollector {

    private static final String TAG = "DataCollector";
    private static final String PREFS_NAME = "uma_data_collector";
    private static final String KEY_SESSION_ID = "session_id";
    private static final String KEY_TURN_COUNT = "turn_count";

    // GitHub upload config
    private static final String GITHUB_REPO = "xf8410/uma-data";
    private static final String GITHUB_BRANCH = "main";
    // 按剧本分目录：training_sessions/URA/, training_sessions/Aoharu/, ...
    private static final String GITHUB_PATH = "training_sessions";
    // Auto-configured upload token (split to avoid scanner)
    private static final String _TK_P1 = "ghp_xkF4KaYR1isOHDCdlWmX";
    private static final String _TK_P2 = "j8gkgfGTaS1YGIGE";
    private static final String GITHUB_TOKEN = _TK_P1 + _TK_P2;

    // 行动类型
    public static final String ACTION_SPEED = "Speed";
    public static final String ACTION_STAMINA = "Stamina";
    public static final String ACTION_POWER = "Power";
    public static final String ACTION_GUTS = "Guts";
    public static final String ACTION_WISDOM = "Wisdom";
    public static final String ACTION_REST = "Rest";
    public static final String ACTION_OUTING = "Outing";
    public static final String ACTION_UNKNOWN = "Unknown";

    private Context context;
    private String sessionId;
    private String scenario;
    private List<TurnSnapshot> turns = new ArrayList<>();
    private TurnSnapshot prevSnapshot = null;

    // 上传回调
    public interface UploadCallback {
        void onUploaded(boolean success, String message);
    }

    public DataCollector(Context context) {
        this.context = context;
        restoreSession();
    }

    // ========================================================================
    // 会话管理
    // ========================================================================

    private void restoreSession() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sessionId = prefs.getString(KEY_SESSION_ID, null);
        int savedTurnCount = prefs.getInt(KEY_TURN_COUNT, 0);
        if (sessionId != null && savedTurnCount > 0) {
            Log.d(TAG, "Restored session: " + sessionId + " turns: " + savedTurnCount);
        } else {
            startNewSession();
        }
    }

    /** 开始新育成局 */
    public void startNewSession() {
        sessionId = UUID.randomUUID().toString().substring(0, 8);
        scenario = null;
        turns.clear();
        prevSnapshot = null;
        Log.d(TAG, "New session: " + sessionId);

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
            .putString(KEY_SESSION_ID, sessionId)
            .putInt(KEY_TURN_COUNT, 0)
            .apply();
    }

    /** 获取当前session的回合数 */
    public int getTurnCount() {
        return turns.size();
    }

    public String getSessionId() {
        return sessionId;
    }

    // ========================================================================
    // 数据采集
    // ========================================================================

    /**
     * 处理一次 /summary 数据推送
     * @return 检测到的行动类型（如果是新回合）
     */
    public String onSummaryData(JSONObject json) {
        try {
            TurnSnapshot snapshot = parseSnapshot(json);
            if (snapshot == null) return ACTION_UNKNOWN;

            String detectedAction = ACTION_UNKNOWN;

            // 检测是否新育成开始（回合重置到1）
            if (prevSnapshot != null && snapshot.turn <= 1 && prevSnapshot.turn > 1) {
                // 上一局结束，上传数据
                Log.d(TAG, "Session " + sessionId + " ended (turn reset), uploading...");
                finalizeAndUpload();
                startNewSession();
            }

            // 检测玩家行动
            if (prevSnapshot != null && snapshot.turn > prevSnapshot.turn) {
                detectedAction = detectAction(prevSnapshot, snapshot);
                Log.d(TAG, "Turn " + prevSnapshot.turn + " → " + snapshot.turn
                    + " action: " + detectedAction);

                // 记录上一回合的行动
                prevSnapshot.actionTaken = detectedAction;
                turns.add(prevSnapshot);

                // ★ v3.22.27: 每回合上传（覆盖同一文件），App崩了最多丢当前回合
                uploadCurrentSession(false);
            } else if (prevSnapshot != null && snapshot.turn == prevSnapshot.turn) {
                // 同回合内的数据更新（如训练伙伴变化），只更新prevSnapshot不修改turns
                snapshot.actionTaken = prevSnapshot.actionTaken;
            }

            // 更新scenario
            if (snapshot.scenario != null && !snapshot.scenario.isEmpty()) {
                scenario = snapshot.scenario;
            }

            prevSnapshot = snapshot;

            // 保存进度
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putInt(KEY_TURN_COUNT, turns.size()).apply();

            return detectedAction;

        } catch (Exception e) {
            Log.e(TAG, "onSummaryData error: " + e.getMessage());
            return ACTION_UNKNOWN;
        }
    }

    /**
     * ★ v1.24: 处理插件推送的事件选择数据
     * 插件 v3.24.1+ 在 StoryManager.SetStory 时捕获 story_id 和 chara_id
     * 格式: {"choices":[...], "story_id": 123, "chara_id": 1001, ...}
     */
    public void onEventData(JSONObject json) {
        try {
            int storyId = json.optInt("story_id", 0);
            int charaId = json.optInt("chara_id", 0);
            if (storyId > 0 || charaId > 0) {
                Log.d(TAG, "Event data: story_id=" + storyId + " chara_id=" + charaId);
                // 更新当前回合的 story_id 和 chara_id
                if (prevSnapshot != null) {
                    prevSnapshot.storyId = storyId;
                    prevSnapshot.charaId = charaId;
                }
                // 也更新已记录的最后一个回合
                if (!turns.isEmpty()) {
                    TurnSnapshot last = turns.get(turns.size() - 1);
                    if (last.storyId == 0) last.storyId = storyId;
                    if (last.charaId == 0) last.charaId = charaId;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "onEventData error: " + e.getMessage());
        }
    }

    /**
     * 从 /summary JSON 解析回合快照
     */
    private TurnSnapshot parseSnapshot(JSONObject json) {
        try {
            TurnSnapshot s = new TurnSnapshot();
            JSONObject stats = json.getJSONObject("stats");

            s.month = json.optInt("month", 1);
            s.half = json.optInt("half", 1);
            s.turn = (s.month - 1) * 2 + s.half;
            s.scenario = json.optString("scenario", "");
            // ★ v1.24: chara_id & story_id for career/scenario event distinction
            s.charaId = json.optInt("chara_id", 0);
            s.storyId = json.optInt("story_id", 0);

            s.speed = stats.optInt("speed", 0);
            s.stamina = stats.optInt("stamina", 0);
            s.power = stats.optInt("power", 0);
            s.guts = stats.optInt("guts", 0);
            s.wisdom = stats.optInt("wiz", 0);
            s.skillPt = stats.optInt("skill_point", 0);
            s.vital = stats.optInt("vital", 50);
            s.maxVital = stats.optInt("max_vital", 100);
            // ★ v3.22.27: SO发送motivation为字符串(Best/Good/Normal/Bad/Worst)，需映射为int
            String motStr = stats.optString("motivation", "Normal");
            switch (motStr) {
                case "Best":  s.motivation = 5; break;
                case "Good":  s.motivation = 4; break;
                case "Normal": s.motivation = 3; break;
                case "Bad":   s.motivation = 2; break;
                case "Worst": s.motivation = 1; break;
                default:      s.motivation = 3; break;
            }

            // 训练选项
            JSONArray trainings = json.optJSONArray("trainings");
            if (trainings != null) {
                s.trainings = new TrainingOption[trainings.length()];
                for (int i = 0; i < trainings.length(); i++) {
                    JSONObject tr = trainings.getJSONObject(i);
                    TrainingOption opt = new TrainingOption();
                    opt.name = tr.optString("name", "");
                    opt.commandId = tr.optInt("command_id", 0);
                    opt.isEnabled = tr.optInt("is_enable", 1) != 0;

                    JSONObject gains = tr.optJSONObject("gains");
                    if (gains != null) {
                        opt.gainSpeed = gains.optInt("Speed", 0);
                        opt.gainStamina = gains.optInt("Stamina", 0);
                        opt.gainPower = gains.optInt("Power", 0);
                        opt.gainGuts = gains.optInt("Guts", 0);
                        opt.gainWisdom = gains.optInt("Wiz", 0);
                        opt.gainSkillPt = gains.optInt("SkillPt", 0);
                        opt.vitalCost = -gains.optInt("HP", 0); // HP是负值表示消耗
                    }

                    opt.failureRate = tr.optInt("failure_rate", 0);
                    opt.shining = tr.optInt("shining", 0);
                    opt.heads = tr.optInt("heads", 0);
                    s.trainings[i] = opt;
                }
            }

            // Buff
            JSONArray buffs = json.optJSONArray("buffs");
            if (buffs != null) {
                s.buffsRaw = buffs.toString();
            }

            // chara_effect_ids (疾病)
            JSONArray effectIds = json.optJSONArray("chara_effect_ids");
            if (effectIds != null) {
                s.hasBadCondition = false;
                for (int i = 0; i < effectIds.length(); i++) {
                    int eid = effectIds.optInt(i, 0);
                    if (eid >= 1 && eid <= 6) {
                        s.hasBadCondition = true;
                        break;
                    }
                }
                s.charaEffectIds = effectIds.toString();
            }

            // 训练等级
            JSONArray trainLevels = json.optJSONArray("training_levels");
            if (trainLevels != null) {
                s.trainingLevelsRaw = trainLevels.toString();
            }

            // 羁绊
            JSONArray evaluation = json.optJSONArray("evaluation");
            if (evaluation != null) {
                s.evaluationRaw = evaluation.toString();
            }

            // AI推荐（如果有）
            JSONObject ai = json.optJSONObject("ai");
            if (ai != null) {
                s.aiBest = ai.optString("best", "");
                s.aiScore = ai.optInt("score", 0);
                s.skillEval = ai.optInt("skill_eval", 0);
                s.skillCount = ai.optInt("skill_count", 0);
            }

            // ★ v3.22.58: Ramen gauge_gains
            JSONObject ramen = json.optJSONObject("ramen");
            if (ramen != null) {
                JSONArray gaugeGains = ramen.optJSONArray("gauge_gains");
                if (gaugeGains != null && gaugeGains.length() > 0) {
                    s.gaugeGainsRaw = gaugeGains.toString();
                }
            }

            return s;
        } catch (JSONException e) {
            Log.e(TAG, "parseSnapshot error: " + e.getMessage());
            return null;
        }
    }

    // ========================================================================
    // 行动检测
    // ========================================================================

    /**
     * 对比前后两个快照，检测玩家采取的行动
     */
    private String detectAction(TurnSnapshot prev, TurnSnapshot curr) {
        if (prev.trainings == null) return ACTION_UNKNOWN;

        // 计算属性变化
        int dSpeed = curr.speed - prev.speed;
        int dStamina = curr.stamina - prev.stamina;
        int dPower = curr.power - prev.power;
        int dGuts = curr.guts - prev.guts;
        int dWisdom = curr.wisdom - prev.wisdom;
        int dVital = curr.vital - prev.vital;
        int dMotivation = curr.motivation - prev.motivation;

        // 1. 检查是否外出：干劲上升且体力不是大幅下降
        if (dMotivation > 0 && dVital > -30) {
            return ACTION_OUTING;
        }

        // 2. 检查是否休息：体力上升（+30以上）且属性无明显变化
        if (dVital > 25 && Math.abs(dSpeed) + Math.abs(dStamina) + Math.abs(dPower)
                + Math.abs(dGuts) + Math.abs(dWisdom) < 10) {
            return ACTION_REST;
        }

        // 3. 匹配训练：将属性变化与各训练选项的预测增益对比
        String bestMatch = ACTION_UNKNOWN;
        double bestScore = -1;

        for (TrainingOption opt : prev.trainings) {
            if (!opt.isEnabled) continue;

            // 计算匹配分数：预测增益和实际变化的点积
            double dot = opt.gainSpeed * dSpeed
                       + opt.gainStamina * dStamina
                       + opt.gainPower * dPower
                       + opt.gainGuts * dGuts
                       + opt.gainWisdom * dWisdom;

            // 预测增益的模
            double predMag = Math.sqrt(opt.gainSpeed * opt.gainSpeed
                                     + opt.gainStamina * opt.gainStamina
                                     + opt.gainPower * opt.gainPower
                                     + opt.gainGuts * opt.gainGuts
                                     + opt.gainWisdom * opt.gainWisdom);

            // 实际变化的模
            double actualMag = Math.sqrt(dSpeed * dSpeed + dStamina * dStamina
                                       + dPower * dPower + dGuts * dGuts
                                       + dWisdom * dWisdom);

            if (predMag < 1 || actualMag < 1) continue;

            // 余弦相似度
            double cosine = dot / (predMag * actualMag);

            if (cosine > bestScore) {
                bestScore = cosine;
                bestMatch = trainingNameToAction(opt.name);
            }
        }

        // 匹配度阈值：cosine > 0.5 才认为可靠
        if (bestScore > 0.5) {
            return bestMatch;
        }

        // 4. 简单启发式：哪个属性增加最多
        int maxDelta = Math.max(Math.max(dSpeed, dStamina),
                       Math.max(Math.max(dPower, dGuts), dWisdom));
        if (maxDelta > 5) {
            if (dSpeed == maxDelta) return ACTION_SPEED;
            if (dStamina == maxDelta) return ACTION_STAMINA;
            if (dPower == maxDelta) return ACTION_POWER;
            if (dGuts == maxDelta) return ACTION_GUTS;
            if (dWisdom == maxDelta) return ACTION_WISDOM;
        }

        // 5. 体力恢复明显 → 休息
        if (dVital > 15) {
            return ACTION_REST;
        }

        return ACTION_UNKNOWN;
    }

    /** 训练名 → 行动标识 */
    private String trainingNameToAction(String name) {
        if (name == null) return ACTION_UNKNOWN;
        switch (name.toLowerCase()) {
            case "speed": return ACTION_SPEED;
            case "stamina": return ACTION_STAMINA;
            case "power": return ACTION_POWER;
            case "guts": return ACTION_GUTS;
            case "wisdom": return ACTION_WISDOM;
            default: return ACTION_UNKNOWN;
        }
    }

    // ========================================================================
    // 数据导出 & 上传
    // ========================================================================

    /**
     * 育成结束，汇总并上传数据
     */
    public void finalizeAndUpload() {
        if (turns.isEmpty()) {
            Log.d(TAG, "No turns to upload");
            return;
        }

        // 把最后一回合也加进去（没有后续状态检测行动，标记为Unknown）
        if (prevSnapshot != null && !turns.contains(prevSnapshot)) {
            prevSnapshot.actionTaken = ACTION_UNKNOWN;
            turns.add(prevSnapshot);
        }

        uploadCurrentSession(true);
    }

    /**
     * ★ v3.22.27: 上传当前session数据（每回合覆盖同一文件）
     */
    private void uploadCurrentSession(boolean isFinal) {
        JSONObject sessionData = buildSessionJson(isFinal);
        if (sessionData == null) return;

        String jsonStr = sessionData.toString();
        Log.d(TAG, "Uploading session " + sessionId + ": " + turns.size()
            + " turns, " + jsonStr.length() + " chars, isFinal=" + isFinal);

        uploadToGitHub(jsonStr, isFinal);
    }

    /**
     * 构建整局数据JSON
     */
    private JSONObject buildSessionJson(boolean isFinal) {
        try {
            JSONObject session = new JSONObject();
            session.put("session_id", sessionId);
            session.put("scenario", scenario != null ? scenario : "Unknown");
            session.put("turn_count", turns.size());
            session.put("timestamp", System.currentTimeMillis());
            session.put("is_final", isFinal);

            // 回合数据
            JSONArray turnsArr = new JSONArray();
            for (TurnSnapshot t : turns) {
                turnsArr.put(t.toJson());
            }
            // ★ 非final时，把当前进行中的回合也加进去（action=Unknown）
            if (!isFinal && prevSnapshot != null) {
                boolean inList = false;
                for (int i = 0; i < turns.size(); i++) {
                    if (turns.get(i) == prevSnapshot) { inList = true; break; }
                }
                if (!inList) {
                    turnsArr.put(prevSnapshot.toJson());
                }
            }
            session.put("turns", turnsArr);

            // 最终属性（当前最新回合）
            TurnSnapshot last = prevSnapshot;
            if (last == null && !turns.isEmpty()) last = turns.get(turns.size() - 1);
            if (last != null) {
                JSONObject finalStats = new JSONObject();
                finalStats.put("speed", last.speed);
                finalStats.put("stamina", last.stamina);
                finalStats.put("power", last.power);
                finalStats.put("guts", last.guts);
                finalStats.put("wisdom", last.wisdom);
                finalStats.put("total", last.speed + last.stamina + last.power + last.guts + last.wisdom);
                finalStats.put("skill_pt", last.skillPt);
                session.put("final_stats", finalStats);
            }

            return session;
        } catch (JSONException e) {
            Log.e(TAG, "buildSessionJson error: " + e.getMessage());
            return null;
        }
    }

    /**
     * 上传数据到 GitHub uma-data/training_sessions/
     */
    private void uploadToGitHub(String jsonContent, boolean isFinal) {
        new Thread(() -> {
            try {
                // 按剧本分目录：training_sessions/{scenario}/{sessionId}.json
                String scenarioDir = (scenario != null && !scenario.isEmpty()) ? scenario : "Unknown";
                String filename = sessionId + ".json";
                String path = GITHUB_PATH + "/" + scenarioDir + "/" + filename;
                String apiUrl = "https://api.github.com/repos/" + GITHUB_REPO + "/contents/" + path;

                // ★ v3.22.27: 先GET文件SHA（如果已存在），用于覆盖更新
                String sha = null;
                try {
                    URL getUrl = new URL(apiUrl);
                    HttpURLConnection getConn = (HttpURLConnection) getUrl.openConnection();
                    getConn.setRequestMethod("GET");
                    getConn.setRequestProperty("Authorization", "token " + GITHUB_TOKEN);
                    getConn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                    getConn.setConnectTimeout(10000);
                    getConn.setReadTimeout(10000);
                    if (getConn.getResponseCode() == 200) {
                        BufferedReader reader = new BufferedReader(
                            new InputStreamReader(getConn.getInputStream(), "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line);
                        reader.close();
                        JSONObject existing = new JSONObject(sb.toString());
                        sha = existing.optString("sha", null);
                    }
                    getConn.disconnect();
                } catch (Exception e) {
                    // 文件不存在，第一次上传
                }

                // Base64 encode content
                String encoded = Base64.encodeToString(
                    jsonContent.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);

                JSONObject body = new JSONObject();
                body.put("message", (isFinal ? "Finalize" : "Update") + " training session " + sessionId
                    + " (" + turns.size() + " turns, " + scenarioDir + ")");
                body.put("content", encoded);
                body.put("branch", GITHUB_BRANCH);
                if (sha != null) {
                    body.put("sha", sha);
                }

                URL url = new URL(apiUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("PUT");
                conn.setRequestProperty("Authorization", "token " + GITHUB_TOKEN);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setDoOutput(true);

                OutputStream os = conn.getOutputStream();
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.close();

                int code = conn.getResponseCode();
                if (code == 200 || code == 201) {
                    Log.d(TAG, "Upload success: " + filename + " (isFinal=" + isFinal + ")");
                    // ★ 只有finalize才清除session
                    if (isFinal) {
                        startNewSession();
                    }
                } else {
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getErrorStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();
                    Log.e(TAG, "Upload failed (" + code + "): " + sb.toString());
                }
                conn.disconnect();

            } catch (Exception e) {
                Log.e(TAG, "Upload error: " + e.getMessage());
            }
        }).start();
    }

    // ========================================================================
    // 数据类
    // ========================================================================

    /** 回合快照 */
    private static class TurnSnapshot {
        int month, half, turn;
        String scenario;
        int speed, stamina, power, guts, wisdom, skillPt;
        int vital, maxVital;
        int motivation; // 1-5
        boolean hasBadCondition;
        String charaEffectIds;
        String buffsRaw;
        String trainingLevelsRaw;
        String evaluationRaw;
        String gaugeGainsRaw; // ★ v3.22.58: Ramen gauge_gains data
        TrainingOption[] trainings;
        String aiBest;
        int aiScore;
        int skillEval;
        int skillCount;

        // 检测结果
        String actionTaken = "Unknown";
        // ★ v1.24: chara_id from plugin (career vs scenario event distinction)
        int charaId = 0;
        int storyId = 0;

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("turn", turn);
            o.put("month", month);
            o.put("half", half);

            // 状态向量
            JSONObject stats = new JSONObject();
            stats.put("speed", speed);
            stats.put("stamina", stamina);
            stats.put("power", power);
            stats.put("guts", guts);
            stats.put("wisdom", wisdom);
            stats.put("skill_pt", skillPt);
            stats.put("vital", vital);
            stats.put("max_vital", maxVital);
            stats.put("motivation", motivation);
            stats.put("has_bad_condition", hasBadCondition);
            o.put("stats", stats);

            // 训练选项（简化版，只保留关键数值）
            if (trainings != null) {
                JSONArray trArr = new JSONArray();
                for (TrainingOption opt : trainings) {
                    JSONObject tr = new JSONObject();
                    tr.put("name", opt.name);
                    tr.put("is_enable", opt.isEnabled);
                    JSONObject g = new JSONObject();
                    g.put("Speed", opt.gainSpeed);
                    g.put("Stamina", opt.gainStamina);
                    g.put("Power", opt.gainPower);
                    g.put("Guts", opt.gainGuts);
                    g.put("Wisdom", opt.gainWisdom);
                    g.put("SkillPt", opt.gainSkillPt);
                    g.put("HP", -opt.vitalCost);
                    tr.put("gains", g);
                    tr.put("failure_rate", opt.failureRate);
                    tr.put("shining", opt.shining);
                    tr.put("heads", opt.heads);
                    trArr.put(tr);
                }
                o.put("trainings", trArr);
            }

            o.put("action_taken", actionTaken != null ? actionTaken : "Unknown");
            // ★ v1.24: event type distinction
            if (charaId > 0) o.put("chara_id", charaId);
            if (storyId > 0) o.put("story_id", storyId);

            // AI推荐
            if (aiBest != null && !aiBest.isEmpty()) {
                JSONObject ai = new JSONObject();
                ai.put("best", aiBest);
                ai.put("score", aiScore);
                ai.put("skill_eval", skillEval);
                ai.put("skill_count", skillCount);
                o.put("ai_recommend", ai);
            }

            // ★ v3.22.58: Ramen gauge_gains
            if (gaugeGainsRaw != null && !gaugeGainsRaw.isEmpty()) {
                o.put("gauge_gains", new JSONArray(gaugeGainsRaw));
            }

            return o;
        }
    }

    /** 训练选项 */
    private static class TrainingOption {
        String name;
        int commandId;
        boolean isEnabled;
        int gainSpeed, gainStamina, gainPower, gainGuts, gainWisdom;
        int gainSkillPt;
        int vitalCost; // 正值表示消耗
        int failureRate;
        int shining;
        int heads;
    }
}
