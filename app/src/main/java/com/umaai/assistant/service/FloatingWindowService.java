package com.umaai.assistant.service;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

public class FloatingWindowService extends Service {
    private WindowManager windowManager;
    private View floatingView;
    private TextView tvRecommend;
    private LocalServer localServer;
    private BroadcastReceiver dataReceiver;

    @Override
    public void onCreate() {
        super.onCreate();

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_window, null);
        tvRecommend = floatingView.findViewById(R.id.tv_recommend);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 100;
        params.y = 100;
        windowManager.addView(floatingView, params);

        localServer = new LocalServer(this);
        localServer.startServer();

        dataReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String data = intent.getStringExtra("data");
                if (data != null) {
                    tvRecommend.setText(data);
                }
            }
        };
        registerReceiver(dataReceiver, new IntentFilter("UPDATE_FLOATING"));

        tvRecommend.setText("等待数据...");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (localServer != null) localServer.stopServer();
        if (dataReceiver != null) unregisterReceiver(dataReceiver);
        if (floatingView != null) windowManager.removeView(floatingView);
        super.onDestroy();
    }
}