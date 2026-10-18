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
 * OpenList 适配器的联网用例（asmrgay.com）。
 *
 * 默认跳过，手动跑：
 *
 * <pre>
 *   ./gradlew :app:testDebugUnitTest --tests '*OpenListLiveTest*' -Dhg.live=1
 *   # 换站点/镜像：-Dhg.site=https://asmrgay.org
 * </pre>
 *
 * 走完整条链：识别 → 探测 → 生成配置 → 过校验 → 首页 → 分类 → 详情 → 播放直链 → 搜索。
 */
public class OpenListLiveTest {

    private static String site() {
        String override = System.getProperty("hg.site", "");
        return override.length() > 0 ? override : "https://www.asmrgay.com/";
    }

    private static void live() {
        Assume.assumeTrue("联网用例：加 -Dhg.live=1 才跑", "1".equals(System.getProperty("hg.live")));
    }

    private static JSONObject data(byte[] body) throws Exception {
        assertNotNull("适配器返回了 null", body);
        String text = new String(body, StandardCharsets.UTF_8);
        assertTrue("响应不是 JSON：" + text.substring(0, Math.min(160, text.length())),
                text.trim().startsWith("{"));
        JSONObject envelope = new JSONObject(text);
        JSONObject data = envelope.optJSONObject("data");
        assertNotNull("没有 data 信封：" + text.substring(0, Math.min(160, text.length())), data);
        return data;
    }

    @Test
    public void 探针能生成配置() throws Exception {
        live();
        JSONObject config = new OpenListSite().probe(site());
        assertNotNull(config);

        assertEquals("openlist", config.optString("adapter"));
        assertEquals("https://www.asmrgay.com", config.optString("origin"));

        JSONObject web = config.getJSONObject("web");
        JSONObject paths = web.getJSONObject("paths");
        for (String key : new String[] { "home", "detail", "episode", "search", "category", "tags" }) {
            assertTrue("配置里缺 paths." + key, paths.optString(key, "").length() > 0);
        }

        JSONObject categories = web.getJSONObject("categories");
        assertTrue("分类表是空的", categories.length() > 0);

        // ui 在 web 里面 —— 页面读的是 window.HG_SITE.ui，HG_SITE 就是 web 块
        assertTrue("ui 没放进 web 里，底部导航会退回模板", web.has("ui"));
        JSONArray tabs = web.getJSONObject("ui").getJSONArray("tabs");
        assertTrue("底部导航是空的", tabs.length() > 0);
        assertTrue("底部导航最多 6 格", tabs.length() <= 6);
        for (int i = 0; i < tabs.length(); i++) {
            String key = tabs.getJSONObject(i).optString("key");
            assertTrue("tab 的 key 在分类表里找不到：" + key, categories.has(key));
        }

        // 封面白名单不能空 —— SiteProfile 构造时会抛「缺少封面域名」
        JSONArray hosts = config.getJSONObject("cover").getJSONArray("allowedHosts");
        assertTrue(hosts.length() > 0);
    }

    @Test
    public void 生成的配置能过校验() throws Exception {
        live();
        JSONObject config = new OpenListSite().probe(site());
        assertNull("配置没过校验：" + UserSource.validate(config), UserSource.validate(config));
    }

    @Test
    public void 首页能出作品卡片() throws Exception {
        live();
        JSONObject home = data(OpenListAdapter.fetch("/api/home", null));
        JSONArray featured = home.optJSONArray("featured");
        JSONArray latest = home.optJSONArray("latest");
        assertNotNull("首页没有 featured", featured);
        assertNotNull("首页没有 latest", latest);
        assertTrue("首页一张卡都没有", featured.length() + latest.length() >= 5);

        JSONObject first = featured.getJSONObject(0);
        assertTrue("卡片没有 id", first.optString("id").length() > 0);
        assertTrue("卡片没有标题", first.optString("title").length() > 0);
        // id 解回来必须是一条绝对路径 —— 详情页要靠它
        String path = OpenListAdapter.decodeId(first.optString("id"));
        assertTrue("id 解出来不是路径：" + path, path.startsWith("/"));

        // 卡片 = 第 3 段目录（tab/CV/作品）。不按名字认 —— /asmr 底下大部分作品没有 RJ 号。
        assertEquals("卡片不是第 3 段目录：" + path, 3, OpenListAdapter.depth(path));
    }

