package com.umaai.assistant.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import android.widget.TextView;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.umaai.assistant.R;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 小黑板风格浮窗服务
 * 黑底+彩色文字，模仿URA训练分析面板
 * 
 * 数据格式（Hook端推送JSON）：
 * {
 *   "turn": "Classic 1年",
 *   "total": 3248, "pt": 442,
 *   "stamina": 64, "max_stamina": 100,
 *   "motivation": "好調",
 *   "recommend": "速度 SP訓練 4人/友情2/失敗率6%",
 *   "speed": {"current":1050,"remain":550,"gain":99,"pt":13},
 *   "stamina_stat": {"current":685,"remain":915,"gain":47,"pt":18},
 *   "power": {"current":959,"remain":641,"gain":1,"pt":0},
 * "guts": {"current":554,"remain":1046,"gain":21,"pt":7},
 *   "wisdom": {"current":543,"remain":1057,"gain":28,"pt":9},
 *   "facility": "2 5 4 4 3"
 * }
 */
public class FloatingWindowService extends Service implements HttpDataService.OnDataListener, HookPoller.OnDataListener {

    private static final String TAG = "UmaFloat";
    private static final String CHANNEL_ID = "floating_service_channel";
    private static final int NOTIFICATION_ID = 1001;
    public static final String ACTION_DATA = "com.umaai.assistant.ACTION_DATA";
    public static final String EXTRA_DATA = "data";

    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams params;
    private boolean isViewAdded = false;
    private Handler handler = new Handler(Looper.getMainLooper());

    // 顶部
    private TextView tvTurn, tvTotal, tvStamina, tvMotivation;
    // 推荐
    private TextView tvRecommend;
    // 五维: 速耐力根智
    private TextView tvSpdVal, tvSpdGain;
    private TextView tvStaVal, tvStaGain;
    private TextView tvPwrVal, tvPwrGain;
    private TextView tvGutVal, tvGutGain;
    private TextView tvWitVal, tvWitGain;
    // 底部
    private TextView tvFacility, tvHookStatus;

    // HTTP服务 + Hook轮询
    private HttpDataService httpServer;
    private HookPoller hookPoller;
    private boolean hookOnline = false;

    // 拖拽
    private int initialX, initialY;
    private float initialTouchX, initialTouchY;
    private boolean isDragging = false;

    private BroadcastReceiver dataReceiver;

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

        handler.postDelayed(this::createFloatingView, 300);
        registerDataReceiver();
        startHttpServer();

