package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import okhttp3.Request;

/**
 * 出站装配点的两条判据。
 *
 * <p>这个类存在的理由就是「两条通道别再各判一遍」，所以测试盯的也是这句话：
 * 请求头模板和路由判定各自只有一处实现，边界行为写在用例里。
 */
public class OutboundTest {

    /** 最小合法源配置（校验项见 AiiHttpRouteTest 那段注释）。 */
    private static SiteProfile profile(String origin, JSONObject headers) throws Exception {
        JSONObject cover = new JSONObject()
                .put("mode", "plain")
                .put("transformation", "AES/CBC/PKCS5Padding")
                .put("allowedHosts", new JSONArray().put("example.com"));
        JSONObject config = new JSONObject()
                .put("origin", origin)
                .put("cover", cover);
        if (headers != null) config.put("headers", headers);
        return new SiteProfile(new JSONObject(), config);
    }

    private static Request.Builder builder() {
        return new Request.Builder().url("https://cdn.example.net/a/b.ts");
    }

    // ── 请求头模板 ─────────────────────────────────────────────────────

    /** {origin} 换成源自己声明的域名，不是目标 URL 的域名。 */
    @Test public void originTakesSiteNotTarget() throws Exception {
        JSONObject headers = new JSONObject().put("Referer", "{origin}/").put("User-Agent", "UA-1");
        Request.Builder b = builder();
        Outbound.applyHeaders(b, profile("https://site.example", headers), "https://cdn.example.net/a/b.ts");
        Request r = b.build();
        assertEquals("https://site.example/", r.header("Referer"));
        assertEquals("UA-1", r.header("User-Agent"));
    }

    /** 源没声明 origin（spider 源）时退回目标 URL 的 origin。 */
    @Test public void originFallsBackToTarget() throws Exception {
        JSONObject headers = new JSONObject().put("Referer", "{origin}/");
        Request.Builder b = builder();
        Outbound.applyHeaders(b, profile("", headers), "https://cdn.example.net/a/b.ts");
        assertEquals("https://cdn.example.net/", b.build().header("Referer"));
    }

    /**
     * 换完是空串或光一个 {@code /} 的头不发。
     * 缺 Referer 是 200，发个错的反而 403 —— 这条在 missav 的图床上量过。
     */
    @Test public void templateThatCollapsesIsDropped() throws Exception {
        JSONObject headers = new JSONObject().put("Referer", "/").put("X-Origin", "{origin}");
        Request.Builder b = builder();
        Outbound.applyHeaders(b, profile("", headers), "not-a-url");
        Request r = b.build();
        assertNull(r.header("Referer"));
        assertNull(r.header("X-Origin"));   // originOf 取不到 → 空串 → {origin} 也塌成空，不发
    }

    /** 没有源配置（首启还没装源）时什么都不做，不 NPE。 */
    @Test public void nullProfileIsNoop() {
        Request.Builder b = builder();
        Outbound.applyHeaders(b, null, "https://cdn.example.net/a/b.ts");
        assertNull(b.build().header("Referer"));
    }

    /** 调用方先设的头，被模板覆盖 —— 源写的才是权威。 */
    @Test public void templateOverridesCallerHeader() throws Exception {
        JSONObject headers = new JSONObject().put("User-Agent", "source-ua");
        Request.Builder b = builder().header("User-Agent", "VLC/3.0.21");
        Outbound.applyHeaders(b, profile("https://site.example", headers), "https://cdn.example.net/a/b.ts");
        assertEquals("source-ua", b.build().header("User-Agent"));
    }

    // ── 路由 ───────────────────────────────────────────────────────────

    /** 直连表里的域一律不走代理，跟全局开关无关。 */
    @Test public void directHostDoesNotTakeProxy() {
        assertFalse(Outbound.viaProxy("https://vip.ffzy-plays.com/x/index.m3u8"));
    }

    /** 解析不出主机名时按「带代理」保守处理 —— 直连超时比 403 更难解释。 */
    @Test public void unparsableTakesProxy() {
        assertTrue(Outbound.viaProxy("::not a uri::"));
        // 空串/ null 不是「解析不出」，是「没地址」，本来就不该有请求 —— 返回 false 不出错。
        assertFalse(Outbound.viaProxy(""));
        assertFalse(Outbound.viaProxy(null));
    }
}