    @Test
    public void 分类页能翻页() throws Exception {
        live();
        String cate = "/asmr2";
        String path = "/api/videos?cate_id="
                + java.net.URLEncoder.encode(cate, "UTF-8") + "&page=1";
        JSONObject list = data(OpenListAdapter.fetch(path, null));
        JSONArray items = list.optJSONArray("items");
        assertNotNull("分类页没有 items", items);
        assertTrue("分类页一条都没有", items.length() > 0);
        JSONObject page = list.optJSONObject("pagination");
        assertNotNull("分类页没有分页信息", page);
        assertTrue("总页数不对", page.optInt("pages", 0) >= 1);

        for (int i = 0; i < Math.min(items.length(), 5); i++) {
            String p = OpenListAdapter.decodeId(items.getJSONObject(i).optString("id"));
            assertTrue("分类页混进了别的目录：" + p, p.startsWith(cate + "/"));
            assertEquals("分类页混进了非作品层：" + p, 3, OpenListAdapter.depth(p));
        }
    }

    @Test
    public void 详情能出分集() throws Exception {
        live();
        JSONObject home = data(OpenListAdapter.fetch("/api/home", null));
        String id = home.getJSONArray("featured").getJSONObject(0).optString("id");

        JSONObject detail = data(OpenListAdapter.fetch("/api/videos/" + id, null));
        assertEquals(id, detail.optString("id"));
        assertTrue("详情没有标题", detail.optString("title").length() > 0);

        JSONArray episodes = detail.optJSONArray("episodes");
        assertNotNull("详情没有 episodes", episodes);
        assertTrue("一个音频都没有 —— 可能作品里只有图片", episodes.length() > 0);
        assertTrue("标题是空的", episodes.getJSONObject(0).optString("title").length() > 0);
        assertEquals(1, episodes.getJSONObject(0).optInt("ep_num"));
    }

    @Test
    public void 播放能换到可下载的直链() throws Exception {
        live();
        JSONObject home = data(OpenListAdapter.fetch("/api/home", null));
        String id = home.getJSONArray("featured").getJSONObject(0).optString("id");

        JSONObject play = data(OpenListAdapter.fetch("/api/videos/" + id + "/play?ep=1", null));
        String source = play.optString("source", "");
        assertTrue("播放地址不是 http：" + source, source.startsWith("http"));
        assertNotNull("没有 lines", play.optJSONArray("lines"));
        assertTrue(play.getJSONArray("lines").length() > 0);

        // 直链在另一个域名上（asmr.121231234.xyz），真拉一段看是不是媒体
        byte[] head = AiiHttp.getRawRange(source, 0, 1024, SiteCfg.browserHeaders(), 20);
        assertTrue("直链没拉到字节", head != null && head.length > 0);
        int b0 = head[0] & 0xff;
        int b1 = head.length > 1 ? head[1] & 0xff : 0;
        boolean id3 = b0 == 0x49 && b1 == 0x44;                    // ID3
        boolean riff = b0 == 0x52 && b1 == 0x49;                   // RIFF/WAV
        boolean ftyp = head.length > 7 && (head[4] & 0xff) == 0x66; // mp4 ftyp
        assertTrue("直链不是音频/视频（前两字节 " + Integer.toHexString(b0) + " "
                + Integer.toHexString(b1) + "）", id3 || riff || ftyp);
    }

    @Test
    public void 搜索能出结果() throws Exception {
        live();
        // 搜作品名里的词。搜 CV 名（MOMOKA）搜不到 —— 关键词匹配的是目录名本身，
        // 作品目录叫 [RJ…]，父目录才叫 CV 名。这是站点搜索的语义，不是我们的 bug。
        String path = "/api/search?q=" + java.net.URLEncoder.encode("RJ0102", "UTF-8") + "&page=1";
        JSONObject result = data(OpenListAdapter.fetch(path, null));
        JSONArray items = result.optJSONArray("items");
        assertNotNull("搜索没有 items", items);
        assertTrue("搜索 RJ0102 一条都没有", items.length() > 0);
    }

