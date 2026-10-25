package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 失败消息的可归因性。
 *
 * 线上 313 条「请求失败：https://…」只有 URL，`code` 列统一是 IOException ——
 * 翻数据库分不清是超时、被 RST、还是 TLS 失败。{@link AiiHttp#describe} 负责
 * 把底层异常压成一句能看的话，这里钉住它的形状。
 */
public class AiiHttpErrorTextTest {

    @Test
    public void 类名加消息() {
        assertEquals("SocketTimeoutException: timeout",
                AiiHttp.describe(new java.net.SocketTimeoutException("timeout")));
        assertEquals("SSLHandshakeException: Read error",
                AiiHttp.describe(new javax.net.ssl.SSLHandshakeException("Read error")));
    }

    @Test
    public void 没有消息时只给类名() {
        // `new IOException()` 的 getMessage() 是 null。旧代码在这里会写出字面量
        // "null"（String.valueOf(null)），后台那 156 条就是这么来的。
        assertEquals("IOException", AiiHttp.describe(new java.io.IOException()));
        assertEquals("IOException", AiiHttp.describe(new java.io.IOException("   ")));
        assertEquals("原因不明", AiiHttp.describe(null));
    }

    @Test
    public void 消息里已经带类名就不重复拼() {
        // OkHttp 有些异常 message 是 "java.net.SocketTimeoutException: timeout"，
        // 直接取 simpleName 会拼成 "SocketTimeoutException: java.net.SocketTimeoutException: timeout"。
        java.io.IOException odd = new java.io.IOException("IOException: 底层坏了");
        assertEquals("IOException: 底层坏了", AiiHttp.describe(odd));
    }

    @Test
    public void 重定向链能读出人话() {
        // OkHttp 的原话 `Too many follow-up requests: 21` 只有数字 —— 看不出绕去哪。
        // 拦截器把每一跳记进 ThreadLocal，这里负责拼成一句。
        AiiHttp.clearRedirectsForTest();
        assertEquals("没绕过就别加料", "", AiiHttp.describeRedirects());

        AiiHttp.noteRedirectForTest("https://a.com/x", "https://a.com/y");
        String one = AiiHttp.describeRedirects();
        assertTrue(one, one.contains("https://a.com/x → https://a.com/y"));

        // 读完要清干净：链是按线程的，留着会串到下一条请求。
        assertEquals("读完要清空", "", AiiHttp.describeRedirects());
    }

    @Test
    public void 长链只报前三跳加总数() {
        AiiHttp.clearRedirectsForTest();
        for (int i = 0; i < 8; i++) {
            AiiHttp.noteRedirectForTest("https://a.com/" + i, "https://a.com/" + (i + 1));
        }
        String text = AiiHttp.describeRedirects();
        assertTrue(text, text.contains("共 8 跳"));
        // 别把 8 跳全塞进上报：单条事件有 4KB 上限（见 Report.EVENT_BUDGET）。
        // 只展开前三跳 = 0→1、1→2、2→3；第 4 跳 (3→4) 不该出现。
        assertTrue("前三跳要在", text.contains("https://a.com/2 → https://a.com/3"));
        assertTrue("第四跳不该展开", !text.contains("https://a.com/3 → https://a.com/4"));
    }
}
