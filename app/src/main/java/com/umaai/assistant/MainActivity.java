package com.umaai.assistant;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.umaai.assistant.service.FloatingWindowService;
import com.umaai.assistant.service.HttpDataService;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private static final int OVERLAY_PERMISSION_REQUEST = 123;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);

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

        // HTTP通信测试：App自己请求自己的HTTP服务，验证闭环
        Button btnTestHttp = findViewById(R.id.btn_test_http);
        btnTestHttp.setOnClickListener(v -> {
            testHttpCommunication();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
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
        tvStatus.setText("HTTP服务: 127.0.0.1:" + HttpDataService.PORT);
    }

    private void sendTestData(String data) {
        Intent intent = new Intent(FloatingWindowService.ACTION_DATA);
        intent.putExtra(FloatingWindowService.EXTRA_DATA, data);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        Toast.makeText(this, "已发送测试数据", Toast.LENGTH_SHORT).show();
    }

    /**
     * 测试HTTP通信闭环：
     * 1. 请求自己的HTTP服务 /status
     * 2. 请求 /data?msg=xxx 推送数据到浮窗
     */
    private void testHttpCommunication() {
        new Thread(() -> {
            try {
                // 测试1: 获取状态
                String status = httpGet("http://127.0.0.1:" + HttpDataService.PORT + "/status");
                if (status != null) {
                    runOnUiThread(() -> {
                        tvStatus.setText("HTTP服务: 在线 ✓\n" + status);
                        Toast.makeText(MainActivity.this,
                                "HTTP服务在线!", Toast.LENGTH_SHORT).show();
                    });

                    // 测试2: 推送数据到浮窗
                    Thread.sleep(500);
                    String pushResult = httpGet(
                            "http://127.0.0.1:" + HttpDataService.PORT
                                    + "/data?msg=HTTP通信测试成功!");
                    if (pushResult != null) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                "数据已通过HTTP推送到浮窗", Toast.LENGTH_SHORT).show());
                    }
                } else {
                    runOnUiThread(() -> {
                        tvStatus.setText("HTTP服务: 离线\n请先启动悬浮窗");
                        Toast.makeText(MainActivity.this,
                                "HTTP服务未启动，请先点启动悬浮窗", Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    tvStatus.setText("HTTP测试失败: " + e.getMessage());
                });
            }
        }).start();
    }

    private String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);

            int code = conn.getResponseCode();
            if (code == 200) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
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

    private void updateStatus() {
        // 异步检查HTTP服务是否在线
        new Thread(() -> {
            String status = httpGet("http://127.0.0.1:" + HttpDataService.PORT + "/status");
            runOnUiThread(() -> {
                if (status != null) {
                    tvStatus.setText("HTTP服务: 在线 ✓\n127.0.0.1:" + HttpDataService.PORT);
                } else {
                    tvStatus.setText("HTTP服务: 未启动\n请先启动悬浮窗");
                }
            });
        }).start();
    }
}
