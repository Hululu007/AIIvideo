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
    public void 取消判定按消息也按进程生命周期() {
        // 2.0.21 线上：InterruptedIOException 99 条、DeadObjectException 36 条、
        // ConnectionShutdownException 1 条，全被算成失败。它们本质都是「我们这边
        // 的生命周期结束了」，不是故障。
        assertTrue(Report.isCancellation(new java.io.InterruptedIOException("interrupted")));
        assertTrue(Report.isCancellation(new java.io.InterruptedIOException("Canceled")));
        assertTrue(Report.isCancellation(new java.io.InterruptedIOException("Socket closed")));
        assertTrue(Report.isCancellation(new java.io.InterruptedIOException()));
        assertTrue(Report.isCancellation(new android.os.DeadObjectException()));

        // 真超时必须留在失败那边：SocketTimeoutException extends InterruptedIOException，
        // okhttp 的 callTimeout 也抛 InterruptedIOException("timeout")。线上 26 条
        // SocketTimeoutException 均值 30 秒、全在 detail 阶段 —— 洗成取消就等于把
        // 「站点慢到读不出来」这个信号藏起来。
        assertTrue(!Report.isCancellation(new java.net.SocketTimeoutException("timeout")));
        assertTrue(!Report.isCancellation(new java.net.SocketTimeoutException("Read timed out")));
        assertTrue(!Report.isCancellation(new java.io.InterruptedIOException("timeout")));
        assertTrue(!Report.isCancellation(new java.io.InterruptedIOException("timed out")));
    }

    @Test
    public void 空的异常消息不写出字面量_null() {
        // 线上 156 条事件的 props.msg 是字符串 "null" —— String.valueOf(null) 的锅。
        // 后台看着像「有消息」，实际什么都没说。
        assertEquals("", Report.text(null, 200));
        assertEquals("", Report.text("", 200));
        assertTrue(!Report.text(null, 200).equals("null"));

        // 正常的照抄，超长的按字符截并带省略号。
        assertEquals("站点必须是 HTTPS 域名", Report.text("站点必须是 HTTPS 域名", 200));
        String longText = Report.text("x".repeat(300), 200);
        assertEquals(201, longText.length());
        assertTrue(longText.endsWith("…"));
    }

    @Test
    public void 数字错误码不会让整条事件超预算() {
        // feed-player.js:517 塞的是 video.error.code（1~4 的枚举），天生是数字。
        // 客户端这边只能保证「整条序列化后在预算内」，数字本身由服务端的宽松
        // 解析收下（server/track/tests/test_events.py::数字错误码照收）。
        JSONObject props = new JSONObject();
        try {
            props.put("stage", "decode").put("code", 3).put("why", "网络错误").put("ep", 2);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        JSONObject event = Report.build("play", "play_error", "51cg", props);
        assertTrue("整条 " + Report.serializedSize(event) + " 字节",
                Report.serializedSize(event) <= Report.EVENT_BUDGET);
    }

    @Test
    public void 超长堆栈按字节裁到预算内() {
        // 真实 Android 堆栈：40 帧 × 70 字符 + cause 10 行，实测序列化后 4400+ 字节，
        // 而服务端上限是 4096 —— 只有手搓的十帧 diag 崩溃（958 字节）过得去。
        // 线上 crash 表里就那两条，正是这么来的。
        StackTraceElement[] frames = new StackTraceElement[60];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = new StackTraceElement(
                    "com.huangguo.mobile.SiteRepository$4", "run",
                    "SiteRepository.java", 1000 + i);
        }
        Throwable fat = new IllegalStateException("中".repeat(200), new NullPointerException("cause"));
        fat.setStackTrace(frames);

        JSONObject props = new JSONObject();
        try {
            props.put("stage", "uncaught");
            props.put("code", fat.getClass().getSimpleName());
            props.put("msg", Report.text(fat.getMessage(), 200));
            props.put("trace", Report.traceOf(fat));
            props.put("thread", "main");
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        JSONObject event = Report.build("crash", "uncaught", "", props);
        int size = Report.serializedSize(event);
        assertTrue("整条 " + size + " 字节，超了 " + Report.EVENT_BUDGET, size <= Report.EVENT_BUDGET);

        // 裁完还得剩下能定位的东西：至少前面几帧在。
        String trace = props.optString("trace");
        assertTrue("堆栈被裁空了", trace.length() > 100);
        assertTrue(trace.contains("SiteRepository"));
    }

    @Test
    public void 按字节裁不会把中文切成半个字符() {
        String chinese = "中".repeat(100);          // 300 字节
        String cut = Report.shortenBytes(chinese, 100);
        assertTrue(cut.length() < 100);
        // 能原样还原成合法字符串就说明没切在字符中间（否则会出现替换字符）。
        assertEquals(-1, cut.indexOf('\uFFFD'));
        assertTrue(cut.endsWith("…"));
        // 每个中文字符 3 字节，100 字节装得下 33 个。
        assertEquals(33, cut.length() - 1);
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
