package com.umaai.assistant.service;

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
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.umaai.assistant.R;

public class FloatingWindowService extends Service {

    private WindowManager windowManager;
    private View floatingView;
    private TextView tvRecommend;
    private BroadcastReceiver fakeDataReceiver;
    private WindowManager.LayoutParams params;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // 初始化悬浮窗
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // 加载布局
        try {
            floatingView = LayoutInflater.from(this).inflate(R.layout.floating_window, null);
            tvRecommend = floatingView.findViewById(R.id.tv_recommend);
            // 设置背景色为红色，确保可见
            floatingView.setBackgroundColor(Color.RED);
        } catch (Exception e) {
            Toast.makeText(this, "加载布局失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        // 窗口参数
        int layoutFlag;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
        }

        params = new WindowManager.LayoutParams(
                400, // 固定宽度（像素）
                200, // 固定高度（像素）
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.CENTER; // 让悬浮窗出现在屏幕中央，方便看到
        params.x = 0;
        params.y = 0;

        // 添加拖拽
        floatingView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(floatingView, params);
                        return true;
                }
                return false;
            }
        });

        // 添加窗口（带重试）
        addViewWithRetry();

        // 更新显示文字
        if (tvRecommend != null) {
            tvRecommend.setText("等待数据...");
        }

        // 注册广播接收器
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

    private void addViewWithRetry() {
        try {
            windowManager.addView(floatingView, params);
            Toast.makeText(this, "悬浮窗添加成功", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "添加悬浮窗失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            // 尝试延迟重试
            new Handler().postDelayed(() -> {
                try {
                    windowManager.addView(floatingView, params);
                    Toast.makeText(FloatingWindowService.this, "重试添加成功", Toast.LENGTH_SHORT).show();
                } catch (Exception ex) {
                    Toast.makeText(FloatingWindowService.this, "重试依然失败: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                }
            }, 500);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (floatingView != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception e) {
                // ignore
            }
        }
        if (fakeDataReceiver != null) {
            unregisterReceiver(fakeDataReceiver);
        }
    }
}