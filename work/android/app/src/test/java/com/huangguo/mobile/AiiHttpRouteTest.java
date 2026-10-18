package com.huangguo.mobile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import okhttp3.Dns;
import okhttp3.OkHttpClient;

/**
 * AiiHttp 里「直连表命中 / 要兜底挂代理时，那个新 client 该用哪个 DNS」的决策。
 *
 * <p>为什么单独钉它：两个 bug 都长在这一行上，而它们的症状都是<b>间歇性</b>的 ——
 * <ul>
 *   <li>无条件 {@code .dns(IPV4_FIRST)} 会把 SmartDns 盖掉 → 配置里钉死的地址
 *       突然不生效，表现为「这个站有时候能连有时候不能」，看起来像站点抽风。</li>
 *   <li>{@code proxiedClient(null, …)} 丢掉 profile → POST 那条路上既没有 SmartDns
 *       也没有 hosts 钉死。OpenList 三个接口全是 POST，这是这一轮的真实风险。</li>
 * </ul>
 * 两种都不会崩，只会让人以为是网络问题。所以只测「选哪个」，不测「发出去成不成」。
 *
 * <p>不 mock OkHttp 调用链：{@link AiiHttp#dnsFor} 的输入是一个 client 和一个 profile，
 * 输出是一个 DNS 实现，判据全在里面。
 */
public class AiiHttpRouteTest {

    /**
     * 最小可用的源配置。
     *
     * <p>SiteProfile 的构造要过几道校验（origin 必须是裸 https 域、cover.allowedHosts
     * 非空、mode / transformation 合法），这里照着给齐 —— 少一样就抛，而抛出来的是
     * 「配置不合法」这种和本测试无关的话。
     */
    private static SiteProfile profile() throws Exception {
        JSONObject cover = new JSONObject()
                .put("mode", "plain")
                .put("transformation", "AES/CBC/PKCS5Padding")
                .put("allowedHosts", new JSONArray().put("example.com"));
        JSONObject config = new JSONObject()
                .put("origin", "https://example.com")
                .put("cover", cover);
        return new SiteProfile(new JSONObject(), config);
    }

    /** 一个 DNS 是 OkHttp 默认实现的 client（plainClient 就是这种形态）。 */
    private static OkHttpClient clientWithoutSmartDns() {
        return new OkHttpClient.Builder().build();
    }

    /** 一个 DNS 是 SmartDns 的 client（client(profile) 就是这种形态）。 */
    private static OkHttpClient clientWithSmartDns(SiteProfile profile) {
        return new OkHttpClient.Builder().dns(AiiHttp.smartDnsFor(profile)).build();
    }

    // ── 第一条：手里已经是 SmartDns → 原样保留 ────────────────────────

    /**
     * 手里是 SmartDns 就必须<b>保留同一实例</b>，不能被 IPv4First 之类的外层包装顶掉。
     *
     * <p>这是 rawWith 那个 bug 的正解：以前它无条件 {@code .dns(IPV4_FIRST)}，
     * 于是配置里 {@code hosts} 钉死的 IP 和 DoH 全部失效。
     */
    @Test public void 手里是SmartDns就原样保留() throws Exception {
        SiteProfile profile = profile();
        OkHttpClient http = clientWithSmartDns(profile);
        Dns original = http.dns();
        assertSame("SmartDns 必须原样保留（同一实例）", original, AiiHttp.dnsFor(http, profile));
        // 没传 profile 也一样：client 自己带的 DNS 比外部知道得多。
        assertSame("profile 为空也要保留 client 自己的 SmartDns", original, AiiHttp.dnsFor(http, null));
    }

    // ── 第二条：不是 SmartDns → 按 profile 建 SmartDns，没有 profile 才 IPv4First ──

