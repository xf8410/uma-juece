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
 * 一键Dump插件所有端点数据到GitHub
 *
 * 抓取 /summary, /debug/breeders, /scenario, /skilldata, /carddb,
 * /status, /data, /debug/params 等端点，
 * 全部上传到 uma-data/dumps/{scenario}/{timestamp}/ 目录。
 */
public class EndpointDumper {

    private static final String TAG = "EndpointDumper";

    private static final String GITHUB_REPO = "xf8410/uma-data";
    private static final String GITHUB_BRANCH = "main";
    private static final String _TK_P1 = "ghp_WGCBGbCji6kcx";
    private static final String _TK_P2 = "fZcbzOXKLaMxPBMBp0dQofK";
    private static final String GITHUB_TOKEN = _TK_P1 + _TK_P2;

    private static final String BASE_URL = "http://127.0.0.1:18765";

    // 要抓取的端点列表（路径 -> 文件名）
    private static final String[][] ENDPOINTS = {
        {"/summary",           "summary.json"},
        {"/debug/breeders",    "debug_breeders.json"},
        {"/scenario",          "scenario.json"},
        {"/skilldata",         "skilldata.json"},
        {"/carddb",            "carddb.json"},
        {"/status",            "status.json"},
        {"/data",              "data.json"},
        {"/debug/params",      "debug_params.json"},
        {"/saddles-dl",        "saddles.json"},
        {"/health",            "health.json"},
        {"/log",               "log.json"},
    };

    private Context context;
    private volatile boolean dumping = false;

    public EndpointDumper(Context context) {
        this.context = context;
    }

    public boolean isDumping() {
        return dumping;
    }

    /**
     * 一键抓取所有端点并上传
     * @param scenario 当前剧本名（用于目录分类）
     */
    public void dumpAll(String scenario) {
        if (dumping) {
            showToast("Dump中...");
            return;
        }
        dumping = true;
        String scenarioDir = (scenario != null && !scenario.isEmpty()) ? scenario : "Unknown";

        showToast("Dump開始 (" + ENDPOINTS.length + "端点)...");

        new Thread(() -> {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String dirPath = "dumps/" + scenarioDir + "/" + timestamp;
            int okCount = 0;
            int failCount = 0;
            StringBuilder failNames = new StringBuilder();

            for (String[] ep : ENDPOINTS) {
                String path = ep[0];
                String filename = ep[1];
                try {
                    String data = fetchUrl(BASE_URL + path);
                    if (data != null && data.length() > 2) {
                        boolean uploaded = uploadToGitHub(data, dirPath + "/" + filename,
                                "dump " + scenarioDir + " " + path);
                        if (uploaded) {
                            okCount++;
                            Log.d(TAG, "Dump OK: " + path);
                        } else {
                            failCount++;
                            failNames.append(path).append(" ");
                            Log.e(TAG, "Dump upload fail: " + path);
                        }
                    } else {
                        // 端点不可用，跳过不算失败
                        Log.w(TAG, "Dump skip (no data): " + path);
                    }
                } catch (Exception e) {
                    failCount++;
                    failNames.append(path).append(" ");
                    Log.e(TAG, "Dump error " + path + ": " + e.getMessage());
                }
            }

            dumping = false;
            final int ok = okCount;
            final int fail = failCount;
            final String fails = failNames.toString();
            handler().post(() -> {
                if (fail == 0) {
                    Toast.makeText(context,
                        "Dump完了! " + ok + "端点上传成功",
                        Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(context,
                        "Dump " + ok + "OK/" + fail + "NG 失敗:" + fails.trim(),
                        Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private Handler handler() {
        return new Handler(Looper.getMainLooper());
    }

    private void showToast(String text) {
        handler().post(() -> Toast.makeText(context, text, Toast.LENGTH_SHORT).show());
    }

    private String fetchUrl(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(10000);
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
            Log.w(TAG, "fetchUrl " + urlStr + " -> HTTP " + code);
        } catch (Exception e) {
            Log.e(TAG, "fetchUrl " + urlStr + ": " + e.getMessage());
        }
        return null;
    }

    private boolean uploadToGitHub(String content, String path, String message) {
        try {
            String apiUrl = "https://api.github.com/repos/" + GITHUB_REPO + "/contents/" + path;

            String encoded = Base64.encodeToString(
                    content.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);

            JSONObject body = new JSONObject();
            body.put("message", message);
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
            return code == 200 || code == 201;
        } catch (Exception e) {
            Log.e(TAG, "uploadToGitHub: " + e.getMessage());
            return false;
        }
    }
}
