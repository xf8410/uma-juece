package com.umaai.assistant.service;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * App端HTTP服务器，监听18766端口
 * 接收外部推送数据并转发到悬浮窗显示
 *
 * 接口：
 *   GET  /          → 状态页
 *   GET  /status    → JSON状态信息
 *   GET  /data?msg=xxx  → 推送数据到悬浮窗（方便浏览器测试）
 *   POST /data      → 推送JSON数据到悬浮窗
 */
public class HttpDataService extends NanoHTTPD {

    private static final String TAG = "UmaHttp";
    public static final int PORT = 18766;

    private OnDataListener dataListener;

    public interface OnDataListener {
        void onDataReceived(String data);
    }

    public HttpDataService(OnDataListener listener) {
        super(PORT);
        this.dataListener = listener;
    }

    public void startServer() throws IOException {
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        Log.d(TAG, "HTTP server started on port " + PORT);
    }

    public void stopServer() {
        stop();
        Log.d(TAG, "HTTP server stopped");
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        Log.d(TAG, method + " " + uri);

        try {
            switch (uri) {
                case "/":
                case "/status":
                    return handleStatus(session);

                case "/data":
                    if (method == Method.GET) {
                        return handleGetData(session);
                    } else if (method == Method.POST) {
                        return handlePostData(session);
                    }
                    return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED,
                            "text/plain", "Use GET or POST");

                default:
                    return newFixedLengthResponse(Response.Status.NOT_FOUND,
                            "text/plain", "Not found. Try /status or /data");
            }
        } catch (Exception e) {
            Log.e(TAG, "serve error: " + e.getMessage(), e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    "text/plain", "Error: " + e.getMessage());
        }
    }

    /**
     * GET /status → 返回JSON状态信息
     */
    private Response handleStatus(IHTTPSession session) {
        JSONObject status = new JSONObject();
        try {
            status.put("app", "uma-juece");
            status.put("version", "1.1");
            status.put("http_port", PORT);
            status.put("hook_port", 18765);
            status.put("status", "running");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", status.toString());
    }

    /**
     * GET /data?msg=xxx → 浏览器直接测试推送数据
     */
    private Response handleGetData(IHTTPSession session) {
        Map<String, String> params = session.getParms();
        String msg = params.get("msg");

        if (msg == null || msg.isEmpty()) {
            String html = "<html><body>"
                    + "<h2>uma-juece 数据推送</h2>"
                    + "<p>用法: /data?msg=你的消息</p>"
                    + "<form action='/data' method='get'>"
                    + "<input name='msg' placeholder='输入推送内容' size='40'>"
                    + "<button type='submit'>推送</button>"
                    + "</form>"
                    + "</body></html>";
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html);
        }

        if (dataListener != null) {
            dataListener.onDataReceived(msg);
        }

        JSONObject result = new JSONObject();
        try {
            result.put("ok", true);
            result.put("msg", msg);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", result.toString());
    }

    /**
     * POST /data → 接收JSON数据推送
     */
    private Response handlePostData(IHTTPSession session) throws IOException, ResponseException {
        Map<String, String> body = new HashMap<>();
        session.parseBody(body);
        String postData = body.get("postData");

        String displayText = null;

        if (postData != null && !postData.isEmpty()) {
            try {
                JSONObject json = new JSONObject(postData);
                if (json.has("data")) {
                    displayText = json.getString("data");
                } else {
                    displayText = formatGameData(json);
                }
            } catch (JSONException e) {
                displayText = postData;
            }
        }

        if (displayText != null && dataListener != null) {
            dataListener.onDataReceived(displayText);
        }

        JSONObject result = new JSONObject();
        try {
            result.put("ok", true);
            result.put("received", displayText != null ? displayText : "empty");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", result.toString());
    }

    /**
     * 格式化游戏数据为可读文本
     */
    private String formatGameData(JSONObject json) throws JSONException {
        StringBuilder sb = new StringBuilder();
        if (json.has("recommend")) {
            sb.append("推荐: ").append(json.getString("recommend"));
        }
        if (json.has("speed")) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append("速度+").append(json.getInt("speed"));
        }
        if (json.has("stamina")) {
            if (sb.length() > 0) sb.append(" ");
            sb.append("耐力+").append(json.getInt("stamina"));
        }
        if (json.has("power")) {
            if (sb.length() > 0) sb.append(" ");
            sb.append("力量+").append(json.getInt("power"));
        }
        if (json.has("wisdom")) {
            if (sb.length() > 0) sb.append(" ");
            sb.append("智力+").append(json.getInt("wisdom"));
        }
        if (sb.length() == 0) {
            sb.append(json.toString());
        }
        return sb.toString();
    }
}