    @Test
    public void 标签页给的是根目录() throws Exception {
        live();
        JSONObject tags = data(OpenListAdapter.fetch("/api/tags", null));
        JSONArray groups = tags.optJSONArray("categories");
        assertNotNull("标签页没有 categories", groups);
        assertTrue(groups.length() > 0);
        JSONArray items = groups.getJSONObject(0).optJSONArray("tags");
        assertNotNull(items);
        assertTrue("一个目录都没列出来", items.length() > 0);
        String slug = items.getJSONObject(0).optString("slug");
        assertTrue("标签 slug 不是路径：" + slug, slug.startsWith("/"));
    }

    /**
     * 递归：作品目录里是多层子目录（mp3版 / wav版 / イラスト），
     * 音频在第三层。这一条钉住「递归两层 + 只收媒体扩展名」。
     */
    @Test
    public void 多层目录里的音频都能捞到() throws Exception {
        live();
        String work = "/asmr2/MOMOKA。/[RJ117843]時代(とき)を超えた乙女～珠姫～";
        org.json.JSONArray episodes = OpenListAdapter.episodes("https://www.asmrgay.com", work);
        assertTrue("这一层没捞到音频（站点结构变了？）", episodes.length() >= 5);
        for (int i = 0; i < episodes.length(); i++) {
            String path = episodes.getJSONObject(i).optString("path", "");
            String name = OpenListAdapter.leaf(path);
            // 音视频，或者「体积像视频的伪装文件」（.jpg / .woff2）—— 后者是这个站的常态
            assertTrue("捞到了既不是音视频也不是伪装文件的东西：" + path,
                    OpenListAdapter.isMedia(name)
                            || episodes.getJSONObject(i).optBoolean("disguised"));
            assertFalse("把小的封面图也爬进来了：" + path,
                    OpenListAdapter.isDisguised(name) && !episodes.getJSONObject(i).optBoolean("disguised"));
        }
    }

    /** 每个 Tab 都得有东西 —— 空大类（asmr3 / asmr5）在探测阶段就该被剔掉。 */
    @Test
    public void 每个大类都能出卡片() throws Exception {
        live();
        JSONObject config = new OpenListSite().probe(site());
        JSONObject categories = config.getJSONObject("web").getJSONObject("categories");
        java.util.Iterator<String> keys = categories.keys();
        int checked = 0;
        while (keys.hasNext()) {
            String key = keys.next();
            if ("more".equals(key)) continue;
            String cate = categories.optString(key);
            String path = "/api/videos?cate_id="
                    + java.net.URLEncoder.encode(cate, "UTF-8") + "&page=1";
            JSONObject list = data(OpenListAdapter.fetch(path, null));
            JSONArray items = list.optJSONArray("items");
            assertNotNull("大类 " + key + " 没有 items", items);
            assertTrue("大类 " + key + " 一张卡都没有", items.length() > 0);
            checked++;
        }
        assertTrue("一个分类都没检查到", checked >= 2);
    }

    /**
     * /asmr6 那一批「图片」其实是视频：改名成 .jpg，内容是 1080p MPEG-TS。
     * 适配器要按体积放行，并且播放前嗅一个字节确认不是真图片。
     */
    @Test
    public void 伪装成jpg的视频能进选集也能播() throws Exception {
        live();
        String dir = "/asmr6/buy1take2/JOI/dir_JOI1";
        JSONArray eps = OpenListAdapter.episodes("https://www.asmrgay.com", dir);
        assertTrue("一个都没认出来", eps.length() >= 1);
        JSONObject first = eps.getJSONObject(0);
        assertTrue("没标成伪装文件", first.optBoolean("disguised"));
        assertTrue("标题没标记", first.optString("title").contains("伪装"));

        String id = OpenListAdapter.encodeId(dir);
        JSONObject play = data(OpenListAdapter.fetch("/api/videos/" + id + "/play?ep=1", null));
        assertTrue("播放地址不是 http：" + play.optString("source"),
                play.optString("source").startsWith("http"));
    }
}
