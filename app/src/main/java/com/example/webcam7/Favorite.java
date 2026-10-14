package com.example.webcam7;

/**
 * 收藏项模型：视频流地址 + 用户自定义标签。
 */
public class Favorite {

    /** 视频流地址。 */
    public final String url;

    /** 用户标签；空字符串表示无标签。 */
    public String tag;

    public Favorite(String url, String tag) {
        this.url = url;
        this.tag = tag == null ? "" : tag;
    }
}