    /**
     * client 的 DNS 不是 SmartDns、但手上<b>有</b> profile → 换成一个 SmartDns。
     *
     * <p>这条覆盖的就是 POST 的洞：{@code plainClient} 的 DNS 是 OkHttp 默认的，
     * 源声明要走代理时兜底 client 拿不到 SmartDns，hosts 钉死从来没生效过。
     * 判据用「换出来的和 {@code smartDnsFor} 造的是同一个类型，而和原来的不同」，
     * 因为 {@code SmartDns} 是私有嵌套类，外面不能直接写 {@code instanceof}。
     */
    @Test public void 不是SmartDns但有profile就换成SmartDns() throws Exception {
        SiteProfile profile = profile();
        OkHttpClient http = clientWithoutSmartDns();
        Dns picked = AiiHttp.dnsFor(http, profile);
        assertNotNull(picked);
        assertFalse("有 profile 时不能继续用那个默认 DNS", picked == http.dns());
        assertTrue("有 profile 时结果的类型必须是 SmartDns",
                sameType(picked, AiiHttp.smartDnsFor(profile)));
    }

    /**
     * 两样都没有（client 不是 SmartDns、profile 也是 null）→ 那个共享的 IPv4First。
     *
     * <p>这个兜底<b>不能删</b>：IPv6 单栈网络上报给 App 的默认路由没有 IPv4 出口，
     * OkHttp 不会 Happy Eyeballs 降级，拿到 AAAA 就一路连到超时
     * （见 AiiHttp.IPV4_FIRST 的实测注释）。所以它只是「没有别的情报时」的答案，
     * 有 profile 就必须优先 profile。
     *
     * <p>判据用「两次调用返回同一个实例」—— 常量复用，而不是每次 new 一个。
     * 同时确认它不是 SmartDns：否则说明规则被反过来了。
     */
    @Test public void 既没有SmartDns也没有profile就用IPv4First() throws Exception {
        OkHttpClient http = clientWithoutSmartDns();
        Dns picked = AiiHttp.dnsFor(http, null);
        assertNotNull(picked);
        assertSame("没有别的情报时应该是那个共享的 IPv4First 常量", picked, AiiHttp.dnsFor(http, null));
        assertFalse("IPv4First 不能被当成 SmartDns",
                sameType(picked, AiiHttp.smartDnsFor(profile())));
        assertFalse("IPv4First 必须换掉传进来的默认 DNS", picked == http.dns());
    }

    /** client 为 null 也不能 NPE —— 分支只该看 profile。 */
    @Test public void client为null时按profile决定() throws Exception {
        SiteProfile profile = profile();
        Dns withProfile = AiiHttp.dnsFor(null, profile);
        assertNotNull(withProfile);
        assertTrue(withProfile instanceof Dns);
        assertTrue(sameType(withProfile, AiiHttp.smartDnsFor(profile)));
        assertNotNull("client 为 null 且没有 profile 也要给出一个 DNS，不能返回 null",
                AiiHttp.dnsFor(null, null));
    }

    /** 有 profile 时决策可重复：两次都得出 SmartDns，不会第二次退回默认。 */
    @Test public void 有profile时决策可重复() throws Exception {
        SiteProfile profile = profile();
        OkHttpClient http = clientWithoutSmartDns();
        Dns first = AiiHttp.dnsFor(http, profile);
        Dns second = AiiHttp.dnsFor(http, profile);
        assertTrue(sameType(first, AiiHttp.smartDnsFor(profile)));
        assertTrue(sameType(second, AiiHttp.smartDnsFor(profile)));
    }

    // ── 小工具 ────────────────────────────────────────────────────────

    /**
     * 两个 DNS 是不是同一个实现类型。
     *
     * <p>为什么比类名而不是 {@code instanceof}：{@code SmartDns} 是 {@code AiiHttp} 的
     * 私有嵌套类，包外的测试写不出 {@code instanceof SmartDns}。类名比较在这里是
     * 足够强的判据 —— 两个不同实例的类名相同，就说明走的是同一条分支。
     */
    private static boolean sameType(Dns a, Dns b) {
        return a != null && b != null && a.getClass().getName().equals(b.getClass().getName());
    }
}
