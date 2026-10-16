package com.huangguo.mobile;

import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 把媒体请求从 WebView 手里接管过来，走我们自己的 OkHttp。
 *
 * ── 为什么需要这个 ────────────────────────────────────────────────────
 * 实测（2026-10-16，真机 + WebView 自己的 CDP）：
 *
 *   bilibili  upos-sz-mirrorhw.bilivideo.com   签名地址   HTTP/2  → 403
 *   同一台手机、同一个 IPv6、同一组请求头、同一个地址
 *   bilibili  同一个地址                                  HTTP/1.1 → 206
 *   xigua     v16.toutiao50.com                           HTTP/1.1 → 206
 *
 * 两次响应的后端根本不是同一个服务：
 *
 *   403:  x-service-module: hw-h2-server    via: ...CACHE45[0]
 *   206:  x-service-module: hw-h1-server    via: ...CACHE2[67],...CACHE10[0,TCP_HIT,62]
 *
 * 也就是说**这台 CDN 的 HTTP/2 后端不认 bilibili 的 upsig 签名**。
 * 而 `<video>` 用什么协议是 WebView 自己协商的，WebSettings 里没有开关，
 * 前端也无从干涉 —— 所以只能把请求接过来自己发，指定 HTTP/1.1。
 *
 * 排除过的（都不是原因）：Referer（加 no-referrer 照样 403）、User-Agent、
 * sec-ch-ua*、Accept-Encoding、Range 的六种形态、IPv4/IPv6、容器格式
 * （ftypisom + avc1 + H.264 High，Chrome 里 loadedmetadata 1920x1080 正常）。
 *
 * ── 怎么接 ────────────────────────────────────────────────────────────
 * MainActivity$1 的 shouldInterceptRequest 里，先问一句 handles()，
 * 是媒体域名就 fetch() 拿一个 WebResourceResponse 回去，否则照旧交给
 * WebViewAssetLoader。
 *
 * 只接管下面那三个域名，不做「所有媒体都走代理」—— 别的 CDN 现在好好的，
 * 没有理由把它们也拽进这条新路径上来试错。
 */
public final class MediaProxy {

    /**
     * 要接管的域名（后缀匹配）。
     *
     * bilibili 那一族是实测在 h2 下会 403 的。
     *
     * 白名单里那个视频 CDN 域（2026-10-16 加）**成因不一样**，别混为一谈：
     *
     *   · 它服务器侧完全正常 —— 裸 curl、带 Referer、带 Origin、换 UA，
     *     五种组合全部 206 video/mp4（在手机上量的，不是本机）。
     *   · 编码也正常 —— ftyp isom + avc1（H.264），moov 在文件头（faststart），
     *     是兼容性最好的组合，不是 HEVC 那种 WebView 盲区。
     *   · 但 WebView 直接拉它 → MediaError 4（SRC_NOT_SUPPORTED），
     *     而同一台手机上，别的源播 m3u8 是好的。
     *
     * 所以它挂的是「WebView 自己发的那个请求」，不是这个地址本身。接过来
     * 用 OkHttp 发（HTTP/1.1、我们自己的头），是唯一能改的那一层。
     * 真机日志里修完之后会看到 `[media] 媒体代理 206 http/1.1`。
     *
     * ⚠️ 加新域名之前先量一次。判据：**服务器侧正常、只有 WebView 发才挂** ——
     *    如果服务器侧本身就出错（403 / 404），那接管过来也救不了，
     *    往里加只会让日志多一层噪音。
     */
    private static final String[] HOSTS = {
            "bilivideo.com",
            "bilivideo.cn",
            "bilibili.com",
            "hembed.com",
    };

    /** 是否由我们接管。不看路径 —— 同一个域下的分片、主播放列表都得走同一条路。 */
    public static boolean handles(String url) {
        if (url == null) return false;
        try {
            String host = new java.net.URI(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(java.util.Locale.ROOT);
            for (int i = 0; i < HOSTS.length; i++) {
                String h = HOSTS[i];
                if (host.equals(h) || host.endsWith("." + h)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 强制 HTTP/1.1 的客户端。
     *
     * **不能复用 AiiHttp 的 plainClient** —— 它默认允许 h2，而 h2 正是要绕开的
     * 那个东西。这里 .protocols() 只给 HTTP_1_1，OkHttp 就不会去 ALPN 谈 h2 了。
     *
     * 超时给得比一般请求长：一集 90 MB，虽然每次只取一个 Range，但播放途中
     * 连着取的时候别让读超时把连接掐了。
     */
    private static volatile OkHttpClient mediaClient;

    private static OkHttpClient client() {
        OkHttpClient c = mediaClient;
        if (c == null) {
            synchronized (MediaProxy.class) {
                c = mediaClient;
                if (c == null) {
                    c = new OkHttpClient.Builder()
                            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .writeTimeout(15, TimeUnit.SECONDS)
                            .build();
                    mediaClient = c;
                }
            }
        }
        return c;
    }

    /**
     * 转发一次。拿不到响应就返回 null，调用方回落到 WebView 自己的路径 ——
     * 代理是来补洞的，不该变成新的单点。
     *
     * **必须原样带上客户端的 Range。** 媒体栈靠它做 seek 和拖进度；抽掉的话
     * 要么整集从头读，要么直接判「不支持」。
     */
    public static WebResourceResponse fetch(WebResourceRequest request) {
        try {
            String url = request.getUrl().toString();
            Request.Builder builder = new Request.Builder().url(url).get();

            Map<String, String> asked = request.getRequestHeaders();
            String range = asked == null ? null : asked.get("Range");
            if (range != null && range.length() > 0) builder.header("Range", range);
            // 媒体栈要求原样字节（identity;q=1, *;q=0），转过去。
            builder.header("Accept-Encoding", "identity");

            Response response = client().newCall(builder.build()).execute();
            ResponseBody body = response.body();
            if (body == null) {
                response.close();
                return null;
            }

            // 只透传播放器真正用得上的几个。响应头里那串 via / Age / X-CCDN-*
            // 是 CDN 自己的事，透过去没有意义，反而可能撞上 WebView 的解析。
            Map<String, String> headers = new LinkedHashMap<String, String>();
            copy(response, headers, "Content-Type");
            copy(response, headers, "Content-Length");
            copy(response, headers, "Content-Range");
            copy(response, headers, "Accept-Ranges");
            copy(response, headers, "Last-Modified");
            copy(response, headers, "ETag");

            String mime = response.header("Content-Type", "video/mp4");
            // encoding 传 null —— 二进制流，写 "utf-8" 会让 WebView 去做文本解码。
            WebResourceResponse out = new WebResourceResponse(
                    mime, null, response.code(), reason(response.code()), headers, body.byteStream());
            log("媒体代理 " + response.code() + " " + response.protocol()
                    + " " + url.substring(0, Math.min(90, url.length())));
            // 不 close()：流要一直活到 WebView 读完。它读完会把流关掉，
            // OkHttp 那边跟着释放连接。
            return out;
        } catch (Throwable e) {
            log("媒体代理失败，回落到 WebView：" + e.getClass().getSimpleName() + " " + e.getMessage());
            return null;
        }
    }

    private static void copy(Response response, Map<String, String> out, String name) {
        String v = response.header(name);
        if (v != null && v.length() > 0) out.put(name, v);
    }

    private static void log(String message) {
        try { android.util.Log.d("AIIvideo", "[media] " + message); } catch (Throwable ignored) {}
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 304: return "Not Modified";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 416: return "Range Not Satisfiable";
            default:  return "";
        }
    }
}
