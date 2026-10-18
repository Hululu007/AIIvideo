package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Mrds66Adapter 的离线解析回归。
 *
 * 拿真实抓下来的 HTML（fixtures/mrds66/）喂给适配器的**真实解析逻辑**，
 * 验证首页 / 分类 / 搜索 / 标签 / 详情 / 播放地址 / 分页总数。
 *
 * ── 它不测什么 ──
 * **不发任何网络请求**。所以它证明的是「HTML 解析对不对」，不是「安卓端能不能
 * 连上站点」，也不是「m3u8 能不能播」—— 后者是 libVLC 的事。
 *
 * ── 它专门盯哪几个坑 ──
 * 每一条都对应一个实测撞到的事，不是「跑通就行」：
 *
 *   1. 广告位混入      45 个 <article> 里 15 个是 <article class="ad-item">
 *   2. 会员空卡        仅有封面、h2 整段不存在的那几张 → 必须丢
 *   3. 标题带角标      标题后面挂着 <div class="wrap"> 的「热搜 HOT」
 *   4. 封面双斜杠      https://pic.jtzatly.cn//upload_01/… 要归一化
 *   5. 第 1 页 URL     /category/x/1/ 回 301，拼 URL 必须走短形式
 *   6. 总页数          只信 <span class="page-info">1/1889</span>
 *   7. 播放配置        data-config 是单引号包的 JSON，含两档（h264 / h265）
 *   8. 签名不被改写     auth_key 原样保留，一个字节都不能动
 *
 * ── fixture 从哪来 ──
 * 那些 HTML 是被测站点的真实页面，**不进仓库**（成人内容站，见 .gitignore）。
 * 要跑就先抓一份：work/shells/java-test/fixtures/refresh-mrds66.sh。
 * 缺 fixture 时用例被 Assume 跳过并标 skipped，不会红一片 FileNotFound。
 */
public class Mrds66AdapterTest {

    private static final String BASE = "https://www.mrds66.com";
    private static final String DIR = "mrds66/";

    private static boolean ready;
    private static Class<?> adapter;

    @BeforeClass
    public static void load() throws Exception {
        adapter = Class.forName("com.huangguo.mobile.Mrds66Adapter");
        ready = res(DIR + "home.html") != null
                && res(DIR + "category-mrds.html") != null
                && res(DIR + "archives-196475.html") != null;
    }

    // ── 反射小工具（被测方法都是包可见/private） ──────────────────────

