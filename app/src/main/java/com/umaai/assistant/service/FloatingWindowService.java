package com.umaai.assistant.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.umaai.assistant.MainActivity;
import com.umaai.assistant.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;

/**
 * 小黑板风格浮窗服务 v1.14
 * 黑底+彩色文字，显示插件推送的 /summary 数据
 * 支持剧本切换（Spinner选择+广播通知）
 * 支持剧本buff显示（青・緑・桃 / 新剧本适配）
 *
 * 数据来源：插件 v3.10.0+ 主动 POST /summary JSON 到 18766
 */
public class FloatingWindowService extends Service implements HttpDataService.OnDataListener {

    private static final String TAG = "UmaFloat";
    private static final String CHANNEL_ID = "floating_service_channel";
    private static final int NOTIFICATION_ID = 1001;
    public static final String ACTION_DATA = "com.umaai.assistant.ACTION_DATA";
    public static final String EXTRA_DATA = "data";
    public static final String ACTION_SCENARIO = "com.umaai.assistant.ACTION_SCENARIO";
    public static final String ACTION_UPLOAD_DATA = "com.umaai.assistant.ACTION_UPLOAD_DATA";

    // 五维颜色
    private static final int COLOR_SPD = 0xFF4488FF;
    private static final int COLOR_SPD_DIM = 0xFF6688CC;
    private static final int COLOR_STA = 0xFFFF4444;
    private static final int COLOR_STA_DIM = 0xFFCC6666;
    private static final int COLOR_PWR = 0xFFFF8800;
    private static final int COLOR_PWR_DIM = 0xFFCC8844;
    private static final int COLOR_GUT = 0xFFFF66AA;
    private static final int COLOR_GUT_DIM = 0xFFCC6688;
    private static final int COLOR_WIT = 0xFFFFDD00;
    private static final int COLOR_WIT_DIM = 0xFFCCBB44;
    private static final int COLOR_DEFAULT = 0xFF00FF88;

    // 状态颜色
    private static final int COLOR_STATE_SICK = 0xFFFF4444;
    private static final int COLOR_STATE_WARN = 0xFFFFAA00;

    // Buff颜色
    private static final int COLOR_BUFF_AO = 0xFF66AAFF;    // 青
    private static final int COLOR_BUFF_MIDORI = 0xFF66FF88; // 緑
    private static final int COLOR_BUFF_MOMO = 0xFFFF88AA;   // 桃

    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams params;
    private boolean isViewAdded = false;
    private Handler handler = new Handler(Looper.getMainLooper());

    // 顶部
    private TextView tvTurn, tvTotal, tvStamina, tvMotivation;
    // 推荐
    private TextView tvRecommend;
    // 五维
    private TextView tvSpdVal, tvSpdGain, tvStaVal, tvStaGain;
    private TextView tvPwrVal, tvPwrGain, tvGutVal, tvGutGain;
    private TextView tvWitVal, tvWitGain;
    // 训练等级
    private TextView tvSpdLv, tvStaLv, tvPwrLv, tvGutLv, tvWitLv;
    // 状态指示
    private TextView tvState;
    // AI评估详情
    private TextView tvAiDetail;
    // Buff
    private LinearLayout buffContainer;
    private TextView tvBuffAo, tvBuffMidori, tvBuffMomo;
    private TextView tvBuffDetail;
    private View buffSeparator;
    // 底部
    private TextView tvFacility, tvHookStatus;
    // 剧本标签
    private TextView tvScenarioLabel;

    // HTTP服务
    private HttpDataService httpServer;

    // 拖拽
    private int initialX, initialY;
    private float initialTouchX, initialTouchY;
    private boolean isDragging = false;

    // 数据更新时间
    private long lastDataTime = 0;

    // 当前剧本（来自插件推送）
    private String currentScenario = "";
    // 用户选择的剧本（来自Spinner）
    private String selectedScenario = "URA";

    private BroadcastReceiver dataReceiver;
    private BroadcastReceiver scenarioReceiver;

    // 训练评分引擎
    private TrainingEvaluator evaluator = new TrainingEvaluator();
    private DataCollector dataCollector;

