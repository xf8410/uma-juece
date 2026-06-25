package com.umaai.assistant;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.umaai.assistant.service.FloatingWindowService;
import com.umaai.assistant.service.HttpDataService;
import com.umaai.assistant.service.RemoteDataLoader;

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

        // 小黑板测试
        Button btnTestFake = findViewById(R.id.btn_test_fake);
        btnTestFake.setOnClickListener(v -> {
            new Thread(() -> {
                String result = httpGet("http://127.0.0.1:" + HttpDataService.PORT + "/test_board");
                runOnUiThread(() -> {
                    if (result != null) {
                        Toast.makeText(this, "小黑板测试数据已推送", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "推送失败，请先启动悬浮窗", Toast.LENGTH_SHORT).show();
                    }
                });
            }).start();
        });

        Button btnTestHttp = findViewById(R.id.btn_test_http);
        btnTestHttp.setOnClickListener(v -> testHttpCommunication());

        // 启动时加载数据
        loadDataIfNeeded();
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

    private void loadDataIfNeeded() {
        if (RemoteDataLoader.isCached(this)) {
            SharedPreferences prefs = getSharedPreferences(RemoteDataLoader.PREFS_NAME, MODE_PRIVATE);
            tvStatus.setText("数据已缓存 ✓\nHTTP: 127.0.0.1:" + HttpDataService.PORT);
            return;
        }

        tvStatus.setText("正在加载游戏数据...");
        RemoteDataLoader.loadAll(this, success -> {
            runOnUiThread(() -> {
                if (success) {
                    tvStatus.setText("数据加载成功 ✓\n813角色 / 8063事件 / 2008技能\nHTTP: 127.0.0.1:" + HttpDataService.PORT);
                    Toast.makeText(this, "游戏数据加载完成", Toast.LENGTH_SHORT).show();
                } else {
                    tvStatus.setText("数据加载失败，使用离线模式\nHTTP: 127.0.0.1:" + HttpDataService.PORT);
                    Toast.makeText(this, "数据加载失败，将使用离线模式", Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void startFloatingService() {
        Intent intent = new Intent(this, FloatingWindowService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "小黑板已启动", Toast.LENGTH_SHORT).show();
        tvStatus.setText("HTTP服务: 127.0.0.1:" + HttpDataService.PORT);
    }

    private void testHttpCommunication() {
        new Thread(() -> {
            try {
                String status = httpGet("http://127.0.0.1:" + HttpDataService.PORT + "/status");
                if (status != null) {
                    runOnUiThread(() -> {
                        tvStatus.setText("HTTP服务: 在线\n" + status);
                        Toast.makeText(this, "HTTP服务在线!", Toast.LENGTH_SHORT).show();
                    });

                    Thread.sleep(500);
                    String pushResult = httpGet(
                            "http://127.0.0.1:" + HttpDataService.PORT
                                    + "/data?msg=HTTP通信测试成功!");
                    if (pushResult != null) {
                        runOnUiThread(() ->
                                Toast.makeText(this, "数据已通过HTTP推送到浮窗", Toast.LENGTH_SHORT).show());
                    }
                } else {
                    runOnUiThread(() -> {
                        tvStatus.setText("HTTP服务: 离线\n请先启动悬浮窗");
                        Toast.makeText(this, "HTTP服务未启动", Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> tvStatus.setText("HTTP测试失败: " + e.getMessage()));
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
        if (RemoteDataLoader.isCached(this)) {
            tvStatus.setText("数据已缓存 ✓\nHTTP: 127.0.0.1:" + HttpDataService.PORT);
        }
    }
}
