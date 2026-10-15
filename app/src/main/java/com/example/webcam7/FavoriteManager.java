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

    // ================= 导入 / 导出 =================

    /** 导入结果统计。 */
    public static class ImportResult {

        /** 数据是否解析成功（格式正确）。 */
        public boolean valid;

        /** 新增条数。 */
        public int added;

        /** 更新标签条数。 */
        public int updated;

        /** 已存在且无变化、被跳过的条数。 */
        public int skipped;
    }

    /**
     * 导出全部收藏为带元信息的 JSON 文本，便于备份与分享。
     */
    public static String exportToJson(Context context) {
        try {
            JSONArray array = new JSONArray();
            for (Favorite f : getFavorites(context)) {
                JSONObject obj = new JSONObject();
                obj.put("url", f.url);
                obj.put("tag", f.tag == null ? "" : f.tag);
                array.put(obj);
            }
            JSONObject root = new JSONObject();
            root.put("app", "Webcam7");
            root.put("type", "favorites");
            root.put("version", 1);
            root.put("exportedAt", System.currentTimeMillis());
            root.put("favorites", array);
            return root.toString(2);
        } catch (Exception e) {
            Log.e(TAG, "导出收藏失败", e);
            return "{}";
        }
    }

    /**
     * 从 JSON 文本导入收藏，采用合并策略：
     * 新增不存在的地址；已存在但本地无标签而导入数据有标签时补全标签；
     * 其余重复项跳过。兼容裸数组与带 favorites 字段的对象两种格式。
     */
    public static ImportResult importFromJson(Context context, String json) {
        ImportResult result = new ImportResult();
        JSONArray array = parseFavoritesArray(json);
        if (array == null) {
            return result;
        }
        result.valid = true;
        try {
            List<Favorite> list = getFavorites(context);
            for (int i = 0; i < array.length(); i++) {
                Object element = array.opt(i);
                String url = null;
                String tag = "";
                if (element instanceof String) {
                    url = (String) element;
                } else if (element instanceof JSONObject) {
                    JSONObject obj = (JSONObject) element;
                    url = obj.optString("url", null);
                    tag = obj.optString("tag", "");
                }
                if (TextUtils.isEmpty(url)) {
                    continue;
                }
                Favorite existing = find(list, url);
                if (existing == null) {
                    list.add(new Favorite(url, tag));
                    result.added++;
                } else if (TextUtils.isEmpty(existing.tag) && !TextUtils.isEmpty(tag)) {
                    existing.tag = tag;
                    result.updated++;
                } else {
                    result.skipped++;
                }
            }
            if (result.added > 0 || result.updated > 0) {
                save(context, list);
            }
        } catch (Exception e) {
            Log.e(TAG, "导入收藏失败", e);
            result.valid = false;
        }
        return result;
    }

    /** 解析导入文本为收藏数组，失败返回 null。 */
    private static JSONArray parseFavoritesArray(String json) {
        if (TextUtils.isEmpty(json)) {
            return null;
        }
        try {
            String trimmed = json.trim();
            if (trimmed.startsWith("{")) {
                JSONObject root = new JSONObject(trimmed);
                JSONArray array = root.optJSONArray("favorites");
                return array != null ? array : root.optJSONArray("items");
            }
            return new JSONArray(trimmed);
        } catch (Exception e) {
            Log.e(TAG, "解析导入数据失败", e);
            return null;
        }
    }

    /** 在列表中按地址查找收藏项。 */
    private static Favorite find(List<Favorite> list, String url) {
        for (Favorite f : list) {
            if (TextUtils.equals(f.url, url)) {
                return f;
            }
        }
        return null;
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
