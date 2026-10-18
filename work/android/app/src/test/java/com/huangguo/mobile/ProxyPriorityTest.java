package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 代理优先级：**直连 &gt; 内置核心 &gt; 外部地址**。
 *
 * 这条规则以前是坏的：`effectiveProxy()` 只做「内置非空就用内置，否则用手填的」，
 * 压根不看模式。于是「直连」实际是「把核心停掉」，盘上那个手填地址照样生效 ——
 * 界面写着「所有请求都不走代理」，请求却在往一个可能已经死掉的端口上撞。
 *
 * 这些用例是纯函数，不碰 Context，所以在 JVM 上直接跑。
 */
public class ProxyPriorityTest {

    private static final String CORE = "socks5://127.0.0.1:23456";
    private static final String MANUAL = "127.0.0.1:7897";

    @Test
    public void 直连忽略内置和手填() {
        assertEquals("", AiiHttp.effectiveProxy(UserSource.MODE_DIRECT, CORE, MANUAL));
        assertEquals("", AiiHttp.effectiveProxy(UserSource.MODE_DIRECT, "", MANUAL));
        assertEquals("", AiiHttp.effectiveProxy(UserSource.MODE_DIRECT, CORE, ""));
    }

    @Test
    public void 内置核心压过手填地址() {
        assertEquals(CORE, AiiHttp.effectiveProxy(UserSource.MODE_BUILTIN, CORE, MANUAL));
    }

    @Test
    public void 内置没跑时仍然回落到手填() {
        // 这条是既有契约（见 EffectiveProxyTest）：核心起不来不等于用户配的外部代理作废。
        // 改造只动了「直连」这一档，回落保持原样。
        assertEquals(MANUAL, AiiHttp.effectiveProxy(UserSource.MODE_BUILTIN, "", MANUAL));
    }

    @Test
    public void 外部地址只用填的那个() {
        assertEquals(MANUAL, AiiHttp.effectiveProxy(UserSource.MODE_EXTERNAL, CORE, MANUAL));
        assertEquals(MANUAL, AiiHttp.effectiveProxy(UserSource.MODE_EXTERNAL, "", MANUAL));
    }

    @Test
    public void 外部地址没填等于直连() {
        assertEquals("", AiiHttp.effectiveProxy(UserSource.MODE_EXTERNAL, CORE, ""));
        assertEquals("", AiiHttp.effectiveProxy(UserSource.MODE_EXTERNAL, CORE, null));
    }

    @Test
    public void 模式读不到时退回老规矩() {
        // init 之前 appContext 还是 null，调用点拿不到模式。这时候内置优先 ——
        // 和改造前的行为一致，别让早期请求突然直连。
        assertEquals(CORE, AiiHttp.effectiveProxy("", CORE, MANUAL));
        assertEquals(MANUAL, AiiHttp.effectiveProxy(null, "", MANUAL));
        assertEquals("", AiiHttp.effectiveProxy(null, null, null));
    }
}
