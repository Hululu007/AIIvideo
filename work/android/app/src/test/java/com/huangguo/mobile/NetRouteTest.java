package com.huangguo.mobile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

/**
 * 网络路由的回归 —— 「谁走代理、谁直连」这套决策只该有一个答案。
 *
 * 2026-10-18 接樱花动漫时，播放全线失败，根因是全局代理把国内 CDN 也拖走了。
 * 当时的修法是往 {@link DirectHosts} 手填 16 个采集站域名 —— 而域名天天换。
 * 现在改成「源自己声明哪些域走代理，其余直连」，这条用例钉住的就是这个语义。
 */
public class NetRouteTest {

    @After public void reset() {
        // 每个用例跑完回到「未声明」状态，免得污染别的用例（都是 static 状态）
        NetRoute.configure(null, "");
    }

    /**
     * 有 net.proxy：只有表里那个域走代理，别的（含新出现的 CDN）一律直连。
     *
     * <p>⚠️ 这里断言的是 {@code mustProxy}（源声明）而不是 {@code directOnly}（实际怎么发）。
     * 两者在 2026-10-19 之前被当成一回事，真机踩到才发现不是：
     *
     * <pre>
     *   源的 net.proxy 说 yhdm.one 要走代理
     *   全局设置是 mode=direct，effectiveProxy() 是空串
     *   于是 directOnly 说「不是直连」，mustProxy 说「要代理」，
     *   可谁也给不出代理 —— 请求空着直到 45 秒超时
     * </pre>
     *
     * <p>单测环境永远没有代理（appContext 是 null），所以 {@code directOnly} 对
     * 代理表里的域必然返回 true —— 那是「没有代理可用时按直连放行」的新语义，
     * 不是 bug。真正的「有没有代理」由 {@link NetRoute#proxyUnavailable} 单独表达。
     */
    @Test public void 声明了代理域就只代理那几个() throws Exception {
        JSONObject net = new JSONObject();
        net.put("proxy", new JSONArray().put("yhdm.one"));
        NetRoute.configure(net, "https://yhdm.one");

        // 站点入口：源声明了要走代理。这是「意图」，与实际发不发得出去无关。
        assertTrue("入口域必须是代理表里的", NetRoute.mustProxy("yhdm.one"));
        assertTrue(NetRoute.mustProxy("www.yhdm.one"));
        // 单测里没有代理可走，所以路由层把它按直连放行（可以失败，不能干等）。
        assertTrue("没有代理可用时应降级直连", NetRoute.proxyUnavailable("yhdm.one"));
        assertTrue("降级之后就是直连", NetRoute.directOnly("yhdm.one"));

        // 播放 CDN：必须直连 —— 这一条就是当初那个 bug 的全部内容
        assertTrue(NetRoute.directOnly("bfikuncdn.com"));
        assertTrue(NetRoute.directOnly("play.modujx17.com"));
        assertTrue(NetRoute.directOnly("vod12.wgslsw.com"));
        // 连没见过的 CDN 也直连：这正是「不维护白名单」的意义
        assertTrue(NetRoute.directOnly("some-cdn-nobody-has-seen.example"));
        assertFalse(NetRoute.mustProxy("bfikuncdn.com"));
        // CDN 不在代理表里，所以不存在「无代理可用」这回事
        assertFalse(NetRoute.proxyUnavailable("bfikuncdn.com"));
    }

    /** origin 就算源配置忘了写进 net.proxy，也必须进代理表 —— 否则整站直连，加源直接超时。 */
    @Test public void origin兜底进代理表() throws Exception {
        JSONObject net = new JSONObject();
        net.put("proxy", new JSONArray());        // 空表，等于「什么都不代理」
        NetRoute.configure(net, "https://yhdm.one");
        assertTrue("origin 必须被兜底塞进代理表", NetRoute.mustProxy("yhdm.one"));
        assertTrue(NetRoute.directOnly("bfikuncdn.com"));
    }

    /** 没声明 net 的源维持老语义：DirectHosts 白名单 + 全局开关。 */
    @Test public void 没声明时沿用老规则() {
        NetRoute.configure(null, "https://example.com");
        assertFalse("没有 net 块就不算声明", NetRoute.declared());
        // DirectHosts 里的老条目照旧直连
        assertTrue(NetRoute.directOnly("api.agedm.io"));
        assertTrue(NetRoute.directOnly("jx.wuzhoupai.com"));
        // 不在表里的保持「看全局开关」，这里不能断言死 —— 但至少不能误判成直连
        assertFalse(NetRoute.directOnly("example.com"));
    }
}
