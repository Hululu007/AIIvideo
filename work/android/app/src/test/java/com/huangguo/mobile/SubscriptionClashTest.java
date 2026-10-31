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

        // 那条 vmess 缺 uuid，转不成链接：算「看不懂被跳过」，不是「协议不支持」
        assertEquals("缺 uuid 的映射该记进 failed", 1, r.failed);
        assertEquals("vmess 现在是支持的协议，不该进 unsupported 名单",
                0, r.unsupported);
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

    /**
     * Clash Verge / CMFA 导出的 vmess 与 vless（含 reality）要能转出来。
     *
     * 这两类占了 Clash 订阅的绝大多数，只转 ss/trojan 的话正常配置导进来是 0 个节点。
     */
    @Test
    public void VMESS与VLESS都能转成链接() throws Exception {
        String yaml = "proxies:\n"
                + "  - name: \"bwh-usca6-reality\"\n"
                + "    type: vless\n"
                + "    server: 64.64.224.121\n"
                + "    port: 443\n"
                + "    uuid: 2ccbb505-15f2-4e31-b309-169ba1cbaf39\n"
                + "    network: tcp\n"
                + "    udp: true\n"
                + "    tls: true\n"
                + "    flow: xtls-rprx-vision\n"
                + "    servername: www.samsung.com\n"
                + "    client-fingerprint: chrome\n"
                + "    skip-cert-verify: false\n"
                + "    reality-opts:\n"
                + "      public-key: Cf3w2GhZxnWntQlWimHBx3a2oGvuPrqXEn1zrj8IMxg\n"
                + "      short-id: d5710f7e182acefb\n"
                + "  - name: ws 节点\n"
                + "    type: vmess\n"
                + "    server: 1.2.3.4\n"
                + "    port: 8443\n"
                + "    uuid: 11111111-2222-3333-4444-555555555555\n"
                + "    alterId: 0\n"
                + "    cipher: auto\n"
                + "    network: ws\n"
                + "    tls: true\n"
                + "    ws-opts:\n"
                + "      path: /ray\n"
                + "      headers:\n"
                + "        Host: cdn.example.com\n";

        Subscription.Result r = Subscription.parse(yaml);
        assertEquals("两条都该认出来：" + r.firstError, 2, r.size());

        XrayConfig.Node vless = r.nodes.get(0);
        assertEquals("vless", vless.protocol);
        assertEquals("64.64.224.121", vless.server);
        assertEquals("reality", vless.tls);
        assertEquals("xtls-rprx-vision", vless.flow);
        assertEquals("www.samsung.com", vless.host);
        assertEquals("chrome", vless.fingerprint);
        assertEquals("Cf3w2GhZxnWntQlWimHBx3a2oGvuPrqXEn1zrj8IMxg", vless.publicKey);
        assertEquals("d5710f7e182acefb", vless.shortId);
        // 能生成出来才算真认得
        String cfg = XrayConfig.build(vless, 20482).toString();
        assertTrue("reality 没写进配置：" + cfg, cfg.contains("reality"));

        XrayConfig.Node vmess = r.nodes.get(1);
        assertEquals("vmess", vmess.protocol);
        assertEquals("1.2.3.4", vmess.server);
        assertEquals(8443, vmess.port);
        assertEquals("ws", vmess.network);
        assertEquals("tls", vmess.tls);
        assertEquals("/ray", vmess.path);
        assertEquals("cdn.example.com", vmess.host);
        assertNotNull(XrayConfig.build(vmess, 20483));

        assertEquals(0, r.failed);
        assertEquals(0, r.unsupported);
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

    /**
     * 行内写法 {@code ws-opts: {path: /ws, headers: {Host: x}}} 必须摊平。
     *
     * <p>2026-10-31 真机：订阅里 8 条节点全是这个写法，解析器只认「整行
     * key: value」，于是 {@code ws-opts} 整段花括号被当成一个值塞进去，
     * {@code pick(p,"path")} 取空 —— 拼出来的链接没有 {@code &path=}，
     * Xray 按默认 {@code /} 去拨 {@code wss://host/}，nginx 上只有
     * {@code location = /ws}，每一条出站都 404。
     */
    @Test
    public void 行内_ws_opts_摊平出_path_和_host() {
        String yaml = "proxies:\n"
                + "  - name: cf-ws\n"
                + "    type: vless\n"
                + "    server: zhaoruizhe.site\n"
                + "    port: 443\n"
                + "    uuid: 2ccbb505-15f2-4e31-b309-169ba1cbaf39\n"
                + "    tls: true\n"
                + "    network: ws\n"
                + "    servername: zhaoruizhe.site\n"
                + "    ws-opts: {path: /ws, headers: {Host: zhaoruizhe.site}}\n";
        Subscription.Result r = new Subscription.Result();
        java.util.List<String> links = Subscription.clashToLinks(yaml, r);
        assertEquals("这条该转出 1 条链接：" + links, 1, links.size());
        String link = links.get(0);
        assertTrue("path 丢了就会被拨成 wss://host/：" + link, link.contains("path=%2Fws"));
        assertTrue("Host 丢了握手会被 CF 按别的站回 404：" + link, link.contains("host=zhaoruizhe.site"));
        assertTrue(link.startsWith("vless://2ccbb505-15f2-4e31-b309-169ba1cbaf39@zhaoruizhe.site:443"));
        assertTrue("tls 不能丢", link.contains("security=tls"));
    }

    /** grpc 的行内写法同理：grpc-service-name 要能取到。 */
    @Test
    public void 行内_grpc_opts_摊平出_service_name() {
        String yaml = "proxies:\n"
                + "  - name: g\n"
                + "    type: vless\n"
                + "    server: 1.2.3.4\n"
                + "    port: 443\n"
                + "    uuid: 11111111-2222-3333-4444-555555555555\n"
                + "    network: grpc\n"
                + "    grpc-opts: {grpc-service-name: mygrpc}\n";
        Subscription.Result r = new Subscription.Result();
        java.util.List<String> links = Subscription.clashToLinks(yaml, r);
        assertEquals(1, links.size());
        assertTrue("serviceName 没摊平：" + links.get(0), links.get(0).contains("serviceName=mygrpc"));
    }

    /** 多行缩进写法不能因为这次改动而坏掉。 */
    @Test
    public void 缩进写法的_ws_path_仍然可用() {
        String yaml = "proxies:\n"
                + "  - name: ws2\n"
                + "    type: vless\n"
                + "    server: 5.6.7.8\n"
                + "    port: 443\n"
                + "    uuid: 11111111-2222-3333-4444-555555555555\n"
                + "    network: ws\n"
                + "    ws-opts:\n"
                + "      path: /abc\n"
                + "      headers:\n"
                + "        Host: 5.6.7.8\n";
        Subscription.Result r = new Subscription.Result();
        java.util.List<String> links = Subscription.clashToLinks(yaml, r);
        assertEquals(1, links.size());
        assertTrue("缩进写法的 path 丢了：" + links.get(0), links.get(0).contains("path=%2Fabc"));
    }


    /**
     * 订阅里那条 UDP 主线路：hysteria2，无混淆，要能转出来。
     *
     * <p>之前它被当「不支持的协议」扔了 —— 而它是唯一不经过中转、也不经过
     * CF 边缘的直连路径。
     */
    @Test
    public void hysteria2_无混淆要转出() {
        String yaml = "proxies:\n"
                + "  - name: hy2\n"
                + "    type: hysteria2\n"
                + "    server: 1.2.3.4\n"
                + "    port: 24444\n"
                + "    password: abc123\n"
                + "    sni: zhaoruizhe.site\n"
                + "    skip-cert-verify: true\n"
                + "    up: 50 Mbps\n"
                + "    down: 200 Mbps\n";
        Subscription.Result r = new Subscription.Result();
        java.util.List<String> links = Subscription.clashToLinks(yaml, r);
        assertEquals("该转出 1 条：" + links, 1, links.size());
        String link = links.get(0);
        assertTrue("前缀不对：" + link, link.startsWith("hy2://abc123@1.2.3.4:24444?"));
        assertTrue("sni 丢了：" + link, link.contains("sni=zhaoruizhe.site"));
        assertTrue("自签证书要标 insecure：" + link, link.contains("insecure=1"));
        assertTrue("带宽要带单位前的数字：" + link, link.contains("up=50") && link.contains("down=200"));
        assertEquals("不该记成不支持", 0, r.unsupported);
    }

    /**
     * 带 salamander 混淆的节点要**明确记账**，不能静默转出去。
     *
     * <p>核心（v2fly 的 transport/internet/hysteria2）没有 obfs 字段，转出去
     * 必然连不上，而且用户看到的是「节点在列表里、就是上不了网」，比直接说明
     * 白更糟。
     */
    @Test
    public void hysteria2_带obfs要明确跳过() {
        String yaml = "proxies:\n"
                + "  - name: hy2-obfs\n"
                + "    type: hysteria2\n"
                + "    server: 5.6.7.8\n"
                + "    port: 24443\n"
                + "    password: abc123\n"
                + "    obfs: salamander\n"
                + "    obfs-password: zzz\n";
        Subscription.Result r = new Subscription.Result();
        java.util.List<String> links = Subscription.clashToLinks(yaml, r);
        assertEquals("带混淆的不该转出", 0, links.size());
        assertEquals("要记进 unsupported", 1, r.unsupported);
        assertTrue("原因要能读出来：" + r.unsupportedNames,
                r.unsupportedNames.toString().contains("salamander"));
    }


    /**
     * 订阅直接给 hy2:// 链接（不经 Clash 转换）时，不能又被判「不支持」。
     *
     * <p>2026-10-31 踩过：hy2 加进了解析器，但 `Subscription.looksLikeLink` 的
     * 白名单忘了同步，链接在进解析器之前就被判掉，界面写「1 个不支持（hy2）」。
     * 这条用例同时盯住两个入口（clash 转换 / 原生链接），任何一处漏了都会红。
     */
    @Test
    public void 原生hy2链接要能进解析器() {
        String sub = "hy2://abc123@1.2.3.4:24444?sni=x.example&insecure=1&up=50&down=200#原生hy2\n";
        Subscription.Result r = Subscription.parse(sub);
        assertEquals("该认出 1 个节点：" + r.nodes.size(), 1, r.nodes.size());
        assertEquals("不该记成不支持：" + r.unsupportedNames, 0, r.unsupported);
        XrayConfig.Node node = r.nodes.get(0);
        assertEquals("hysteria2", node.protocol);
        assertEquals(24444, node.port);
    }

}
