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

    /**
     * **回归：详情页的封面和它给的播放直链必须是同一部。**
     *
     * 2026-10-18 的 bug 是「点进去片子跟标题封面对不上」。根因是站点在没有游客
     * 标记 `ga` 时，`view_video.php` 每次渲染随机挑一部片 —— 标题按 viewkey 取
     * （永远对），媒体和封面按会话取。
     *
     * 这条钉的是**同一次渲染内部自洽**：`poster` 的数字段必须等于播放直链文件名
     * 的数字段（`thumb/1252139.jpg` ↔ `mp43/1252139.mp4`）。注意随机渲染时这一页
     * 也是自洽的，所以光靠这条抓不到 bug —— 抓它的是下面那条「连取三次不变」。
     *
     * ── 为什么不断言「卡片封面 == 详情封面」──────────────────────────
     * 试过，站点上不成立：同一个封面会被两条不同的 viewkey 共用（同一段片子被
     * 不同人重复投稿），实测 8 条里 2 条对不上，而同一条连取又稳定。拿它当判据
     * 会把站点的去重行为误判成我们的 bug。
     */
    @Test
    public void 封面和播放直链指向同一部() throws Exception {
        live();
        Porn91Adapter.forgetGuest();          // 每次从「没有标记」开始，走真实冷启动路径

        JSONObject home = data(Porn91Adapter.fetch("/api/home", null));
        JSONArray latest = home.getJSONArray("latest");
        assertTrue("首页卡片太少", latest.length() >= 3);

        int checked = 0;
        for (int i = 0; i < latest.length() && checked < 3; i++) {
            String id = latest.getJSONObject(i).optString("id", "");
            if (id.length() == 0) continue;

            JSONObject detail = data(Porn91Adapter.fetch("/api/videos/" + id, null));
            JSONObject play = data(Porn91Adapter.fetch("/api/videos/" + id + "/play?ep=1", null));
            String cover = fileId(detail.optString("cover", ""));
            String media = fileId(play.optString("source", ""));
            assertTrue("封面/直链里抠不出数字段：cover=" + cover + " media=" + media,
                    cover.length() > 0 && media.length() > 0);
            assertEquals("封面和直链不是同一部片（id=" + id + "）", cover, media);
            checked++;
        }
        assertEquals("应该比到 3 条", 3, checked);
    }

    /** 同一条连取三次，封面必须纹丝不动（之前是三次数三个不同值）。 */
    @Test
    public void 同一条连取三次封面不变() throws Exception {
        live();
        Porn91Adapter.forgetGuest();

        JSONObject home = data(Porn91Adapter.fetch("/api/home", null));
        String id = home.getJSONArray("latest").getJSONObject(0).optString("id");
        String first = fileId(data(Porn91Adapter.fetch("/api/videos/" + id, null)).optString("cover", ""));
        Porn91Adapter.forgetGuest();          // 连缓存一起丢掉，逼它重新渲染
        String second = fileId(data(Porn91Adapter.fetch("/api/videos/" + id, null)).optString("cover", ""));
        Porn91Adapter.forgetGuest();
        String third = fileId(data(Porn91Adapter.fetch("/api/videos/" + id, null)).optString("cover", ""));
        assertEquals("同一 viewkey 三次渲染封面都不一样："
                + first + " / " + second + " / " + third, first, second);
        assertEquals(first, third);
    }

    /**
     * **校正接口：把列表卡片改成详情页的真值。**
     *
     * 站点网格页在乱写（同页一个 viewkey 出现 2~4 次、配不同封面标题），
     * 所以前端先照网格页铺图，再调 `/api/reconcile?ids=…` 拿详情页的真值盖掉。
     * 这条钉的就是那个接口：返回的标题/封面必须和各自详情页逐字段一致。
     */
    @Test
    public void 校正接口能给出详情真值() throws Exception {
        live();
        Porn91Adapter.forgetGuest();

        JSONObject home = data(Porn91Adapter.fetch("/api/home", null));
        JSONArray latest = home.getJSONArray("latest");
        assertTrue("首页卡片太少", latest.length() >= 6);

        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (ids.length() > 0) ids.append(',');
            ids.append(latest.getJSONObject(i).optString("id", ""));
        }
        JSONObject fixed = data(Porn91Adapter.fetch(
                "/api/reconcile?ids=" + java.net.URLEncoder.encode(ids.toString(), "UTF-8"), null));
        JSONArray items = fixed.optJSONArray("items");
        assertNotNull("校正接口没有 items", items);
        assertTrue("校正回来条数太少：" + items.length(), items.length() >= 5);

        for (int i = 0; i < items.length(); i++) {
            JSONObject one = items.getJSONObject(i);
            String id = one.optString("id", "");
            JSONObject detail = data(Porn91Adapter.fetch("/api/videos/" + id, null));
            assertEquals("校正结果和详情封面不一致 id=" + id,
                    fileId(one.optString("cover", "")), fileId(detail.optString("cover", "")));
            assertEquals("校正结果和详情标题不一致 id=" + id,
                    one.optString("title", ""), detail.optString("title", ""));
        }
    }

/**
     * **高清分类必须有卡片。**
     *
     * 这条对应真机上「高清 → 已显示 0 部作品」：那个分类的整页卡片都走
     * `view_video_hd.php?viewkey=…`，只认 `view_video.php` 就一条都匹配不上。
     */
    @Test
    public void 高清分类能出卡片() throws Exception {
        live();
        JSONObject list = data(Porn91Adapter.fetch("/api/videos?cate_id=hd&page=1", null));
        JSONArray items = list.getJSONArray("items");
        assertTrue("高清分类 0 部作品（view_video_hd.php 没认？实际 " + items.length() + " 条）",
                items.length() >= 10);
        JSONObject first = items.getJSONObject(0);
        assertTrue("高清卡片没有 id", first.optString("id").length() > 0);
        assertTrue("高清卡片没有封面", first.optString("cover").startsWith("http"));
    }

    /**
     * 地址里那个数字段：`…/thumb/1252139.jpg` / `…/mp43/1252139.mp4` → `1252139`。
     * 站点的封面和直链指向同一部片时，这两个数字必然相等。
     */
    private static String fileId(String url) {
        if (url == null || url.length() == 0) return "";
        int slash = url.lastIndexOf('/');
        String name = slash >= 0 ? url.substring(slash + 1) : url;
        int dot = name.indexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void assertNull(Object value) {
        org.junit.Assert.assertNull(value);
    }
}