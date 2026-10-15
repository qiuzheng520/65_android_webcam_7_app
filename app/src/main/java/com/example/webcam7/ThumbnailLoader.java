package com.example.webcam7;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 预览缩略图加载器。
 *
 * <p>后台下载快照图片并做采样压缩，使用内存 LruCache 缓存，供收藏页预览模式展示。
 * 通过给 ImageView 打 tag 避免列表复用导致图片错位。</p>
 */
public final class ThumbnailLoader {

    /** 单张图片最大字节数，超过则放弃，避免异常大图占用内存。 */
    private static final int MAX_BYTES = 3 * 1024 * 1024;

    /** 目标解码宽度（像素），用于计算采样率。 */
    private static final int TARGET_WIDTH = 480;

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static LruCache<String, Bitmap> cache;

    private ThumbnailLoader() {
        // 工具类，禁止实例化
    }

    private static synchronized LruCache<String, Bitmap> cache() {
        if (cache == null) {
            int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
            int cacheSize = Math.max(maxKb / 8, 4 * 1024);
            cache = new LruCache<String, Bitmap>(cacheSize) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount() / 1024;
                }
            };
        }
        return cache;
    }

    /** 加载缩略图到指定的 ImageView；命中缓存则立即显示。 */
    public static void load(ImageView view, String url) {
        if (view == null) {
            return;
        }
        if (TextUtils.isEmpty(url)) {
            view.setImageBitmap(null);
            return;
        }
        view.setTag(url);
        Bitmap cached = cache().get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageBitmap(null);
        EXECUTOR.execute(() -> {
            final Bitmap bitmap = fetch(url);
            if (bitmap != null) {
                cache().put(url, bitmap);
            }
            MAIN.post(() -> {
                if (bitmap != null && url.equals(view.getTag())) {
                    view.setImageBitmap(bitmap);
                }
            });
        });
    }

    /** 下载并解码一张图片。 */
    private static Bitmap fetch(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            conn.setUseCaches(true);
            conn.connect();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            byte[] data;
            try (InputStream in = conn.getInputStream()) {
                data = readBytes(in);
            }
            if (data == null || data.length == 0) {
                return null;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = calcSampleSize(bounds.outWidth, TARGET_WIDTH);
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 读取全部字节，超过上限返回 null。 */
    private static byte[] readBytes(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        int total = 0;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > MAX_BYTES) {
                return null;
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** 按目标宽度计算二的幂采样率。 */
    private static int calcSampleSize(int width, int target) {
        int sample = 1;
        if (width <= 0) {
            return sample;
        }
        while (width / (sample * 2) >= target) {
            sample *= 2;
        }
        return sample;
    }
}