        hookPoller = new HookPoller(this);
        hookPoller.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra(EXTRA_DATA)) {
            handleData(intent.getStringExtra(EXTRA_DATA));
        }
        return START_STICKY;
    }

    // ======== HttpDataService.OnDataListener ========
    @Override
    public void onDataReceived(String data) {
        Log.d(TAG, "HTTP data received: " + data);
        handleData(data);
    }

    // ======== HookPoller.OnDataListener ========
    @Override
    public void onGameData(String data) {
        Log.d(TAG, "Hook game data: " + data);
        handleData(data);
    }

    @Override
    public void onHookStatus(boolean online) {
        if (online != hookOnline) {
            hookOnline = online;
            Log.d(TAG, "Hook status: " + (online ? "ONLINE" : "OFFLINE"));
            handler.post(() -> {
                if (tvHookStatus != null) {
                    tvHookStatus.setText(online ? "Hook:ONLINE" : "Hook:---");
                    tvHookStatus.setTextColor(online ? 0xFF00FF88 : 0xFF555555);
                }
            });
            if (online && tvRecommend != null) {
                handler.post(() -> tvRecommend.setText("已连接Hook，等待数据..."));
            }
        }
    }

    // ======== 数据处理核心 ========
    private void handleData(String data) {
        if (data == null || data.isEmpty()) return;

        // 尝试JSON解析
        try {
            JSONObject json = new JSONObject(data);
            updateFromJson(json);
            return;
        } catch (JSONException e) {
            // 不是JSON，当纯文本处理
        }

        // 纯文本 → 显示在推荐区
        handler.post(() -> {
            if (tvRecommend != null) tvRecommend.setText(data);
        });
    }

    private void updateFromJson(JSONObject json) {
        handler.post(() -> {
            try {
                // 顶部信息
                if (json.has("turn")) {
                    tvTurn.setText(json.getString("turn"));
                }
                if (json.has("total") || json.has("pt")) {
                    int total = json.optInt("total", 0);
                    int pt = json.optInt("pt", 0);
                    tvTotal.setText("総" + total + " Pt" + pt);
                }
                if (json.has("stamina")) {
                    int sta = json.getInt("stamina");
                    int maxSta = json.optInt("max_stamina", 100);
                    tvStamina.setText("体" + sta + "/" + maxSta);
                }
                if (json.has("motivation")) {
                    tvMotivation.setText(json.getString("motivation"));
                }

                // 推荐
                if (json.has("recommend")) {
                    String rec = json.getString("recommend");
                    tvRecommend.setText("\u25B6 " + rec);
                    // 如果推荐包含训练名，高亮颜色
                    setRecommendColor(rec);
                }

                // 五维属性
                updateStat(tvSpdVal, tvSpdGain, json, "speed");
                updateStat(tvStaVal, tvStaGain, json, "stamina_stat");
                updateStat(tvPwrVal, tvPwrGain, json, "power");
                updateStat(tvGutVal, tvGutGain, json, "guts");
                updateStat(tvWitVal, tvWitGain, json, "wisdom");

                // 设施
                if (json.has("facility")) {
                    tvFacility.setText("施設 " + json.getString("facility"));
                }

            } catch (JSONException e) {
                Log.w(TAG, "JSON parse error: " + e.getMessage());
                tvRecommend.setText(json.toString());
            }
        });
    }

    private void updateStat(TextView tvVal, TextView tvGain, JSONObject json, String key) throws JSONException {
        if (!json.has(key)) return;
        JSONObject stat = json.getJSONObject(key);
        int current = stat.optInt("current", 0);
        int remain = stat.optInt("remain", 0);
        int gain = stat.optInt("gain", 0);
        int pt = stat.optInt("pt", 0);

        tvVal.setText(current + ":" + remain);

        StringBuilder gainStr = new StringBuilder();
        if (gain > 0) gainStr.append("+").append(gain);
        if (pt > 0) gainStr.append(" Pt").append(pt);
        tvGain.setText(gainStr.toString());

        // 增量>0时高亮
        if (gain > 0) {
            tvGain.setTextColor(0xFF00FF88);
        }
    }

    private void setRecommendColor(String rec) {
        if (rec.contains("速")) {
            tvRecommend.setTextColor(0xFF4488FF);
        } else if (rec.contains("耐")) {
            tvRecommend.setTextColor(0xFFFF4444);
        } else if (rec.contains("力") && !rec.contains("力量")) {
            tvRecommend.setTextColor(0xFFFF8800);
        } else if (rec.contains("根")) {
            tvRecommend.setTextColor(0xFFFF66AA);
        } else if (rec.contains("智")) {
            tvRecommend.setTextColor(0xFFFFDD00);
        } else {
            tvRecommend.setTextColor(0xFF00FF88);
        }
    }

    // ======== HTTP服务器 ========
    private void startHttpServer() {
        try {
            httpServer = new HttpDataService(this);
            httpServer.startServer();
            Log.d(TAG, "HTTP server started on port " + HttpDataService.PORT);
            updateNotification("HTTP:" + HttpDataService.PORT + " | 等待连接");
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
                .setContentText("HTTP:18766 | 小黑板模式")
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
            Log.d(TAG, "Floating view added - 小黑板模式");

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

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopHttpServer();
        if (hookPoller != null) {
            hookPoller.stop();
        }
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
        handler.removeCallbacksAndMessages(null);
    }
}
