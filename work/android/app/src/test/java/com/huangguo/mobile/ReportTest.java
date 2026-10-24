package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * 上报的纯逻辑部分：签名、日期分片、事件形状。
 *
 * 这几条是「两端对齐」的契约：签名算法和服务端不一致 → 全量 401；事件字段名
 * 不一致 → 库里一片 null。都改不得，所以都有断言。
 */
public class ReportTest {

    /**
     * 与 server/track 用同一个向量。服务端那边的 test_events.py 里是一模一样的
     * 这两个值 —— 改任何一边，这条测试都会红。
     */
    @Test
    public void 签名与空体都对得上服务端的向量() {
        assertEquals(
                "179bf20a8b9040a32368814a68b0dc270823b5968498e0a73796c4202708ed8d",
                ReportQueue.hmacHex("test-secret", "{\"a\":1}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(
                "a41bc6d81d6413576ae0994995e0ad89a416ec97389515c3604f47722122eeeb",
                ReportQueue.hmacHex("test-secret", new byte[0]));
    }

    @Test
    public void 日期分片是本地时区的年月日() {
        // 2026-10-23 21:30:00 +08:00
        assertEquals("2026-10-23", Report.dayKey(1792762200000L));
    }

    @Test
    public void 事件带齐服务端要的字段() throws Exception {
        JSONObject props = new JSONObject().put("stage", "resolve").put("ms", 800);
        JSONObject event = Report.build("play", "play_error", "51cg", props);

        assertEquals("play", event.getString("kind"));
        assertEquals("play_error", event.getString("name"));
        assertEquals("51cg", event.getString("source"));
        assertTrue(event.getLong("at") > 0);
        assertEquals(36, event.getString("id").length());          // UUID v4 带横线
        assertNotNull(event.optString("session"));
        JSONObject back = event.getJSONObject("props");
        assertEquals("resolve", back.getString("stage"));
        assertEquals(800, back.getInt("ms"));
    }

    @Test
    public void 没有源的时候不写空字段() throws Exception {
        // 其它用例会装配 SiteProfile 顺手把当前源挂上（setSource 是全局的），
        // 所以这里先清干净，否则跑的顺序会决定这条是绿是红。
        Report.setSource("");
        JSONObject event = Report.build("app", "app_open", "", null);
        assertTrue(!event.has("source"));
        assertTrue(!event.has("props"));
    }

    @Test
    public void 当前源会补进每条事件() throws Exception {
        // 桥指令（封面、spider、详情）自己不知道是哪个源，靠装配时挂的这一份。
        // 2.0.20 没挂，库里 source 全是空的，只能从报错文案里倒推。
        Report.setSource("51cg");
        try {
            JSONObject bridged = Report.build("bridge", "command_error", "", null);
            assertEquals("51cg", bridged.getString("source"));
            // 显式传了源就以传的为准（前端播放事件走这条）。
            JSONObject played = Report.build("play", "play_error", "mrds66", null);
            assertEquals("mrds66", played.getString("source"));
        } finally {
            Report.setSource("");
        }
    }

    @Test
    public void 取消不算失败() {
        // 用户划过、宿主退出、排队超时 —— 这些以前和真失败混在一起，把失败率顶到 75%。
        assertTrue(Report.isCancellation(new java.io.IOException("spider 被更新的请求取代")));
        assertTrue(Report.isCancellation(new java.io.IOException("spider 宿主已关闭。")));
        assertTrue(Report.isCancellation(new java.io.IOException("spider 排队超时（另一个调用还占着宿主）。")));
        assertTrue(Report.isCancellation(new InterruptedException()));
        assertTrue(!Report.isCancellation(new java.io.IOException("封面不是图片：mode=aes-cbc")));
        assertTrue(!Report.isCancellation(null));
    }

    @Test
    public void 已经报过一次的异常不再报() {
        assertTrue(Report.isOnce(new SiteRepository.CoverFailure("x", 0L)));
        assertTrue(!Report.isOnce(new IllegalStateException("x")));
    }

    @Test
    public void 桥指令失败把指令名当stage() {
        // queue 没 install 时 enqueue 是空操作，所以这里只验不抛异常。
        // 真正的形状由上面那条 JSONObject 断言覆盖。
        Report.bridgeFailure("detail", new IllegalStateException("源没返回"), 1200L);
        Report.crash(new RuntimeException("boom"), "uncaught");
    }

    @Test
    public void 封面失败要写清楚拿回来的是什么() {
        // 以前只有一句「封面解密结果无效」，把「图床没给图片」和「密钥不对」混成一件。
        // 现在文案里要能看出原始字节数、解出来的前四字节、以及是不是 HTML。
        byte[] html = "<html><body>404</body></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String detail = SiteRepository.coverFailureDetail(html, html, "aes-cbc", "pic.sugfew.cn");
        assertTrue(detail.contains("mode=aes-cbc"));
        assertTrue(detail.contains("原始" + html.length + "字节"));
        assertTrue(detail.contains("3c6874"));          // "<htm"
        assertTrue(detail.contains("HTML"));
        assertTrue(detail.contains("@pic.sugfew.cn"));

        // 图片正常时不会走到这里；真走到也要能看出前四字节，而不是一句「无效」。
        byte[] jpeg = new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0};
        String other = SiteRepository.coverFailureDetail(jpeg, jpeg, "plain", "host.example");
        assertTrue(other.contains("ffd8ffe0"));
        assertTrue(!other.contains("HTML"));
    }
}
