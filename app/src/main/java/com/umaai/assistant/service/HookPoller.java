package com.umaai.assistant.service;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 轮询Hook端HTTP服务（18765端口），拉取游戏数据
 * Hook注入游戏后，在127.0.0.1:18765提供数据接口
 *
 * 接口：
 *   GET /status      → Hook状态
 *   GET /game_data   → 最新截获的游戏数据
 */
public class HookPoller {

    private static final String TAG = "UmaPoller";
    private static final String HOOK_BASE = "http://127.0.0.1:18765";
    private static final int POLL_INTERVAL_MS = 2000; // 2秒轮询一次

    private volatile boolean running = false;
    private Thread pollThread;
    private OnDataListener dataListener;

    public interface OnDataListener {
        void onGameData(String data);
        void onHookStatus(boolean online);
    }

    public HookPoller(OnDataListener listener) {
        this.dataListener = listener;
    }

    public void start() {
        if (running) return;
        running = true;
        pollThread = new Thread(this::pollLoop);
        pollThread.setDaemon(true);
        pollThread.setName("HookPoller");
        pollThread.start();
        Log.d(TAG, "Poller started");
    }

    public void stop() {
        running = false;
        if (pollThread != null) {
            pollThread.interrupt();
        }
        Log.d(TAG, "Poller stopped");
    }

    private void pollLoop() {
        while (running) {
            try {
                // 先检查Hook是否在线
                String status = httpGet(HOOK_BASE + "/status");
                if (status != null) {
                    try {
                        JSONObject json = new JSONObject(status);
                        if (json.optBoolean("hook_active", false)) {
                            dataListener.onHookStatus(true);

                            // 拉取游戏数据
                            String gameData = httpGet(HOOK_BASE + "/game_data");
                            if (gameData != null && !gameData.isEmpty()) {
                                dataListener.onGameData(gameData);
                            }
                        } else {
                            dataListener.onHookStatus(false);
                        }
                    } catch (JSONException e) {
                        Log.w(TAG, "Status parse error: " + e.getMessage());
                        dataListener.onHookStatus(true); // 有响应就算在线
                    }
                } else {
                    dataListener.onHookStatus(false);
                }
            } catch (Exception e) {
                Log.d(TAG, "Poll error: " + e.getMessage());
                dataListener.onHookStatus(false);
            }

            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /**
     * 简单HTTP GET请求
     */
    private String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Connection", "keep-alive");
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);

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
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
