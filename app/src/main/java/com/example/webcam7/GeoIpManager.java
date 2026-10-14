package com.example.webcam7;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * IP 归属地查询工具。
 *
 * <p>通过免费接口 ip-api.com 根据 IP 查询国家，带内存缓存与 SharedPreferences 持久缓存，
 * 避免重复请求与受限流；所有网络操作在后台线程执行，结果回调切回主线程。</p>
 */
public final class GeoIpManager {

    private static final String TAG = "GeoIpManager";
    private static final String PREF_NAME = "webcam7_geo";
    /** 缓存键带 zh 后缀：强制重新查询为中文，避免沿用旧的英文缓存。 */
    private static final String KEY_MAP = "country_map_zh";
    /** ip-api.com 免费接口（http）。 */
    private static final String API = "http://ip-api.com/json/";

    /** host -> country 内存缓存。 */
    private static final Map<String, String> MEMORY = new ConcurrentHashMap<>();
    private static volatile boolean loaded = false;
    private static final Object LOCK = new Object();

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 查询结果回调（始终返回非 null，查询失败返回“未知”）。 */
    public interface CountryCallback {
        void onResult(String country);
    }

    private GeoIpManager() {
    }

    /**
     * 查询某视频流地址所属国家。
     *
     * @param context 上下文
     * @param url     视频流地址
     * @param callback 结果回调（主线程）
     */
    public static void lookupCountry(final Context context, final String url,
                                     final CountryCallback callback) {
        if (context == null || url == null) {
            dispatch(callback, context == null ? "未知"
                    : context.getString(R.string.country_unknown));
            return;
        }
        ensureLoaded(context);
        final Context app = context.getApplicationContext();

        final String host = extractHost(url);
        if (host == null || host.isEmpty()) {
            dispatch(callback, app.getString(R.string.country_unknown));
            return;
        }

        // 命中缓存直接返回
        String cached = MEMORY.get(host);
        if (cached != null && !cached.isEmpty()) {
            dispatch(callback, cached);
            return;
        }

        EXECUTOR.execute(() -> {
            String country = null;
            try {
                String ip = host;
                if (!isIpLiteral(host)) {
                    // 域名先解析为 IP
                    try {
                        ip = InetAddress.getByName(host).getHostAddress();
                    } catch (Exception e) {
                        Log.w(TAG, "域名解析失败: " + host);
                    }
                }
                country = query(ip);
            } catch (Exception e) {
                Log.e(TAG, "查询归属地失败", e);
            }
            if (country != null && !country.isEmpty()) {
                MEMORY.put(host, country);
                persist(app, host, country);
                dispatch(callback, country);
            } else {
                dispatch(callback, app.getString(R.string.country_unknown));
            }
        });
    }

    /** 从缓存预取（同步，仅内存/持久缓存，不发网络请求），供列表快速展示。 */
    public static String getCached(Context context, String url) {
        if (context == null || url == null) {
            return null;
        }
        ensureLoaded(context);
        String host = extractHost(url);
        if (host == null) {
            return null;
        }
        return MEMORY.get(host);
    }

    private static void dispatch(CountryCallback callback, String country) {
        if (callback == null) {
            return;
        }
        final String value = country == null ? "未知" : country;
        MAIN.post(() -> callback.onResult(value));
    }

    private static String extractHost(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isIpLiteral(String h) {
        if (h == null) {
            return false;
        }
        if (h.indexOf(':') >= 0) {
            return true; // IPv6
        }
        return h.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    /** 调用 ip-api.com 查询单个 IP 的国家。 */
    private static String query(String ip) {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            URL u = new URL(API + ip + "?fields=status,country&lang=zh-CN");
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Webcam7/1.0");
            conn.setUseCaches(false);
            conn.connect();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "ip-api 返回码: " + conn.getResponseCode());
                return null;
            }
            in = conn.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            JSONObject obj = new JSONObject(sb.toString());
            if ("success".equalsIgnoreCase(obj.optString("status"))) {
                String country = obj.optString("country", "");
                return country.isEmpty() ? null : country;
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "ip-api 请求失败", e);
            return null;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Exception ignored) {
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 加载持久缓存到内存。 */
    private static void ensureLoaded(Context context) {
        if (loaded) {
            return;
        }
        synchronized (LOCK) {
            if (loaded) {
                return;
            }
            try {
                SharedPreferences sp = context.getApplicationContext()
                        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                String json = sp.getString(KEY_MAP, "{}");
                JSONObject obj = new JSONObject(json == null ? "{}" : json);
                Iterator<String> it = obj.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    MEMORY.put(k, obj.optString(k));
                }
            } catch (Exception e) {
                Log.e(TAG, "读取归属地缓存失败", e);
            }
            loaded = true;
        }
    }

    /** 写入持久缓存。 */
    private static void persist(Context context, String host, String country) {
        synchronized (LOCK) {
            try {
                SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
                String json = sp.getString(KEY_MAP, "{}");
                JSONObject obj = new JSONObject(json == null ? "{}" : json);
                obj.put(host, country);
                sp.edit().putString(KEY_MAP, obj.toString()).apply();
            } catch (Exception e) {
                Log.e(TAG, "保存归属地缓存失败", e);
            }
        }
    }
}
