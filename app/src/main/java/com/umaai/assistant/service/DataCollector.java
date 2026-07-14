package com.umaai.assistant.service;
import com.umaai.assistant.BuildConfig;

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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 赛马娘育成数据收集器 v2.0
 *
 * v2.0 修复清单（2026-07-14）：
 *   1. parseSnapshot 对 /summary error JSON 做防御，不再抛异常
 *   2. detectAction 在 gains 全0时不再跳过，改用 command_id 直接映射
 *   3. 上传改为单线程串行队列，不再每回合开新线程
 *   4. 上传时冻结 session 快照，不再读取可变的 sessionId/scenario/turns
 *   5. startNewSession 不再被 finalizeAndUpload 间接重复调用
 *   6. 恢复时持久化 turns/prevSnapshot/scenario 的 JSON 序列化
 *   7. 文件名使用 session_id，与内部 session_id 保持一致
 *   8. shining/heads 的 -1/null/0 统一为 0 语义
 *   9. 对 summary 错误、回合跳跃和数据断档做显式标记
 */
public class DataCollector {

    private static final String TAG = "DataCollector";
    private static final String PREFS_NAME = "uma_data_collector";
    private static final String KEY_SESSION_ID = "session_id";
    private static final String KEY_TURN_COUNT = "turn_count";
    private static final String KEY_SCENARIO = "scenario";
    private static final String KEY_TURNS_JSON = "turns_json";
    private static final String KEY_PREV_SNAPSHOT = "prev_snapshot";

    // GitHub upload config
    private static final String GITHUB_REPO = "xf8410/uma-data";
    private static final String GITHUB_BRANCH = "main";
    private static final String GITHUB_PATH = "training_sessions";
    private static final String GITHUB_TOKEN = BuildConfig.GITHUB_TOKEN;

    // 行动类型
    public static final String ACTION_SPEED = "Speed";
    public static final String ACTION_STAMINA = "Stamina";
    public static final String ACTION_POWER = "Power";
    public static final String ACTION_GUTS = "Guts";
    public static final String ACTION_WISDOM = "Wisdom";
    public static final String ACTION_REST = "Rest";
    public static final String ACTION_OUTING = "Outing";
    public static final String ACTION_UNKNOWN = "Unknown";
    public static final String ACTION_ERROR = "Error"; // ★ v2.0: summary 错误标记

    private Context context;
    private String sessionId;
    private String scenario;
    private List<TurnSnapshot> turns = new ArrayList<>();
    private TurnSnapshot prevSnapshot = null;
    private boolean isFinalizing = false; // ★ v2.0: 防重复 startNewSession

    // ★ v2.0: 单线程上传队列
    private final Object uploadLock = new Object();
    private AtomicBoolean uploadInProgress = new AtomicBoolean(false);
    private String lastUploadedSha = null; // 缓存上次 GET 的 SHA，避免每次都 GET

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
        scenario = prefs.getString(KEY_SCENARIO, null);

