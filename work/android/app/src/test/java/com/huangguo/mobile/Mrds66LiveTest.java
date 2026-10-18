package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * 每日大赛（mrds66.com）的联网用例。
 *
 * **默认不跑。** 这条会真的去打 https://www.mrds66.com/ —— CI 不该依赖外网，
 * 站点改版也不该让 178 个离线用例变红。手动验真站：
 *
 * <pre>
 *   ./gradlew :app:testDebugUnitTest --tests '*Mrds66LiveTest*' -Dhg.live=1
 *   # 换站点验证（镜像域）：
 *   ./gradlew :app:testDebugUnitTest --tests '*Mrds66LiveTest*' -Dhg.live=1 -Dhg.site=https://...
 * </pre>
 *
 * 七个用例正好走完一条链：识别 → 生成配置 → 过校验 → 首页 → 分类 → 详情 → 分集。
 * 哪一段断了，报错会直接指到那一段。
 *
 * `Mrds66Adapter.fetch(path, null)` 里的 null 是有意的：profile 只用来取 origin，
 * 传 null 就落回 DEFAULT_HOST —— 也就是这条用例要测的那个站。
 */
public class Mrds66LiveTest {

    private static String site() {
        String override = System.getProperty("hg.site", "");
        return override.length() > 0 ? override : "https://www.mrds66.com/";
    }

    private static void live() {
        Assume.assumeTrue("联网用例：加 -Dhg.live=1 才跑", "1".equals(System.getProperty("hg.live")));
    }

    /** 适配器统一把结果包在 `{data: …}` 里 —— 前端按 envelopeKey 拆，这里也照做。 */
    private static JSONObject json(byte[] body) throws Exception {
        assertNotNull("适配器返回了 null", body);
        String text = new String(body, StandardCharsets.UTF_8);
        assertTrue("响应不是 JSON：" + text.substring(0, Math.min(200, text.length())),
                text.trim().startsWith("{"));
        JSONObject envelope = new JSONObject(text);
        JSONObject data = envelope.optJSONObject("data");
        assertNotNull("响应里没有 data 信封：" + text.substring(0, Math.min(200, text.length())), data);
        return data;
    }

    @Test
    public void 域名能认出来() {
        live();
        Mrds66Site impl = new Mrds66Site();
        assertTrue(impl.matchesUrl(site()));
        assertTrue(impl.matchesUrl("https://mrds66.com/"));
        assertTrue(impl.matchesUrl("https://www.mrds66.com/category/dyds/"));
        // 别的站不能被认成每日大赛 —— 认错的表现是首页一片空白，比报错还糟
        assertFalse(impl.matchesUrl("https://hanime1.me/"));
        assertFalse(impl.matchesUrl("https://example.com/"));
    }

    @Test
    public void 探针能生成配置() throws Exception {
        live();
        JSONObject config = new Mrds66Site().probe(site());
        assertNotNull("探针没通过：首页变了、被墙了，或者填错了站", config);

        assertEquals("mrds66", config.optString("adapter"));
        assertEquals("https://www.mrds66.com", config.optString("origin"));

        JSONObject paths = config.getJSONObject("web").getJSONObject("paths");
        for (String key : new String[] { "home", "detail", "episode", "search", "category", "tags" }) {
            assertTrue("配置里缺 paths." + key, paths.optString(key, "").length() > 0);
        }

        // 封面是 AES 密的（2026-10-18 起），模式写 plain 的话解密步骤不会执行，
        // 结果是「封面解密结果无效」→ 首页全是灰底占位图。
        JSONObject cover = config.getJSONObject("cover");
        assertEquals("aes-cbc", cover.optString("mode"));
        assertEquals("AES/CBC/PKCS5Padding", cover.optString("transformation"));
        assertEquals(16, cover.optString("key").length());
        assertEquals(16, cover.optString("iv").length());

        // 封面白名单必须包含站点实际用的图床，否则封面全挂
        JSONArray hosts = cover.getJSONArray("allowedHosts");
        assertTrue("封面白名单是空的", hosts.length() > 0);
        boolean hasPic = false;
        for (int i = 0; i < hosts.length(); i++) {
            if ("pic.jtzatly.cn".equals(hosts.getString(i))) hasPic = true;
            assertTrue("封面域名格式不对：" + hosts.getString(i),
                    hosts.getString(i).matches("[a-zA-Z0-9.-]+"));
        }
        assertTrue("封面白名单里没有被实测的图床 pic.jtzatly.cn", hasPic);

        // 分类表要跟适配器里那份对上 —— 对不上就是「配置里能点、站点上 404」。
        assertEquals(Mrds66Adapter.CATEGORIES.length,
                config.getJSONObject("web").getJSONObject("categories").length());

        // ui.tabs 不在这里生成：这个源的 tab 由模板（site-default.json）合并出来。
        // 配置里塞一份反而会和前端那份打架（见 wrapMrds66 的注释）。
    }

