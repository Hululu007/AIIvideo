package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * 91porn（901.26702.app）的联网用例。
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*Porn91LiveTest*' -Dhg.live=1
 *   # 换镜像：-Dhg.site=https://其他域名
 *
 * 站点是 KVS 模板，服务端渲染，`/api/*` 全 404 —— 所以适配器抓 HTML、
 * 自己拼我们那套 JSON。这条用例钉的是「页面结构还没变」。
 */
public class Porn91LiveTest {

    private static String site() {
        String override = System.getProperty("hg.site", "");
        return override.length() > 0 ? override : "https://901.26702.app/";
    }

    private static void live() {
        Assume.assumeTrue("联网用例：加 -Dhg.live=1 才跑", "1".equals(System.getProperty("hg.live")));
    }

    private static JSONObject data(byte[] body) throws Exception {
        assertNotNull("适配器返回了 null", body);
        String text = new String(body, StandardCharsets.UTF_8);
        assertTrue("响应不是 JSON：" + text.substring(0, Math.min(160, text.length())),
                text.trim().startsWith("{"));
        JSONObject data = new JSONObject(text).optJSONObject("data");
        assertNotNull("没有 data 信封：" + text.substring(0, Math.min(160, text.length())), data);
        return data;
    }

    @Test
    public void 首页能出卡片() throws Exception {
        live();
        JSONObject home = data(Porn91Adapter.fetch("/api/home", null));
        JSONArray latest = home.optJSONArray("latest");
        assertNotNull("首页没有 latest", latest);
        assertTrue("首页一张卡都没有（站点改版了？）", latest.length() >= 10);

        JSONObject first = latest.getJSONObject(0);
        assertTrue("卡片没有 id", first.optString("id").length() > 0);
        assertTrue("卡片没有标题", first.optString("title").length() > 0);
        assertTrue("卡片没有封面：" + first.optString("cover"),
                first.optString("cover").startsWith("http"));
    }

    @Test
    public void 详情能出播放地址() throws Exception {
        live();
        JSONObject home = data(Porn91Adapter.fetch("/api/home", null));
        String id = home.getJSONArray("latest").getJSONObject(0).optString("id");

        JSONObject detail = data(Porn91Adapter.fetch("/api/videos/" + id, null));
        assertEquals(id, detail.optString("id"));
        assertTrue("详情没有标题", detail.optString("title").length() > 0);
        JSONArray episodes = detail.optJSONArray("episodes");
        assertNotNull("详情没有 episodes", episodes);
        assertTrue("一个分集都没有", episodes.length() > 0);
    }

    @Test
    public void 播放直链能拉到字节() throws Exception {
        live();
        JSONObject home = data(Porn91Adapter.fetch("/api/home", null));
        String id = home.getJSONArray("latest").getJSONObject(0).optString("id");

        JSONObject play = data(Porn91Adapter.fetch("/api/videos/" + id + "/play?ep=1", null));
        String source = play.optString("source", "");
        assertTrue("播放地址不是 http：" + source, source.startsWith("http"));
        assertNotNull("没有 lines", play.optJSONArray("lines"));

        byte[] head = AiiHttp.getRawRange(source, 0, 1024, SiteCfg.browserHeaders(), 20);
        assertTrue("直链没拉到字节", head != null && head.length > 0);
    }

    /**
     * 这个站**没有标签体系**（只有十个固定分类），tags 返回空数组是站点的形状，
     * 不是解析失败。所以这里只断言字段存在、是数组。
     */
    @Test
    public void 标签接口返回空数组不算错() throws Exception {
        live();
        JSONObject tags = data(Porn91Adapter.fetch("/api/tags", null));
        JSONArray groups = tags.optJSONArray("categories");
        assertNotNull("标签页没有 categories", groups);
        assertEquals("这个站不该有标签分组", 0, groups.length());
    }

    @Test
    public void 搜索能出结果() throws Exception {
        live();
        JSONObject result = data(Porn91Adapter.fetch(
                "/api/search?q=" + java.net.URLEncoder.encode("自拍", "UTF-8") + "&page=1", null));
        assertNotNull("搜索没有 items", result.optJSONArray("items"));
        assertTrue("搜索「自拍」一条都没有", result.getJSONArray("items").length() > 0);
    }

    /** 填地址能自动认出来 —— 以前这一站没有 probe，只能手贴配置。 */
    @Test
    public void 能把站点地址认出来并生成配置() throws Exception {
        live();
        Porn91Site site = new Porn91Site();
        assertTrue("域名没认出来", site.matchesUrl(site()));

        JSONObject config = site.probe(site());
        assertNotNull(config);
        assertEquals("porn91", config.optString("adapter"));
        assertEquals("https://901.26702.app", config.optString("origin"));

        JSONObject web = config.getJSONObject("web");
        assertTrue("ui 没放进 web 里", web.has("ui"));
        JSONArray tabs = web.getJSONObject("ui").getJSONArray("tabs");
        assertTrue("底部导航是空的", tabs.length() > 0);
        assertEquals("十个分类应该折成 5 + 更多", 6, tabs.length());

        // 封面白名单必须带上 CDN 域名，否则全是灰底
        JSONArray hosts = config.getJSONObject("cover").getJSONArray("allowedHosts");
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < hosts.length(); i++) joined.append(hosts.getString(i)).append(" ");
        assertTrue("封面白名单里没有 CDN：" + joined, joined.toString().contains("cdn77.org"));

        assertNull(UserSource.validate(config));
    }

    private static void assertNull(Object value) {
        org.junit.Assert.assertNull(value);
    }
}
