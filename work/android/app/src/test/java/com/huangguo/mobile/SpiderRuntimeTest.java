package com.huangguo.mobile;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * spider 运行时里唯一能脱离 Android 测的一段：白名单解析。
 *
 * <p>为什么单独钉它：这条链路错了<b>不会崩</b>。解析成空表 = 所有 {@code net}
 * 被拒（表现为「这个源取不到数据」，和站点改版一模一样）；解析成过宽 = 白名单
 * 形同不存在（表现为「什么都没发生」，永远查不出来）。两种都只能靠用例守着。
 *
 * <p>输入是 {@code evaluateJavascript} 的回包，形如 {@code ["a.com","b.com"]}。
 */
public class SpiderRuntimeTest {

    /** 正常声明：原样取出来，统一小写、去掉首尾空白。 */
    @Test public void 声明的主机名统一小写并去空白() {
        assertArrayEquals(new String[]{"api.example.com", "cdn.example.com"},
                SpiderRuntime.parseHosts("[\" API.Example.com \",\"cdn.example.com\"]"));
    }

    /** 空条目要丢掉 —— 留着它等于允许访问空主机名，比不放行更糟。 */
    @Test public void 空条目被丢掉() {
        assertArrayEquals(new String[]{"a.com"},
                SpiderRuntime.parseHosts("[\"\",\"  \",\"a.com\"]"));
    }

    /** 没声明就是空表：net 全拒。这个语义不能退化成「不限」。 */
    @Test public void 没声明就是空表() {
        assertEquals(0, SpiderRuntime.parseHosts(null).length);
        assertEquals(0, SpiderRuntime.parseHosts("").length);
        assertEquals(0, SpiderRuntime.parseHosts("null").length);
        assertEquals(0, SpiderRuntime.parseHosts("[]").length);
    }

    /** 回包不是 JSON 时按空白名单算：宁可拒绝，不能放行。 */
    @Test public void 解析失败按空白名单算() {
        assertEquals(0, SpiderRuntime.parseHosts("这不是 JSON").length);
        assertEquals(0, SpiderRuntime.parseHosts("{}").length);
    }
}
