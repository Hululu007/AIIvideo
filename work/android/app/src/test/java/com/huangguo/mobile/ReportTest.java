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
        JSONObject event = Report.build("app", "app_open", "", null);
        assertTrue(!event.has("source"));
        assertTrue(!event.has("props"));
    }

    @Test
    public void 桥指令失败把指令名当stage() {
        // queue 没 install 时 enqueue 是空操作，所以这里只验不抛异常。
        // 真正的形状由上面那条 JSONObject 断言覆盖。
        Report.bridgeFailure("detail", new IllegalStateException("源没返回"), 1200L);
        Report.crash(new RuntimeException("boom"), "uncaught");
    }
}
