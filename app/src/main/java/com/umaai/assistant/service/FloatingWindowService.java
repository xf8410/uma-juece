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
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.umaai.assistant.R;

public class FloatingWindowService extends Service {

    private static final String CHANNEL_ID = "floating_service_channel";
    private static final int NOTIFICATION_ID = 1001;

    private WindowManager windowManager;
    private View floatingView;
    private TextView tvRecommend;
    private BroadcastReceiver fakeDataReceiver;
    private Handler handler = new Handler();
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
            tvRecommend = floatingView.findViewById(R.id.tv_recommend);
            floatingView.setBackgroundColor(Color.RED);

            int layoutFlag;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
            }

            params = new WindowManager.LayoutParams(
                    400,
                    200,
                    layoutFlag,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.CENTER;

            addViewWithRetry();
        } catch (Exception e) {
            Toast.makeText(this, "初始化失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void addViewWithRetry() {
        try {
            windowManager.addView(floatingView, params);
            isViewAdded = true;
            tvRecommend.setText("等待数据...");
            Toast.makeText(this, "✅ 悬浮窗添加成功", Toast.LENGTH_SHORT).show();
            startAutoUpdate();
        } catch (Exception e) {
            Toast.makeText(this, "❌ 添加失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            handler.postDelayed(() -> {
                try {
                    if (!isViewAdded) {
                        windowManager.addView(floatingView, params);
                        isViewAdded = true;
                        tvRecommend.setText("重试成功");
                        Toast.makeText(FloatingWindowService.this, "✅ 重试添加成功", Toast.LENGTH_SHORT).show();
                        startAutoUpdate();
                    }
                } catch (Exception ex) {
                    Toast.makeText(FloatingWindowService.this, "❌ 重试失败: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                }
            }, 500);
        }
    }private void registerReceiver() {
    fakeDataReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String data = intent.getStringExtra("fake_data");
            if (data != null && tvRecommend != null) {
                tvRecommend.setText(data);
                Toast.makeText(FloatingWindowService.this, "📩 收到: " + data, Toast.LENGTH_SHORT).show();
            }
        }
    };
    registerReceiver(fakeDataReceiver, new IntentFilter("com.umaai.assistant.FAKE_DATA"));
}

private void startAutoUpdate() {
    handler.postDelayed(new Runnable() {
        @Override
        public void run() {
            if (tvRecommend != null && !tvRecommend.getText().toString().startsWith("速度")) {
                tvRecommend.setText("🟢 存活中");
            }
            handler.postDelayed(this, 3000);
        }
    }, 3000);
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
            unregisterReceiver(fakeDataReceiver);
        }
        handler.removeCallbacksAndMessages(null);
    }
}