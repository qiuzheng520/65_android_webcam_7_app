package com.example.webcam7;

import android.text.TextUtils;

/**
 * 视频流地址判断工具。
 */
public final class StreamUrlUtils {

    private StreamUrlUtils() {
        // 工具类，禁止实例化
    }

    /** 根据扩展名判断是否为图片/快照流（MJPEG 网络摄像机）。 */
    public static boolean isImageUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return false;
        }
        String lower = url.toLowerCase();
        int q = lower.indexOf('?');
        if (q >= 0) {
            lower = lower.substring(0, q);
        }
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".gif")
                || lower.endsWith(".bmp") || lower.endsWith(".webp");
    }
}
