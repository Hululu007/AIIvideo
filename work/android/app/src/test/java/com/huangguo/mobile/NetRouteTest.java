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

    /** 有 net.proxy：只有表里那个域走代理，别的（含新出现的 CDN）一律直连。 */
    @Test public void 声明了代理域就只代理那几个() throws Exception {
        JSONObject net = new JSONObject();
        net.put("proxy", new JSONArray().put("yhdm.one"));
        NetRoute.configure(net, "https://yhdm.one");

        // 站点入口：必须走代理（被墙的是它）
        assertFalse("入口域不该直连", NetRoute.directOnly("yhdm.one"));
        assertFalse(NetRoute.directOnly("www.yhdm.one"));
        assertTrue("入口域必须走代理", NetRoute.mustProxy("yhdm.one"));

        // 播放 CDN：必须直连 —— 这一条就是这次 bug 的全部内容
        assertTrue(NetRoute.directOnly("bfikuncdn.com"));
        assertTrue(NetRoute.directOnly("play.modujx17.com"));
        assertTrue(NetRoute.directOnly("vod12.wgslsw.com"));
        // 连没见过的 CDN 也直连：这正是「不维护白名单」的意义
        assertTrue(NetRoute.directOnly("some-cdn-nobody-has-seen.example"));
        assertFalse(NetRoute.mustProxy("bfikuncdn.com"));
    }

    /** origin 就算源配置忘了写进 net.proxy，也必须进代理表 —— 否则整站直连，加源直接超时。 */
    @Test public void origin兜底进代理表() throws Exception {
        JSONObject net = new JSONObject();
        net.put("proxy", new JSONArray());        // 空表，等于「什么都不代理」
        NetRoute.configure(net, "https://yhdm.one");
        assertFalse("origin 必须被兜底塞进代理表", NetRoute.directOnly("yhdm.one"));
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
