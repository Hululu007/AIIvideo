package com.huangguo.mobile;

import org.json.JSONObject;

import java.net.URI;
import java.util.Iterator;

/**
 * 播放层的 HTTP 出口 —— `__http:get|post?payload=<urlencode 后的 JSON>`。
 *
 * ── 为什么要开这个口子 ──────────────────────────────────────────────
 * 页面 CSP 的 connect-src 是 'none'，网络一律走 Java —— 这是刻意的收紧，
 * 所以这个出口也按同样的调子收着：**只放行 https，且主机必须在白名单里**。
 *
 * 触发原因是 CORS 而不是 CSP：播放层的解析接口要带 Video-Parse-Sign 那几个
 * 自定义头，对面的预检响应只回 access-control-allow-origin，没有
 * allow-headers，浏览器根本不会把请求发出去。OkHttp 不受同源策略管，
 * 借这条桥转一手就绕开了。
 *
 * ── 为什么单独一个类 ────────────────────────────────────────────────
 * 2026-10-18 之前它在 {@code UserSource}（2492 行）里，和源配置、代理核心、
 * 播放器控制、进度上报共用同一个类，唯一原因是它们历史上都借同一座桥。
 * 这条链路和它们没有一个共享字段。
 */
final class PlaybackHttpBridge {

    private PlaybackHttpBridge() {}


    private static final String[] HTTP_HOSTS = {
        "jx.wuzhoupai.com",     // AGE动漫 的播放解析
        "xcdn.khfed.com",       // 它的播放器资源
    };



    static JSONObject handle(String body, JSONObject out) {
        try {
            String query = body;
            int mark = body.indexOf('?');
            if (mark >= 0) query = body.substring(mark + 1);
            String payload = UserSource.param(query, "payload");
            if (payload.length() == 0) return UserSource.fail(out, "HTTP 指令没有 payload");

            JSONObject req = new JSONObject(payload);
            String url = req.optString("url", "");
            if (!url.startsWith("https://")) return UserSource.fail(out, "只放行 https：" + url);
            String host = URI.create(url).getHost();
            boolean allowed = false;
            for (String h : HTTP_HOSTS) {
                if (h.equalsIgnoreCase(host)) { allowed = true; break; }
            }
            if (!allowed) return UserSource.fail(out, "主机不在播放层白名单里：" + host);

            java.util.Map<String, String> headers = new java.util.LinkedHashMap<String, String>();
            JSONObject head = req.optJSONObject("headers");
            if (head != null) {
                for (Iterator<String> it = head.keys(); it.hasNext(); ) {
                    String k = it.next();
                    headers.put(k, head.optString(k, ""));
                }
            }

            byte[] raw = body.startsWith("post")
                    ? AiiHttp.postFormRaw(url, req.optString("body", ""), headers, 15)
                    : AiiHttp.getRaw(url, headers, 15);

            out.put("ok", true);
            out.put("body", new String(raw, "UTF-8"));
            return out;
        } catch (Throwable e) {
            return UserSource.fail(out, "HTTP 失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }
}
