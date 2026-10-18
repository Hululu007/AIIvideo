package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 适配器注册表的单元测试。
 *
 * 这一层守的是「加站点只改一处」这条规矩 —— 名单、识别、校验、id 空间全从
 * {@link Adapters#ALL} 来。哪天有人又手抄一份名单出去，最先红的是这里。
 *
 * 另外两条是这次改造顺带修的回归：
 *   · porn91 有实现、有 /api/ 分流，却不在 validate 的白名单里，源根本加不进来；
 *   · 报错文案里的适配器名单是手写的，和真实名单对不上。
 */
public class AdaptersTest {

    /** 最小可用的接口型配置（cms / agedm）。 */
    private static JSONObject api(String api) throws Exception {
        JSONObject config = new JSONObject();
        if (api != null) config.put("api", api);
        config.put("adapter", "cms");
        config.put("web", new JSONObject().put("paths", new JSONObject().put("home", "/api/home")));
        return config;
    }

    /** 最小可用的单站 HTML 型配置（hanime1 / mrds66 / porn91）。 */
    private static JSONObject origin(String origin) throws Exception {
        JSONObject config = new JSONObject();
        if (origin != null) config.put("origin", origin);
        config.put("web", new JSONObject().put("paths", new JSONObject().put("home", "/api/home")));
        return config;
    }

    @Test
    public void 名单非空且id不重复() {
        assertTrue(Adapters.ALL.size() >= 5);
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        for (SiteAdapter adapter : Adapters.ALL) {
            assertNotNull(adapter.id());
            assertFalse(adapter.id().trim().length() == 0);
            assertTrue("重复的适配器 id：" + adapter.id(), seen.add(adapter.id()));
        }
    }

    @Test
    public void byId能找回每一个() {
        for (SiteAdapter adapter : Adapters.ALL) {
            assertSame(adapter, Adapters.byId(adapter.id()));
        }
        assertNull(Adapters.byId("不存在"));
        assertNull(Adapters.byId(null));
    }

    /** 回归：porn91 以前不在 validate 的白名单里，一份合法的配置会被判「不认识的适配器」。 */
    @Test
    public void porn91的配置能过校验() throws Exception {
        assertNull(Adapters.byId("porn91").check(origin("https://example.com")));
        JSONObject config = origin("https://example.com");
        config.put("adapter", "porn91");
        assertNull(UserSource.validate(config));
    }

    @Test
    public void 未注册的适配器被拒且文案列出全部名字() throws Exception {
        JSONObject config = origin("https://example.com");
        config.put("adapter", "不存在的适配器");
        String problem = UserSource.validate(config);
        assertNotNull(problem);
        assertTrue(problem.contains("不存在的适配器"));
        for (SiteAdapter adapter : Adapters.ALL) {
            assertTrue("文案里漏了 " + adapter.id(), problem.contains(adapter.id()));
        }
    }

    @Test
    public void 接口型缺api被拒() throws Exception {
        assertEquals("缺少 api 地址", Adapters.byId("cms").check(api(null)));
        assertEquals("缺少 api 地址", Adapters.byId("agedm").check(api("")));
        assertEquals("api 地址必须是 https 开头", Adapters.byId("cms").check(api("http://a.com")));
        assertNull(Adapters.byId("cms").check(api("https://a.com/api.php/provide/vod/")));
    }

    @Test
    public void 单站型缺origin被拒() throws Exception {
        for (String id : new String[] { "hanime1", "mrds66", "porn91" }) {
            assertEquals("缺少 origin（这个源要填站点地址）", Adapters.byId(id).check(origin(null)));
            assertEquals("origin 必须是 https 开头", Adapters.byId(id).check(origin("http://a.com")));
            assertNull(Adapters.byId(id).check(origin("https://a.com")));
        }
    }

    /** 识别顺序：AGE动漫 / hanime1 / 每日大赛 都得在「按响应形状认」的 cms 之前。 */
    @Test
    public void 按地址认的排在按形状认的前面() {
        int cms = -1;
        for (int i = 0; i < Adapters.ALL.size(); i++) {
            if ("cms".equals(Adapters.ALL.get(i).id())) cms = i;
            assertTrue("cms 之前不该有别的按形状认的适配器",
                    Adapters.ALL.get(i).id().equals("cms") || !Adapters.ALL.get(i).matchesBody(null));
        }
        assertTrue(cms > 0);
    }

    @Test
    public void 内部动作路径原样通过() {
        assertEquals("/api/home", Adapters.actionPath("/api/home", null));
        assertEquals("/api/videos/1", Adapters.actionPath("/api/videos/1", null));
    }

    @Test
    public void 非动作路径不分流() {
        // 封面图的绝对地址、外站接口都从这里走过去，不能被当成动作路径。
        assertNull(Adapters.actionPath("https://pic.site/a.jpg", null));
        assertNull(Adapters.actionPath("/static/app.js", null));
        assertNull(Adapters.actionPath(null, null));
    }

    /** id 空间归适配器自己说 —— 说错了观看历史会串源。 */
    @Test
    public void id空间由适配器给出() throws Exception {
        assertEquals("hgsrc:agedm|https://api.agedm.io/v2/detail/",
                Adapters.byId("agedm").identity(api(null)));
        assertEquals("hgsrc:agedm|https://api.agedm.io/v2/detail/",
                Adapters.byId("agedm").identity(api("https://api.agedm.io/")));
        assertEquals("hgsrc:cms|https://a.com/api.php/provide/vod",
                Adapters.byId("cms").identity(api("https://a.com/api.php/provide/vod")));
        assertNull(Adapters.byId("cms").identity(api(null)));
    }

    /** cms 只能按形状认，不能按域名认 —— 认错了会把普通 JSON 配置拽进 CMS 那条路。 */
    @Test
    public void cms只认形状不认地址() throws Exception {
        SiteAdapter cms = Adapters.byId("cms");
        assertFalse(cms.matchesUrl("https://any.cms.site/"));
        assertTrue(cms.matchesBody(new JSONObject().put("code", 1).put("list", new org.json.JSONArray()).put("class", new org.json.JSONArray())));
        assertFalse(cms.matchesBody(new JSONObject().put("origin", "https://a.com")));
        assertFalse(cms.matchesBody(null));
    }
}
