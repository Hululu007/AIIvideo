package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

/**
 * hysteria2（hy2）出站的装配与解析。
 *
 * <p>为什么现在才支持：核心一直是 v2fly v2ray-core v5，而它的
 * {@code proxy/hysteria2} 从 2025 年就在里面了 —— 是我们自己的订阅解析只认
 * http / socks5 / ss / trojan / vmess / vless 六种，把订阅里那条 UDP 主线路
 * 当「不支持的协议」扔掉了。
 *
 * <p>这套配置有两处和别的协议不一样，也正是这里要锁死的：
 *
 * <ol>
 *   <li><b>密码在传输层</b>（{@code streamSettings.hy2Settings.password}），
 *       settings 里只有 server 列表 —— 和 trojan 把密码放 settings 相反；</li>
 *   <li><b>必须带 TLS</b>：dialer.go 里 {@code tls.ConfigFromStreamSettings}
 *       返回 nil 就直接报 {@code Hy2MustNeedTLS}，所以 security 不许是 none。</li>
 * </ol>
 */
public class XrayHysteria2Test {

    @After
    public void resetRoute() {
        NetRoute.configure(null, null);
    }

    private static final String LINK =
            "hy2://4943ecd58294e366588088f1@1.2.3.4:24444"
                    + "?sni=zhaoruizhe.site&insecure=1&up=50&down=200#hy2-plain";

    private static JSONObject outbound() throws Exception {
        return built(XrayConfig.parseShareLink(LINK)).getJSONArray("outbounds").getJSONObject(0);
    }

    private static JSONObject built(XrayConfig.Node node) throws Exception {
        return XrayConfig.build(node, 20480);
    }

    // ── 链接解析 ───────────────────────────────────────────────────────

    @Test
    public void hy2链接解析出协议与带宽() throws Exception {
        XrayConfig.Node node = XrayConfig.parseShareLink(LINK);
        assertEquals("hysteria2", node.protocol);
        assertEquals("1.2.3.4", node.server);
        assertEquals(24444, node.port);
        assertEquals("4943ecd58294e366588088f1", node.uuid);
        assertEquals("传输层网络名必须跟着协议名", "hysteria2", node.network);
        assertEquals("hysteria2 必须走 TLS", "tls", node.tls);
        assertEquals("zhaoruizhe.site", node.host);
        assertTrue("insecure=1 要落到 allowInsecure", node.allowInsecure);
        assertEquals(50, node.upMbps);
        assertEquals(200, node.downMbps);
        assertEquals("hy2-plain", node.name);
    }

    /** hysteria2:// 这个全名写法也要认。 */
    @Test
    public void 全名写法也认() throws Exception {
        XrayConfig.Node node = XrayConfig.parseShareLink(
                "hysteria2://pw@5.6.7.8:8443?sni=x.example#n");
        assertEquals("hysteria2", node.protocol);
        assertEquals(8443, node.port);
    }

    // ── 配置生成 ───────────────────────────────────────────────────────

    @Test
    public void 出站用hysteria2协议且密码在传输层() throws Exception {
        JSONObject out = outbound();
        assertEquals("proxy", out.getString("tag"));
        assertEquals("hysteria2", out.getString("protocol"));

        // 键是 servers（复数）：conf/v4 那层的 json tag，写成 server 会得到
        // 「0 Hysteria2 server configured.」
        JSONObject server = out.getJSONObject("settings").getJSONArray("servers").getJSONObject(0);
        assertEquals("1.2.3.4", server.getString("address"));
        assertEquals(24444, server.getInt("port"));
        // 密码**不**在 settings 里：v2fly 的 ClientConfig 只有 server 列表。
        assertTrue("settings 里不该有 password",
                !out.getJSONObject("settings").toString().contains("4943ecd5"));

        JSONObject stream = out.getJSONObject("streamSettings");
        assertEquals("hysteria2", stream.getString("network"));
        assertEquals("tls", stream.getString("security"));
        assertEquals("zhaoruizhe.site",
                stream.getJSONObject("tlsSettings").getString("serverName"));
        assertTrue("insecure=1 要传给核心",
                stream.getJSONObject("tlsSettings").getBoolean("allowInsecure"));

        // 键名是 hy2Settings（conf/v4 的 json tag），写成 hysteria2Settings 会被静默丢掉。
        JSONObject hy = stream.getJSONObject("hy2Settings");
        assertEquals("密码必须在传输层", "4943ecd58294e366588088f1", hy.getString("password"));
        JSONObject congestion = hy.getJSONObject("congestion");
        assertEquals("bbr", congestion.getString("type"));
        assertEquals(50, congestion.getInt("up_mbps"));
        assertEquals(200, congestion.getInt("down_mbps"));
    }

    /** 订阅里没写 up/down 时用兜底值 —— 给 0 会让 BBR 没有起点。 */
    @Test
    public void 带宽缺省时用兜底值() throws Exception {
        XrayConfig.Node node = XrayConfig.parseShareLink("hy2://pw@1.2.3.4:24444#n");
        JSONObject hy = built(node).getJSONArray("outbounds").getJSONObject(0)
                .getJSONObject("streamSettings").getJSONObject("hy2Settings");
        JSONObject congestion = hy.getJSONObject("congestion");
        assertTrue("up 兜底值该 > 0", congestion.getInt("up_mbps") > 0);
        assertTrue("down 兜底值该 > 0", congestion.getInt("down_mbps") > 0);
    }

    @Test
    public void 缺密码要报错() throws Exception {
        try {
            XrayConfig.Node node = XrayConfig.parseShareLink("hy2://@1.2.3.4:24444#n");
            built(node);
            fail("缺密码该抛");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    /** outbounds 的顺序不能变：Xray 里「第一条是默认出站」。 */
    @Test
    public void hysteria2仍是第一条出站() throws Exception {
        JSONArray outbounds = built(XrayConfig.parseShareLink(LINK)).getJSONArray("outbounds");
        assertEquals("proxy", outbounds.getJSONObject(0).getString("tag"));
        assertEquals("direct", outbounds.getJSONObject(1).getString("tag"));
        assertEquals("block", outbounds.getJSONObject(2).getString("tag"));
    }
}
