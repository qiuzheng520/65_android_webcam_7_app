package com.example.webcam7;

/**
 * 视频流列表项数据模型：链接 + 归属国家 + 用户标签。
 */
public class StreamItem {

    /** 视频流地址。 */
    public final String url;

    /** 国家名称；null 表示尚未查询到（查询中）。 */
    public String country;

    /** 用户标签；空字符串表示无标签。 */
    public String tag;

    public StreamItem(String url) {
        this.url = url;
        this.tag = "";
    }

    public StreamItem(String url, String tag) {
        this.url = url;
        this.tag = tag == null ? "" : tag;
    }
}
