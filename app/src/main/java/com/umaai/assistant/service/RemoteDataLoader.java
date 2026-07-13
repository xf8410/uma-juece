package com.umaai.assistant.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 远程赛马娘数据加载器 v3
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
 *   [6] 技能Hint等级
 *   [7] 保留
 *   [8] 羁绊增量
 *   [9] 干劲增量
 * 
 * ★ v3.22.58: 大文件改用文件缓存，不再受SP 2MB限制
 */
public class RemoteDataLoader {

    private static final String TAG = "UmaData";
    public static final String DATA_BASE = "https://raw.githubusercontent.com/xf8410/uma-data/main";
    public static final String PREFS_NAME = "uma_data";
    private static final int CACHE_VERSION = 3; // 升级版本号触发重新加载

    public static final String KEY_NAMES = "names";
    public static final String KEY_EVENTS = "events";
    public static final String KEY_SKILLS = "skills";
    public static final String KEY_FACTORS = "factors";
    private static final String KEY_CACHE_VER = "cache_version";

    // 文件缓存目录名
    private static final String CACHE_DIR = "uma_data_cache";

    /**
     * 异步加载所有数据
     * names/skills/factors从API1（小体积），events从API2（含Values）
     */
    public static void loadAll(Context ctx, DataCallback cb) {
        new Thread(() -> {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            File cacheDir = getDataCacheDir(ctx);

            // API1: 小体积数据，直接拿JSON
            boolean namesOk = loadAndCache(prefs, cacheDir, KEY_NAMES, DATA_BASE + "/uma_names.json");
            boolean skillsOk = loadAndCache(prefs, cacheDir, KEY_SKILLS, DATA_BASE + "/uma_skills.json");
            boolean factorsOk = loadAndCache(prefs, cacheDir, KEY_FACTORS, DATA_BASE + "/uma_factors.json");

            // API2: 事件数据（含Values数组，~12MB）
            boolean eventsOk = loadAndCache(prefs, cacheDir, KEY_EVENTS, DATA_BASE + "/uma_events.json");

            if (namesOk) prefs.edit().putInt(KEY_CACHE_VER, CACHE_VERSION).apply();

            boolean allOk = namesOk && eventsOk && skillsOk && factorsOk;
            Log.d(TAG, "Data load: names=" + namesOk + " events=" + eventsOk
                    + " skills=" + skillsOk + " factors=" + factorsOk);

            if (cb != null) cb.onLoaded(allOk);
        }).start();
    }

    public static boolean isCached(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.getInt(KEY_CACHE_VER, 0) < CACHE_VERSION) return false;
        // events必须存在（文件或SP均可）
        return getCachedData(ctx, KEY_EVENTS) != null;
    }

    /** 获取缓存数据：优先从文件缓存读，fallback到SP */
    public static String getCachedData(Context ctx, String key) {
        // 优先读文件缓存
        File file = new File(getDataCacheDir(ctx), key + ".json");
        if (file.exists() && file.length() > 10) {
            try {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[8192];
                int len;
                while ((len = reader.read(buf)) != -1) {
                    sb.append(buf, 0, len);
                }
                reader.close();
                String data = sb.toString();
                if (data.length() > 10) return data;
            } catch (Exception e) {
                Log.w(TAG, "File cache read failed for " + key + ": " + e.getMessage());
            }
        }

        // Fallback到SP（兼容旧版本缓存）
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String spData = prefs.getString(key, null);
        if (spData != null && !spData.equals("LOADED_V2") && spData.length() > 10) {
            // 迁移到文件缓存
            saveToFileCache(getDataCacheDir(ctx), key, spData);
            return spData;
        }

        return null;
    }

    /** 获取文件缓存目录 */
    private static File getDataCacheDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), CACHE_DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static boolean loadAndCache(SharedPreferences prefs, File cacheDir, String key, String urlStr) {
        String json = httpGet(urlStr);
        if (json != null && json.length() > 10) {
            if (json.length() > 1_500_000) {
                // 大文件：写入文件缓存
                if (saveToFileCache(cacheDir, key, json)) {
                    // SP只存标记，不存实际数据
                    prefs.edit().putString(key, "FILE_CACHED").apply();
                    Log.d(TAG, key + " file cached: " + json.length() + " chars");
                    return true;
                } else {
                    Log.e(TAG, key + " file cache write failed");
                    return false;
                }
            }
            // 小文件：双写SP和文件缓存
            prefs.edit().putString(key, json).apply();
            saveToFileCache(cacheDir, key, json);
            Log.d(TAG, key + " cached: " + json.length() + " chars");
            return true;
        }
        Log.w(TAG, key + " load failed");
        // 加载失败时检查是否有文件缓存
        File cached = new File(cacheDir, key + ".json");
        return cached.exists() && cached.length() > 10;
    }

    private static boolean saveToFileCache(File cacheDir, String key, String json) {
        try {
            File file = new File(cacheDir, key + ".json");
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(json.getBytes("UTF-8"));
            fos.flush();
            fos.close();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "saveToFileCache failed: " + key + " - " + e.getMessage());
            return false;
        }
    }

    private static String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
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
