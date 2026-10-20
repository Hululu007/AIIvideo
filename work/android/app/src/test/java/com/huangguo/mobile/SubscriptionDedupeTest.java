package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 订阅里的重复条目与公告条目。
 *
 * 真实样本（youzizi 那份）：10 条「节点」去掉名字后 md5 完全一致 —— 同一台机器
 * 复制五份，另外几条是「剩余流量：50GB」「套餐到期」这种公告。以前全进测速池，
 * 列表上就是六条一模一样的 host:port。
 */
public class SubscriptionDedupeTest {

    private static String vless(String uuid, String name) {
        return "vless://" + uuid + "@cdn.example.xyz:443"
                + "?type=ws&security=tls&path=%2Fapi%2Fv1%2Fguests%2Fabc%2F&host=mao.example.net"
                + (name == null ? "" : "#" + name);
    }

    @Test
    public void 同一份配置只留一条且保留名字() {
        String raw = vless("af8b57d4-0000-0000-0000-000000000000", "%E7%BE%8E%E5%9B%BD%201") + "\n"
                + vless("af8b57d4-0000-0000-0000-000000000000", "%E7%BE%8E%E5%9B%BD%202") + "\n"
                + vless("af8b57d4-0000-0000-0000-000000000000", "%E7%BE%8E%E5%9B%BD%203") + "\n";
        Subscription.Result r = Subscription.parse(raw);
        assertEquals("重复配置只该留一条", 1, r.size());
        assertEquals("其余两条算重复", 2, r.duplicates);
        assertEquals("名字要保住", "美国 1", r.nodes.get(0).name);
    }

    @Test
    public void 公告条目直接忽略不计入节点() {
        String raw = vless("af8b57d4-0000-0000-0000-000000000000", "%E7%BE%8E%E5%9B%BD%201") + "\n"
                + vless("af8b57d4-0000-0000-0000-000000000000", "%E5%89%A9%E4%BD%99%E6%B5%81%E9%87%8F%EF%BC%9A50%20GB") + "\n"
                + vless("af8b57d4-0000-0000-0000-000000000000", "%E5%A5%97%E9%A4%90%E5%88%B0%E6%9C%9F%EF%BC%9A2026-11-20") + "\n";
        Subscription.Result r = Subscription.parse(raw);
        assertEquals(1, r.size());
        assertEquals(2, r.notices);
        assertEquals(0, r.duplicates);
        assertTrue("摘要要提到公告", Subscription.summary(r).contains("公告"));
    }

    /** 不同 uuid 就是不同节点，不能因为 host:port 一样被合并。 */
    @Test
    public void 同机器不同凭据不算重复() {
        String raw = vless("aaaaaaaa-0000-0000-0000-000000000000", "A") + "\n"
                + vless("bbbbbbbb-0000-0000-0000-000000000000", "B") + "\n";
        Subscription.Result r = Subscription.parse(raw);
        assertEquals(2, r.size());
        assertEquals(0, r.duplicates);
    }

    /**
     * 公告排在真节点前面时，保留的名字要是真节点那个。
     *
     * 真实订阅（youzizi）第一条就是「剩余流量：50 GB」，真名字在后面几条 ——
     * 只留第一条的话，列表里那条节点就叫「剩余流量：50 GB」。
     */
    @Test
    public void 公告在前时用真名字覆盖() {
        String raw = vless("af8b57d4-0000-0000-0000-000000000000", "%E5%89%A9%E4%BD%99%E6%B5%81%E9%87%8F%EF%BC%9A50%20GB") + "\n"
                + vless("af8b57d4-0000-0000-0000-000000000000", "%E5%A5%97%E9%A4%90%E5%88%B0%E6%9C%9F") + "\n"
                + vless("af8b57d4-0000-0000-0000-000000000000", "%E7%BE%8E%E5%9B%BD%201") + "\n"
                + vless("af8b57d4-0000-0000-0000-000000000000", "%E7%BE%8E%E5%9B%BD%202") + "\n";
        Subscription.Result r = Subscription.parse(raw);
        assertEquals(1, r.size());
        assertEquals("美国 1", r.nodes.get(0).name);
        assertEquals(2, r.notices);
        assertEquals(1, r.duplicates);
    }

    /** 唯一一条恰好叫「到期提醒」时，它仍然是个节点。 */
    @Test
    public void 唯一的节点就算名字像公告也留着() {
        Subscription.Result r = Subscription.parse(vless("cccccccc-0000-0000-0000-000000000000", "套餐到期"));
        assertEquals(1, r.size());
        assertEquals(0, r.notices);
    }
}
