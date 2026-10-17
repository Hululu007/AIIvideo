package com.huangguo.mobile;

/**
 * 原 com.google.common.net.HttpHeaders 里被我们用到的常量。
 * 换成 jadx 前它就是几个字面量，Guava 那一整个包（19MB）为它们不值得。
 */
public final class Http {
    private Http() {}

    public static final String ACCEPT_ENCODING = "Accept-Encoding";
    public static final String ACCEPT_RANGES = "Accept-Ranges";
    public static final String CACHE_CONTROL = "Cache-Control";
    public static final String CONTENT_LENGTH = "Content-Length";
    public static final String CONTENT_RANGE = "Content-Range";
    public static final String CONTENT_SECURITY_POLICY = "Content-Security-Policy";
    public static final String ETAG = "ETag";
    public static final String LAST_MODIFIED = "Last-Modified";
    public static final String LOCATION = "Location";
    public static final String PROXY_AUTHORIZATION = "Proxy-Authorization";
    public static final String RANGE = "Range";
    public static final String USER_AGENT = "User-Agent";
    public static final String X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
}
