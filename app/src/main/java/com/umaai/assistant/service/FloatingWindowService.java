package com.umaai.assistant.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.umaai.assistant.R;

public class FloatingWindowService extends Service {

    private static final String CHANNEL_ID = "floating_service_channel";
    private static final int NOTIFICATION_ID = 1001;

    private WindowManager windowManager;
    private View floatingView;
    private ImageView floatImage;
    private TextView tvRecommend;
    private BroadcastReceiver fakeDataReceiver;
    private Handler handler = new Handler(android.os.Looper.getMainLooper());
    private boolean isViewAdded = false;
    private WindowManager.LayoutParams params;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        Toast.makeText(this, "Service 启动", Toast.LENGTH_SHORT).show();
        createFloatingView();
        registerReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "悬浮窗服务",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("保持悬浮窗运行");
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("赛马娘助手")
                .setContentText("悬浮窗正在运行...")
                .setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void createFloatingView() {
        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            floatingView = LayoutInflater.from(this).inflate(R.layout.floating_window, null);
            floatImage = floatingView.findViewById(R.id.float_image);
            tvRecommend = floatingView.findViewById(R.id.tv_recommend);

            int layoutFlag;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
            }

            // 固定悬浮窗尺寸（适配120dp图片 + 文字区域）
            // 120dp ≈ 360-480px 根据屏幕密度
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            windowManager.getDefaultDisplay().getMetrics(dm);
            int density = dm.densityDpi;
            int dp120 = (int) (120 * (density / 160f));
            int w = dp120 + 20;  // 图片宽度 + 边距
            int h = dp120 + 60;  // 图片高度 + 文字区域

            params = new WindowManager.LayoutParams(
                    w, h, layoutFlag,
                    // 修复: 移除FLAG_LAYOUT_IN_SCREEN（导致闪回桌面的元凶）
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
            );
            // 修复: 使用TOP|START+偏移，避免CENTER覆盖全屏触发返回桌面
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = dm.widthPixels / 20;
            params.y = dm.heightPixels / 10;

            addViewWithRetry();
        } catch (Exception e) {
            Toast.makeText(this, "初始化失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void addViewWithRetry() {
        handler.post(() -> {
            try {
                if (floatingView != null && floatingView.getParent() == null) {
                    windowManager.addView(floatingView, params);
                    isViewAdded = true;
                    if (tvRecommend != null) tvRecommend.setText("等待数据...");
                }
            } catch (Exception e) {
                // 延迟重试一次
                handler.postDelayed(() -> {
                    try {
                        if (!isViewAdded && floatingView != null && floatingView.getParent() == null) {
                            windowManager.addView(floatingView, params);
                            isViewAdded = true;
                            if (tvRecommend != null) tvRecommend.setText("等待数据...");
                        }
                    } catch (Exception ex) {
                        // 彻底放弃，但Service继续运行
                    }
                }, 1000);
            }
        });
    }

    private void registerReceiver() {
        fakeDataReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String data = intent.getStringExtra("fake_data");
                if (data != null && tvRecommend != null) {
                    tvRecommend.setText(data);
                }
            }
        };
        registerReceiver(fakeDataReceiver, new IntentFilter("com.umaai.assistant.FAKE_DATA"));
    }

    private void startAutoUpdate() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (tvRecommend != null) {
                    String text = tvRecommend.getText().toString();
                    if (!text.startsWith("速度") && !text.startsWith("等待")) {
                        tvRecommend.setText("运行中...");
                    }
                }
                handler.postDelayed(this, 5000);
            }
        }, 5000);
    }    @Override
    public void onDestroy() {
        super.onDestroy();
        if (floatingView != null && isViewAdded) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception e) {
                // ignore
            }
        }
        if (fakeDataReceiver != null) {
            try { unregisterReceiver(fakeDataReceiver); } catch (Exception e) { /* ignore */ }
        }
        handler.removeCallbacksAndMessages(null);
    }
}