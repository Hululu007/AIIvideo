package com.huangguo.mobile;

import java.nio.charset.StandardCharsets;

/**
 * 适配器的取字节层 —— `get(url)` 这件事的唯一实现。
 *
 * ── 为什么不能各写各的 ──────────────────────────────────────────────
 * 五个适配器里各有一份 `get()`，做的事一模一样：`AiiHttp.getRaw(url,
 * SiteCfg.browserHeaders(), N秒)` 再把字节转成字符串。差别只在超时写 15 还是 20。
 *
 * 看着无害，但这条路上有三个必须一致的约定：
 *   1. **必须带浏览器 UA** —— okhttp 默认 UA 会被 CloudFront / 各种 WAF 挡
 *      （mrds66 首页 200、分类页 403 就是这么来的）
 *   2. **必须走 AiiHttp** —— 路由（NetRoute）、DoH、HTTP/1.1 降级都在那一层，
 *      绕过它 = 绕过上面所有
 *   3. **空响应要当失败** —— 有的站挡法更阴：200 但 0 字节（porn91）
 *
 * 三条写在注释里没人看得住，写成一份实现才看得住。
 */
final class AdapterIo {

    /** 默认超时。站点慢的时候 15 秒够，再长用户就开始以为死了。 */
    static final int TIMEOUT = 15;

    private AdapterIo() {}

    /** 站点根。origin 空着才退回兜底域名。 */
    static String base(SiteProfile profile, String fallback) {
        String origin = profile == null ? "" : profile.origin;
        if (origin == null) origin = "";
        origin = HtmlKit.trimSlash(origin);
        return origin.length() == 0 ? fallback : origin;
    }

    /** 取一个页面的文本。空响应直接抛 —— 空串往下走会变成「解析出 0 条」，最难查。 */
    static String get(String url) throws Exception {
        return get(url, TIMEOUT);
    }

    static String get(String url, int timeoutSeconds) throws Exception {
        byte[] body = AiiHttp.getRaw(url, SiteCfg.browserHeaders(), timeoutSeconds);
        if (body == null || body.length == 0) throw new Exception("空响应：" + url);
        return new String(body, StandardCharsets.UTF_8);
    }

    /** 取 JSON。解析失败把响应前 120 字节带进异常 —— 那通常是 HTML 错误页。 */
    static org.json.JSONObject getJson(String url) throws Exception {
        return getJson(url, TIMEOUT);
    }

    static org.json.JSONObject getJson(String url, int timeoutSeconds) throws Exception {
        String text = get(url, timeoutSeconds);
        try {
            return new org.json.JSONObject(text);
        } catch (Exception e) {
            String head = text.length() > 120 ? text.substring(0, 120) : text;
            throw new Exception("返回的不是 JSON（" + url + "）：" + head.replaceAll("\\s+", " "));
        }
    }

    /** head 请求式的浅探测：能拿到 ≥1 字节就算通，不解析。 */
    static boolean reachable(String url, int timeoutSeconds) {
        try {
            byte[] body = AiiHttp.getRaw(url, SiteCfg.browserHeaders(), timeoutSeconds);
            return body != null && body.length > 0;
        } catch (Throwable e) {
            return false;
        }
    }
}
