package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * vless:// 与 trojan:// 分享链接的解析。
 *
 * 单独一个文件而不是并进 ProxyCoreTest：那边已经 456 行、30 个用例，都是核心和
 * 配置生成的；链接解析是另一个方向（从外部字符串进来），夹具也更长。
 *
 * 夹具用的是真实机场链接的形状（2026-10-18 从公开订阅里取的样本，改过主机名），
 * 不是凭空编的理想格式 —— 机场链接的坑恰恰都在「不规范」那一面：密码带 @、
 * base64 不补 padding、sni 和 host 不一致、trojan 不写 security。
 */
public class ShareLinkTest {

    // ── vless ─────────────────────────────────────────────────────────

    /** 最常见的一种：vless + ws + tls，path/host/sni 三个都在。 */
    @Test
    public void vless的ws加tls完整形态() throws Exception {
        String link = "vless://965ffbbe-c6b6-4d5f-8dea-6d8abc55d9c0@95.174.68.188:14430"
                + "?encryption=none&security=tls&sni=cdn.example.com&type=ws"
                + "&host=cdn.example.com&path=%2Fws#top-1";

        XrayConfig.Node node = XrayConfig.parseShareLink(link);
        assertEquals("vless", node.protocol);
        assertEquals("95.174.68.188", node.server);
        assertEquals(14430, node.port);
        assertEquals("965ffbbe-c6b6-4d5f-8dea-6d8abc55d9c0", node.uuid);
        assertEquals("ws", node.network);
        assertEquals("tls", node.tls);
        // %2Fws 必须解开成 /ws，不然 Xray 拿到的路径是 %2Fws，ws 握手直接 404
        assertEquals("/ws", node.path);
        assertEquals("cdn.example.com", node.host);
    }

    /** reality：pbk 和 sid 必须落到 Node 上，缺了 reality 根本起不来。 */
    @Test
    public void vless的reality带公钥和shortId() throws Exception {
        String link = "vless://uuid-reality@1.2.3.4:443"
                + "?encryption=none&security=reality&sni=www.microsoft.com"
                + "&fp=chrome&pbk=PUBKEY_BASE64URL&sid=ab12&type=tcp&flow=xtls-rprx-vision";

        XrayConfig.Node node = XrayConfig.parseShareLink(link);
        assertEquals("reality", node.tls);
        assertEquals("PUBKEY_BASE64URL", node.publicKey);
        assertEquals("ab12", node.shortId);
        assertEquals("chrome", node.fingerprint);
        assertEquals("xtls-rprx-vision", node.flow);
        assertEquals("www.microsoft.com", node.host);
        assertEquals("tcp", node.network);
    }

    /** grpc：serviceName 单独一个键。 */
    @Test
    public void vless的grpc读serviceName() throws Exception {
        String link = "vless://uuid-g@5.6.7.8:443"
                + "?encryption=none&security=tls&type=grpc&serviceName=mygrpc&sni=a.example.com";

        XrayConfig.Node node = XrayConfig.parseShareLink(link);
        assertEquals("grpc", node.network);
        assertEquals("mygrpc", node.serviceName);
    }

    /** 没有 security 字段：按 none 处理，不该报错。 */
    @Test
    public void vless缺security按none处理() throws Exception {
        String link = "vless://uuid-plain@9.9.9.9:8080?encryption=none&type=tcp";
        XrayConfig.Node node = XrayConfig.parseShareLink(link);
        assertEquals("none", node.tls);
        assertEquals("tcp", node.network);
    }

