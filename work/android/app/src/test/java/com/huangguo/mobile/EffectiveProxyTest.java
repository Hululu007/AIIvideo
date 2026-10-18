package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 代理来源优先级的单元测试。
 *
 * 测的是 {@link AiiHttp#effectiveProxy(String, String, String)} 这个纯函数：三个入参
 * 分别是「当前模式」「内置核心给的地址」「用户手填的代理串」，它只决定走哪条。真正去问 ProxyCore
 * 的那层（effectiveProxy()）是两行胶水，在 JVM 里跑不了 —— .so 只有 arm64-v8a。
 *
 * 所以把纯函数抽出来就是为了这个：**优先级规则必须能在机器上断言**，
 * 而不是等真机上「怎么流量还是从本地 IP 出去的」来发现。
 *
 * 三件事值得钉死：
 *   1. 两个都有时，内置赢 —— 用户按了「开启」，那是明确表态。
 *   2. 内置没跑时**回落**到用户手填，不是置空。核心起不来不等于用户配的外部代理作废。
 *   3. 内置启动失败给的是空串（ProxyCore 的约定），配合第 2 条自然退化到
 *      「有外部代理就还走它，没配才直连」。
 */
public class EffectiveProxyTest {

    private static final String BUILTIN = "socks5://127.0.0.1:20001";
    private static final String EXTERNAL = "socks5://10.0.0.2:1080";

    /** 核心在跑 + 用户也填了 → 用核心的。 */
    @Test
    public void 内置核心在跑时压过用户手填的代理() {
        assertEquals(BUILTIN, AiiHttp.effectiveProxy(UserSource.MODE_BUILTIN, BUILTIN, EXTERNAL));
    }

    /** 核心没跑（空串）+ 用户填了 → 回落，不是直连。 */
    @Test
    public void 核心没跑时回落到用户手填的代理() {
        assertEquals(EXTERNAL, AiiHttp.effectiveProxy(UserSource.MODE_BUILTIN, "", EXTERNAL));
    }

    /** 都没有 → 空串，也就是直连。 */
    @Test
    public void 两个都没有时是空串() {
        assertEquals("", AiiHttp.effectiveProxy(UserSource.MODE_BUILTIN, "", ""));
    }

    /** 核心在跑 + 用户没填 → 用核心的。这是最常见的组合（用户直接用内置节点）。 */
    @Test
    public void 用户没填过代理时直接用内置核心的() {
        assertEquals(BUILTIN, AiiHttp.effectiveProxy(UserSource.MODE_BUILTIN, BUILTIN, ""));
    }

    /**
     * 内置启动失败时 ProxyCore.start() 已经把状态复位，socksUrl() 返回空串而不是 null。
     * 但这里连 null 也一并兜住 —— 这个方法会被每次建客户端时调用，一个 NPE
     * 会让所有网络请求炸在构造 OkHttpClient 那一步。
     */
    @Test
    public void 内置返回null时也当没跑来处理() {
        assertEquals(EXTERNAL, AiiHttp.effectiveProxy(UserSource.MODE_BUILTIN, null, EXTERNAL));
        assertEquals("", AiiHttp.effectiveProxy(UserSource.MODE_BUILTIN, null, null));
    }

    /** proxyRaw() 走的是 trim 过的值，正常不会是 null；这里确认它不被当成「有值」。 */
    @Test
    public void 用户代理是null时当没填() {
        assertEquals("", AiiHttp.effectiveProxy(UserSource.MODE_BUILTIN, "", null));
        assertEquals(BUILTIN, AiiHttp.effectiveProxy(UserSource.MODE_BUILTIN, BUILTIN, null));
    }
}
