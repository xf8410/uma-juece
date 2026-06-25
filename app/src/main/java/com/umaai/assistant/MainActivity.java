package com.umaai.assistant;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.umaai.assistant.service.FloatingWindowService;

public class MainActivity extends Activity {
    private static final int OVERLAY_PERMISSION_REQUEST = 123;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnStart = findViewById(R.id.btn_start_float);
        btnStart.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST);
            } else {
                startFloatingService();
            }
        });

        Button btnTest = findViewById(R.id.btn_test_fake);
        btnTest.setOnClickListener(v -> {
            sendTestData("速度+5，耐力+3，推荐训练: 速度");
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startFloatingService();
            } else {
                Toast.makeText(this, "需要悬浮窗权限", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startFloatingService() {
        Intent intent = new Intent(this, FloatingWindowService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "悬浮窗已启动", Toast.LENGTH_SHORT).show();
    }

    private void sendTestData(String data) {
        Intent intent = new Intent(FloatingWindowService.ACTION_DATA);
        intent.putExtra(FloatingWindowService.EXTRA_DATA, data);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        Toast.makeText(this, "已发送测试数据", Toast.LENGTH_SHORT).show();
    }
}
