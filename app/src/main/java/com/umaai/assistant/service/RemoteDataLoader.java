package com.umaai.assistant.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 远程赛马娘数据加载器
 * 从静态API拉取角色/事件/技能/因子数据，缓存到本地
 *
 * 数据源：预处理的日版游戏数据
 *   - uma_names.json   → 813角色日文名→中文昵称
 *   - uma_events.json  → 8063育成事件+选项效果
 *   - uma_skills.json  → 2008技能数据
 *   - uma_factors.json → 2436因子效果
 */
public class RemoteDataLoader {

    private static final String TAG = "UmaData";
    public static final String BASE_URL = "https://k3ftlokgmwsms.ok.kimi.link";
    public static final String PREFS_NAME = "uma_data";
    private static final int CACHE_VERSION = 1;

    // 数据键名
    public static final String KEY_NAMES = "names";
    public static final String KEY_EVENTS = "events";
    public static final String KEY_SKILLS = "skills";
    public static final String KEY_FACTORS = "factors";
    public static final String KEY_CACHE_VER = "cache_version";

    /**
     * 异步加载所有数据，缓存到SharedPreferences
     */
    public static void loadAll(Context ctx, DataCallback cb) {
        new Thread(() -> {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            boolean namesOk = loadAndCache(prefs, KEY_NAMES, "uma_names.json");
            boolean eventsOk = loadAndCache(prefs, KEY_EVENTS, "uma_events.json");
            boolean skillsOk = loadAndCache(prefs, KEY_SKILLS, "uma_skills.json");
            boolean factorsOk = loadAndCache(prefs, KEY_FACTORS, "uma_factors.json");

            if (namesOk) prefs.edit().putInt(KEY_CACHE_VER, CACHE_VERSION).apply();

            boolean allOk = namesOk && eventsOk && skillsOk && factorsOk;
            Log.d(TAG, "Data load result: names=" + namesOk + " events=" + eventsOk
                    + " skills=" + skillsOk + " factors=" + factorsOk);

            if (cb != null) cb.onLoaded(allOk);
        }).start();
    }

    /**
     * 检查缓存是否有效
     */
    public static boolean isCached(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_CACHE_VER, 0) == CACHE_VERSION
                && prefs.getString(KEY_NAMES, null) != null;
    }

    /**
     * 获取缓存的数据
     */
    public static String getCachedData(Context ctx, String key) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(key, null);
    }

    private static boolean loadAndCache(SharedPreferences prefs, String key, String filename) {
        String json = httpGet(BASE_URL + "/" + filename);
        if (json != null && json.length() > 10) {
            prefs.edit().putString(key, json).apply();
            Log.d(TAG, key + " cached: " + json.length() + " chars");
            return true;
        }
        Log.w(TAG, key + " load failed, using existing cache if any");
        return prefs.getString(key, null) != null; // 有旧缓存也算成功
    }

    private static String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);

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
