package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

/**
 * 订阅解析与测速结果排序。
 *
 * 这一层是纯字符串处理，没有 native、没有网络，所以能完整地在 JVM 里测。
 * 夹具用的是真实订阅的形状（2026-10-18 从公开订阅取的样本，改过地址）。
 */
public class SubscriptionTest {

    // ── 解析 ──────────────────────────────────────────────────────────

    /** 最基础：一行一条，直接列出来。 */
    @Test
    public void 裸列表直接解析() {
        String text = "vless://uuid-1@1.2.3.4:443?security=tls&type=ws&path=%2Fa\n"
                + "trojan://pw@5.6.7.8:8443?security=tls&sni=a.example.com\n"
                + "ss://YWVzLTI1Ni1nY206cGFzcw==@9.9.9.9:8388#x\n";

        Subscription.Result r = Subscription.parse(text);
        assertEquals(3, r.size());
        assertEquals("vless", r.nodes.get(0).protocol);
        assertEquals("trojan", r.nodes.get(1).protocol);
        assertEquals("shadowsocks", r.nodes.get(2).protocol);
        assertEquals(0, r.failed);
    }

    /** 整段 base64：这是最常见的订阅形态。 */
    @Test
    public void 整段base64先解码再解析() {
        String nodes = "vless://uuid-a@1.1.1.1:443?security=tls\n"
                + "vless://uuid-b@2.2.2.2:443?security=tls\n";
        String encoded = java.util.Base64.getEncoder()
                .encodeToString(nodes.getBytes(StandardCharsets.UTF_8));

        Subscription.Result r = Subscription.parse(encoded);
        assertEquals(2, r.size());
        assertEquals("1.1.1.1", r.nodes.get(0).server);
        assertEquals("2.2.2.2", r.nodes.get(1).server);
    }

    /** base64 不补 padding 也要能解 —— 机场导出经常去掉尾部 =。 */
    @Test
    public void base64缺padding也能解() {
        String nodes = "vless://uuid-c@3.3.3.3:443?security=tls\n";
        String encoded = java.util.Base64.getEncoder()
                .encodeToString(nodes.getBytes(StandardCharsets.UTF_8))
                .replaceAll("=+$", "");

        Subscription.Result r = Subscription.parse(encoded);
        assertEquals(1, r.size());
        assertTrue(encoded.indexOf('=') < 0);
    }

    /** 重复节点要合并 —— 多分组订阅里重复很常见。 */
    @Test
    public void 重复节点去重() {
        String one = "vless://same-uuid@1.2.3.4:443?security=tls";
        Subscription.Result r = Subscription.parse(one + "\n" + one + "\n" + one);
        assertEquals(1, r.size());
    }

    /** 注释行和空行跳过，不算失败。 */
    @Test
    public void 注释和空行被忽略() {
        String text = "# 这是注释\n\n// 这也算\n"
                + "vless://uuid-d@1.2.3.4:443?security=tls\n\n";
        Subscription.Result r = Subscription.parse(text);
        assertEquals(1, r.size());
        assertEquals(0, r.failed);
    }

    /** 坏行计入 failed，好行照收 —— 不能因为一条坏的就整份丢掉。 */
    @Test
    public void 坏节点只计失败不影响其他() {
        String text = "vless://uuid-e@1.2.3.4:443?security=tls\n"
                + "vless://@:443?security=tls\n"                    // 空 uuid
                + "trojan://pw@5.6.7.8:8443?security=tls\n";
        Subscription.Result r = Subscription.parse(text);
        assertEquals(2, r.size());
        assertEquals(1, r.failed);
        assertNotNull(r.firstError);
    }

    /**
     * Clash YAML 要明确报「不支持」而不是当成 0 个节点。
     *
     * 后者用户会以为订阅坏了，前者能告诉他去后台换成 V2Ray 订阅。
     */
    @Test
    public void Clash配置给出明确提示() {
        String yaml = "mixed-port: 7890\nallow-lan: false\nproxies:\n  - name: a\n    type: vless\n";
        Subscription.Result r = Subscription.parse(yaml);
        assertEquals(0, r.size());
        assertNotNull(r.firstError);
        assertTrue("应提示改用 V2Ray 订阅：" + r.firstError,
                r.firstError.contains("Clash") || r.firstError.contains("V2Ray"));
    }

    /** 完全看不懂的内容要给一句话，不能让 UI 显示空白。 */
    @Test
    public void 无法识别的内容有提示() {
        Subscription.Result r = Subscription.parse("这不是订阅，只是一段话");
        assertEquals(0, r.size());
        assertNotNull(r.firstError);
    }