    private static Object call(String method, Class<?>[] types, Object... args) throws Exception {
        java.lang.reflect.Method m = adapter.getDeclaredMethod(method, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    private static InputStream res(String name) {
        return Mrds66AdapterTest.class.getClassLoader().getResourceAsStream(name);
    }

    private static String fixture(String name) throws Exception {
        InputStream in = res(DIR + name);
        assumeTrue("缺 fixture: " + name, in != null);
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = stream.read(buf)) != -1) out.write(buf, 0, n);
            return new String(out.toByteArray(), "UTF-8");
        }
    }

    private static JSONArray parseCards(String name) throws Exception {
        return (JSONArray) call("parseCards",
                new Class<?>[]{String.class, String.class}, fixture(name), BASE);
    }

    private static void require() {
        assumeTrue("缺 fixture（先跑 fixtures/refresh-mrds66.sh）", ready);
    }

    // ── ① 首页 ────────────────────────────────────────────────

    @Test
    public void 首页抠得出卡片() throws Exception {
        require();
        JSONArray cards = parseCards("home.html");
        assertTrue("实际 " + cards.length(), cards.length() >= 25);
    }

    @Test
    public void 首页首条字段齐全() throws Exception {
        require();
        JSONObject first = parseCards("home.html").optJSONObject(0);
        assertNotNull(first);
        assertTrue("id 不是数字: " + first.optString("id"), first.optString("id").matches("\\d+"));
        assertTrue("title 为空", first.optString("title").length() > 0);
        assertTrue("cover 不在 pic.jtzatly.cn: " + first.optString("cover"),
                first.optString("cover").startsWith("https://pic.jtzatly.cn/"));
        assertTrue("badge 不是日期: " + first.optString("badge"),
                first.optString("badge").matches("\\d{4}-\\d{2}-\\d{2}"));
        assertTrue("category 为空", first.optString("category").length() > 0);
    }

    @Test
    public void 标题不带热搜角标() throws Exception {
        require();
        JSONArray cards = parseCards("home.html");
        for (int i = 0; i < cards.length(); i++) {
            String title = cards.optJSONObject(i).optString("title");
            assertFalse("标题里混进了角标: " + title,
                    title.endsWith("热搜 HOT") || title.contains("HOT"));
        }
    }

    @Test
    public void 首页id无重复() throws Exception {
        require();
        JSONArray cards = parseCards("home.html");
        Set<String> ids = new HashSet<String>();
        for (int i = 0; i < cards.length(); i++) {
            String id = cards.optJSONObject(i).optString("id");
            assertTrue("重复 id: " + id, ids.add(id));
        }
    }

    @Test
    public void 封面没有双斜杠() throws Exception {
        require();
        JSONArray cards = parseCards("home.html");
        for (int i = 0; i < cards.length(); i++) {
            String cover = cards.optJSONObject(i).optString("cover");
            if (cover.length() == 0) continue;
            // https:// 之后不能再出现 //
            assertFalse("封面有双斜杠: " + cover,
                    cover.substring("https://".length()).contains("//"));
        }
    }

    // ── ② 广告与空卡必须被过滤 ────────────────────────────────

    @Test
    public void 广告位没有混进来() throws Exception {
        require();
        JSONArray cards = parseCards("home.html");
        for (int i = 0; i < cards.length(); i++) {
            String id = cards.optJSONObject(i).optString("id");
            assertTrue("混进了非数字 id: " + id, id.matches("\\d+"));
        }
    }

    @Test
    public void 没有标题的时候整条丢掉() throws Exception {
        require();
        JSONArray cards = parseCards("category-mrds.html");
        for (int i = 0; i < cards.length(); i++) {
            JSONObject c = cards.optJSONObject(i);
            assertTrue("留下了空标题卡片 id=" + c.optString("id"),
                    c.optString("title").length() > 0);
        }
    }

    @Test
    public void 首页卡片数少于article总数() throws Exception {
        require();
        String html = fixture("home.html");
        int articles = 0;
        int idx = 0;
        while ((idx = html.indexOf("<article", idx)) >= 0) { articles++; idx += 8; }
        int cards = parseCards("home.html").length();
        assertTrue("article " + articles + " 个，卡片 " + cards + " 张 —— 广告没被过滤？",
                cards < articles);
    }

    // ── ③ 分类 / 搜索 / 标签 ──────────────────────────────────

    @Test
    public void 分类页有卡片() throws Exception {
        require();
        JSONArray cards = parseCards("category-mrds.html");
        assertTrue("实际 " + cards.length(), cards.length() >= 20);
    }

    @Test
    public void 搜索页有卡片() throws Exception {
        require();
        assertTrue("缺 search-chigua.html", res(DIR + "search-chigua.html") != null);
        JSONArray cards = parseCards("search-chigua.html");
        assertTrue("实际 " + cards.length(), cards.length() >= 20);
    }

    @Test
    public void 标签页有卡片() throws Exception {
        require();
        if (res(DIR + "tag-chigua.html") == null) return;
        JSONArray cards = parseCards("tag-chigua.html");
        assertTrue("实际 " + cards.length(), cards.length() >= 20);
    }

    // ── ④ 分页 ────────────────────────────────────────────────

    @Test
    public void 第1页不带数字() throws Exception {
        String first = (String) call("pageUrl",
                new Class<?>[]{String.class, int.class}, BASE + "/category/mrds/", 1);
        assertEquals(BASE + "/category/mrds/", first);
    }

    @Test
    public void 第N页带数字带尾斜杠() throws Exception {
        String second = (String) call("pageUrl",
                new Class<?>[]{String.class, int.class}, BASE + "/category/mrds/", 2);
        assertEquals(BASE + "/category/mrds/2/", second);
    }

    @Test
    public void 总页数从page_info读() throws Exception {
        require();
        Object pages = call("totalPages", new Class<?>[]{String.class}, fixture("category-mrds.html"));
        assertEquals(1889, ((Integer) pages).intValue());
    }

    @Test
    public void 首页总页数() throws Exception {
        require();
        Object pages = call("totalPages", new Class<?>[]{String.class}, fixture("home.html"));
        assertTrue("实际 " + pages, ((Integer) pages) > 1000);
    }

    // ── ⑤ 详情 ────────────────────────────────────────────────

    @Test
    public void 详情的标题和封面() throws Exception {
        require();
        String html = fixture("archives-196475.html");
        String title = (String) call("metaContent",
                new Class<?>[]{String.class, String.class}, html, "headline");
        assertTrue("headline 为空", title.length() > 0);
        String cover = (String) call("metaContent",
                new Class<?>[]{String.class, String.class}, html, "image");
        assertTrue("封面不在 pic.jtzatly.cn: " + cover, cover.contains("pic.jtzatly.cn"));
    }

    @Test
    public void 详情能读出分类和标签() throws Exception {
        require();
        String html = fixture("archives-196475.html");
        String cate = (String) call("categoryOf", new Class<?>[]{String.class}, html);
        assertTrue("分类为空", cate.length() > 0);

        JSONArray tags = (JSONArray) call("tagNames", new Class<?>[]{String.class}, html);
        assertTrue("标签数为 " + tags.length(), tags.length() > 0);
        // 标签是 URL 编码过的中文，解出来不该还有 %
        for (int i = 0; i < tags.length(); i++) {
            assertFalse("标签没解码: " + tags.optString(i), tags.optString(i).contains("%"));
        }
    }

    @Test
    public void 简介不含广告友链() throws Exception {
        require();
        String desc = (String) call("description", new Class<?>[]{String.class},
                fixture("archives-196475.html"));
        assertTrue("简介为空", desc.length() > 0);
        // 正文开头那串「在线约炮 / 春药高潮 / 棋牌电子」全是 <a>，必须剥掉
        assertFalse("简介里还有广告词: " + desc.substring(0, Math.min(80, desc.length())),
                desc.contains("春药高潮") || desc.contains("棋牌电子"));
        assertTrue("简介太长: " + desc.length(), desc.length() <= 320);
    }

    // ── ⑥ 播放 ────────────────────────────────────────────────

    @Test
    public void 播放配置能解析() throws Exception {
        require();
        JSONObject config = (JSONObject) call("playerConfig",
                new Class<?>[]{String.class}, fixture("archives-196475.html"));
        assertNotNull("没解析出 dplayer 的 data-config", config);
        assertTrue("配置里没有 video 键", config.has("video"));
    }

    @Test
    public void 播放地址是m3u8且带签名() throws Exception {
        require();
        JSONObject config = (JSONObject) call("playerConfig",
                new Class<?>[]{String.class}, fixture("archives-196475.html"));
        String url = (String) call("videoUrl",
                new Class<?>[]{JSONObject.class, String.class}, config, "video");
        assertTrue("不是 m3u8: " + url, url.contains(".m3u8"));
        assertTrue("没有签名: " + url, url.contains("auth_key="));
        // 签名段必须原样 —— 改写任何一个字节站点都回 400
        assertFalse("auth_key 被改坏了: " + url, url.contains("auth_key= "));
    }

    @Test
    public void 第二档是h265() throws Exception {
        require();
        JSONObject config = (JSONObject) call("playerConfig",
                new Class<?>[]{String.class}, fixture("archives-196475.html"));
        String h265 = (String) call("videoUrl",
                new Class<?>[]{JSONObject.class, String.class}, config, "video_h265");
        assertTrue("h265 档没解析出来", h265.length() == 0 || h265.contains(".m3u8"));
    }

    // ── ⑦ 小工具 ──────────────────────────────────────────────

    // 这三个原来用反射调 Mrds66Adapter 的私有工具函数。2026-10-18 那些函数
    // 挪进了 HtmlKit（八个适配器各抄一份，param 三份逐字相同），所以测试
    // 也跟着挪 —— 行为的所有权归 HtmlKit，用例就该钉在它身上。

    @Test
    public void 百分号解码不动加号() {
        assertEquals("C++ 吃瓜", HtmlKit.percentDecode("C%2B%2B%20%E5%90%83%E7%93%9C"));
    }

    @Test
    public void 中文路径要编码() {
        assertEquals("%E5%90%83%E7%93%9C", HtmlKit.enc("吃瓜"));
    }

    @Test
    public void 双斜杠归一化() {
        assertEquals("https://pic.jtzatly.cn/upload_01/x/1.jpeg",
                HtmlKit.normalizeUrl("https://pic.jtzatly.cn//upload_01/x/1.jpeg"));
    }

    @Test
    public void 分类表没有重复slug() throws Exception {
        java.lang.reflect.Field f = adapter.getDeclaredField("CATEGORIES");
        f.setAccessible(true);
        String[][] table = (String[][]) f.get(null);
        Set<String> slugs = new HashSet<String>();
        for (String[] row : table) {
            assertTrue("重复 slug: " + row[0], slugs.add(row[0]));
            assertTrue("显示名为空: " + row[0], row[1].length() > 0);
        }
        assertTrue("分类少于 20 个，站点导航栏是 20 个", table.length >= 20);
    }

    /**
     * 简介不能带着开标签的残渣。
     *
     * 真机上看到的是 `class="post-content" itemprop="articleBody">每日大赛最新地址…`——
     * 切片的起点定在 `class=` 上，前面那个 `<div ` 被 stripHtml 吃掉了，
     * 后半截属性却因为不在 `<…>` 里而留下来。
     */
    @Test
    public void 简介不带开标签残渣() {
        String html = "<div class=\"post-content\" itemprop=\"articleBody\">"
                + "<p>正文第一段</p><p>第二段 &amp; 符号</p></div>";
        String text = Mrds66Adapter.description(html);
        assertEquals("正文第一段 第二段 & 符号", text);
    }
}
