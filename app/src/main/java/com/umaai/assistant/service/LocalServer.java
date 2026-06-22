package com.umaai.assistant.service;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.nanohttpd.protocols.http.IHTTPSession;
import org.nanohttpd.protocols.http.NanoHTTPD;
import org.nanohttpd.protocols.http.response.Response;
import org.nanohttpd.protocols.http.response.Status;

import java.io.IOException;

public class LocalServer extends NanoHTTPD {
    private static final String TAG = "LocalServer";
    private static final int PORT = 4693;
    private Context context;

    public LocalServer(Context context) {
        super(PORT);
        this.context = context;
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            if (session.getMethod() == Method.POST) {
                StringBuilder sb = new StringBuilder();
                session.parseBody(sb);
                String body = sb.toString();
                Log.i(TAG, "收到数据: " + body);

                Intent intent = new Intent("UPDATE_FLOATING");
                intent.putExtra("data", body);
                context.sendBroadcast(intent);

                return NanoHTTPD.newFixedLengthResponse(Status.OK, "application/json", "{\"status\":\"ok\"}");
            }
        } catch (Exception e) {
            Log.e(TAG, "处理失败: " + e.getMessage());
        }
        return NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "Bad Request");
    }

    public void startServer() {
        try {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            Log.i(TAG, "HTTP服务器启动，端口: " + PORT);
        } catch (IOException e) {
            Log.e(TAG, "启动失败: " + e.getMessage());
        }
    }

    public void stopServer() {
        stop();
        Log.i(TAG, "HTTP服务器已停止");
    }
}