    /** 空输入不崩。 */
    @Test
    public void 空输入返回空结果() {
        assertEquals(0, Subscription.parse("").size());
        assertEquals(0, Subscription.parse(null).size());
        assertEquals(0, Subscription.parse("   \n\n  ").size());
    }

    /** 摘要要能说人话。 */
    @Test
    public void 摘要包含节点数与跳过数() {
        Subscription.Result r = Subscription.parse(
                "vless://uuid-f@1.2.3.4:443?security=tls\nbadline\n");
        String s = Subscription.summary(r);
        assertTrue("应含节点数：" + s, s.contains("1"));
    }

    // ── 内置节点文件 ───────────────────────────────────────────────────

    /**
     * assets/free-nodes.json 必须能被解析出节点。
     *
     * 这条测的是**真实资产文件**，不是构造的字符串 —— 维护的人改了那个 JSON
     * （加节点、改格式、手滑删个引号），这里会直接红，而不是等到用户点测速
     * 才发现「内置节点列表是空的」。
     */
    @Test
    public void 内置节点文件可解析() throws Exception {
        String path = "src/main/assets/free-nodes.json";
        java.io.File file = new java.io.File(path);
        if (!file.exists()) {
            // Gradle 单测的工作目录是模块根目录；换个位置再找一次。
            file = new java.io.File("app/" + path);
        }
        assertTrue("找不到 " + path, file.exists());

        java.io.InputStream in = new java.io.FileInputStream(file);
        String text;
        try {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            text = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }

        org.json.JSONObject root = new org.json.JSONObject(text);
        org.json.JSONArray links = root.optJSONArray("links");
        assertNotNull("缺 links 数组", links);
        assertTrue("links 是空的", links.length() > 0);

        // 把 links 拼成行再走正常解析 —— 和 Subscription.builtin() 同一套逻辑。
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < links.length(); i++) {
            joined.append(links.optString(i, "")).append('\n');
        }
        Subscription.Result r = Subscription.parse(joined.toString());
        assertEquals("links 里的条目应该全部能解析（失败的会在 failed 里）",
                links.length() - r.failed, r.size());
        assertTrue("至少要解析出一个节点", r.size() > 0);
    }

    // ── 测速结果排序 ──────────────────────────────────────────────────

    /** 排序：通的在前按延迟升序，不通的沉底。 */
    @Test
    public void 测速结果按延迟排序且不通的沉底() {
        XrayConfig.Node a = new XrayConfig.Node("vless", "1.1.1.1", 443);
        XrayConfig.Node b = new XrayConfig.Node("vless", "2.2.2.2", 443);
        XrayConfig.Node c = new XrayConfig.Node("vless", "3.3.3.3", 443);

        List<SpeedTest.Item> items = new java.util.ArrayList<SpeedTest.Item>();
        items.add(new SpeedTest.Item(0, a, "l0", -1, "不通"));
        items.add(new SpeedTest.Item(1, b, "l1", 300, ""));
        items.add(new SpeedTest.Item(2, c, "l2", 80, ""));

        SpeedTest.sortBySpeed(items);

        assertEquals(80, items.get(0).ms);
        assertEquals(300, items.get(1).ms);
        assertFalse(items.get(2).ok());
    }

    /**
     * 排序不能改 index —— use?index=N 靠它回查原始链接，
     * 下标一旦被排序打乱，用户点了会连到别的节点上去。
     */
    @Test
    public void 排序后index保持原值() {
        List<SpeedTest.Item> items = new java.util.ArrayList<SpeedTest.Item>();
        items.add(new SpeedTest.Item(7, new XrayConfig.Node("vless", "1.1.1.1", 443), "la", 500, ""));
        items.add(new SpeedTest.Item(2, new XrayConfig.Node("vless", "2.2.2.2", 443), "lb", 50, ""));

        SpeedTest.sortBySpeed(items);

        assertEquals("最快的原始下标是 2", 2, items.get(0).index);
        assertEquals("慢的原始下标是 7", 7, items.get(1).index);
        assertEquals("lb", items.get(0).link);
    }

    /** describe 不能带凭据 —— 这行会进 logcat。 */
    @Test
    public void 测速结果描述不含凭据() {
        XrayConfig.Node n = new XrayConfig.Node("trojan", "1.2.3.4", 443);
        n.uuid = "SUPER_SECRET_PASSWORD";
        SpeedTest.Item item = new SpeedTest.Item(0, n, "trojan://SUPER_SECRET_PASSWORD@1.2.3.4:443", 120, "");

        String d = item.describe();
        assertFalse("描述里不该出现密码：" + d, d.contains("SUPER_SECRET_PASSWORD"));
        assertTrue(d.contains("1.2.3.4"));
    }
}