    /** 抓一张真封面、按配置解一遍，确认密钥和模式对得上。 */
    @Test
    public void 封面能解密成图片() throws Exception {
        live();
        JSONObject config = new Mrds66Site().probe(site());
        JSONObject home = json(Mrds66Adapter.fetch("/api/home", null));
        String coverUrl = home.getJSONArray("featured").getJSONObject(0).optString("cover", "");
        assertTrue("首页卡片没有封面地址", coverUrl.startsWith("http"));

        byte[] raw = AiiHttp.getRaw(coverUrl, SiteCfg.browserHeaders(), 20);
        assertTrue("封面没拉下来", raw != null && raw.length > 1000);

        JSONObject cover = config.getJSONObject("cover");
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(
                cover.getString("transformation"));
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
                new javax.crypto.spec.SecretKeySpec(
                        cover.getString("key").getBytes(StandardCharsets.UTF_8), "AES"),
                new javax.crypto.spec.IvParameterSpec(
                        cover.getString("iv").getBytes(StandardCharsets.UTF_8)));
        byte[] image = cipher.doFinal(raw);
        assertTrue("解出来太小：" + image.length, image.length > 1000);

        String hex = String.format("%02x%02x%02x", image[0], image[1], image[2]);
        assertTrue("解出来不是图片（头 " + hex + "）",
                hex.equals("ffd8ff") || hex.equals("89504e") || hex.startsWith("474946")
                        || hex.startsWith("524946"));
    }

    @Test
    public void 生成的配置能过校验() throws Exception {
        live();
        JSONObject config = new Mrds66Site().probe(site());
        assertNull("配置没过校验：" + UserSource.validate(config), UserSource.validate(config));
    }

    @Test
    public void 首页接口出卡片() throws Exception {
        live();
        JSONObject home = json(Mrds66Adapter.fetch("/api/home", null));
        JSONArray featured = home.optJSONArray("featured");
        JSONArray latest = home.optJSONArray("latest");
        assertNotNull(featured);
        assertNotNull(latest);

        int total = featured.length() + latest.length();
        assertTrue("首页一张卡都没有（站点改版了？）", total >= 5);

        JSONObject first = featured.optJSONObject(0);
        assertNotNull(first);
        assertTrue("卡片没有 id", first.optString("id", "").length() > 0);
        assertTrue("卡片没有标题", first.optString("title", "").length() > 0);
        assertTrue("卡片没有封面：" + first.optString("cover", ""),
                first.optString("cover", "").startsWith("http"));
    }

    @Test
    public void 分类列表能翻页() throws Exception {
        live();
        String slug = Mrds66Adapter.CATEGORIES[0][0];
        JSONObject list = json(Mrds66Adapter.fetch("/api/videos?cate_id=" + slug + "&page=1", null));
        JSONArray items = list.optJSONArray("items");
        assertNotNull("分类页没有 items", items);
        assertTrue("分类 " + slug + " 一条都没有（slug 变了？）", items.length() > 0);
        assertNotNull("分类页没有分页信息", list.optJSONObject("pagination"));
    }

    @Test
    public void 详情能出分集() throws Exception {
        live();
        JSONObject home = json(Mrds66Adapter.fetch("/api/home", null));
        String id = home.getJSONArray("featured").getJSONObject(0).optString("id", "");
        assertTrue(id.length() > 0);

        JSONObject detail = json(Mrds66Adapter.fetch("/api/videos/" + id, null));
        assertEquals(id, detail.optString("id"));
        assertTrue("详情没有标题", detail.optString("title", "").length() > 0);
        // 单集站：详情恒一条 episode，**不带播放地址** —— 地址是 signed m3u8，
        // 每次点播放现抓，缓存等于「过一会儿点开就是 400」。
        JSONArray episodes = detail.optJSONArray("episodes");
        assertNotNull("详情没有 episodes", episodes);
        assertEquals(1, episodes.length());
        assertEquals(1, episodes.getJSONObject(0).optInt("ep_num", 0));
    }

    @Test
    public void 播放能拿到签名地址() throws Exception {
        live();
        JSONObject home = json(Mrds66Adapter.fetch("/api/home", null));
        String id = home.getJSONArray("featured").getJSONObject(0).optString("id", "");

        JSONObject play = json(Mrds66Adapter.fetch("/api/videos/" + id + "/play?ep=1", null));
        String source = play.optString("source", "");
        assertTrue("播放地址不是 http 开头：" + source, source.startsWith("http"));
        // 站点给的是签名 m3u8（auth_key 绑时间戳），拿到 HTML 或空串都算没通
        assertTrue("播放地址看着不像 m3u8：" + source, source.contains(".m3u8"));
        JSONArray lines = play.optJSONArray("lines");
        assertNotNull("播放接口没有 lines", lines);
        assertTrue("一档清晰度都没有", lines.length() > 0);
        assertTrue(lines.getJSONObject(0).optString("url", "").startsWith("http"));
    }

    /** 适配器得在注册表里 —— 不然校验和 /api/ 分流都找不到它。 */
    @Test
    public void 注册表里能找到() {
        live();
        assertNotNull(Adapters.byId("mrds66"));
        assertTrue(Adapters.ids().contains("mrds66"));
    }
}
