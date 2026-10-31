package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * 节点池热更新的**准入校验**。
 *
 * 这一层是整个热更新的安全阀。它守的不是「安全」（节点列表是纯数据，最坏情况
 * 是连不上），而是**可用性**：一份坏数据一旦写进缓存，用户下次打开 App 就没有
 * 节点可测，而他没有任何办法自己修 —— 重置按钮藏在设置页最下面。
 *
 * 所以要挡的东西很具体：
 *   - 运营商劫持页 / 登录门户返回的 HTML（parse 会失败，但得确认不会误判成空 feed）
 *   - 半截文件（链接数量不够）
 *   - 格式对但链接全是 hysteria2 / tuic 这种核心不支持的（解析出 0 个节点）
 *
 * 全是纯字符串，JVM 里直接能跑。
 */
public class NodesFeedTest {

    private static String feed(String... links) {
        StringBuilder sb = new StringBuilder("{\"updated\":\"2026-10-18\",\"links\":[");
        for (int i = 0; i < links.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(links[i]).append('"');
        }
        return sb.append("]}").toString();
    }

    private static final String GOOD_SS = "ss://YWVzLTI1Ni1nY206cGFzcw==@1.2.3.4:8388#a";
    private static final String GOOD_TROJAN = "trojan://pw@5.6.7.8:8443?security=tls&sni=a.example.com#b";
    private static final String GOOD_VLESS = "vless://11111111-2222-3333-4444-555555555555@9.9.9.9:443?security=tls&type=ws&path=%2Fx#c";

    // ── 该放行的 ──────────────────────────────────────────────────────

    @Test
    public void 正常的三条通过() {
        NodesFeed.Stats stats = NodesFeed.validate(feed(GOOD_SS, GOOD_TROJAN, GOOD_VLESS));
        assertNotNull(stats);
        assertEquals(3, stats.links);
        assertEquals(3, stats.nodes);
        assertEquals("2026-10-18", stats.updated);
    }

    @Test
    public void 维护日期缺失也能用() {
        String raw = "{\"links\":[\"" + GOOD_SS + "\",\"" + GOOD_TROJAN + "\",\"" + GOOD_VLESS + "\"]}";
        NodesFeed.Stats stats = NodesFeed.validate(raw);
        assertNotNull(stats);
        assertEquals("", stats.updated);
    }

    @Test
    public void links里的空串被跳过但仍要够数() {
        String raw = "{\"links\":[\"" + GOOD_SS + "\",\"\",\"" + GOOD_TROJAN + "\",\" \",\"" + GOOD_VLESS + "\"]}";
        NodesFeed.Stats stats = NodesFeed.validate(raw);
        assertNotNull(stats);
        assertEquals(3, stats.links);
    }

    // ── 该拦下的 ──────────────────────────────────────────────────────

    @Test
    public void 劫持页面不是JSON直接拒() {
        assertNull(NodesFeed.validate("<html><body>请先登录 Wi-Fi</body></html>"));
    }

    @Test
    public void 空串拒() {
        assertNull(NodesFeed.validate(""));
        assertNull(NodesFeed.validate("   \n  "));
        assertNull(NodesFeed.validate(null));
    }

    @Test
    public void 没有links数组拒() {
        assertNull(NodesFeed.validate("{\"updated\":\"2026-10-18\"}"));
        assertNull(NodesFeed.validate("{\"nodes\":[]}"));
    }

    @Test
    public void 只有两条不够数拒() {
        // 底线是 3 条：真维护节点池的人不会只留两条，这说明文件不完整。
        assertNull(NodesFeed.validate(feed(GOOD_SS, GOOD_TROJAN)));
    }

    @Test
    public void 链接数量够但一条都解析不出来要拒() {
        // 这是最阴的一种：JSON 合法、links 有 5 条、校验「看起来没问题」，
        // 但全是核心不支持的协议 —— 用户点测速会得到「节点列表是空的」。
        // hysteria2 从 2.0.28 起能解析了，换成确实没人实现的协议。
        String raw = feed(
                "tuic://a@1.1.1.1:443#1",
                "tuic://b@2.2.2.2:443#2",
                "ssr://c29tZS1ibG9i#3",
                "tuic://d@4.4.4.4:443#4",
                "juicity://e@5.5.5.5:443#5");
        assertNull(NodesFeed.validate(raw));
    }

    @Test
    public void 一堆垃圾字符串要拒() {
        String raw = feed("hello", "world", "not-a-link", "also-not", "still-not");
        assertNull(NodesFeed.validate(raw));
    }

    @Test
    public void 半截JSON要拒() {
        assertNull(NodesFeed.validate("{\"updated\":\"2026-10-18\",\"links\":[\"ss://a@1.2.3.4:1"));
    }

    @Test
    public void 数组里塞对象而不是字符串要拒() {
        // optString 对对象会返回 ""，所以这些条目会被当成空串跳过 → 不足 3 条。
        assertNull(NodesFeed.validate("{\"links\":[{\"a\":1},{\"b\":2},{\"c\":3}]}"));
    }
}
