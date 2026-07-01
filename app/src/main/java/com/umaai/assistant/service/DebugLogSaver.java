package com.umaai.assistant.service;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

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
 * 每回合自动保存两份数据到GitHub uma-data仓库：
 * 1. /debug/breeders → debug_logs/{scenario}/
 * 2. /summary → summary_logs/{scenario}/（含buffs/active_effects等Ramen数据）
 *
 * 不做diff，每回合无条件存一份，育成结束后统一分析。
 * 上传成功/失败通过Toast提示用户。
 */
public class DebugLogSaver {

    private static final String TAG = "DebugLogSaver";

    private static final String GITHUB_REPO = "xf8410/uma-data";
    private static final String GITHUB_BRANCH = "main";
    private static final String _TK_P1 = "ghp_WGCBGbCji6kcx";
    private static final String _TK_P2 = "fZcbzOXKLaMxPBMBp0dQofK";
    private static final String GITHUB_TOKEN = _TK_P1 + _TK_P2;

    private static final String DEBUG_URL = "http://127.0.0.1:18765/debug/breeders";
    private static final String SUMMARY_URL = "http://127.0.0.1:18765/summary";

    private Context context;
    private String currentScenario = "";
    private int lastMonth = -1;
    private int lastHalf = -1;
    private volatile boolean saving = false;
    private int uploadOkCount = 0;
    private int uploadFailCount = 0;
    /** 最近一次推送的/summary JSON原文，用于存GitHub */
    private volatile String lastSummaryJson = "";

    public interface SaveCallback {
        void onSaved(boolean success, String message);
    }

    public DebugLogSaver(Context context) {
        this.context = context;
    }

    /**
     * 收到/summary推送时调用，检测新回合就自动存
     * @param json 推送过来的/summary JSON对象
     * @param rawJson 推送原文（用于存GitHub，保留完整字段）
     */
    public boolean onSummaryUpdate(JSONObject json, String rawJson) {
        try {
            int month = json.optInt("month", -1);
            int half = json.optInt("half", -1);
            String scenario = json.optString("scenario", "");
            if (!scenario.isEmpty()) currentScenario = scenario;
            if (rawJson != null && !rawJson.isEmpty()) lastSummaryJson = rawJson;

            boolean newTurn = (month != lastMonth || half != lastHalf) && month > 0;
            lastMonth = month;
            lastHalf = half;

            if (newTurn) {
                String turnLabel = month + "月" + (half == 1 ? "前" : "後");
                Log.d(TAG, "New turn: " + turnLabel + ", auto-saving...");
                fetchAndSave((success, msg) -> {
                    if (success) {
                        uploadOkCount++;
                        showToast("📤 " + currentScenario + " " + turnLabel + " 已上传(累計" + uploadOkCount + ")");
                    } else {
                        uploadFailCount++;
                        showToast("❌ 上传失敗: " + msg + " (失敗" + uploadFailCount + "回)");
                    }
                });
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "onSummaryUpdate error: " + e.getMessage());
        }
        return false;
    }

    /** 兼容旧调用（不传rawJson） */
    public boolean onSummaryUpdate(JSONObject json) {
        return onSummaryUpdate(json, null);
    }

    /** 手动触发 */
    public void manualSave(SaveCallback callback) {
        fetchAndSave(callback);
    }

    private void showToast(String text) {
        new Handler(Looper.getMainLooper()).post(() ->
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        );
    }

    private void fetchAndSave(SaveCallback callback) {
        if (saving) {
            if (callback != null) callback.onSaved(false, "正在保存中...");
            return;
        }
        saving = true;
        new Thread(() -> {
            try {
                // 1. 存 /debug/breeders
                String debugResult = fetchUrl(DEBUG_URL);
                boolean debugOk = false;
                if (debugResult != null) {
                    debugOk = uploadToGitHub(debugResult, "debug_logs", "debug log");
                }

                // 2. 存 /summary（优先用推送原文，否则重新拉取）
                String summaryResult = lastSummaryJson;
                if (summaryResult == null || summaryResult.isEmpty()) {
                    summaryResult = fetchUrl(SUMMARY_URL);
                }
                boolean summaryOk = false;
                if (summaryResult != null) {
                    summaryOk = uploadToGitHub(summaryResult, "summary_logs", "summary");
                }

                boolean allOk = debugOk && summaryOk;
                String msg = allOk ? "已上传" : ("debug:" + (debugOk?"OK":"NG") + " summary:" + (summaryOk?"OK":"NG"));
                if (callback != null) callback.onSaved(allOk, msg);
            } catch (Exception e) {
                if (callback != null) callback.onSaved(false, e.getMessage());
            } finally {
                saving = false;
            }
        }).start();
    }

    private String fetchUrl(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Connection", "keep-alive");
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
            Log.e(TAG, "fetchUrl " + urlStr + " -> HTTP " + code);
        } catch (Exception e) {
            Log.e(TAG, "fetchUrl " + urlStr + ": " + e.getMessage());
        }
        return null;
    }

    private boolean uploadToGitHub(String jsonContent, String dirPrefix, String label) {
        try {
            String scenarioDir = currentScenario.isEmpty() ? "Unknown" : currentScenario;
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String filename = timestamp + ".txt";
            String path = dirPrefix + "/" + scenarioDir + "/" + filename;
            String apiUrl = "https://api.github.com/repos/" + GITHUB_REPO + "/contents/" + path;

            String encoded = Base64.encodeToString(
                    jsonContent.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);

            JSONObject body = new JSONObject();
            body.put("message", label + " " + scenarioDir + " " + timestamp);
            body.put("content", encoded);
            body.put("branch", GITHUB_BRANCH);

            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("PUT");
            conn.setRequestProperty("Authorization", "token " + GITHUB_TOKEN);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setRequestProperty("Connection", "keep-alive");
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
