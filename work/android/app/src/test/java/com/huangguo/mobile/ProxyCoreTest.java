package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.ServerSocket;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * ProxyCore 与 XrayConfig 的单元测试。
 *
 * 只测纯逻辑：端口分配、配置 JSON 的结构、分享链接解析。native 那部分
 * （.so 只有 arm64-v8a，JVM 里加载不了）留给真机，这里碰它等于给 CI 埋雷。
 *
 * 断言风格：先用 flatten() 把 JSON 摊成 Map 再按路径取值。JSONObject 的迭代
 * 顺序不保证，逐层 optJSONObject 的写法一个字段写三行，一个协议写二十行。
 */
public class ProxyCoreTest {

    // ── 端口 ──────────────────────────────────────────────────────────

    /** 系统分配的那个端口必须真的能用：拿到之后立刻 bind 一次不该失败。 */
    @Test
    public void 分配到的端口本机可以绑定() throws Exception {
        int port = ProxyCore.pickPort();
        assertTrue("端口应在合法区间：" + port, port > 0 && port <= 65535);
        assertTrue("回环口应该空的：" + port, ProxyCore.probe(port));
    }

    /** 默认区间是 20000-40000，避开 1080 那个行内默认值。 */
    @Test
    public void 默认端口区间避开常见默认值() throws Exception {
        for (int i = 0; i < 8; i++) {
            int port = ProxyCore.pickPort();
            assertTrue("不在 20000-40000 里：" + port, port >= 20000 && port <= 40000);
            assertTrue("不该是 1080（会与 v2rayNG 撞）", port != 1080);
            // 交出去的端口必须真的空着 —— ProxyCore 里那是 close 之后再用的。
            assertTrue("这个端口其实有人占：" + port, ProxyCore.probe(port));
        }
    }

    /** 连续分配不该互相撞 —— 关掉再交出去那段窗口里的拿到的应该是不同端口。 */
    @Test
    public void 连续两次分配基本不重复() throws Exception {
        int a = ProxyCore.freePort();
        int b = ProxyCore.freePort();
        assertTrue("系统分配失败", a > 0 && b > 0);
        // 理论上可能重（TIME_WAIT 放开了），但概率极低；不重复才是正常路径。
        assertFalse("两次拿到同一个端口，说明探测没起作用：" + a, a == b);
    }

    /** 端口被真占着的时候，probe 必须说「不空」。 */
    @Test
    public void 被占用的端口探测为不可用() throws Exception {
        ServerSocket occupying = new ServerSocket(0, 1,
                java.net.InetAddress.getByName("127.0.0.1"));
        try {
            int busy = occupying.getLocalPort();
            assertFalse("这个端口正被占着：" + busy, ProxyCore.probe(busy));
        } finally {
            occupying.close();
        }
    }

