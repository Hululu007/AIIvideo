package com.huangguo.mobile;

/**
 * 原 androidx.media3.common.MimeTypes 里被用到的几个值。
 *
 * 用法全部是"给 WebResourceResponse 一个 Content-Type"，与 ExoPlayer 无关。
 */
public final class Mime {
    private Mime() {}

    public static final String IMAGE_JPEG = "image/jpeg";
    public static final String IMAGE_PNG = "image/png";
    public static final String IMAGE_WEBP = "image/webp";
    public static final String VIDEO_MP4 = "video/mp4";

    /** 这几个值也被当成 getSystemService / JSON 键用，不是 MIME 类型 */
    public static final String BASE_TYPE_AUDIO = "audio";
    public static final String BASE_TYPE_VIDEO = "video";
    public static final String BASE_TYPE_IMAGE = "image";
}
