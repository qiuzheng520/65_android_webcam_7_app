package com.example.webcam7;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 收藏数据的持久化管理类。
 *
 * <p>使用 SharedPreferences 保存 JSON 数组，每项包含 url 与 tag，保证顺序，
 * 重启 App 后不丢失。兼容旧版本仅保存字符串数组的格式。</p>
 */
public final class FavoriteManager {

    private static final String TAG = "FavoriteManager";
    private static final String PREF_NAME = "webcam7_prefs";
    private static final String KEY_FAVORITES = "favorites_json";

    private FavoriteManager() {
        // 工具类，禁止实例化
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /** 读取全部收藏（保持保存顺序）。 */
    public static List<Favorite> getFavorites(Context context) {
        List<Favorite> result = new ArrayList<>();
        try {
            String json = prefs(context).getString(KEY_FAVORITES, "[]");
            JSONArray array = new JSONArray(json == null ? "[]" : json);
            for (int i = 0; i < array.length(); i++) {
                Object element = array.opt(i);
                if (element instanceof String) {
                    // 旧格式：仅字符串
                    String url = (String) element;
                    if (!TextUtils.isEmpty(url)) {
                        result.add(new Favorite(url, ""));
                    }
                } else if (element instanceof JSONObject) {
                    JSONObject obj = (JSONObject) element;
                    String url = obj.optString("url", "");
                    if (!TextUtils.isEmpty(url)) {
                        result.add(new Favorite(url, obj.optString("tag", "")));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "读取收藏失败", e);
        }
        return result;
    }

    /** 判断某地址是否已收藏。 */
    public static boolean contains(Context context, String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        for (Favorite f : getFavorites(context)) {
            if (TextUtils.equals(f.url, url)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 添加收藏（无标签）。
     *
     * @return true 表示新增成功；false 表示已存在或参数非法
     */
    public static boolean addFavorite(Context context, String url) {
        return addFavorite(context, url, "");
    }

    /**
     * 添加收藏并指定标签。
     *
     * @return true 表示新增成功；false 表示已存在或参数非法
     */
    public static boolean addFavorite(Context context, String url, String tag) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        try {
            List<Favorite> list = getFavorites(context);
            for (Favorite f : list) {
                if (TextUtils.equals(f.url, url)) {
                    return false;
                }
            }
            list.add(new Favorite(url, tag));
            save(context, list);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "添加收藏失败", e);
            return false;
        }
    }

    /** 移除单条收藏。 */
    public static boolean removeFavorite(Context context, String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        try {
            List<Favorite> list = getFavorites(context);
            boolean removed = false;
            for (int i = list.size() - 1; i >= 0; i--) {
                if (TextUtils.equals(list.get(i).url, url)) {
                    list.remove(i);
                    removed = true;
                }
            }
            if (removed) {
                save(context, list);
            }
            return removed;
        } catch (Exception e) {
            Log.e(TAG, "移除收藏失败", e);
            return false;
        }
    }

    /**
     * 添加或修改某条收藏的标签。
     *
     * @return true 表示更新成功
     */
    public static boolean updateTag(Context context, String url, String tag) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        try {
            List<Favorite> list = getFavorites(context);
            boolean found = false;
            for (Favorite f : list) {
                if (TextUtils.equals(f.url, url)) {
                    f.tag = tag == null ? "" : tag;
                    found = true;
                }
            }
            if (found) {
                save(context, list);
            }
            return found;
        } catch (Exception e) {
            Log.e(TAG, "更新标签失败", e);
            return false;
        }
    }

    /** 一键清空收藏。 */
    public static void clearFavorites(Context context) {
        try {
            save(context, new ArrayList<Favorite>());
        } catch (Exception e) {
            Log.e(TAG, "清空收藏失败", e);
        }
    }

    /** 将列表写入 SharedPreferences。 */
    private static void save(Context context, List<Favorite> list) {
        try {
            JSONArray array = new JSONArray();
            for (Favorite f : list) {
                JSONObject obj = new JSONObject();
                obj.put("url", f.url);
                obj.put("tag", f.tag == null ? "" : f.tag);
                array.put(obj);
            }
            prefs(context).edit().putString(KEY_FAVORITES, array.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "保存收藏失败", e);
        }
    }
}
