package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

/**
 * 测速配置（{@link XrayConfig#buildProbe}）的判据。
 *
 * <p>这个类存在的理由只有一句：<b>探针请求不许被分流规则截去直连</b>。
 *
 * <p>2026-10-31 真机事故 —— {@code PROBE_URL} 是
 * {@code https://www.gstatic.com/generate_204}，而这个域在 geosite 的 cn 分类里
 * （和 {@code gstatic.cn}、{@code gstaticcnapps.cn} 同组）。生产 routing 的
 * {@code geosite:cn → direct} 于是把探针请求送去了直连，测速报的「延迟」是手机
 * 到联通缓存（114.250.64.34）的往返，跟节点毫无关系：
 *
 * <pre>
 *   测速 8 个节点 → 643ms / 85 / 85 / 88 / 84 / 85 …
 *   11:56:08 真正启动 → 所有出站 wss://…/: 404 Not Found
 * </pre>
 *
 * 所以这里锁两件事：探针配置里没有 geosite / DirectHosts / 源声明这些规则；
 * {@code PROBE_URL} 不落在 geosite:cn 里（换成国内直连不通的域，漏了也不会假绿灯）。
 */
public class XrayProbeConfigTest {

    @After
    public void resetRoute() {
        NetRoute.configure(null, null);
    }

    private static XrayConfig.Node node() {
        XrayConfig.Node n = new XrayConfig.Node();
        n.protocol = "vless";
        n.server = "example.net";
        n.port = 443;
        n.uuid = "2ccbb505-15f2-4e31-b309-000000000000";
        // 真机上出事的那个节点就是 vless + ws + tls，照它来。
        n.network = "ws";
        n.tls = "tls";
        n.path = "/ws";
        n.host = "example.net";
        return n;
    }

    /** 生产配置：带上源声明的代理域，让 ③geosite:cn 那条规则一定在。 */
    private static JSONObject production() throws Exception {
        NetRoute.configure(new JSONObject("{\"proxy\":[\"site.example\"]}"), "https://site.example");
        return XrayConfig.build(node(), 20480);
    }

    // ── 探针配置 ───────────────────────────────────────────────────────

    /** 只有一条规则，且强制 proxy —— 不按域、不按入站。 */
    @Test
    public void probeHasExactlyOneForceProxyRule() throws Exception {
        JSONObject routing = XrayConfig.buildProbe(node(), 20480).getJSONObject("routing");
        JSONArray rules = routing.getJSONArray("rules");
        assertEquals("探针配置有且只能有一条规则", 1, rules.length());
        JSONObject rule = rules.getJSONObject(0);
        assertEquals("field", rule.getString("type"));
        assertEquals(XrayConfig.TAG_OUT, rule.getString("outboundTag"));
        assertEquals("tcp,udp", rule.getString("network"));
        // 不许再出现按域裁决的字段 —— 有 domain/ip 就意味着又能被截走。
        assertFalse("探针规则不许带 domain", rule.has("domain"));
        assertFalse("探针规则不许带 ip", rule.has("ip"));
        assertFalse("探针规则不许带 inboundTag", rule.has("inboundTag"));
    }

    /** geosite:cn 那条直连规则在探针配置里必须不存在。这是本次事故的正主。 */
    @Test
    public void probeHasNoGeositeRule() throws Exception {
        String probe = XrayConfig.buildProbeString(node(), 20480);
        assertFalse("探针配置里不许出现 geosite", probe.contains("geosite:"));
        assertFalse("探针配置里不许出现 DirectHosts 的域", probe.contains("site.example"));
        // 分母：同一份源码在生产的 build() 里是有的，否则这条断言等于没测。
        assertTrue("生产配置本该带 geosite:cn", production().toString().contains("geosite:cn"));
    }

    /** outbounds 不动：第一条仍是这个节点，探针只在 routing 上做手术。 */
    @Test
    public void probeKeepsNodeAsFirstOutbound() throws Exception {
        JSONObject probe = XrayConfig.buildProbe(node(), 20480);
        JSONArray outbounds = probe.getJSONArray("outbounds");
        assertTrue(outbounds.length() >= 2);
        JSONObject first = outbounds.getJSONObject(0);
        assertEquals(XrayConfig.TAG_OUT, first.getString("tag"));
        assertEquals("vless", first.getString("protocol"));
        assertNotNull(first.getJSONObject("streamSettings"));
    }

    /** 字符串入口可解析，且内容与对象版一致（Go 侧拿到的是这个串）。 */
    @Test
    public void probeStringParses() throws Exception {
        String s = XrayConfig.buildProbeString(node(), 20480);
        JSONObject parsed = new JSONObject(s);
        assertEquals(1, parsed.getJSONObject("routing").getJSONArray("rules").length());
        assertEquals(XrayConfig.TAG_OUT,
                parsed.getJSONObject("routing").getJSONArray("rules")
                        .getJSONObject(0).getString("outboundTag"));
    }

    // ── 探针目标 ───────────────────────────────────────────────────────

    /**
     * PROBE_URL 不能落在 geosite:cn 里。
     *
     * <p>只锁两个已知会出事的具体域，不做完整 geosite 判定 —— 那个表在 AAR 里，
     * 单测读不到。gstatic 系列（google-cn 那组）和国内直连能通的域都属于「测了
     * 也是白测」的类型。
     */
    @Test
    public void probeUrlIsNotBehindGeositeCn() {
        String url = ProxyCore.PROBE_URL;
        assertTrue("探针地址必须是 https", url.startsWith("https://"));
        assertFalse("gstatic 在 geosite:cn 里，会被 geosite:cn → direct 截走", url.contains("gstatic"));
        assertFalse("CN 站不能当探针：直连通就永远是绿灯", url.contains("baidu"));
        assertFalse("国内可直连的域同理", url.contains(".cn/"));
        // 正主：换成 gstatic 立刻复发，见类注释。
        assertTrue("探针应该指向一个国内直连不通的通用 204", url.contains("generate_204"));
    }
}
