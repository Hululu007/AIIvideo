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

    /** origin 就算适配器忘了写，也必须进代理表 —— 否则整站直连，加源直接超时。 */
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

    /** 樱花动漫的配置必须真的带上 net.proxy，否则上面那些语义就没生效。 */
    @Test public void 樱花配置声明了代理域() throws Exception {
        JSONObject config = YhdmSite.buildConfig("https://yhdm.one");
        JSONObject net = config.optJSONObject("net");
        assertNotNull("配置里必须有 net 块", net);
        JSONArray proxy = net.optJSONArray("proxy");
        assertNotNull(proxy);
        boolean hasOrigin = false;
        for (int i = 0; i < proxy.length(); i++) {
            if ("yhdm.one".equals(proxy.optString(i))) hasOrigin = true;
        }
        assertTrue("net.proxy 里必须有站点入口域", hasOrigin);
    }

    /**
     * 旧配置（没有 net 块）必须被适配器补上。
     *
     * 2026-10-18 真机踩到：配置是加源那天存的快照，适配器后来加了 net 声明，
     * 老配置里没有 —— 运行时还是走老规则，CDN 照样被代理拖走。
     * 症状和「没修」一模一样，而代码看起来完全正确。
     */
    @Test public void 旧配置会被补上net块() throws Exception {
        JSONObject old = YhdmSite.buildConfig("https://yhdm.one");
        old.remove("net");                       // 模拟 22:42 存下来的那份
        NetRoute.configure(old.optJSONObject("net"), "");   // 没补之前：未声明
        assertFalse(NetRoute.declared());

        new YhdmSite().patch(old);               // SiteProfile 构造时会调这个
        assertNotNull("适配器必须补上 net 块", old.optJSONObject("net"));

        NetRoute.configure(old.optJSONObject("net"), "https://yhdm.one");
        assertTrue(NetRoute.declared());
        assertFalse("入口域走代理", NetRoute.directOnly("yhdm.one"));
        assertTrue("CDN 直连", NetRoute.directOnly("bfikuncdn.com"));
    }

    /** 路径模板里的占位符，前端必须都填得上 —— 少一个就是静默空请求。 */
    @Test public void 路径模板的占位符都能填上() throws Exception {
        JSONObject config = YhdmSite.buildConfig("https://yhdm.one");
        JSONObject paths = config.optJSONObject("web").optJSONObject("paths");
        // 前端 fillTemplate 的参数表（adapters.js apiPathFor 里那个对象）
        java.util.Set<String> known = new java.util.HashSet<String>(java.util.Arrays.asList(
                "id", "episode", "keyword", "slug", "cateId", "page", "size"));
        for (java.util.Iterator<String> it = paths.keys(); it.hasNext(); ) {
            String key = it.next();
            String tpl = paths.optString(key, "");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{(\\w+)\\}").matcher(tpl);
            while (m.find()) {
                assertTrue("web.paths." + key + " 用了未知占位符 {" + m.group(1) + "}",
                        known.contains(m.group(1)));
            }
        }
    }
}