    // 兜底轮询：5秒没收到push就主动去18765拉数据
    private static final long POLL_THRESHOLD_MS = 5000;
    private static final long POLL_INTERVAL_MS = 2000;
    private volatile boolean pollRunning = false;
    private Thread pollThread;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());

        // 读取用户选择的剧本
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        selectedScenario = prefs.getString(MainActivity.KEY_SCENARIO, "URA");
        dataCollector = new DataCollector(this);

        handler.postDelayed(this::createFloatingView, 300);
        registerDataReceiver();
        registerScenarioReceiver();
        startHttpServer();
        startFallbackPoll();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra(EXTRA_DATA)) {
            handleData(intent.getStringExtra(EXTRA_DATA));
        }
        // 从MainActivity传入剧本选择
        if (intent != null && intent.hasExtra("scenario")) {
            selectedScenario = intent.getStringExtra("scenario");
            Log.d(TAG, "Scenario from intent: " + selectedScenario);
            updateScenarioLabel();
        }
        return START_STICKY;
    }

    // ======== HttpDataService.OnDataListener ========
    @Override
    public void onDataReceived(String data) {
        Log.d(TAG, "HTTP data received: " + data);
        handleData(data);
    }

    // ======== 数据处理核心 ========
    private void handleData(String data) {
        if (data == null || data.isEmpty()) return;

        try {
            JSONObject json = new JSONObject(data);

            // ★ /summary format from plugin push (v3.10.0+)
            if (json.has("version") && json.has("stats") && json.has("trainings")) {
                updateFromSummary(json);
                return;
            }

            // Legacy: just show as text
            final String text = data;
            handler.post(() -> {
                if (tvRecommend != null) {
                    tvRecommend.setTextColor(COLOR_DEFAULT);
                    tvRecommend.setText(text);
                }
            });
        } catch (JSONException e) {
            handler.post(() -> {
                if (tvRecommend != null) {
                    tvRecommend.setTextColor(COLOR_DEFAULT);
                    tvRecommend.setText(data);
                }
            });
        }
    }

    /**
     * 解析插件 /summary 格式JSON并更新浮窗
     */
    private void updateFromSummary(JSONObject json) {
        handler.post(() -> {
            try {
                JSONObject stats = json.getJSONObject("stats");
                JSONArray trainings = json.getJSONArray("trainings");
                JSONArray buffs = json.optJSONArray("buffs");
                lastDataTime = System.currentTimeMillis();

                // ★ 数据收集：记录回合快照
                if (dataCollector != null) {
                    String action = dataCollector.onSummaryData(json);
                    // 更新收集状态显示
                    int turnCount = dataCollector.getTurnCount();
                    if (tvHookStatus != null) {
                        tvHookStatus.setText("Push:ON 記録:" + turnCount);
                        tvHookStatus.setTextColor(0xFF00FF88);
                    }
                }

                // 记录当前剧本（来自插件推送）
                currentScenario = json.optString("scenario", "");

                // 回合信息
                int month = json.optInt("month", -1);
                int half = json.optInt("half", -1);
                if (month > 0) {
                    tvTurn.setText(month + "月" + (half == 1 ? "前" : "後"));
                } else {
                    tvTurn.setText(currentScenario.isEmpty() ? "---" : currentScenario);
                }

                // ★ 更新剧本标签
                updateScenarioLabel();

                // 総合 + Pt
                int total = stats.getInt("speed") + stats.getInt("stamina")
                        + stats.getInt("power") + stats.getInt("guts") + stats.getInt("wiz");
                int pt = stats.getInt("skill_point");
                tvTotal.setText("総" + total + " Pt" + pt);

                // 体力
                int vital = stats.getInt("vital");
                int maxVital = stats.getInt("max_vital");
                tvStamina.setText("体" + vital + "/" + maxVital);
                if (vital <= 30) {
                    tvStamina.setTextColor(0xFFFF4444);
                } else if (vital <= 50) {
                    tvStamina.setTextColor(0xFFFFAA00);
                } else {
                    tvStamina.setTextColor(0xFF66CCFF);
                }

                // 干劲
                String mot = stats.getString("motivation");
                // ★ 状态显示（生病等）— 用 chara_effect_ids 判断
                org.json.JSONArray effectIds = json.optJSONArray("chara_effect_ids");
                boolean hasBadCondition = false;
                if (effectIds != null && effectIds.length() > 0) {
                    for (int ei = 0; ei < effectIds.length(); ei++) {
                        int eid = effectIds.optInt(ei, 0);
                        if (eid >= 1 && eid <= 6) { hasBadCondition = true; break; }
                    }
                }
                String stateStr = hasBadCondition ? " ⚠病" : "";
                tvMotivation.setText(mot + stateStr);
                if (hasBadCondition) {
                    tvMotivation.setTextColor(COLOR_STATE_SICK);
                } else if (mot.contains("Best") || mot.contains("Good")) {
                    tvMotivation.setTextColor(0xFFFFCC00);
                } else if (mot.contains("Normal")) {
                    tvMotivation.setTextColor(0xFFAAAAAA);
                } else {
                    tvMotivation.setTextColor(0xFFFF4444);
                }

                // 五维属性 + 该属性的训练增益
                updateStatFromSummary(tvSpdVal, tvSpdGain, stats, trainings,
                        "speed", "Speed", COLOR_SPD, COLOR_SPD_DIM);
                updateStatFromSummary(tvStaVal, tvStaGain, stats, trainings,
                        "stamina", "Stamina", COLOR_STA, COLOR_STA_DIM);
                updateStatFromSummary(tvPwrVal, tvPwrGain, stats, trainings,
                        "power", "Power", COLOR_PWR, COLOR_PWR_DIM);
                updateStatFromSummary(tvGutVal, tvGutGain, stats, trainings,
                        "guts", "Guts", COLOR_GUT, COLOR_GUT_DIM);
                updateStatFromSummary(tvWitVal, tvWitGain, stats, trainings,
                        "wiz", "Wiz", COLOR_WIT, COLOR_WIT_DIM);

                // ★ 训练等级显示
                JSONArray trainLevels = json.optJSONArray("training_levels");
                updateTrainingLevels(trainLevels);

                // ★ 羁绊概要（显示在设施栏）
                JSONArray evaluation = json.optJSONArray("evaluation");
                updateEvaluationInfo(evaluation);

                // 推荐训练 — 优先使用插件AI评估
                JSONObject aiObj = json.optJSONObject("ai");
                if (aiObj != null) {
                    updateAiRecommendation(aiObj);
                } else {
                    // 回退到App端评分引擎（传入剧本信息）
                    if (tvAiDetail != null) tvAiDetail.setVisibility(View.GONE);
                    evaluator.setScenario(selectedScenario);
                    TrainingEvaluator.EvalResult evalResult = evaluator.evaluate(json);
                    if ("rest".equals(evalResult.bestType)) {
                        tvRecommend.setText("▶ 休息 (" + evalResult.bestDetail + ")");
                        tvRecommend.setTextColor(0xFFFF4444);
                    } else if (!"unknown".equals(evalResult.bestType) && !"error".equals(evalResult.bestType)) {
                        tvRecommend.setText("▶ " + evalResult.bestDetail);
                        tvRecommend.setTextColor(evalResult.bestColor);
                    } else {
                        tvRecommend.setText("▶ " + evalResult.bestDetail);
                        tvRecommend.setTextColor(COLOR_DEFAULT);
                    }
                }

                // ★ Buff显示 - 根据剧本分支
                updateBuffs(buffs);

                // 状态
                if (tvHookStatus != null) {
                    tvHookStatus.setText("Push:ON");
                    tvHookStatus.setTextColor(0xFF00FF88);
                }

            } catch (JSONException e) {
                Log.w(TAG, "Summary parse error: " + e.getMessage());
            }
        });
    }

    /**
     * 更新剧本标签显示
     * 优先用用户选择的剧本，插件推送的作为辅助显示
     */
    private void updateScenarioLabel() {
        if (tvScenarioLabel == null) return;
        String label = scenarioIdToLabel(selectedScenario);
        tvScenarioLabel.setText(label);
        tvScenarioLabel.setVisibility(View.VISIBLE);
    }

    /** 剧本ID → 显示名 */
    private String scenarioIdToLabel(String id) {
        if (id == null) return "";
        switch (id) {
            case "URA": return "URA";
            case "Aoharu": return "青春杯";
            case "Climax": return "巅峰杯";
            case "GrandDrive": return "偶像杯";
            case "GrandMasters": return "女神杯";
            case "LArc": return "凯旋门杯";
            case "UAF": return "UAF";
            case "Harvest": return "种田杯";
            case "Mecha": return "赛博杯";
            case "Legends": return "传奇杯";
            case "DesertIsland": return "无人岛杯";
            case "HotSpring": return "温泉杯";
            case "Dreams": return "育马者杯";
            default: return id;
        }
    }

    /**
     * 解析插件AI评估结果并更新浮窗
     */
    private void updateAiRecommendation(JSONObject ai) {
        try {
            int score = ai.optInt("score", 0);
            int totalStats = ai.optInt("total_stats", 0);
            String best = ai.optString("best", "");
            double bestV = ai.optDouble("best_v", 0);
            JSONObject train = ai.optJSONObject("train");
            double restV = ai.optDouble("rest", 0);
            double outgoingV = ai.optDouble("outgoing", 0);

            String bestLabel = aiActionLabel(best);
            if ("Rest".equals(best)) {
                tvRecommend.setText("▶ AI:休息 評価" + score);
                tvRecommend.setTextColor(0xFFFF4444);
            } else if ("Outgoing".equals(best)) {
                tvRecommend.setText("▶ AI:外出 評価" + score);
                tvRecommend.setTextColor(0xFF66CCFF);
            } else {
                tvRecommend.setText("▶ AI:" + bestLabel + " 評価" + score);
                tvRecommend.setTextColor(aiActionColor(best));
            }

            if (tvAiDetail != null) {
                StringBuilder sb = new StringBuilder();
                String[] trainKeys = {"Speed", "Stamina", "Power", "Guts", "Wisdom"};
                String[] trainLabels = {"速", "耐", "力", "根", "智"};
                if (train != null) {
                    for (int i = 0; i < trainKeys.length; i++) {
                        double v = train.optDouble(trainKeys[i], 0);
                        if (i > 0) sb.append(" ");
                        sb.append(trainLabels[i]).append(fmtAiVal(v));
                    }
                }
                sb.append(" | 休").append(fmtAiVal(restV));
                sb.append(" 外").append(fmtAiVal(outgoingV));
                if (totalStats > 0) {
                    sb.append(" 五維").append(totalStats);
                }
                tvAiDetail.setText(sb.toString());
                tvAiDetail.setVisibility(View.VISIBLE);
            }

        } catch (Exception e) {
            Log.w(TAG, "AI parse error: " + e.getMessage());
            tvRecommend.setText("▶ AI解析エラー");
            tvRecommend.setTextColor(0xFFFF4444);
            if (tvAiDetail != null) tvAiDetail.setVisibility(View.GONE);
        }
    }

    private String aiActionLabel(String action) {
        switch (action) {
            case "Speed": return "速";
            case "Stamina": return "耐";
            case "Power": return "力";
            case "Guts": return "根";
            case "Wisdom": return "智";
            case "Rest": return "休息";
            case "Outgoing": return "外出";
            default: return action;
        }
    }

    private int aiActionColor(String action) {
        switch (action) {
            case "Speed": return COLOR_SPD;
            case "Stamina": return COLOR_STA;
            case "Power": return COLOR_PWR;
            case "Guts": return COLOR_GUT;
            case "Wisdom": return COLOR_WIT;
            case "Rest": return 0xFFFF4444;
            case "Outgoing": return 0xFF66CCFF;
            default: return COLOR_DEFAULT;
        }
    }

    private String fmtAiVal(double v) {
        if (Math.abs(v) < 0.05) return "0";
        if (v == Math.floor(v)) {
            return (v >= 0 ? "+" : "") + (int) v;
        }
        return (v >= 0 ? "+" : "") + String.format("%.1f", v);
    }

    /**
     * 更新Buff显示区域
     */
    private void updateBuffs(JSONArray buffs) {
        if (buffs == null || buffs.length() == 0) {
            if (buffContainer != null) buffContainer.setVisibility(View.GONE);
            if (tvBuffDetail != null) tvBuffDetail.setVisibility(View.GONE);
            if (buffSeparator != null) buffSeparator.setVisibility(View.GONE);
            return;
        }

        if (buffContainer != null) buffContainer.setVisibility(View.VISIBLE);
        if (buffSeparator != null) buffSeparator.setVisibility(View.VISIBLE);

        try {
            boolean hasBreedersBuff = false;
            for (int i = 0; i < buffs.length(); i++) {
                if ("Breeders".equals(buffs.getJSONObject(i).optString("type", ""))) {
                    hasBreedersBuff = true;
                    break;
                }
            }
            if (hasBreedersBuff) {
                updateBreedersBuffs(buffs);
            } else {
                updateGenericBuffs(buffs);
            }
        } catch (JSONException e) {
            Log.w(TAG, "Buff parse error: " + e.getMessage());
        }
    }

    private void updateBreedersBuffs(JSONArray buffs) throws JSONException {
        String aoLevel = "-", aoDesc = "";
        String midoriLevel = "-", midoriDesc = "";
        String momoLevel = "-", momoDesc = "";

        for (int i = 0; i < buffs.length(); i++) {
            JSONObject b = buffs.getJSONObject(i);
            String name = b.getString("name");
            int level = b.getInt("level");
            String desc = b.optString("desc", "");

            if ("青".equals(name)) {
                aoLevel = "Lv" + level;
                aoDesc = desc;
            } else if ("緑".equals(name)) {
                midoriLevel = "Lv" + level;
                midoriDesc = desc;
            } else if ("桃".equals(name)) {
                momoLevel = "Lv" + level;
                momoDesc = desc;
            }
        }

        if (tvBuffAo != null) {
            tvBuffAo.setText("青" + aoLevel);
            tvBuffAo.setTextColor(COLOR_BUFF_AO);
        }
        if (tvBuffMidori != null) {
            tvBuffMidori.setText("緑" + midoriLevel);
            tvBuffMidori.setTextColor(COLOR_BUFF_MIDORI);
        }
        if (tvBuffMomo != null) {
            tvBuffMomo.setText("桃" + momoLevel);
            tvBuffMomo.setTextColor(COLOR_BUFF_MOMO);
        }

        if (tvBuffDetail != null) {
            StringBuilder detail = new StringBuilder();
            if (!aoDesc.isEmpty()) detail.append("青:").append(aoDesc).append(" ");
            if (!midoriDesc.isEmpty()) detail.append("緑:").append(midoriDesc).append(" ");
            if (!momoDesc.isEmpty()) detail.append("桃:").append(momoDesc);
            if (detail.length() > 0) {
                tvBuffDetail.setText(detail.toString().trim());
                tvBuffDetail.setVisibility(View.VISIBLE);
            } else {
                tvBuffDetail.setVisibility(View.GONE);
            }
        }
    }

    private void updateGenericBuffs(JSONArray buffs) throws JSONException {
        StringBuilder goodBuffs = new StringBuilder();
        StringBuilder badBuffs = new StringBuilder();
        StringBuilder otherBuffs = new StringBuilder();
        StringBuilder detail = new StringBuilder();

        for (int i = 0; i < buffs.length(); i++) {
            JSONObject b = buffs.getJSONObject(i);
            String name = b.getString("name");
            int level = b.optInt("level", 0);
            String type = b.optString("type", "");
            String desc = b.optString("desc", "");

            String label = level > 0 ? name + "Lv" + level : name;

            if ("Good".equals(type)) {
                if (goodBuffs.length() > 0) goodBuffs.append(" ");
                goodBuffs.append(label);
            } else if ("Bad".equals(type)) {
                if (badBuffs.length() > 0) badBuffs.append(" ");
                badBuffs.append(label);
            } else {
                if (otherBuffs.length() > 0) otherBuffs.append(" ");
                otherBuffs.append(label);
            }

            if (!desc.isEmpty() && !desc.equals(name)) {
                if (detail.length() > 0) detail.append(" ");
                detail.append(name).append(":").append(desc);
            }
        }

        if (tvBuffAo != null) {
            if (goodBuffs.length() > 0) {
                tvBuffAo.setText(goodBuffs.toString());
                tvBuffAo.setTextColor(COLOR_BUFF_AO);
            } else {
                tvBuffAo.setText("");
            }
        }
        if (tvBuffMidori != null) {
            if (otherBuffs.length() > 0) {
                tvBuffMidori.setText(otherBuffs.toString());
                tvBuffMidori.setTextColor(COLOR_BUFF_MIDORI);
            } else {
                tvBuffMidori.setText("");
            }
        }
        if (tvBuffMomo != null) {
            if (badBuffs.length() > 0) {
                tvBuffMomo.setText(badBuffs.toString());
                tvBuffMomo.setTextColor(COLOR_BUFF_MOMO);
            } else {
                tvBuffMomo.setText("");
            }
        }

        if (tvBuffDetail != null) {
            if (detail.length() > 0) {
                tvBuffDetail.setText(detail.toString());
                tvBuffDetail.setVisibility(View.VISIBLE);
            } else {
                tvBuffDetail.setVisibility(View.GONE);
            }
        }
    }

    private static final int[] CMD_ID_MAP = {101, 102, 105, 103, 106};

    private void updateStatFromSummary(TextView tvVal, TextView tvGain,
                                        JSONObject stats, JSONArray trainings,
                                        String statKey, String gainKey,
                                        int brightColor, int dimColor) throws JSONException {
        int current = stats.getInt(statKey);

        int totalGain = 0;
        for (int i = 0; i < trainings.length(); i++) {
            JSONObject tr = trainings.getJSONObject(i);
            JSONObject gains = tr.optJSONObject("gains");
            if (gains != null && gains.has(gainKey)) {
                totalGain += gains.getInt(gainKey);
            }
        }

        tvVal.setText(String.valueOf(current));

        int cmdId = -1;
        if ("Speed".equals(gainKey)) cmdId = 101;
        else if ("Stamina".equals(gainKey)) cmdId = 102;
        else if ("Power".equals(gainKey)) cmdId = 105;
        else if ("Guts".equals(gainKey)) cmdId = 103;
        else if ("Wiz".equals(gainKey)) cmdId = 106;

        int failureRate = -1;
        int heads = -1;
        int shining = -1;
        if (cmdId > 0) {
            for (int i = 0; i < trainings.length(); i++) {
                JSONObject tr = trainings.getJSONObject(i);
                if (tr.optInt("command_id", -1) == cmdId) {
                    failureRate = tr.optInt("failure_rate", -1);
                    heads = tr.optInt("heads", -1);
                    shining = tr.optInt("shining", -1);
                    break;
                }
            }
        }

        StringBuilder gainText = new StringBuilder();
        if (totalGain > 0) {
            gainText.append("+").append(totalGain);
        }
        if (failureRate > 0) {
            gainText.append(" ⚠").append(failureRate).append("%");
        }
        if (heads > 0) {
            gainText.append(" ★").append(heads);
        }
        if (shining > 0) {
            gainText.append(" !!").append(shining);
        }

        if (gainText.length() > 0) {
            tvGain.setText(gainText.toString());
            if (failureRate > 30) {
                tvGain.setTextColor(0xFFFF4444);
            } else if (failureRate > 10) {
                tvGain.setTextColor(0xFFFFAA00);
            } else if (failureRate > 0) {
                tvGain.setTextColor(0xFFCCCC44);
            } else {
                tvGain.setTextColor(brightColor);
            }
        } else {
            tvGain.setText("");
            tvGain.setTextColor(dimColor);
        }
    }

    private void updateTrainingLevels(JSONArray trainingLevels) {
        java.util.Map<Integer, Integer> cmdMap = new java.util.HashMap<>();
        cmdMap.put(101, 0); cmdMap.put(102, 1); cmdMap.put(103, 3);
        cmdMap.put(105, 2); cmdMap.put(106, 4);

        int[] levels = {1, 1, 1, 1, 1};
        TextView[] lvViews = {tvSpdLv, tvStaLv, tvPwrLv, tvGutLv, tvWitLv};

        if (trainingLevels != null) {
            try {
                for (int i = 0; i < trainingLevels.length(); i++) {
                    JSONObject tl = trainingLevels.getJSONObject(i);
                    int cmdId = tl.optInt("command_id", 0);
                    int lv = tl.optInt("level", 1);
                    Integer idx = cmdMap.get(cmdId);
                    if (idx != null && idx < levels.length) {
                        levels[idx] = lv;
                    }
                }
            } catch (JSONException e) {
                Log.w(TAG, "trainingLevels parse error: " + e.getMessage());
            }
        }

        for (int i = 0; i < lvViews.length; i++) {
            if (lvViews[i] != null) {
                if (levels[i] > 1) {
                    lvViews[i].setText("Lv" + levels[i]);
                    lvViews[i].setVisibility(View.VISIBLE);
                } else {
                    lvViews[i].setVisibility(View.GONE);
                }
            }
        }
    }

    private void updateEvaluationInfo(JSONArray evaluation) {
        if (tvFacility == null || evaluation == null || evaluation.length() == 0) return;
        try {
            int maxEval = 0;
            int appearCount = 0;
            for (int i = 0; i < evaluation.length(); i++) {
                JSONObject ev = evaluation.getJSONObject(i);
                int eval = ev.optInt("evaluation", 0);
                int isAppear = ev.optInt("is_appear", 0);
                if (isAppear != 0 && eval > maxEval) {
                    maxEval = eval;
                }
                if (isAppear != 0) appearCount++;
            }
            if (appearCount > 0) {
                String existing = tvFacility.getText().toString();
                String evalStr = "絆" + maxEval;
                tvFacility.setText(existing.isEmpty() ? evalStr : existing + " " + evalStr);
            }
        } catch (JSONException e) {
            Log.w(TAG, "evaluation parse error: " + e.getMessage());
        }
    }

    // ======== HTTP服务器 ========
    private void startHttpServer() {
        try {
            httpServer = new HttpDataService(this);
            httpServer.startServer();
            Log.d(TAG, "HTTP server started on port " + HttpDataService.PORT);
            updateNotification("HTTP:" + HttpDataService.PORT + " | " + scenarioIdToLabel(selectedScenario) + " | 等待插件推送");
        } catch (Exception e) {
            Log.e(TAG, "HTTP server start failed: " + e.getMessage(), e);
        }
    }

    private void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stopServer();
            httpServer = null;
        }
    }

    // ======== 兜底轮询 ========
    private void startFallbackPoll() {
        pollRunning = true;
        pollThread = new Thread(() -> {
            try { Thread.sleep(POLL_THRESHOLD_MS); } catch (InterruptedException e) { return; }
            while (pollRunning) {
                long elapsed = System.currentTimeMillis() - lastDataTime;
                if (elapsed > POLL_THRESHOLD_MS) {
                    String data = httpGet("http://127.0.0.1:18765/summary");
                    if (data != null && !data.isEmpty() && !data.contains("\"error\"")) {
                        Log.d(TAG, "Fallback poll got data");
                        handleData(data);
                    }
                }
                try { Thread.sleep(POLL_INTERVAL_MS); } catch (InterruptedException e) { break; }
            }
        });
        pollThread.setDaemon(true);
        pollThread.setName("FallbackPoll");
        pollThread.start();
    }

    private void stopFallbackPoll() {
        pollRunning = false;
        if (pollThread != null) pollThread.interrupt();
    }

    private String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            int code = conn.getResponseCode();
            if (code == 200) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                return sb.toString();
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ======== 通知栏 ========
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "悬浮窗服务", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("保持悬浮窗运行");
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("赛马娘助手")
                .setContentText("HTTP:18766 | " + scenarioIdToLabel(selectedScenario) + " | 等待插件推送")
                .setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("赛马娘助手")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, notification);
        }
    }

    // ======== 浮窗 ========
    private void createFloatingView() {
        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (windowManager == null) {
                Log.e(TAG, "WindowManager is null");
                return;
            }

            floatingView = LayoutInflater.from(this).inflate(R.layout.floating_window, null);

            // 绑定视图
            tvTurn = floatingView.findViewById(R.id.tv_turn);
            tvTotal = floatingView.findViewById(R.id.tv_total);
            tvStamina = floatingView.findViewById(R.id.tv_stamina);
            tvMotivation = floatingView.findViewById(R.id.tv_motivation);
            tvRecommend = floatingView.findViewById(R.id.tv_recommend);
            tvSpdVal = floatingView.findViewById(R.id.tv_spd_val);
            tvSpdGain = floatingView.findViewById(R.id.tv_spd_gain);
            tvStaVal = floatingView.findViewById(R.id.tv_sta_val);
            tvStaGain = floatingView.findViewById(R.id.tv_sta_gain);
            tvPwrVal = floatingView.findViewById(R.id.tv_pwr_val);
            tvPwrGain = floatingView.findViewById(R.id.tv_pwr_gain);
            tvGutVal = floatingView.findViewById(R.id.tv_gut_val);
            tvGutGain = floatingView.findViewById(R.id.tv_gut_gain);
            tvWitVal = floatingView.findViewById(R.id.tv_wit_val);
            tvWitGain = floatingView.findViewById(R.id.tv_wit_gain);
            tvFacility = floatingView.findViewById(R.id.tv_facility);
            tvHookStatus = floatingView.findViewById(R.id.tv_hook_status);
            // 剧本标签
            tvScenarioLabel = floatingView.findViewById(R.id.tv_scenario_label);

            // 训练等级视图
            tvSpdLv = floatingView.findViewById(R.id.tv_spd_lv);
            tvStaLv = floatingView.findViewById(R.id.tv_sta_lv);
            tvPwrLv = floatingView.findViewById(R.id.tv_pwr_lv);
            tvGutLv = floatingView.findViewById(R.id.tv_gut_lv);
            tvWitLv = floatingView.findViewById(R.id.tv_wit_lv);
            // 状态指示
            tvState = floatingView.findViewById(R.id.tv_state);
            // AI评估详情
            tvAiDetail = floatingView.findViewById(R.id.tv_ai_detail);
            // Buff视图
            buffContainer = floatingView.findViewById(R.id.buff_container);
            tvBuffAo = floatingView.findViewById(R.id.tv_buff_ao);
            tvBuffMidori = floatingView.findViewById(R.id.tv_buff_midori);
            tvBuffMomo = floatingView.findViewById(R.id.tv_buff_momo);
            tvBuffDetail = floatingView.findViewById(R.id.tv_buff_detail);
            buffSeparator = floatingView.findViewById(R.id.buff_separator);

            // ★ 初始化剧本标签
            updateScenarioLabel();

            int layoutFlag;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
            }

            params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutFlag,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 20;
            params.y = 100;

            setupDrag();
            windowManager.addView(floatingView, params);
            isViewAdded = true;
            Log.d(TAG, "Floating view added");

            View btnClose = floatingView.findViewById(R.id.btn_close);
            if (btnClose != null) {
                btnClose.setOnClickListener(v -> {
                    Log.d(TAG, "Close button clicked");
                    stopSelf();
                });
            }

        } catch (Exception e) {
            Log.e(TAG, "createFloatingView failed: " + e.getMessage(), e);
            handler.postDelayed(() -> {
                try {
                    if (!isViewAdded && floatingView != null && floatingView.getParent() == null) {
                        windowManager.addView(floatingView, params);
                        isViewAdded = true;
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "Retry also failed: " + ex.getMessage(), ex);
                }
            }, 2000);
        }
    }

    private void setupDrag() {
        if (floatingView == null) return;

        floatingView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = params.x;
                    initialY = params.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    isDragging = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - initialTouchX;
                    float dy = event.getRawY() - initialTouchY;
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                        isDragging = true;
                        params.x = initialX + (int) dx;
                        params.y = initialY + (int) dy;
                        if (windowManager != null && floatingView != null) {
                            windowManager.updateViewLayout(floatingView, params);
                        }
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (!isDragging) {
                        v.performClick();
                    }
                    return true;
            }
            return false;
        });
    }

    private void registerDataReceiver() {
        dataReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String data = intent.getStringExtra(EXTRA_DATA);
                handleData(data);
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(
                dataReceiver, new IntentFilter(ACTION_DATA));
    }

    /** 注册剧本切换广播接收器 */
    private void registerScenarioReceiver() {
        scenarioReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (ACTION_SCENARIO.equals(action)) {
                    String scenario = intent.getStringExtra("scenario");
                    if (scenario != null) {
                        selectedScenario = scenario;
                        Log.d(TAG, "Scenario changed via broadcast: " + scenario);
                        updateScenarioLabel();
                        updateNotification("HTTP:" + HttpDataService.PORT + " | " + scenarioIdToLabel(selectedScenario) + " | 等待插件推送");
                    }
                } else if (ACTION_UPLOAD_DATA.equals(action)) {
                    // 手动上传当前育成数据
                    if (dataCollector != null && dataCollector.getTurnCount() > 0) {
                        Log.d(TAG, "Manual upload requested, turns: " + dataCollector.getTurnCount());
                        dataCollector.finalizeAndUpload();
                        Toast.makeText(FloatingWindowService.this,
                            dataCollector.getTurnCount() + "ターン分をアップロード中...",
                            Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(FloatingWindowService.this,
                            "アップロードするデータがありません", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(
                scenarioReceiver, new IntentFilter(ACTION_SCENARIO));
        LocalBroadcastManager.getInstance(this).registerReceiver(
                scenarioReceiver, new IntentFilter(ACTION_UPLOAD_DATA));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopFallbackPoll();
        stopHttpServer();
        if (floatingView != null && isViewAdded) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception e) {
                Log.w(TAG, "removeView failed: " + e.getMessage());
            }
        }
        if (dataReceiver != null) {
            try {
                LocalBroadcastManager.getInstance(this).unregisterReceiver(dataReceiver);
            } catch (Exception e) {
                Log.w(TAG, "unregisterReceiver failed: " + e.getMessage());
            }
        }
        if (scenarioReceiver != null) {
            try {
                LocalBroadcastManager.getInstance(this).unregisterReceiver(scenarioReceiver);
            } catch (Exception e) {
                Log.w(TAG, "unregister scenarioReceiver failed: " + e.getMessage());
            }
        }
        handler.removeCallbacksAndMessages(null);
    }
}