    /** 缺 uuid 要报错，而不是生成一个连不上的配置。 */
    @Test
    public void vless缺uuid报错() throws Exception {
        try {
            XrayConfig.parseShareLink("vless://@1.2.3.4:443?type=tcp");
            fail("空 uuid 应该被拒");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("uuid") || expected.getMessage().contains("密码"));
        }
    }

    // ── trojan ────────────────────────────────────────────────────────

    /** trojan 最常见形态：不写 security，缺省必须补成 tls。 */
    @Test
    public void trojan不写security时缺省补tls() throws Exception {
        String link = "trojan://u93yPGuY2zgIsisJZKS2QzJzg@78.17.171.62:443"
                + "?sni=FREETOPLAY-2.example.world&type=tcp#PL";

        XrayConfig.Node node = XrayConfig.parseShareLink(link);
        assertEquals("trojan", node.protocol);
        assertEquals("78.17.171.62", node.server);
        assertEquals(443, node.port);
        assertEquals("u93yPGuY2zgIsisJZKS2QzJzg", node.uuid);
        assertEquals("tls", node.tls);
        assertEquals("FREETOPLAY-2.example.world", node.host);
    }

    /** 密码里带 @：必须按最后一个 @ 切，不能把密码前段当成分隔符。 */
    @Test
    public void trojan密码里带at也不切错() throws Exception {
        String link = "trojan://p@ss@1.2.3.4:8443?security=tls&sni=a.example.com";
        XrayConfig.Node node = XrayConfig.parseShareLink(link);
        assertEquals("p@ss", node.uuid);
        assertEquals("1.2.3.4", node.server);
        assertEquals(8443, node.port);
    }

    /** 密码是 percent-encode 的：@ 写成 %40，要还原成 @。 */
    @Test
    public void trojan密码的percent编码要解开() throws Exception {
        String link = "trojan://p%40ss%3Aword@1.2.3.4:443?security=tls";
        XrayConfig.Node node = XrayConfig.parseShareLink(link);
        assertEquals("p@ss:word", node.uuid);
    }

    /** trojan + ws：path 和 host 都要认。 */
    @Test
    public void trojan的ws形态() throws Exception {
        String link = "trojan://pw@1.2.3.4:443?security=tls&type=ws&path=%2Ftj&host=h.example.com";
        XrayConfig.Node node = XrayConfig.parseShareLink(link);
        assertEquals("ws", node.network);
        assertEquals("/tj", node.path);
        assertEquals("h.example.com", node.host);
    }

    // ── 边界 ──────────────────────────────────────────────────────────

    /**
     * `+` 不能被解成空格。
     *
     * URLDecoder 会把 + 当空格，而 trojan 密码里的 + 是真的加号。
     * 解错了连不上，而且报错信息只会是「密码错误」，极难定位。
     */
    @Test
    public void 密码里的加号不能被解成空格() throws Exception {
        String link = "trojan://a+b+c@1.2.3.4:443?security=tls";
        XrayConfig.Node node = XrayConfig.parseShareLink(link);
        assertEquals("a+b+c", node.uuid);
    }

    /** 备注里的 # 之后不该被当成参数。 */
    @Test
    public void 备注不参与解析() throws Exception {
        String link = "vless://uuid-x@1.2.3.4:443?security=tls&type=ws&path=%2Fa#a?b=c&d";
        XrayConfig.Node node = XrayConfig.parseShareLink(link);
        assertEquals("/a", node.path);
        assertEquals("ws", node.network);
    }

    /** 认不出的协议要说清楚支持哪些，而不是一句「看不懂」。 */
    @Test
    public void 未知协议列出支持的几种() throws Exception {
        try {
            XrayConfig.parseShareLink("tuic://x@1.2.3.4:443");
            fail("tuic 不在支持列表里");
        } catch (IllegalArgumentException expected) {
            String m = expected.getMessage();
            assertTrue("应提到 vmess：" + m, m.contains("vmess"));
            assertTrue("应提到 vless：" + m, m.contains("vless"));
            assertTrue("应提到 trojan：" + m, m.contains("trojan"));
            assertTrue("应提到 ss：" + m, m.contains("ss"));
            assertTrue("应提到 hy2（2.0.28 起支持）：" + m, m.contains("hy2"));
        }
    }

    /** 端口越界要拒，别让 Xray 在启动时才报。 */
    @Test
    public void 端口越界被拒() throws Exception {
        try {
            XrayConfig.parseShareLink("vless://uuid@1.2.3.4:99999?security=tls");
            fail("端口越界该被拒");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("端口"));
        }
    }

    // ── 解析 → 生成，闭环 ─────────────────────────────────────────────

    /**
     * 解析出来的节点要能直接生成配置，而且关键字段真的进到了 JSON 里。
     * 这一步比单看 Node 字段更重要 —— 字段名和生成器对不上，前面解析全对也没用。
     */
    @Test
    public void vless解析结果能生成带reality的配置() throws Exception {
        String link = "vless://uuid-r@1.2.3.4:443"
                + "?encryption=none&security=reality&sni=www.microsoft.com"
                + "&fp=chrome&pbk=PUBKEY&sid=ab12&type=tcp&flow=xtls-rprx-vision";

        XrayConfig.Node node = XrayConfig.parseShareLink(link);
        JSONObject config = new JSONObject(XrayConfig.buildString(node, 20480));
        JSONObject proxy = outbound(config, "proxy");

        assertEquals("vless", proxy.getString("protocol"));
        JSONObject stream = proxy.getJSONObject("streamSettings");
        assertEquals("reality", stream.getString("security"));
        JSONObject reality = stream.getJSONObject("realitySettings");
        assertEquals("PUBKEY", reality.getString("publicKey"));
        assertEquals("ab12", reality.getString("shortId"));
        assertEquals("chrome", reality.getString("fingerprint"));
        assertEquals("www.microsoft.com", reality.getString("serverName"));

        JSONObject user = proxy.getJSONObject("settings")
                .getJSONArray("vnext").getJSONObject(0)
                .getJSONArray("users").getJSONObject(0);
        assertEquals("uuid-r", user.getString("id"));
        assertEquals("xtls-rprx-vision", user.getString("flow"));
    }

    @Test
    public void trojan解析结果能生成配置() throws Exception {
        String link = "trojan://pw123@5.6.7.8:8443?security=tls&sni=a.example.com&type=ws&path=%2Ftj";
        XrayConfig.Node node = XrayConfig.parseShareLink(link);

        JSONObject config = new JSONObject(XrayConfig.buildString(node, 20480));
        JSONObject proxy = outbound(config, "proxy");

        assertEquals("trojan", proxy.getString("protocol"));
        JSONObject server = proxy.getJSONObject("settings")
                .getJSONArray("servers").getJSONObject(0);
        assertEquals("5.6.7.8", server.getString("address"));
        assertEquals(8443, server.getInt("port"));
        assertEquals("pw123", server.getString("password"));

        JSONObject stream = proxy.getJSONObject("streamSettings");
        assertEquals("ws", stream.getString("network"));
        assertEquals("tls", stream.getString("security"));
        assertEquals("/tj", stream.getJSONObject("wsSettings").getString("path"));
        assertEquals("a.example.com",
                stream.getJSONObject("tlsSettings").getString("serverName"));
    }

    /** 从配置里按 tag 取 outbound。 */
    private static JSONObject outbound(JSONObject config, String tag) throws Exception {
        JSONArray list = config.getJSONArray("outbounds");
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);
            if (tag.equals(o.optString("tag"))) return o;
        }
        throw new AssertionError("找不到 tag=" + tag + " 的 outbound");
    }
}
