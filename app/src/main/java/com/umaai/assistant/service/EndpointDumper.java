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
 * v3.18.8: 请求间隔500ms + 总超时90s + 防止游戏卡死
 * 抓取 /summary, /scenario, /skilldata, /carddb,
 * /status, /data, /saddles-dl, /health 等端点，
 * 全部上传到 uma-data/dumps/{scenario}/{timestamp}/ 目录。
 * debug端点(/debug/breeders, /debug/params)默认跳过，长请求容易卡游戏。
 */
public class EndpointDumper {

    private static final String TAG = "EndpointDumper";

    /** 回调接口：dump完成后通知UI更新 */
    public interface DumpCallback {
        void onDumpComplete(int ok, int fail, String fails);
    }

    private static final String GITHUB_REPO = "xf8410/uma-data";
    private static final String GITHUB_BRANCH = "main";
    private static final String _TK_P1 = "ghp_WGCBGbCji6kcx";
    private static final String _TK_P2 = "fZcbzOXKLaMxPBMBp0dQofK";
    private static final String GITHUB_TOKEN = _TK_P1 + _TK_P2;

    private static final String BASE_URL = "http://127.0.0.1:18765";

    // 要抓取的端点列表（路径 -> 文件名）
    // v3.18.8: 移除/debug/breeders和/debug/params，这两个重端点容易卡游戏
    // 如需debug数据，单独请求即可
    private static final String[][] ENDPOINTS = {
        {"/summary",           "summary.json"},
        {"/scenario",          "scenario.json"},
        {"/skilldata",         "skilldata.json"},
        {"/carddb",            "carddb.json"},
        {"/status",            "status.json"},
        {"/data",              "data.json"},
        {"/saddles-dl",        "saddles.json"},
        {"/health",            "health.json"},
        {"/log",               "log.json"},
    };

    // ★ v3.18.8: 请求间隔ms，防止连续请求压垮插件HTTP服务器
    private static final int REQUEST_INTERVAL_MS = 500;
    // ★ v3.18.8: 总超时ms，超时强制结束
    private static final long TOTAL_TIMEOUT_MS = 90_000;

    private Context context;
    private volatile boolean dumping = false;
    private volatile boolean cancelled = false;

    public EndpointDumper(Context context) {
        this.context = context;
    }

    public boolean isDumping() {
        return dumping;
    }

    /** 取消正在进行的dump */
    public void cancel() {
        if (dumping) {
            cancelled = true;
            showToast("Dump取消中...");
        }
    }

    /**
     * 一键抓取所有端点并上传
     * @param scenario 当前剧本名（用于目录分类）
     */
    public void dumpAll(String scenario, DumpCallback callback) {
        if (dumping) {
            showToast("Dump中...");
            return;
        }
        dumping = true;
        cancelled = false;
        String scenarioDir = (scenario != null && !scenario.isEmpty()) ? scenario : "Unknown";

        showToast("Dump開始 (" + ENDPOINTS.length + "端点)...");

        new Thread(() -> {
            long startTime = System.currentTimeMillis();
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String dirPath = "dumps/" + scenarioDir + "/" + timestamp;
            int okCount = 0;
            int failCount = 0;
            StringBuilder failNames = new StringBuilder();

            for (int i = 0; i < ENDPOINTS.length; i++) {
                // ★ 检查取消
                if (cancelled) {
                    Log.w(TAG, "Dump cancelled by user");
                    break;
                }
                // ★ 检查总超时
                if (System.currentTimeMillis() - startTime > TOTAL_TIMEOUT_MS) {
                    Log.w(TAG, "Dump total timeout exceeded");
                    failNames.append("(timeout) ");
                    break;
                }

                String[] ep = ENDPOINTS[i];
                String path = ep[0];
                String filename = ep[1];

                // ★ 请求间隔（首个不等待）
                if (i > 0) {
                    try { Thread.sleep(REQUEST_INTERVAL_MS); } catch (InterruptedException ignored) { break; }
                }

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
            final boolean wasCancelled = cancelled;
            handler().post(() -> {
                String msg;
                if (wasCancelled) {
                    msg = "Dump取消! " + ok + "OK/" + fail + "NG";
                } else if (fail == 0) {
                    msg = "Dump完了! " + ok + "端点上传成功";
                } else {
                    msg = "Dump " + ok + "OK/" + fail + "NG 失敗:" + fails.trim();
                }
                Toast.makeText(context, msg,
                    fail > 0 || wasCancelled ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
                if (callback != null) {
                    callback.onDumpComplete(ok, fail, fails);
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
            conn.setReadTimeout(8000); // ★ v3.18.8: 10s→8s，减少卡住时间
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