    /** 区间非法要报错，不能悄悄返回一个随机端口。 */
    @Test
    public void 非法区间抛异常() throws Exception {
        try {
            ProxyCore.pickPort(0, 100);
            fail("min=0 应该被拒");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            ProxyCore.pickPort(30000, 20000);
            fail("min>max 应该被拒");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // ── inbound ───────────────────────────────────────────────────────

    /** 最关键的一条：监听地址必须是 127.0.0.1。写错成 0.0.0.0 就是给同网段免费开代理。 */
    @Test
    public void 入站只监听回环地址() throws Exception {
        Map<String, Object> flat = XrayConfig.flatten(XrayConfig.build(node(), 23456));
        assertEquals("127.0.0.1", flat.get("inbounds[0].listen"));
        assertEquals(23456, flat.get("inbounds[0].port"));
        assertEquals("socks", flat.get("inbounds[0].protocol"));
        assertEquals(XrayConfig.TAG_IN, flat.get("inbounds[0].tag"));
    }

    /** SOCKS5 不认证、开 UDP、域名交给落地解析。 */
    @Test
    public void 入站设置项齐全() throws Exception {
        Map<String, Object> flat = XrayConfig.flatten(XrayConfig.build(node(), 23456));
        assertEquals("noauth", flat.get("inbounds[0].settings.auth"));
        assertEquals(true, flat.get("inbounds[0].settings.udp"));
        // ip=127.0.0.1 是让 SOCKS5 请求里的域名原样交给落地节点解析，
        // 不给的话本地先解析，DNS 就露在本地网络里了。
        assertEquals("127.0.0.1", flat.get("inbounds[0].settings.ip"));
        assertEquals(true, flat.get("inbounds[0].sniffing.enabled"));
    }

    // ── vmess ─────────────────────────────────────────────────────────

    @Test
    public void vmess出站字段齐全() throws Exception {
        XrayConfig.Node node = node();
        node.protocol = "vmess";
        node.uuid = "b831381d-6324-4d53-ad4f-8cda48b30811";
        node.alterId = 0;
        Map<String, Object> flat = XrayConfig.flatten(XrayConfig.build(node, 23456));

        assertEquals("vmess", flat.get("outbounds[0].protocol"));
        assertEquals(XrayConfig.TAG_OUT, flat.get("outbounds[0].tag"));
        assertEquals("1.2.3.4", flat.get("outbounds[0].settings.vnext[0].address"));
        assertEquals(443, flat.get("outbounds[0].settings.vnext[0].port"));
        assertEquals("b831381d-6324-4d53-ad4f-8cda48b30811",
                flat.get("outbounds[0].settings.vnext[0].users[0].id"));
        assertEquals(0, flat.get("outbounds[0].settings.vnext[0].users[0].alterId"));
        assertEquals("auto", flat.get("outbounds[0].settings.vnext[0].users[0].security"));
    }

    /** 老机场要 alterId=64，别被默认值吞掉。 */
    @Test
    public void vmess保留alterId() throws Exception {
        XrayConfig.Node node = node();
        node.protocol = "vmess";
        node.uuid = "uuid";
        node.alterId = 64;
        Map<String, Object> flat = XrayConfig.flatten(XrayConfig.build(node, 23456));
        assertEquals(64, flat.get("outbounds[0].settings.vnext[0].users[0].alterId"));
    }

    @Test
    public void vmess缺uuid要报错() throws Exception {
        // node() 预置了 uuid，这里必须清掉才是「缺」的状态
        XrayConfig.Node node = node();
        node.protocol = "vmess";
        node.uuid = null;
        try {
            XrayConfig.build(node, 23456);
            fail("缺 uuid 应该抛");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("uuid"));
        }
    }

    // ── shadowsocks ───────────────────────────────────────────────────

    @Test
    public void ss出站字段齐全() throws Exception {
        XrayConfig.Node node = node();
        node.protocol = "shadowsocks";
        node.method = "chacha20-ietf-poly1305";
        node.uuid = "secret-pass";
        Map<String, Object> flat = XrayConfig.flatten(XrayConfig.build(node, 23456));

        assertEquals("shadowsocks", flat.get("outbounds[0].protocol"));
        assertEquals("1.2.3.4", flat.get("outbounds[0].settings.servers[0].address"));
        assertEquals(443, flat.get("outbounds[0].settings.servers[0].port"));
        assertEquals("chacha20-ietf-poly1305",
                flat.get("outbounds[0].settings.servers[0].method"));
        assertEquals("secret-pass", flat.get("outbounds[0].settings.servers[0].password"));
    }

    /**
     * 调用方只填了 method（把密码塞进了加密方式那一格）时，不能生成一个
     * 「password 是加密方式名、method 也还是它」的怪配置。
     *
     * 单独 new 一个空节点而不是用 node()：后者预置了 uuid，测不到这条分支。
     */
    @Test
    public void ss只在method里有值时按密码处理() throws Exception {
        XrayConfig.Node node = new XrayConfig.Node("shadowsocks", "1.2.3.4", 443);
        node.method = "my-password";
        node.uuid = null;
        Map<String, Object> flat = XrayConfig.flatten(XrayConfig.build(node, 23456));
        assertEquals("my-password", flat.get("outbounds[0].settings.servers[0].password"));
        assertEquals("aes-256-gcm", flat.get("outbounds[0].settings.servers[0].method"));
    }

    // ── trojan / vless ────────────────────────────────────────────────

    @Test
    public void trojan出站字段齐全() throws Exception {
        XrayConfig.Node node = node();
        node.protocol = "trojan";
        node.uuid = "trojan-pass";
        node.tls = "tls";
        node.host = "example.com";
        node.network = "ws";
        node.path = "/ray";
        Map<String, Object> flat = XrayConfig.flatten(XrayConfig.build(node, 23456));

        assertEquals("trojan", flat.get("outbounds[0].protocol"));
        assertEquals("1.2.3.4", flat.get("outbounds[0].settings.servers[0].address"));
        assertEquals("trojan-pass", flat.get("outbounds[0].settings.servers[0].password"));
        assertEquals("ws", flat.get("outbounds[0].streamSettings.network"));
        assertEquals("tls", flat.get("outbounds[0].streamSettings.security"));
        assertEquals("example.com", flat.get("outbounds[0].streamSettings.tlsSettings.serverName"));
        assertEquals("/ray", flat.get("outbounds[0].streamSettings.wsSettings.path"));
        assertEquals("example.com",
                flat.get("outbounds[0].streamSettings.wsSettings.headers.Host"));
    }

    @Test
    public void vless出站字段齐全() throws Exception {
        XrayConfig.Node node = node();
        node.protocol = "vless";
        node.uuid = "8f7a1c66-4c1e-4f1a-9e2b-5c0a3d8b7e11";
        node.flow = "xtls-rprx-vision";
        node.tls = "tls";
        Map<String, Object> flat = XrayConfig.flatten(XrayConfig.build(node, 23456));

        assertEquals("vless", flat.get("outbounds[0].protocol"));
        assertEquals("8f7a1c66-4c1e-4f1a-9e2b-5c0a3d8b7e11",
                flat.get("outbounds[0].settings.vnext[0].users[0].id"));
        // vless 必须显式写 encryption=none，协议本身不做应用层加密
        assertEquals("none", flat.get("outbounds[0].settings.vnext[0].users[0].encryption"));
        assertEquals("xtls-rprx-vision",
                flat.get("outbounds[0].settings.vnext[0].users[0].flow"));
    }

    // ── 传输层 ────────────────────────────────────────────────────────

    /** 裸 tcp + 无 TLS 是 Xray 的默认值，不该出现在配置里（少一层噪音）。 */
    @Test
    public void 纯tcp不出streamSettings() throws Exception {
        XrayConfig.Node node = node();
        node.protocol = "vmess";
        node.uuid = "uuid";
        node.network = "tcp";
        node.tls = "none";
        Map<String, Object> flat = XrayConfig.flatten(XrayConfig.build(node, 23456));
        assertNull(flat.get("outbounds[0].streamSettings"));
    }

    @Test
    public void grpc走serviceName() throws Exception {
        XrayConfig.Node node = node();
        node.protocol = "vless";
        node.uuid = "uuid";
        node.network = "grpc";
        node.serviceName = "my-grpc";
        node.tls = "tls";
        Map<String, Object> flat = XrayConfig.flatten(XrayConfig.build(node, 23456));
        assertEquals("grpc", flat.get("outbounds[0].streamSettings.network"));
        assertEquals("my-grpc", flat.get("outbounds[0].streamSettings.grpcSettings.serviceName"));
    }

    /** SNI 没单独填时用节点地址，别留空 —— 留空 TLS 握手直接失败。 */
    @Test
    public void tls的SNI缺省用服务端地址() throws Exception {
        XrayConfig.Node node = node();
        node.protocol = "trojan";
        node.uuid = "pass";
        node.tls = "tls";
        node.host = "";
        Map<String, Object> flat = XrayConfig.flatten(XrayConfig.build(node, 23456));
        assertEquals("1.2.3.4", flat.get("outbounds[0].streamSettings.tlsSettings.serverName"));
    }

    // ── 整体结构 ──────────────────────────────────────────────────────

    /** 出站三条：代理 → 直连 → 拦截。少了 freedom/blackhole，routing 引用的 tag 就悬空。 */
    @Test
    public void 出站包含代理直连和拦截三条() throws Exception {
        Map<String, Object> flat = XrayConfig.flatten(XrayConfig.build(node(), 23456));
        assertEquals(3, flat.get("outbounds.length"));
        assertEquals(XrayConfig.TAG_OUT, flat.get("outbounds[0].tag"));
        assertEquals(XrayConfig.TAG_DIRECT, flat.get("outbounds[1].tag"));
        assertEquals("freedom", flat.get("outbounds[1].protocol"));
        assertEquals("blackhole", flat.get("outbounds[2].protocol"));
    }

    /** loglevel 用 warning：info 在播放时不间断刷屏，真出问题反而找不到有用的行。 */
    @Test
    public void 日志级别是warning() throws Exception {
        Map<String, Object> flat = XrayConfig.flatten(XrayConfig.build(node(), 23456));
        assertEquals("warning", flat.get("log.loglevel"));
    }

    /** api 服务不能带 listen —— 带了就是本机开个控制台，同机任何 App 都能改路由。 */
    @Test
    public void 统计api不监听端口() throws Exception {
        JSONObject api = XrayConfig.build(node(), 23456).getJSONObject("api");
        assertEquals("api", api.getString("tag"));
        assertFalse("api 不该有 listen 字段", api.has("listen"));
        assertFalse("api 不该有 port 字段", api.has("port"));
    }

    /**
     * queryStats 要有东西可查，stats 与 policy 得开。
     *
     * "stats" 是个空对象，flatten 之后不会留下任何键 —— 所以这里只能直接问
     * JSONObject 要，不能走 flatten 的路径断言（那会拿到 null）。
     */
    @Test
    public void 流量统计已开启() throws Exception {
        JSONObject config = XrayConfig.build(node(), 23456);
        assertTrue("缺 stats 对象，queryStats 永远是 0", config.has("stats"));
        Map<String, Object> flat = XrayConfig.flatten(config);
        assertEquals(true, flat.get("policy.levels.0.statsUserUplink"));
        assertEquals(true, flat.get("policy.system.statsInboundUplink"));
        assertEquals(true, flat.get("policy.system.statsOutboundDownlink"));
    }

    /** 未知协议必须报错，不能悄悄退化成直连（那会让用户以为连上了）。 */
    @Test
    public void 未知协议明确报错() throws Exception {
        XrayConfig.Node node = node();
        node.protocol = "wireguard";
        try {
            XrayConfig.buildString(node, 23456);
            fail("wireguard 目前不支持，应当抛");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("wireguard"));
        }
    }

