package com.umaai.assistant.service;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Debug日志自动保存器
 *
 * 每回合自动拉取 /debug/breeders 原始JSON，上传到GitHub uma-data仓库。
 * 不做diff，每回合无条件存一份，育成结束后统一分析。
 *
 * 路径: debug_logs/{scenario}/YYYYMMDD_HHmmss.txt
 */
public class DebugLogSaver {

    private static final String TAG = "DebugLogSaver";

    private static final String GITHUB_REPO = "xf8410/uma-data";
    private static final String GITHUB_BRANCH = "main";
    private static final String _TK_P1 = "ghp_WGCBGbCji6kcx";
    private static final String _TK_P2 = "fZcbzOXKLaMxPBMBp0dQofK";
    private static final String GITHUB_TOKEN = _TK_P1 + _TK_P2;

    private static final String DEBUG_URL = "http://127.0.0.1:18765/debug/breeders";

    private Context context;
    private String currentScenario = "";
    private int lastMonth = -1;
    private int lastHalf = -1;
    private volatile boolean saving = false;

    public interface SaveCallback {
        void onSaved(boolean success, String message);
    }

    public DebugLogSaver(Context context) {
        this.context = context;
    }

    /**
     * 收到/summary推送时调用，检测新回合就自动存
     */
    public boolean onSummaryUpdate(JSONObject json) {
        try {
            int month = json.optInt("month", -1);
            int half = json.optInt("half", -1);
            String scenario = json.optString("scenario", "");
            if (!scenario.isEmpty()) currentScenario = scenario;

            boolean newTurn = (month != lastMonth || half != lastHalf) && month > 0;
            lastMonth = month;
            lastHalf = half;

            if (newTurn) {
                Log.d(TAG, "New turn: " + month + "月" + half + "半, auto-saving debug log...");
                fetchAndSave(null);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "onSummaryUpdate error: " + e.getMessage());
        }
        return false;
    }

    /** 手动触发 */
    public void manualSave(SaveCallback callback) {
        fetchAndSave(callback);
    }

    private void fetchAndSave(SaveCallback callback) {
        if (saving) {
            if (callback != null) callback.onSaved(false, "正在保存中...");
            return;
        }
        saving = true;
        new Thread(() -> {
            try {
                String result = fetchDebugLog();
                if (result == null) {
                    if (callback != null) callback.onSaved(false, "插件离线");
                    return;
                }
                boolean ok = uploadToGitHub(result);
                if (callback != null) callback.onSaved(ok, ok ? "已上传" : "上传失败");
            } catch (Exception e) {
                if (callback != null) callback.onSaved(false, e.getMessage());
            } finally {
                saving = false;
            }
        }).start();
    }

    private String fetchDebugLog() {
        try {
            URL url = new URL(DEBUG_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            if (code == 200) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();
                return sb.toString();
            }
            conn.disconnect();
        } catch (Exception e) {
            Log.e(TAG, "fetchDebugLog: " + e.getMessage());
        }
        return null;
    }

    private boolean uploadToGitHub(String jsonContent) {
        try {
            String scenarioDir = currentScenario.isEmpty() ? "Unknown" : currentScenario;
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String filename = timestamp + ".txt";
            String path = "debug_logs/" + scenarioDir + "/" + filename;
            String apiUrl = "https://api.github.com/repos/" + GITHUB_REPO + "/contents/" + path;

            String encoded = Base64.encodeToString(
                    jsonContent.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);

            JSONObject body = new JSONObject();
            body.put("message", "debug log " + scenarioDir + " " + timestamp);
            body.put("content", encoded);
            body.put("branch", GITHUB_BRANCH);

            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("PUT");
            conn.setRequestProperty("Authorization", "token " + GITHUB_TOKEN);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            conn.disconnect();
            Log.d(TAG, "Upload " + path + " -> " + code);
            return code == 200 || code == 201;
        } catch (Exception e) {
            Log.e(TAG, "uploadToGitHub: " + e.getMessage());
            return false;
        }
    }

    public String getStatusText() {
        return saving ? "Log:UP" : "Log:ON";
    }
}
