package com.huangguo.mobile;

/**
 * 原 androidx.media3.extractor.text.ttml.TtmlNode 与 IcyHeaders 里被我们用到的常量。
 *
 * 这两个库在原版里是 ExoPlayer 播放链的依赖（PlayerActivity / HongguoPlayback），
 * 那几个类已经删了。剩下的引用只是 JSON 字段名和 HTTP 头名 —— 为几个字符串
 * 留 31MB 的 media3 不值得，值直接搬过来。
 */
public final class Text {
    private Text() {}

    // TtmlNode
    public static final String ATTR_ID = "id";
    public static final String COMBINE_ALL = "all";
    public static final String TEXT_EMPHASIS_AUTO = "auto";
    public static final String TAG_BODY = "body";
    public static final String TAG_P = "p";
    public static final String TAG_DIV = "div";
    public static final String START = "start";

    // IcyHeaders
    public static final String REQUEST_HEADER_ENABLE_METADATA_NAME = "Icy-MetaData";
    public static final String REQUEST_HEADER_ENABLE_METADATA_VALUE = "1";
}
