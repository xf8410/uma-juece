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
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.umaai.assistant.R;

public class FloatingWindowService extends Service {

    private static final String TAG = "UmaFloat";
    private static final String CHANNEL_ID = "floating_service_channel";
    private static final int NOTIFICATION_ID = 1001;
    public static final String ACTION_DATA = "com.umaai.assistant.ACTION_DATA";
    public static final String EXTRA_DATA = "data";

    private WindowManager windowManager;
    private View floatingView;
    private ImageView floatImage;
    private TextView tvRecommend;
    private BroadcastReceiver dataReceiver;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isViewAdded = false;
    private WindowManager.LayoutParams params;

    // 拖拽相关
    private int initialX, initialY;
    private float initialTouchX, initialTouchY;
    private boolean isDragging = false;

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

        // 延迟创建浮窗，确保Service完全初始化
        handler.postDelayed(this::createFloatingView, 300);

        registerDataReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra(EXTRA_DATA)) {
            updateText(intent.getStringExtra(EXTRA_DATA));
        }
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
                .setContentText("悬浮窗运行中")
                .setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void createFloatingView() {
        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (windowManager == null) {
                Log.e(TAG, "WindowManager is null");
                return;
            }

            floatingView = LayoutInflater.from(this).inflate(R.layout.floating_window, null);
            floatImage = floatingView.findViewById(R.id.float_image);
            tvRecommend = floatingView.findViewById(R.id.tv_recommend);

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
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 50;
            params.y = 200;

            setupDrag();

            windowManager.addView(floatingView, params);
            isViewAdded = true;
            Log.d(TAG, "Floating view added successfully");
            if (tvRecommend != null) tvRecommend.setText("等待数据...");

        } catch (Exception e) {
            Log.e(TAG, "createFloatingView failed: " + e.getMessage(), e);
            // 重试一次
            handler.postDelayed(() -> {
                try {
                    if (!isViewAdded && floatingView != null && floatingView.getParent() == null) {
                        windowManager.addView(floatingView, params);
                        isViewAdded = true;
                        Log.d(TAG, "Floating view added on retry");
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
                        // 单击：折叠/展开
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
                updateText(data);
            }
        };
        // 使用 LocalBroadcastManager，避免隐式广播崩溃（Android 12+）
        LocalBroadcastManager.getInstance(this).registerReceiver(
                dataReceiver, new IntentFilter(ACTION_DATA));
    }

    private void updateText(String data) {
        if (data != null && tvRecommend != null) {
            handler.post(() -> {
                if (tvRecommend != null) {
                    tvRecommend.setText(data);
                }
            });
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
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
