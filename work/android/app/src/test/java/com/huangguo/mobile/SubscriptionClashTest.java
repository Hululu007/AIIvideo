package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * Clash YAML 订阅的解析（不联网）。
 *
 * 以前这一档是直接拒的（「请在机场后台改用 V2Ray 订阅」）。很多公开订阅只给
 * YAML，所以补了一个**够用就好**的子集解析器：只认 `proxies:` 下面那几行。
 */
public class SubscriptionClashTest {

    private static final String YAML =
            "port: 7890\n"
            + "allow-lan: true\n"
            + "mode: Global\n"
            + "proxies:\n"
            + "  - name: 荷兰 - 阿姆斯特丹 - 1\n"
            + "    server: 84.17.47.125\n"
            + "    port: 9002\n"
            + "    type: http\n"
            + "    tls: true\n"
            + "    skip-cert-verify: true\n"
            + "  - name: 普通 socks\n"
            + "    server: 10.0.0.9\n"
            + "    port: 1080\n"
            + "    type: socks5\n"
            + "  - name: 一个 vmess\n"
            + "    server: 1.2.3.4\n"
            + "    port: 443\n"
            + "    type: vmess\n"
            + "proxy-groups:\n"
            + "  - name: auto\n"
            + "    type: url-test\n";

    @Test
    public void YAML能转成链接() {
        Subscription.Result r = new Subscription.Result();
        java.util.List<String> links = Subscription.clashToLinks(YAML, r);
        assertEquals("只该转出 http 和 socks5 两条", 2, links.size());
        assertTrue("https 代理没识别：" + links.get(0), links.get(0).startsWith("https://84.17.47.125:9002"));
        assertTrue("名字没带过去", links.get(0).contains("#"));
        assertTrue(links.get(1).startsWith("socks5://10.0.0.9:1080"));

        // vmess 这一版不转，但要计数 —— 用户得知道「有 1 条没认」
        assertEquals(1, r.unsupported);
        assertEquals("vmess", r.unsupportedNames.iterator().next());
    }

    /** 整份 YAML 走 parse()，出来的节点要能生成 Xray 配置。 */
    @Test
    public void YAML整份能被解析成节点() throws Exception {
        Subscription.Result r = Subscription.parse(YAML);
        assertNotNull(r);
        assertEquals("应该认出 2 条", 2, r.size());
        assertEquals("http", r.nodes.get(0).protocol);
        assertEquals("84.17.47.125", r.nodes.get(0).server);
        assertEquals(9002, r.nodes.get(0).port);
        assertEquals("tls", r.nodes.get(0).tls);
        assertTrue("自签证书必须跳过校验", r.nodes.get(0).allowInsecure);

        JSONObject config = XrayConfig.build(r.nodes.get(0), 20481);
        String text = config.toString();
        assertTrue("没写出 tls：" + text, text.contains("\"security\":\"tls\""));
        assertTrue("没写 allowInsecure", text.contains("\"allowInsecure\":true"));
        assertTrue("没走 http 出站", text.contains("\"protocol\":\"http\""));
    }

    /** 反例：认不出任何代理时给一句人话，而不是「0 个节点」。 */
    @Test
    public void 认不出代理时给出原因() {
        Subscription.Result r = Subscription.parse("proxies:\n  - name: x\n    type: vmess\n");
        assertEquals(0, r.size());
        assertNotNull("应该给原因", r.firstError);
        assertTrue(r.firstError.contains("Clash"));
    }

    /** 普通节点列表不能因为「看起来像 YAML」被误判。 */
    @Test
    public void 普通链接列表不受影响() {
        Subscription.Result r = Subscription.parse("trojan://pw@example.com:443#n1\n");
        assertEquals(1, r.size());
        assertEquals("trojan", r.nodes.get(0).protocol);
    }
}