        if (sessionId != null && savedTurnCount > 0) {
            // ★ v2.0: 恢复 turns 和 prevSnapshot
            String turnsJson = prefs.getString(KEY_TURNS_JSON, null);
            if (turnsJson != null) {
                try {
                    JSONArray arr = new JSONArray(turnsJson);
                    for (int i = 0; i < arr.length(); i++) {
                        turns.add(TurnSnapshot.fromJson(arr.getJSONObject(i)));
                    }
                    Log.d(TAG, "Restored " + turns.size() + " turns from persistence");
                } catch (JSONException e) {
                    Log.e(TAG, "Restore turns failed: " + e.getMessage());
                }
            }
            String prevJson = prefs.getString(KEY_PREV_SNAPSHOT, null);
            if (prevJson != null) {
                try {
                    prevSnapshot = TurnSnapshot.fromJson(new JSONObject(prevJson));
                } catch (JSONException e) {
                    Log.e(TAG, "Restore prevSnapshot failed: " + e.getMessage());
                }
            }
            Log.d(TAG, "Restored session: " + sessionId + " turns: " + turns.size()
                + " scenario: " + scenario);
        } else {
            startNewSession();
        }
    }

    /** 开始新育成局 */
    public void startNewSession() {
        // ★ v2.0: 防止 finalizeAndUpload → startNewSession → 又触发 finalizeAndUpload
        if (isFinalizing) {
            Log.d(TAG, "skip startNewSession: finalizing in progress");
            return;
        }
        sessionId = UUID.randomUUID().toString().substring(0, 8);
        scenario = null;
        turns.clear();
        prevSnapshot = null;
        lastUploadedSha = null;
        Log.d(TAG, "New session: " + sessionId);

        persistState();
    }

    /** ★ v2.0: 持久化完整状态 */
    private void persistState() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = prefs.edit();
        ed.putString(KEY_SESSION_ID, sessionId);
        ed.putInt(KEY_TURN_COUNT, turns.size());
        ed.putString(KEY_SCENARIO, scenario);

        // 序列化 turns
        try {
            JSONArray arr = new JSONArray();
            for (TurnSnapshot t : turns) {
                arr.put(t.toJson());
            }
            ed.putString(KEY_TURNS_JSON, arr.toString());
        } catch (Exception e) {
            Log.e(TAG, "Persist turns failed: " + e.getMessage());
        }

        // 序列化 prevSnapshot
        if (prevSnapshot != null) {
            try {
                ed.putString(KEY_PREV_SNAPSHOT, prevSnapshot.toJson().toString());
            } catch (Exception e) {
                Log.e(TAG, "Persist prevSnapshot failed: " + e.getMessage());
            }
        } else {
            ed.remove(KEY_PREV_SNAPSHOT);
        }

        ed.apply();
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
    // ★ v2.3: 去重 — 同一 turn 的相同快照不重复处理
    private int lastProcessedTurn = -1;
    private String lastSnapshotHash = "";

    public synchronized String onSummaryData(JSONObject json) {
        // ★ v2.0: 防御 — 检查是否是 error JSON
        if (json == null) return ACTION_ERROR;
        if (json.has("error") || json.has("sigsegv_recovered") || json.has("panic_caught")) {
            Log.w(TAG, "Received error summary, recording gap (not overwriting action)");
            // ★ v2.2: 不覆盖 prevSnapshot.actionTaken — 错误不是玩家动作
            // 只记录数据断档事件
            if (prevSnapshot != null) {
                // 保留上一个真实动作标签不变
                Log.d(TAG, "Summary gap at turn after " + prevSnapshot.turn);
            }
            return ACTION_ERROR;
        }
        if (!json.has("stats")) {
            Log.w(TAG, "Summary missing stats field, skipping");
            return ACTION_ERROR;
        }

        try {
            TurnSnapshot snapshot = parseSnapshot(json);
            if (snapshot == null) return ACTION_ERROR;

            // ★ v2.3: 去重 — 同一 turn 的相同快照不重复处理（push+poll 可能同时到达）
            String snapHash = snapshot.speed + "," + snapshot.stamina + ","
                + snapshot.power + "," + snapshot.guts + "," + snapshot.wisdom
                + "," + snapshot.vital + "," + snapshot.turn;
            if (snapshot.turn == lastProcessedTurn && snapHash.equals(lastSnapshotHash)) {
                Log.d(TAG, "Duplicate summary for turn " + snapshot.turn + ", skipping");
                return ACTION_UNKNOWN;
            }
            lastProcessedTurn = snapshot.turn;
            lastSnapshotHash = snapHash;

            String detectedAction = ACTION_UNKNOWN;

            // ★ v2.2: 新育成检测 — 不能只靠 turn<=1，还要检查 chara_id 变化
            // 跨年时 month=1 half=1 会导致旧逻辑误判为新育成
            boolean isNewSession = false;
            if (prevSnapshot != null) {
                // 条件1: turn 从大跳到小（且不是同 year 的正常流动）
                if (snapshot.turn <= 1 && prevSnapshot.turn > 1) {
                    // 可能是跨年（year 2→3 month 1）或真正新育成
                    // 如果 chara_id 变了 → 确定是新育成
                    if (snapshot.charaId > 0 && prevSnapshot.charaId > 0
                            && snapshot.charaId != prevSnapshot.charaId) {
                        isNewSession = true;
                    } else if (snapshot.charaId > 0 && prevSnapshot.charaId > 0
                            && snapshot.charaId == prevSnapshot.charaId) {
                        // 同角色 → 可能是跨年误判，不结束 session
                        Log.d(TAG, "Turn reset but same chara_id=" + snapshot.charaId
                            + " — likely year transition, not new session");
                    } else {
                        // chara_id 未知 → 保守判断，用 scenario 变化
                        if (snapshot.scenario != null && prevSnapshot.scenario != null
                                && !snapshot.scenario.equals(prevSnapshot.scenario)) {
                            isNewSession = true;
                        }
                        // 否则不结束 session
                    }
                }
            }

            if (isNewSession) {
                if (!isFinalizing) {
                    Log.d(TAG, "Session " + sessionId + " ended (new session detected), uploading...");
                    finalizeAndUpload();
                }
            }

            // ★ v2.0: finalizeAndUpload 可能已经 startNewSession，此时 prevSnapshot 已清
            if (prevSnapshot == null) {
                prevSnapshot = snapshot;
                if (snapshot.scenario != null && !snapshot.scenario.isEmpty()) {
                    scenario = snapshot.scenario;
                }
                persistState();
                return detectedAction;
            }

            // 检测玩家行动
            if (snapshot.turn > prevSnapshot.turn) {
                // ★ v2.2: 检测回合跳跃（非连续）— 不伪造全0回合，只记录 gap
                int turnDelta = snapshot.turn - prevSnapshot.turn;
                if (turnDelta > 1) {
                    Log.w(TAG, "Turn jump: " + prevSnapshot.turn + " → " + snapshot.turn
                        + " (gap=" + (turnDelta - 1) + "), recording gap (not faking turns)");
                }

                // ★ v2.3: 优先使用 SO hook 捕获的真实动作
                JSONObject lastAction = json.optJSONObject("last_action");
                if (lastAction != null) {
                    String soAction = lastAction.optString("action", "");
                    int soCmdId = lastAction.optInt("raw_command_id", -1);
                    int soNormId = lastAction.optInt("normalized_command_id", -1);
                    int soSeq = lastAction.optInt("sequence", 0);
                    if (!soAction.isEmpty() && !"None".equals(soAction) && soCmdId >= 0) {
                        detectedAction = soAction;
                        Log.d(TAG, "Turn " + prevSnapshot.turn + " → " + snapshot.turn
                            + " action: " + detectedAction + " (source=training_hook, seq=" + soSeq
                            + ", cmd_id=" + soCmdId + ")");
                        prevSnapshot.actionTaken = detectedAction;
                        prevSnapshot.actionSource = "training_hook";
                        prevSnapshot.actionConfidence = 1.0;
                        prevSnapshot.actionCommandId = soNormId;
                        prevSnapshot.actionRawCommandId = soCmdId;
                        turns.add(prevSnapshot);
                        uploadCurrentSession(false);
                        // 更新scenario
                        if (snapshot.scenario != null && !snapshot.scenario.isEmpty()) {
                            scenario = snapshot.scenario;
                        }
                        prevSnapshot = snapshot;
                        persistState();
                        return detectedAction;
                    }
                }

                // 回退到推断
                detectedAction = detectAction(prevSnapshot, snapshot);
                Log.d(TAG, "Turn " + prevSnapshot.turn + " → " + snapshot.turn
                    + " action: " + detectedAction + " (source=inference)");

                // 记录上一回合的行动
                prevSnapshot.actionTaken = detectedAction;
                prevSnapshot.actionSource = "stat_delta_inference";
                prevSnapshot.actionConfidence = 0.4;
                turns.add(prevSnapshot);

                // ★ v2.0: 串行上传（不再每回合开新线程）
                uploadCurrentSession(false);
            } else if (snapshot.turn == prevSnapshot.turn) {
                // 同回合内的数据更新
                snapshot.actionTaken = prevSnapshot.actionTaken;
            }

            // 更新scenario
            if (snapshot.scenario != null && !snapshot.scenario.isEmpty()) {
                scenario = snapshot.scenario;
            }

            prevSnapshot = snapshot;
            persistState();

            return detectedAction;

        } catch (Exception e) {
            Log.e(TAG, "onSummaryData error: " + e.getMessage());
            return ACTION_ERROR;
        }
    }

    /**
     * ★ v1.24: 处理插件推送的事件选择数据
     */
    public void onEventData(JSONObject json) {
        try {
            int storyId = json.optInt("story_id", 0);
            int charaId = json.optInt("chara_id", 0);
            if (storyId > 0 || charaId > 0) {
                Log.d(TAG, "Event data: story_id=" + storyId + " chara_id=" + charaId);
                if (prevSnapshot != null) {
                    prevSnapshot.storyId = storyId;
                    prevSnapshot.charaId = charaId;
                }
                if (!turns.isEmpty()) {
                    TurnSnapshot last = turns.get(turns.size() - 1);
                    if (last.storyId == 0) last.storyId = storyId;
                    if (last.charaId == 0) last.charaId = charaId;
                }
                persistState();
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
            // ★ v2.2: 累计 turn 计算 — 如果 SO 提供了 "turn" 字段直接用
            int soTurn = json.optInt("turn", 0);
            if (soTurn > 0) {
                s.turn = soTurn;
            } else {
                // ★ v2.2: 从 year + month + half 计算
                // 赛马娘育成：year 1 从 4 月开始，每年 12 个月 × 2 half = 24 turn
                // year=1 month=4 half=1 → turn 1
                // year=2 month=1 half=1 → turn 19
                // year=3 month=1 half=1 → turn 43
                int year = json.optInt("year", 0);
                if (year > 0) {
                    s.turn = (year - 1) * 24 + (s.month - 1) * 2 + s.half;
                } else {
                    // 无 year 字段 — 旧逻辑兜底，但标记可能跨年错误
                    s.turn = (s.month - 1) * 2 + s.half;
                }
            }
            s.scenario = json.optString("scenario", "");
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
            s.fan = stats.optInt("fan", 0);
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
                        opt.vitalCost = -gains.optInt("HP", 0);
                    }

                    opt.failureRate = tr.optInt("failure_rate", 0);
                    // ★ v2.0: 统一 shining/heads 的 -1/null/0 → 0
                    // ★ v2.3: shining/heads — 保留 -1（未知），不洗成 0
                    int sh = tr.optInt("shining", -1);
                    opt.shining = sh; // -1=unknown, 0=none, >0=count
                    int hd = tr.optInt("heads", -1);
                    opt.heads = hd; // -1=unknown, 0=none, >0=count
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

            // AI推荐
            JSONObject ai = json.optJSONObject("ai");
            if (ai != null) {
                s.aiBest = ai.optString("best", "");
                s.aiScore = ai.optInt("score", 0);
                s.skillEval = ai.optInt("skill_eval", 0);
                s.skillCount = ai.optInt("skill_count", 0);
            }

            // Ramen
            JSONObject ramen = json.optJSONObject("ramen");
            if (ramen != null) {
                s.ramenRaw = ramen.toString();
                JSONArray gaugeGains = ramen.optJSONArray("gauge_gains");
                if (gaugeGains != null && gaugeGains.length() > 0) {
                    s.gaugeGainsRaw = gaugeGains.toString();
                }
            }

            // Support cards
            JSONArray supportCards = json.optJSONArray("support_cards");
            if (supportCards != null && supportCards.length() > 0) {
                s.supportCardsRaw = supportCards.toString();
            }

            // Skills
            JSONObject skills = json.optJSONObject("skills");
            if (skills != null) {
                s.skillsRaw = skills.toString();
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
     * ★ v2.1: 通过 training_levels 变化检测动作（弱辅助信号）
     * ★ v2.3: 降级为辅助 — 设施等级不是每次训练都升级
     * training_levels 格式: [{"command_id":101,"level":3}, ...]
     * 只有恰好一个普通训练等级+1时才作为辅助证据
     * 多个变化或非普通训练变化时返回 null
     */
    private String detectActionByTrainingLevels(TurnSnapshot prev, TurnSnapshot curr) {
        if (prev.trainingLevelsRaw == null || curr.trainingLevelsRaw == null) return null;
        try {
            JSONArray prevLevels = new JSONArray(prev.trainingLevelsRaw);
            JSONArray currLevels = new JSONArray(curr.trainingLevelsRaw);
            // ★ v2.3: 不要求长度相同，按 command_id 建 Map

            java.util.Map<Integer, Integer> prevMap = new java.util.HashMap<>();
            java.util.Map<Integer, Integer> currMap = new java.util.HashMap<>();
            for (int i = 0; i < prevLevels.length(); i++) {
                JSONObject pl = prevLevels.optJSONObject(i);
                if (pl != null) {
                    int cid = pl.optInt("command_id", 0);
                    if (cid > 0) prevMap.put(cid, pl.optInt("level", 0));
                }
            }
            for (int i = 0; i < currLevels.length(); i++) {
                JSONObject cl = currLevels.optJSONObject(i);
                if (cl != null) {
                    int cid = cl.optInt("command_id", 0);
                    if (cid > 0) currMap.put(cid, cl.optInt("level", 0));
                }
            }

            // 找等级变化（只看普通训练 101/102/103/105/106）
            int changedCount = 0;
            String changedAction = null;
            int[] validCmdIds = {101, 102, 103, 105, 106};
            for (int cmdId : validCmdIds) {
                int prevLv = prevMap.getOrDefault(cmdId, 0);
                int currLv = currMap.getOrDefault(cmdId, 0);
                if (currLv > prevLv && currLv - prevLv == 1) {
                    changedCount++;
                    switch (cmdId) {
                        case 101: changedAction = ACTION_SPEED; break;
                        case 102: changedAction = ACTION_STAMINA; break;
                        case 103: changedAction = ACTION_GUTS; break;
                        case 105: changedAction = ACTION_POWER; break;
                        case 106: changedAction = ACTION_WISDOM; break;
                    }
                }
            }
            // ★ v2.3: 恰好一个变化才作为辅助，多个变化返回 null
            if (changedCount == 1) return changedAction;
        } catch (Exception e) {
            Log.w(TAG, "detectActionByTrainingLevels error: " + e.getMessage());
        }
        return null;
    }

    /**
     * 对比前后两个快照，检测玩家采取的行动
     * ★ v2.0: 优先用 command_id 直接映射，gains 全0时不跳过
     */
    private String detectAction(TurnSnapshot prev, TurnSnapshot curr) {
        if (prev.trainings == null) return ACTION_UNKNOWN;

        int dSpeed = curr.speed - prev.speed;
        int dStamina = curr.stamina - prev.stamina;
        int dPower = curr.power - prev.power;
        int dGuts = curr.guts - prev.guts;
        int dWisdom = curr.wisdom - prev.wisdom;
        int dVital = curr.vital - prev.vital;
        int dMotivation = curr.motivation - prev.motivation;

        // 1. 外出：干劲上升
        if (dMotivation > 0 && dVital > -30) {
            return ACTION_OUTING;
        }

        // 2. 休息：体力上升且属性无变化
        if (dVital > 25 && Math.abs(dSpeed) + Math.abs(dStamina) + Math.abs(dPower)
                + Math.abs(dGuts) + Math.abs(dWisdom) < 10) {
            return ACTION_REST;
        }

        // ★ v2.1: 优先用 training_levels 变化检测
        // 训练后等级+1的那个就是选中的训练
        String levelAction = detectActionByTrainingLevels(prev, curr);
        if (levelAction != null) return levelAction;

        // ★ v2.0: 优先用 command_id 映射（如果 prev 快照有训练数据）
        // 找属性变化最大的方向，然后匹配有对应 command_id 的训练
        int[] deltas = {dSpeed, dStamina, dGuts, dPower, dWisdom};
        String[] actions = {ACTION_SPEED, ACTION_STAMINA, ACTION_GUTS, ACTION_POWER, ACTION_WISDOM};
        int[] commandIds = {101, 102, 103, 105, 106}; // ★ 精确映射，无 104

        // 找最大变化方向
        int maxIdx = 0;
        for (int i = 1; i < 5; i++) {
            if (deltas[i] > deltas[maxIdx]) maxIdx = i;
        }

        // 如果最大变化 > 5，且对应的训练选项存在且启用，直接匹配
        if (deltas[maxIdx] > 5) {
            // 检查是否有对应 command_id 的训练选项
            for (TrainingOption opt : prev.trainings) {
                if (!opt.isEnabled) continue;
                if (opt.commandId == commandIds[maxIdx]) {
                    return actions[maxIdx];
                }
            }
        }

        // 3. 余弦匹配（gains 非0时才用）
        String bestMatch = ACTION_UNKNOWN;
        double bestScore = -1;

        for (TrainingOption opt : prev.trainings) {
            if (!opt.isEnabled) continue;

            double dot = opt.gainSpeed * dSpeed
                       + opt.gainStamina * dStamina
                       + opt.gainPower * dPower
                       + opt.gainGuts * dGuts
                       + opt.gainWisdom * dWisdom;

            double predMag = Math.sqrt(opt.gainSpeed * opt.gainSpeed
                                     + opt.gainStamina * opt.gainStamina
                                     + opt.gainPower * opt.gainPower
                                     + opt.gainGuts * opt.gainGuts
                                     + opt.gainWisdom * opt.gainWisdom);

            double actualMag = Math.sqrt(dSpeed * dSpeed + dStamina * dStamina
                                       + dPower * dPower + dGuts * dGuts
                                       + dWisdom * dWisdom);

            // ★ v2.0: predMag < 1 时不跳过，而是用 command_id 兜底
            if (predMag < 1 || actualMag < 1) {
                // gains 全0，用 command_id 直接映射
                if (opt.commandId > 0 && deltas[maxIdx] > 5) {
                    if (opt.commandId == commandIds[maxIdx]) {
                        return actions[maxIdx];
                    }
                }
                continue;
            }

            double cosine = dot / (predMag * actualMag);
            if (cosine > bestScore) {
                bestScore = cosine;
                bestMatch = trainingNameToAction(opt.name);
            }
        }

        if (bestScore > 0.5) {
            return bestMatch;
        }

        // 4. 简单启发式
        int maxDelta = Math.max(Math.max(dSpeed, dStamina),
                       Math.max(Math.max(dPower, dGuts), dWisdom));
        if (maxDelta > 5) {
            if (dSpeed == maxDelta) return ACTION_SPEED;
            if (dStamina == maxDelta) return ACTION_STAMINA;
            if (dPower == maxDelta) return ACTION_POWER;
            if (dGuts == maxDelta) return ACTION_GUTS;
            if (dWisdom == maxDelta) return ACTION_WISDOM;
        }

        // 5. 体力恢复
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
        if (isFinalizing) {
            Log.d(TAG, "finalizeAndUpload already in progress, skipping");
            return;
        }
        isFinalizing = true;

        try {
            if (turns.isEmpty() && prevSnapshot == null) {
                Log.d(TAG, "No turns to upload");
                startNewSession();
                return;
            }

            // 把最后一回合也加进去
            if (prevSnapshot != null && !turns.contains(prevSnapshot)) {
                prevSnapshot.actionTaken = ACTION_UNKNOWN;
                turns.add(prevSnapshot);
            }

            uploadCurrentSession(true);
        } finally {
            isFinalizing = false;
        }
    }

    /**
     * ★ v2.0: 串行上传（不再每回合开新线程）
     */
    private void uploadCurrentSession(boolean isFinal) {
        // ★ v2.0: 冻结快照 — 在线程启动前拷贝所有可变状态
        final String frozenSessionId = sessionId;
        final String frozenScenario = scenario;
        final String frozenTurnsJson;
        final int frozenTurnCount;
        final TurnSnapshot frozenPrev = prevSnapshot;
        final boolean frozenIsFinal = isFinal;

        try {
            JSONObject sessionData = buildSessionJson(isFinal, frozenSessionId, frozenScenario, frozenPrev);
            if (sessionData == null) return;
            frozenTurnsJson = sessionData.toString();
            frozenTurnCount = turns.size();
        } catch (Exception e) {
            Log.e(TAG, "Freeze snapshot failed: " + e.getMessage());
            return;
        }

        // ★ v2.0: 串行队列 — 等上一个上传完成再开下一个
        new Thread(() -> {
            synchronized (uploadLock) {
                // 等待上一个上传完成
                while (uploadInProgress.get()) {
                    try { uploadLock.wait(1000); } catch (InterruptedException e) { return; }
                }
                uploadInProgress.set(true);

                try {
                    uploadToGitHub(frozenTurnsJson, frozenSessionId, frozenScenario,
                        frozenTurnCount, frozenIsFinal);
                } finally {
                    uploadInProgress.set(false);
                    uploadLock.notifyAll();
                }
            }
        }).start();
    }

    /**
     * 构建整局数据JSON
     * ★ v2.0: 使用传入的冻结参数，不读取可变字段
     */
    private JSONObject buildSessionJson(boolean isFinal, String sessId,
            String scen, TurnSnapshot prevSnap) {
        try {
            JSONObject session = new JSONObject();
            session.put("session_id", sessId);
            session.put("schema_version", 3);
            session.put("app_version", "v2.3");
            session.put("app_commit", BuildConfig.VERSION_NAME);
            session.put("scenario", scen != null ? scen : "Unknown");
            session.put("turn_count", turns.size());
            session.put("timestamp", System.currentTimeMillis());
            session.put("started_at", turns.isEmpty() ? System.currentTimeMillis() : turns.get(0).capturedAt);
            session.put("is_final", isFinal);
            session.put("valid", true);
            session.put("mapping_version", "v2.3_102sta_105pow");

            // 回合数据
            JSONArray turnsArr = new JSONArray();
            for (TurnSnapshot t : turns) {
                turnsArr.put(t.toJson());
            }
            // 非final时，把当前进行中的回合也加进去
            if (!isFinal && prevSnap != null) {
                boolean inList = false;
                for (int i = 0; i < turns.size(); i++) {
                    if (turns.get(i) == prevSnap) { inList = true; break; }
                }
                if (!inList) {
                    turnsArr.put(prevSnap.toJson());
                }
            }
            session.put("turns", turnsArr);

            // 最终属性
            TurnSnapshot last = prevSnap;
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
     * ★ v2.0: 上传到 GitHub — 使用传入的冻结参数
     */
    private void uploadToGitHub(String jsonContent, String sessId,
            String scen, int turnCount, boolean isFinal) {
        try {
            String scenarioDir = (scen != null && !scen.isEmpty()) ? scen : "Unknown";
            // ★ v2.0: 文件名使用冻结的 session_id，与内部一致
            String filename = sessId + ".json";
            String path = GITHUB_PATH + "/" + scenarioDir + "/" + filename;
            String apiUrl = "https://api.github.com/repos/" + GITHUB_REPO + "/contents/" + path;

            // ★ v2.0: 只在第一次或 isFinal 时 GET SHA，中间用缓存的 SHA
            String sha = lastUploadedSha;
            if (sha == null || isFinal) {
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
                        lastUploadedSha = sha;
                    }
                    getConn.disconnect();
                } catch (Exception e) {
                    // 文件不存在
                }
            }

            String encoded = Base64.encodeToString(
                jsonContent.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);

            JSONObject body = new JSONObject();
            body.put("message", (isFinal ? "Finalize" : "Update") + " training session " + sessId
                + " (" + turnCount + " turns, " + scenarioDir + ")");
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
                // ★ v2.0: 从响应中提取新 SHA 缓存
                try {
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();
                    JSONObject resp = new JSONObject(sb.toString());
                    JSONObject content = resp.optJSONObject("content");
                    if (content != null) {
                        lastUploadedSha = content.optString("sha", lastUploadedSha);
                    }
                } catch (Exception ignored) {}

                Log.d(TAG, "Upload success: " + filename + " (isFinal=" + isFinal + ")");
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
                // ★ v2.0: 409 Conflict 时清除 SHA 缓存重试
                if (code == 409) {
                    lastUploadedSha = null;
                    Log.w(TAG, "409 conflict, cleared SHA cache, will retry next turn");
                }
            }
            conn.disconnect();

        } catch (Exception e) {
            Log.e(TAG, "Upload error: " + e.getMessage());
        }
    }

    // ========================================================================
    // 数据类
    // ========================================================================

    /** 回合快照 */
    private static class TurnSnapshot {
        int month, half, turn;
        String scenario;
        int charaId;
        int speed, stamina, power, guts, wisdom, skillPt;
        int vital, maxVital;
        int motivation;
        int fan;
        boolean hasBadCondition;
        String charaEffectIds;
        String buffsRaw;
        String trainingLevelsRaw;
        String evaluationRaw;
        String gaugeGainsRaw;
        String supportCardsRaw;
        String skillsRaw;
        String ramenRaw;
        TrainingOption[] trainings;
        String aiBest;
        int aiScore;
        int skillEval;
        int skillCount;

        String actionTaken = "Unknown";
        String actionSource = "unknown";
        double actionConfidence = 0.0;
        int actionCommandId = -1;
        int actionRawCommandId = -1;
        int storyId = 0;
        long capturedAt = System.currentTimeMillis();

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("turn", turn);
            o.put("month", month);
            o.put("half", half);
            if (charaId > 0) o.put("chara_id", charaId);
            if (storyId > 0) o.put("story_id", storyId);

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
            stats.put("fan", fan);
            stats.put("has_bad_condition", hasBadCondition);
            o.put("stats", stats);

            if (trainings != null) {
                JSONArray trArr = new JSONArray();
                for (TrainingOption opt : trainings) {
                    JSONObject tr = new JSONObject();
                    tr.put("name", opt.name);
                    tr.put("command_id", opt.commandId);
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

            if (charaId > 0) o.put("chara_id", charaId);
            if (storyId > 0) o.put("story_id", storyId);

            // ★ v2.3: 动作来源元数据
            o.put("action_taken", actionTaken);
            o.put("action_source", actionSource);
            o.put("action_confidence", actionConfidence);
            o.put("captured_at", capturedAt);
            if (actionCommandId >= 0) o.put("action_command_id", actionCommandId);
            if (actionRawCommandId >= 0) o.put("action_raw_command_id", actionRawCommandId);

            if (aiBest != null && !aiBest.isEmpty()) {
                JSONObject ai = new JSONObject();
                ai.put("best", aiBest);
                ai.put("score", aiScore);
                ai.put("skill_eval", skillEval);
                ai.put("skill_count", skillCount);
                o.put("ai_recommend", ai);
            }

            if (gaugeGainsRaw != null && !gaugeGainsRaw.isEmpty()) {
                o.put("gauge_gains", new JSONArray(gaugeGainsRaw));
            }
            if (supportCardsRaw != null && !supportCardsRaw.isEmpty()) {
                o.put("support_cards", new JSONArray(supportCardsRaw));
            }
            if (skillsRaw != null && !skillsRaw.isEmpty()) {
                o.put("skills", new JSONObject(skillsRaw));
            }
            if (ramenRaw != null && !ramenRaw.isEmpty()) {
                o.put("ramen", new JSONObject(ramenRaw));
            }
            if (trainingLevelsRaw != null && !trainingLevelsRaw.isEmpty()) {
                o.put("training_levels", new JSONArray(trainingLevelsRaw));
            }
            if (buffsRaw != null && !buffsRaw.isEmpty()) {
                o.put("buffs", new JSONArray(buffsRaw));
            }
            if (charaEffectIds != null && !charaEffectIds.isEmpty()) {
                o.put("chara_effect_ids", new JSONArray(charaEffectIds));
            }
            if (evaluationRaw != null && !evaluationRaw.isEmpty()) {
                o.put("evaluation", new JSONArray(evaluationRaw));
            }

            return o;
        }

        /** ★ v2.0: 从 JSON 反序列化（用于恢复持久化状态） */
        static TurnSnapshot fromJson(JSONObject o) throws JSONException {
            TurnSnapshot s = new TurnSnapshot();
            s.turn = o.optInt("turn", 0);
            s.month = o.optInt("month", 0);
            s.half = o.optInt("half", 0);
            s.charaId = o.optInt("chara_id", 0);
            s.storyId = o.optInt("story_id", 0);
            s.actionTaken = o.optString("action_taken", "Unknown");

            JSONObject stats = o.optJSONObject("stats");
            if (stats != null) {
                s.speed = stats.optInt("speed", 0);
                s.stamina = stats.optInt("stamina", 0);
                s.power = stats.optInt("power", 0);
                s.guts = stats.optInt("guts", 0);
                s.wisdom = stats.optInt("wisdom", 0);
                s.skillPt = stats.optInt("skill_pt", 0);
                s.vital = stats.optInt("vital", 0);
                s.maxVital = stats.optInt("max_vital", 0);
                s.motivation = stats.optInt("motivation", 3);
                s.fan = stats.optInt("fan", 0);
                s.hasBadCondition = stats.optBoolean("has_bad_condition", false);
            }
            return s;
        }
    }

    /** 训练选项 */
    private static class TrainingOption {
        String name;
        int commandId;
        boolean isEnabled;
        int gainSpeed, gainStamina, gainPower, gainGuts, gainWisdom;
        int gainSkillPt;
        int vitalCost;
        int failureRate;
        int shining;
        int heads;
    }
}
