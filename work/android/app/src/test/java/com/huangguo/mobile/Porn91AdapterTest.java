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
 * Porn91Adapter 的离线解析回归。
 *
 * 拿真实抓下来的 HTML（fixtures/porn91/）喂给适配器的**真实解析逻辑**，
 * 验证首页 / 分类 / 搜索 / 详情 / 播放直链 / 分页总数。
 *
 * ── 它不测什么 ──
 * **不发任何网络请求**。所以它证明的是「HTML 解析对不对」，不是「安卓端能不能连上」。
 *
 * ── 它专门盯哪几个坑 ──
 * 这些用例不是「跑通就行」，每一条都对应一个实测撞到的坑：
 *
 *   1. 卡片重复        一页 24 条卡但 viewkey 出现 38 次 → 必须去重到 24
 *   2. viewkey 两形态  纯数字 10 位 / 20 位十六进制混用 → \d+ 会漏一半
 *   3. 播放诱饵        注释里那条 358999.mp4 全站固定 → 抠错就全站播同一部
 *   4. strencode2      `+` 不能被解成空格（签名段带 base64 风格的 + /）
 *   5. 搜索页分页      搜索页没有 videopaging div，总数在 h4 里
 *   6. 首页是年龄门    `GET /` 是确认页；内容入口是 /v.php
 *
 * ── fixture 从哪来 ──
 * 那些 HTML 是被测站点的真实页面，**不进仓库**（成人内容站，见 .gitignore）。
 * 缺 fixture 时用例被 Assume 跳过并标 skipped，不会红一片 FileNotFound。
 */
public class Porn91AdapterTest {

    private static final String BASE = "https://901.26702.app";
    private static final String DIR = "porn91/";

    private static boolean ready;
    private static Class<?> adapter;

    @BeforeClass
    public static void load() throws Exception {
        adapter = Class.forName("com.huangguo.mobile.Porn91Adapter");
        ready = res(DIR + "home.html") != null
                && res(DIR + "list-ori.html") != null
                && res(DIR + "search.html") != null
                && res(DIR + "watch-51dc03e8e27a694f2fc7.html") != null;
    }

    // ── 反射小工具（被测方法都是包可见/private） ──────────────────────

