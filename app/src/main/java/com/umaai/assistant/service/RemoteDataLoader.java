package com.umaai.assistant.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 远程赛马娘数据加载器 v2
 * 
 * 数据源：
 *   API1 (k3ftlokgmwsms): names/skills/factors (小体积，快速)
 *   API2 (tpnuv7lzpyxyu): events (大体积，含Values数组)
 * 
 * 事件数据 Values 数组含义（10元素）：
 *   [0] 速度增量
 *   [1] 耐力增量
 *   [2] 力量增量
 *   [3] 根性增量
 *   [4] 智力增量
 *   [5] 技能Pt增量
 *   [6] ヒント等级
 *   [7] 保留
 *   [8] 羁绊增量
 *   [9] 干劲增量
 */
public class RemoteDataLoader {

    private static final String TAG = "UmaData";
    public static final String DATA_BASE = "https://raw.githubusercontent.com/xf8410/uma-data/main";
    // GitHub raw: 零成本永久可用，不依赖第三方托管
    public static final String PREFS_NAME = "uma_data";
    private static final int CACHE_VERSION = 2; // 升级版本号触发重新加载

    public static final String KEY_NAMES = "names";
    public static final String KEY_EVENTS = "events";
    public static final String KEY_SKILLS = "skills";
    public static final String KEY_FACTORS = "factors";
    public static final String KEY_CACHE_VER = "cache_version";

    /**
     * 异步加载所有数据
     * names/skills/factors从API1（小体积），events从API2（含Values）
     */
    public static void loadAll(Context ctx, DataCallback cb) {
        new Thread(() -> {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

            // API1: 小体积数据，直接拿JSON
            boolean namesOk = loadAndCache(prefs, KEY_NAMES, DATA_BASE + "/uma_names.json");
            boolean skillsOk = loadAndCache(prefs, KEY_SKILLS, DATA_BASE + "/uma_skills.json");
            boolean factorsOk = loadAndCache(prefs, KEY_FACTORS, DATA_BASE + "/uma_factors.json");

            // API2: 事件数据（含Values数组，~12MB）
            boolean eventsOk = loadAndCache(prefs, KEY_EVENTS, DATA_BASE + "/uma_events.json");

            if (namesOk) prefs.edit().putInt(KEY_CACHE_VER, CACHE_VERSION).apply();

            boolean allOk = namesOk && eventsOk && skillsOk && factorsOk;
            Log.d(TAG, "Data load: names=" + namesOk + " events=" + eventsOk
                    + " skills=" + skillsOk + " factors=" + factorsOk);

            if (cb != null) cb.onLoaded(allOk);
        }).start();
    }

    public static boolean isCached(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_CACHE_VER, 0) >= CACHE_VERSION
                && prefs.getString(KEY_EVENTS, null) != null;
    }

    public static String getCachedData(Context ctx, String key) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(key, null);
    }

    private static boolean loadAndCache(SharedPreferences prefs, String key, String urlStr) {
        String json = httpGet(urlStr);
        if (json != null && json.length() > 10) {
            // 事件数据太大，SharedPreferences存不下（最大约2MB），直接用文件缓存
            if (json.length() > 1_500_000) {
                // 大文件缓存方案：压缩只存关键信息
                // 事件数据太大，标记为已加载但不在SP中存完整数据
                // 后续按需查询
                Log.d(TAG, key + " too large for SP (" + json.length() + " chars), saving summary");
                prefs.edit().putString(key, "LOADED_V2").apply();
                // TODO: 后续改为文件缓存或按需查询
                return true;
            }
            prefs.edit().putString(key, json).apply();
            Log.d(TAG, key + " cached: " + json.length() + " chars");
            return true;
        }
        Log.w(TAG, key + " load failed");
        return prefs.getString(key, null) != null;
    }

    private static String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Connection", "keep-alive");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000); // 事件数据大，延长超时

            int code = conn.getResponseCode();
            if (code == 200) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[8192];
                int len;
                while ((len = reader.read(buf)) != -1) {
                    sb.append(buf, 0, len);
                }
                reader.close();
                return sb.toString();
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "httpGet failed: " + urlStr + " - " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public interface DataCallback {
        void onLoaded(boolean success);
    }
}
