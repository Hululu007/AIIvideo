package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 取消判定：**包装过的复合文案不算取消**。
 *
 * 线上证据（2.0.23，9 台设备共 32 条）：`盾后过盾失败（主进程）：站点侧请求排队
 * 超时。；原始失败：missav 请求失败 https://…` 这条被归成了 `command_cancelled`。
 * 原因很直白 —— 判定是对整条 msg 做无锚点 contains，而「排队超时」在
 * CANCEL_WORDS 里，包装层的话就把它带上了。
 *
 * 但它是**真失败**：盾没过去，用户没拿到内容。把它洗成「取消」等于把
 * 「这个源的盾过不去」这个信号藏起来 —— 和这个类里「先排掉超时」那条注释
 * 是同一个立场。
 *
 * 这里钉的是取舍方向：宁可把一次真取消算成失败，也不要把一次真失败藏进取消。
 */
public class ReportCancellationTest {

    /** 带上「；原始失败：」= 复合文案，散文匹配不可信。 */
    @Test
    public void 带原始失败的复合文案一律不算取消() {
        Exception wrapped = new java.io.IOException(
                "盾后过盾失败（主进程）：站点侧请求排队超时。；原始失败：missav 请求失败 https://missav.live/");
        assertFalse("里面有「排队超时」，但它是真失败", Report.isCancellation(wrapped));
    }

    /** 同上，但复合文案里带的是「被取消」这种看着更像取消的词。 */
    @Test
    public void 复合文案里带被取消也不算取消() {
        Exception wrapped = new java.io.IOException(
                "盾后过盾失败（主进程）：过盾排队被取消（预算已交给主进程）。；原始失败：星空影视 请求失败 https://www.dozagonza.ac/");
        assertFalse(Report.isCancellation(wrapped));
    }

    /** 单层文案还是照旧：这就是真的被新请求取代了。 */
    @Test
    public void 单层文案里的取消词仍然算取消() {
        assertTrue(Report.isCancellation(new java.io.IOException("被更新的请求取代")));
        assertTrue(Report.isCancellation(new java.io.IOException("spider 宿主已关闭。")));
        assertTrue(Report.isCancellation(new java.io.IOException("spider 排队超时（另一个调用还占着宿主）。")));
    }

    /** 类本身就是生命周期事件，不看消息。这一条不能被上面那条规则压掉。 */
    @Test
    public void 生命周期异常优先于文案规则() throws Exception {
        Exception interrupted = new InterruptedException("被更新的请求取代；原始失败：随便");
        assertTrue("InterruptedException 永远是取消", Report.isCancellation(interrupted));
    }

    /** 超时是真失败 —— 这条是原有立场，新规则不能把它带歪。 */
    @Test
    public void 超时仍然是失败() {
        assertFalse(Report.isCancellation(new java.net.SocketTimeoutException("timeout")));
        assertFalse(Report.isCancellation(new java.io.InterruptedIOException("timeout")));
        assertFalse(Report.isCancellation(new java.io.InterruptedIOException("timed out")));
    }

    /** 空消息的 InterruptedIOException 是 okhttp 掐连接的常态，仍然算取消。 */
    @Test
    public void 空消息的全中断仍然是取消() {
        assertTrue(Report.isCancellation(new java.io.InterruptedIOException()));
        assertTrue(Report.isCancellation(new java.io.InterruptedIOException("socket closed")));
    }

    /** null 不是取消，也不是失败 —— 它压根不该走到这里。 */
    @Test
    public void null_不算取消() {
        assertEquals(false, Report.isCancellation(null));
    }
}