    @Test
    public void 非法端口被拒() throws Exception {
        try {
            XrayConfig.build(node(), 0);
            fail("port=0 应该被拒");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            XrayConfig.build(node(), 70000);
            fail("port>65535 应该被拒");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    /** 生成的配置必须能被 JSON 反解回来（toString 之后没有孤立的尾逗号之类）。 */
    @Test
    public void 生成的配置可往返解析() throws Exception {
        String text = XrayConfig.buildString(node(), 23456);
        JSONObject back = new JSONObject(text);
        JSONArray inbounds = back.getJSONArray("inbounds");
        assertEquals(1, inbounds.length());
        assertEquals(23456, inbounds.getJSONObject(0).getInt("port"));
    }

    /** 凭据不该出现在 describe() 里 —— 那行会进 logcat。 */
    @Test
    public void describe不带凭据() {
        XrayConfig.Node node = node();
        node.protocol = "vmess";
        node.uuid = "b831381d-6324-4d53-ad4f-8cda48b30811";
        String text = node.describe();
        assertFalse("describe 漏了 uuid", text.contains(node.uuid));
        assertTrue(text.contains("vmess"));
        assertTrue(text.contains("1.2.3.4:443"));
    }

    // ── 分享链接 ──────────────────────────────────────────────────────

    @Test
    public void 解析vmess分享链接() throws Exception {
        // {"v":"2","ps":"x","add":"1.2.3.4","port":"443","id":"uuid-1",
        //  "aid":"0","scy":"auto","net":"ws","path":"/ray","host":"cdn.example.com","tls":"tls"}
        String json = "{\"v\":\"2\",\"ps\":\"x\",\"add\":\"1.2.3.4\",\"port\":\"443\","
                + "\"id\":\"uuid-1\",\"aid\":\"0\",\"scy\":\"auto\",\"net\":\"ws\","
                + "\"path\":\"/ray\",\"host\":\"cdn.example.com\",\"tls\":\"tls\"}";
        String link = "vmess://" + java.util.Base64.getEncoder()
                .encodeToString(json.getBytes("UTF-8"));

        XrayConfig.Node node = XrayConfig.parseShareLink(link);
        assertEquals("vmess", node.protocol);
        assertEquals("1.2.3.4", node.server);
        assertEquals(443, node.port);
        assertEquals("uuid-1", node.uuid);
        assertEquals(0, node.alterId);
        assertEquals("ws", node.network);
        assertEquals("/ray", node.path);
        assertEquals("cdn.example.com", node.host);
        assertEquals("tls", node.tls);
    }

    @Test
    public void 解析ss分享链接的明文写法() throws Exception {
        String userInfo = java.util.Base64.getEncoder()
                .encodeToString("aes-256-gcm:pass123".getBytes("UTF-8"));
        XrayConfig.Node node = XrayConfig.parseShareLink(
                "ss://" + userInfo + "@5.6.7.8:8388#%E8%8A%82%E7%82%B9");
        assertEquals("shadowsocks", node.protocol);
        assertEquals("5.6.7.8", node.server);
        assertEquals(8388, node.port);
        assertEquals("aes-256-gcm", node.method);
        assertEquals("pass123", node.uuid);
    }

    @Test
    public void 解析ss分享链接的整体加密写法() throws Exception {
        String whole = java.util.Base64.getEncoder()
                .encodeToString("chacha20-ietf-poly1305:pw@9.9.9.9:443".getBytes("UTF-8"));
        XrayConfig.Node node = XrayConfig.parseShareLink("ss://" + whole + "#note");
        assertEquals("9.9.9.9", node.server);
        assertEquals(443, node.port);
        assertEquals("chacha20-ietf-poly1305", node.method);
        assertEquals("pw", node.uuid);
    }

    /** 解析出来的节点必须能直接喂给 build —— 两条路径的字段约定得对得上。 */
    @Test
    public void 解析结果能直接生成配置() throws Exception {
        String userInfo = java.util.Base64.getEncoder()
                .encodeToString("aes-256-gcm:pass123".getBytes("UTF-8"));
        XrayConfig.Node node = XrayConfig.parseShareLink("ss://" + userInfo + "@5.6.7.8:8388");
        Map<String, Object> flat = XrayConfig.flatten(XrayConfig.build(node, 23456));
        assertEquals("shadowsocks", flat.get("outbounds[0].protocol"));
        assertEquals("5.6.7.8", flat.get("outbounds[0].settings.servers[0].address"));
        assertEquals("pass123", flat.get("outbounds[0].settings.servers[0].password"));
    }

    @Test
    public void 不认识的分享链接报错() throws Exception {
        try {
            XrayConfig.parseShareLink("hysteria2://pass@1.2.3.4:443");
            fail("hysteria2 不在支持列表里");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("vmess"));
        }
        try {
            XrayConfig.parseShareLink("");
            fail("空串应该被拒");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // ── 夹具 ──────────────────────────────────────────────────────────

    /** 一个字段都填全的 vmess 节点，各用例按需覆盖自己关心的那几个。 */
    private static XrayConfig.Node node() {
        XrayConfig.Node node = new XrayConfig.Node("vmess", "1.2.3.4", 443);
        node.uuid = "uuid";
        node.alterId = 0;
        node.network = "tcp";
        node.tls = "none";
        return node;
    }
}
