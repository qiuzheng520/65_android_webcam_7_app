package com.example.webcam7;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** 从文本中提取 http(s) 链接。 */
    private static final Pattern URL_PATTERN =
            Pattern.compile("(?i)https?://[^\\s\"'<>()\\[\\]]+");

    /** 视为视频流/快照的扩展名。 */
    private static final String[] STREAM_EXTENSIONS = {
            ".m3u8", ".mp4", ".flv", ".webm", ".mov", ".mkv", ".ts", ".m4v",
            ".avi", ".mpg", ".mpeg", ".ogv", ".3gp", ".mjpg", ".mjpeg",
            ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp"
    };

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
        JSONArray array = parseFavoritesArray(json);
        if (array == null) {
            ImportResult invalid = new ImportResult();
            return invalid;
        }
        return mergeArray(context, array);
    }

    /**
     * 从任意文本导入收藏：优先按 JSON（导出格式或数组）解析，
     * 否则按纯文本提取其中的视频流地址（含 .m3u8/.mp4/cam_N.jpg 等）。
     */
    public static ImportResult importFromContent(Context context, String content) {
        if (TextUtils.isEmpty(content)) {
            return new ImportResult();
        }
        JSONArray array = parseFavoritesArray(content);
        if (array != null) {
            return mergeArray(context, array);
        }
        return importFromPlainText(context, content);
    }

    /** 合并 JSON 数组中的收藏项。 */
    private static ImportResult mergeArray(Context context, JSONArray array) {
        ImportResult result = new ImportResult();
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

    /** 从纯文本中提取视频流地址并合并；优先保留流媒体/快照类地址。 */
    private static ImportResult importFromPlainText(Context context, String text) {
        ImportResult result = new ImportResult();
        result.valid = true;
        List<String> all = new ArrayList<>();
        Matcher matcher = URL_PATTERN.matcher(text);
        while (matcher.find()) {
            String url = stripTrailingPunctuation(matcher.group());
            if (!TextUtils.isEmpty(url)) {
                all.add(url);
            }
        }
        // 过滤出视频流/快照地址；若一条都没有则退化为使用全部链接
        List<String> streamUrls = new ArrayList<>();
        for (String url : all) {
            if (isStreamUrl(url)) {
                streamUrls.add(url);
            }
        }
        List<String> source = streamUrls.isEmpty() ? all : streamUrls;

        try {
            List<Favorite> list = getFavorites(context);
            Set<String> handled = new HashSet<>();
            for (String url : source) {
                if (!handled.add(url)) {
                    continue;
                }
                if (find(list, url) == null) {
                    list.add(new Favorite(url, ""));
                    result.added++;
                } else {
                    result.skipped++;
                }
            }
            if (result.added > 0) {
                save(context, list);
            }
        } catch (Exception e) {
            Log.e(TAG, "导入收藏失败", e);
            result.valid = false;
        }
        return result;
    }

    /** 判断地址是否为视频流/快照（按扩展名）。 */
    private static boolean isStreamUrl(String url) {
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        path = path.toLowerCase();
        for (String ext : STREAM_EXTENSIONS) {
            if (path.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /** 去掉 URL 末尾可能附带的标点。 */
    private static String stripTrailingPunctuation(String url) {
        int end = url.length();
        while (end > 0) {
            char c = url.charAt(end - 1);
            if (",.;:)]}\"'*".indexOf(c) >= 0) {
                end--;
            } else {
                break;
            }
        }
        return url.substring(0, end);
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