    private static Object call(String method, Class<?>[] types, Object... args) throws Exception {
        java.lang.reflect.Method m = adapter.getDeclaredMethod(method, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    private static InputStream res(String name) {
        return Porn91AdapterTest.class.getClassLoader().getResourceAsStream(name);
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

    private static JSONArray parseCards(String html) throws Exception {
        return (JSONArray) call("parseCards", new Class<?>[]{String.class, String.class}, html, BASE);
    }

    // ── ① 卡片解析 + 去重 ─────────────────────────────────────────────

    /**
     * 坑 1 + 坑 2 一起测：一页必须是 24 条唯一卡片，且两种 viewkey 形态都在。
     *
     * fixture 里 `viewkey=` 出现 38 次（同一条在 col-lg-3 和 col-lg-8 两种栅格里
     * 各画一遍），去重后必须正好 24 —— 站点每页就是 24。
     */
    @Test
    public void listDedupesTo24Cards() throws Exception {
        String html = fixture("list-ori.html");
        JSONArray cards = parseCards(html);
        assertEquals("分类页去重后应正好 24 条（站点每页 24）", 24, cards.length());

        Set<String> ids = new HashSet<String>();
        boolean sawNumeric = false;
        boolean sawHex = false;
        for (int i = 0; i < cards.length(); i++) {
            JSONObject c = cards.optJSONObject(i);
            assertNotNull(c);
            String id = c.optString("id", "");
            assertFalse("id 不能为空", id.length() == 0);
            assertTrue("id 不能重复：" + id, ids.add(id));
            // 坑 2：两种形态都要能出现。全数字说明正则写成了 \d+ 之外的另一种错，
            // 全十六进制说明纯数字那批被漏了。
            if (id.matches("\\d+")) sawNumeric = true;
            else if (id.matches("[0-9a-f]{20}")) sawHex = true;
        }
        assertTrue("这份 fixture 里应当有纯数字 viewkey", sawNumeric);
        assertTrue("这份 fixture 里应当有 20 位十六进制 viewkey（写成 \\d+ 会漏掉它）", sawHex);
    }

    /** 每条卡片都要有标题和封面 —— 缺一个，首页就是一片灰块。 */
    @Test
    public void everyCardHasTitleAndCover() throws Exception {
        JSONArray cards = parseCards(fixture("home.html"));
        assertTrue("首页卡片数应当 > 0", cards.length() > 0);
        for (int i = 0; i < cards.length(); i++) {
            JSONObject c = cards.optJSONObject(i);
            assertNotNull(c);
            String title = c.optString("title", "");
            String cover = c.optString("cover", "");
            assertTrue("第 " + i + " 条没有标题", title.trim().length() > 0);
            assertTrue("第 " + i + " 条没有封面", cover.startsWith("https://"));
            // 封面不能是站点图标或广告 gif —— 那是 fallback 分支没过滤干净
            assertFalse("封面抓成了站点图标：" + cover, cover.contains("/images/"));
            assertFalse("封面抓成了广告位：" + cover, cover.contains("fans.91selfie.com"));
        }
    }

    /** 时长要落在 badge 上（首页角标）。不是每条都有，但绝大多数要有。 */
    @Test
    public void cardsCarryDurationBadge() throws Exception {
        JSONArray cards = parseCards(fixture("list-ori.html"));
        int withBadge = 0;
        for (int i = 0; i < cards.length(); i++) {
            JSONObject c = cards.optJSONObject(i);
            if (c == null) continue;
            String badge = c.optString("badge", "");
            if (badge.matches("\\d{2}:\\d{2}:\\d{2}")) withBadge++;
        }
        assertTrue("至少 20 条应当有时长角标，实际 " + withBadge, withBadge >= 20);
    }

    // ── ② 播放地址：诱饵 + strencode2 ────────────────────────────────

    /**
     * 坑 3 的核心断言：**解出来的地址不能是那条全站固定的诱饵**。
     *
     * 诱饵是 `https://ccm.91p52.com/358999.mp4?...`，每个详情页都一样。
     * 按 `<source src=` 直接抠必然抠到它（它在 HTML 里排在真地址前面，
     * 只是被 <!-- --> 包着），表现是全站所有片子播同一部。
     */
    @Test
    public void playSourceIsNotTheDecoy() throws Exception {
        String html = fixture("watch-51dc03e8e27a694f2fc7.html");
        String url = (String) call("playSource", new Class<?>[]{String.class}, html);

        assertNotNull(url);
        assertTrue("必须解出地址", url.length() > 0);
        assertTrue("必须是 https 直链", url.startsWith("https://"));
        assertFalse("抠到了全站固定的诱饵 358999.mp4（坑 3）", url.contains("358999"));
        assertFalse("抠到了诱饵的域名 ccm.91p52.com", url.contains("ccm.91p52.com"));
        assertTrue("应当是 mp4 直链", url.contains(".mp4"));
        // 签名三件套：st / e / f。缺任何一个站点都会 403。
        assertTrue("地址里应当带 st= 签名", url.contains("st="));
        assertTrue("地址里应当带 e= 过期时间戳", url.contains("e="));
        assertTrue("地址里应当带 f= 指纹段", url.contains("f="));
    }

    /**
     * 坑 4：strencode2 是百分号解码，**`+` 必须原样保留**。
     *
     * 用 URLDecoder.decode() 会把 `+` 解成空格，而签名段是 base64 风格
     * （实测带 + 和 /），解错了站点回 403 —— 而且报错信息是「没权限」，
     * 看起来像账号问题，其实是这里。
     */
    @Test
    public void percentDecodeKeepsPlusIntact() {
        // 函数已收进 HtmlKit（三份逐字相同的实现），用例跟着挪
        assertEquals("<source src='a+b/c'>",
                HtmlKit.percentDecode("%3c%73%6f%75%72%63%65%20%73%72%63%3d%27a%2bb%2Fc%27%3e"));
        assertEquals("100%zz", HtmlKit.percentDecode("100%zz"));
    }

    /** strencode 要能从真实页面里抠出整段百分号编码。 */
    @Test
    public void strencodeExtractsPayload() throws Exception {
        String html = fixture("watch-51dc03e8e27a694f2fc7.html");
        String decoded = (String) call("strencode", new Class<?>[]{String.class}, html);
        assertTrue("解出来应当是一个 <source> 标签", decoded.contains("<source"));
        assertTrue("里面应当有 mp4 地址", decoded.contains(".mp4"));
    }

    // ── ③ 详情元数据 ─────────────────────────────────────────────────

    /**
     * 片名取第一个 h4。页面里同 class 的 h4 有三个
     * （片名 / "Video Information" / "Comment On This Video"），
     * 取错了标题会变成 "Video Information"。
     */
    @Test
    public void firstH4IsTheTitle() throws Exception {
        String html = fixture("watch-51dc03e8e27a694f2fc7.html");
        String title = (String) call("firstH4", new Class<?>[]{String.class}, html);
        assertTrue("标题不能为空", title.length() > 0);
        assertFalse("取到了 Video Information 那个 h4", title.contains("Video Information"));
        assertFalse("取到了 Comment 那个 h4", title.contains("Comment On This Video"));
    }

    /** Runtime / Views / Favorites 三个值都要能从嵌套 span 里取出来。 */
    @Test
    public void infoSpansParse() throws Exception {
        String html = fixture("watch-51dc03e8e27a694f2fc7.html");
        String runtime = (String) call("infoSpan", new Class<?>[]{String.class, String.class}, html, "Runtime");
        String views = (String) call("infoSpan", new Class<?>[]{String.class, String.class}, html, "Views");

        assertTrue("Runtime 应当是 HH:MM:SS，实际 " + runtime, runtime.matches("\\d{2}:\\d{2}:\\d{2}"));
        assertTrue("Views 应当是数字，实际 " + views, views.replaceAll("[^0-9]", "").length() > 0);
    }

    /** 简介和上传者 —— 缺了不影响播放，但详情页会空一块。 */
    @Test
    public void descriptionAndUploaderParse() throws Exception {
        String html = fixture("watch-51dc03e8e27a694f2fc7.html");
        String desc = (String) call("description", new Class<?>[]{String.class}, html);
        String uploader = (String) call("uploader", new Class<?>[]{String.class}, html);
        String added = (String) call("addedDate", new Class<?>[]{String.class}, html);

        assertTrue("简介不该为空", desc.length() > 0);
        assertFalse("简介里混进了标签", desc.contains("<"));
        assertTrue("上传者不该为空", uploader.length() > 0);
        assertTrue("上传日期应当是 yyyy-MM-dd，实际 " + added, added.matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    // ── ④ 分页：两个来源 ─────────────────────────────────────────────

    /**
     * 坑 5：分类页有 `videopaging` div，搜索页**没有** —— 总数在 h4 里。
     * 两处都要能读出总数，否则搜索永远只出一页。
     */
    @Test
    public void totalCountFromBothPageShapes() throws Exception {
        int listTotal = (Integer) call("totalCount", new Class<?>[]{String.class},
                fixture("list-ori.html"));
        assertTrue("分类页应当读出 15573 这个总数，实际 " + listTotal, listTotal > 15000);

        int searchTotal = (Integer) call("totalCount", new Class<?>[]{String.class},
                fixture("search.html"));
        assertTrue("搜索页应当读出 121 这个总数，实际 " + searchTotal, searchTotal > 0);
    }

    /** pages 用 ceil(total / got) 算。got=24, total=15573 → 649 页。 */
    @Test
    public void paginationComputesPages() throws Exception {
        String html = fixture("list-ori.html");
        JSONObject p = (JSONObject) call("pagination",
                new Class<?>[]{String.class, int.class, int.class}, html, 1, 24);
        assertEquals(1, p.optInt("page"));
        assertEquals(24, p.optInt("size"));
        int pages = p.optInt("pages");
        assertTrue("页数应当在 600~700 之间，实际 " + pages, pages > 600 && pages < 700);
    }

    /**
     * 拿不到总数时**不编一个总页数**出来。
     *
     * 两种情形分开：
     *   · 这一页有内容 → pages = page + 1（「可能还有下一页」，让前端能继续翻）
     *   · 这一页空的   → pages = page（到底了）
     *
     * 关键是**不能**回一个假的 total/pages —— 那会让用户翻到空页。
     */
    @Test
    public void paginationWithoutTotalDoesNotGuess() throws Exception {
        JSONObject withItems = (JSONObject) call("pagination",
                new Class<?>[]{String.class, int.class, int.class}, "<html>no counter</html>", 3, 24);
        assertEquals(3, withItems.optInt("page"));
        assertEquals("有内容但没总数 → 允许再翻一页", 4, withItems.optInt("pages"));
        assertFalse("没有总数时不该编一个 total 出来", withItems.has("total"));

        JSONObject empty = (JSONObject) call("pagination",
                new Class<?>[]{String.class, int.class, int.class}, "<html>no counter</html>", 3, 0);
        assertEquals("空页 = 到底了", 3, empty.optInt("pages"));
    }

    // ── ⑤ 首页是年龄门（不是内容页） ─────────────────────────────────

    /**
     * 坑 6：`GET /` 返回 1616 字节的确认页，零张卡。
     * 这条 fixture 是 `/v.php` 的真实响应 —— 断言它**有内容**，
     * 反过来证明「home() 打 /v.php 是对的」。
     *
     * 如果哪天有人把 home() 改回打 `/`，这条会红，而真机上表现是
     * 「首页一片空白但分类页正常」—— 那种 bug 最难查，所以留一条在这里。
     */
    @Test
    public void homeFixtureHasCards() throws Exception {
        JSONArray cards = parseCards(fixture("home.html"));
        assertEquals("home.html（/v.php）应当正好 24 条", 24, cards.length());
    }

    // ── ⑥ 路径参数 ───────────────────────────────────────────────────

    @Test
    public void pathParamsParse() throws Exception {
        assertEquals("51dc03e8e27a694f2fc7",
                call("videoId", new Class<?>[]{String.class}, "/api/videos/51dc03e8e27a694f2fc7"));
        assertEquals("1555330457",
                call("videoId", new Class<?>[]{String.class}, "/api/videos/1555330457/play?ep=1"));
        assertEquals(3, HtmlKit.page("/api/videos?cate_id=ori&page=3"));
        assertEquals(1, HtmlKit.page("/api/videos?cate_id=ori"));
        assertEquals("ori", HtmlKit.param(
                "/api/videos?cate_id=ori&page=2", "cate_id"));
    }

    // ── ⑦ User-Agent：站点对 okhttp 默认 UA 回 200 + 0 字节 ─────────

    /**
     * HEADERS 里必须有**非 okhttp** 的 User-Agent。
     *
     * 2026-10-18 真机踩到的坑：OkHttp 默认 UA 是 `okhttp/4.10.0`，站点对它回
     * HTTP 200 + **0 字节**（v.php 和 view_video.php 都是）。所有状态码都是 200，
     * 于是解析出来 0 条卡片，报错信息是「站点没内容」—— 而真实原因是 UA。
     *
     * 这条用例锁住「必须显式给 UA」，任何人把它删掉都会红。
     */
    @Test
    public void headersCarryBrowserUserAgent() throws Exception {
        java.lang.reflect.Field f = adapter.getDeclaredField("HEADERS");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> headers = (java.util.Map<String, String>) f.get(null);

        assertNotNull(headers);
        String ua = headers.get("User-Agent");
        assertNotNull("HEADERS 里必须有 User-Agent（缺了站点回空响应）", ua);
        assertFalse("不能是 okhttp 默认 UA，站点对它回 200+0 字节", ua.toLowerCase().contains("okhttp"));
        assertTrue("应当是浏览器 UA", ua.contains("Mozilla/"));
    }

    /** 中文搜索词要百分号编码，否则站点收到的是乱码。 */
    @Test
    public void encHandlesChinese() throws Exception {
        String encoded = HtmlKit.enc("东京");
        assertTrue("中文应当被编码成 %XX，实际 " + encoded, encoded.startsWith("%"));
        assertFalse(encoded.contains("东"));
        // ASCII 不动
        assertEquals("abc-123_x.y", HtmlKit.enc("abc-123_x.y"));
    }

    // ── ⑧ 游客标记 ga：没有它详情页每次换一部片 ──────────────────────

    /**
     * 有标记就带上 `Cookie: ga=…`。
     *
     * 2026-10-18 的根因：同一个 viewkey 连请求五次，五次五个不同的 mp4，
     * 而标题不变 —— 站点在没有游客标记时走「随机给一部」的渲染路径。
     * 带上标记之后 poster/mp4 正好等于列表卡片封面。
     */
    @Test
    public void requestHeadersCarryGuestCookie() throws Exception {
        java.util.Map<String, String> headers =
                (java.util.Map<String, String>) call("requestHeaders", new Class<?>[]{String.class}, "abc123");
        assertEquals("ga=abc123", headers.get("Cookie"));
        // 原有的浏览器头不能被顶掉
        assertNotNull("Cookie 不能顶掉 User-Agent", headers.get("User-Agent"));
        assertTrue("User-Agent 应当是浏览器的", headers.get("User-Agent").contains("Mozilla/"));
    }

    /** 没有标记时**不能**凭空造一个 Cookie 头 —— 第一次请求要靠它换标记。 */
    @Test
    public void requestHeadersWithoutGuestHaveNoCookie() throws Exception {
        java.util.Map<String, String> headers =
                (java.util.Map<String, String>) call("requestHeaders", new Class<?>[]{String.class}, (Object) null);
        assertFalse("没有标记时不该带 Cookie", headers.containsKey("Cookie"));
        assertNotNull(headers.get("User-Agent"));
    }

    /** 只认 `ga`，别人的 cookie（CLIPSHARE / PHPSESSID）不参与。 */
    @Test
    public void pickGuestTakesOnlyGa() throws Exception {
        java.util.Map<String, String> jar = new java.util.LinkedHashMap<String, String>();
        jar.put("CLIPSHARE", "quu7veee2pt2t3125d4r67iu85");
        assertEquals("没有 ga 就该返回空串", "",
                call("pickGuest", new Class<?>[]{java.util.Map.class}, (Object) jar));

        jar.put("ga", " xyz ");
        assertEquals("xyz", call("pickGuest", new Class<?>[]{java.util.Map.class}, (Object) jar));
    }

    /** 站点没下发标记时用兜底值，不是空手去请求（空手＝随机片）。 */
    @Test
    public void chooseGuestFallsBackWhenMissing() throws Exception {
        java.util.Map<String, String> empty = new java.util.LinkedHashMap<String, String>();
        assertEquals("1", call("chooseGuest", new Class<?>[]{java.util.Map.class}, (Object) empty));

        java.util.Map<String, String> jar = new java.util.LinkedHashMap<String, String>();
        jar.put("ga", "BwHMj%5E90a3");
        assertEquals("BwHMj%5E90a3",
                call("chooseGuest", new Class<?>[]{java.util.Map.class}, (Object) jar));
    }

    /** 校正接口：没给 id 直接报错，一个请求都不发。 */
    @Test
    public void reconcileRejectsEmptyIds() throws Exception {
        try {
            call("reconcile", new Class<?>[]{String.class, String.class}, "https://901.26702.app", "");
            org.junit.Assert.fail("空 idList 应该抛异常");
        } catch (java.lang.reflect.InvocationTargetException e) {
            String message = e.getCause() == null ? "" : String.valueOf(e.getCause().getMessage());
            assertTrue("异常信息要说清是没给 id，实际：" + message, message.contains("没有提供 id"));
        }
    }
}
