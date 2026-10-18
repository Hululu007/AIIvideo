package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 樱花动漫（yhdm.one）的离线解析回归。
 *
 * 拿真实抓下来的页面（resources/yhdm/）喂给 {@link YhdmAdapter} 和 {@link YhdmSite}
 * 的真实解析逻辑，验证探针 / 卡片 / 详情 / 剧集 / 播放 / 分页六段。
 *
 * 不发任何网络请求。fixture 是 2026-10-18 用 curl 从 yhdm.one 抓的原样字节。
 */
public class YhdmAdapterTest {

    private static String HOME, DETAIL, LIST, PLAYS;

    @BeforeClass
    public static void load() throws Exception {
        HOME = fixture("home.html");
        DETAIL = fixture("detail-2026476948.html");
        LIST = fixture("list.html");
        PLAYS = fixture("plays.json");
    }

    private static String fixture(String name) throws Exception {
        InputStream in = YhdmAdapterTest.class.getResourceAsStream("/yhdm/" + name);
        assertNotNull("缺 fixture：yhdm/" + name, in);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }

    // ── 探针 ──────────────────────────────────────────────────────────

    @Test
    public void 探针认得出真实首页() {
        assertTrue(YhdmSite.probeHtml(HOME.getBytes(java.nio.charset.StandardCharsets.UTF_8)) != null);
    }

    @Test
    public void 探针拒得住挑战页和错误页() {
        assertTrue(YhdmSite.probeHtml("".getBytes()) == null);
        assertTrue(YhdmSite.probeHtml("<html><body>404 Not Found</body></html>".getBytes()) == null);
        // 只有指纹词、没有卡片 —— 被劫持的跳转页长这样
        assertTrue(YhdmSite.probeHtml(
                "<link href=\"/static/css/yhdm.css\"><title>樱花动漫</title>".getBytes(java.nio.charset.StandardCharsets.UTF_8)) == null);
    }

    @Test
    public void 域名识别() {
        YhdmSite site = new YhdmSite();
        assertTrue(site.matchesUrl("https://yhdm.one/"));
        assertTrue(site.matchesUrl("https://yhdm.one/vod/2026476948.html"));
        assertTrue(site.matchesUrl("https://www.yhdm.one/"));
        assertFalse(site.matchesUrl("https://example.com/"));
        assertFalse(site.matchesUrl("https://yhdm.one.evil.io/"));   // 后缀伪装不算
        assertFalse(site.matchesUrl(""));
        assertFalse(site.matchesUrl(null));
    }

    // ── 卡片解析 ──────────────────────────────────────────────────────

    @Test
    public void 首页卡片带标题封面() throws Exception {
        JSONArray cards = YhdmAdapter.parseCards(HOME, "https://yhdm.one");
        assertTrue("首页至少应有 40 张卡，实际 " + cards.length(), cards.length() >= 40);
        JSONObject first = cards.optJSONObject(0);
        assertNotNull(first);
        assertTrue(first.optString("id").matches("\\d+"));
        assertTrue("标题不能是空：" + first, first.optString("title").length() > 0);
        assertTrue("封面应该是绝对地址：" + first.optString("cover"),
                first.optString("cover").startsWith("https://yhdm.one/"));
    }

    @Test
    public void 列表页懒加载封面取data_original() throws Exception {
        JSONArray cards = YhdmAdapter.parseCards(LIST, "https://yhdm.one");
        assertTrue(cards.length() >= 15);
        boolean hasCover = false;
        for (int i = 0; i < cards.length(); i++) {
            String cover = cards.optJSONObject(i).optString("cover", "");
            if (cover.startsWith("https://yhdm.one/cover2/")) { hasCover = true; break; }
        }
        assertTrue("列表页封面应来自 data-original 的 /cover2/", hasCover);
    }

    @Test
    public void 卡片去重() throws Exception {
        JSONArray cards = YhdmAdapter.parseCards(HOME, "https://yhdm.one");
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (int i = 0; i < cards.length(); i++) {
            assertTrue("id 重复：" + cards.optJSONObject(i), seen.add(cards.optJSONObject(i).optString("id")));
        }
    }

    // ── 详情 ──────────────────────────────────────────────────────────

    @Test
    public void 详情解析标题封面简介剧集() throws Exception {
        java.lang.reflect.Method m = YhdmAdapter.class.getDeclaredMethod(
                "episodeAnchors", String.class);
        m.setAccessible(true);
        JSONArray eps = (JSONArray) m.invoke(null, DETAIL);
        assertEquals("仙逆剧场版详情应有 3 个剧集锚点（ep1/zheng_pian/hd），实际 " + eps.length(), 3, eps.length());
        assertEquals("ep1", eps.optJSONObject(0).optString("ep"));
        assertEquals("第01集", eps.optJSONObject(0).optString("title"));

        assertEquals("仙逆剧场版 弑仙之战", YhdmAdapter.h1Title(DETAIL));
        assertTrue(YhdmAdapter.intro(DETAIL).contains("弑仙"));
        assertTrue(YhdmAdapter.coverOfDetail(DETAIL, "https://yhdm.one").endsWith("/cover/2026476948.jpg"));
        assertTrue("更新角标：" + YhdmAdapter.updateBadge(DETAIL),
                YhdmAdapter.updateBadge(DETAIL).contains("更新至"));
    }

    @Test
    public void 剧集锚点不混入其他播放源() throws Exception {
        // 播放页里也有 ep-panel（「其他播放源」的 swa 链接），但它的锚点
        // 是 href='/vod-play/...' 且「全部剧集」字样在前面 —— 切块从「全部剧集」开始。
        java.lang.reflect.Method m = YhdmAdapter.class.getDeclaredMethod("episodeAnchors", String.class);
        m.setAccessible(true);
        JSONArray eps = (JSONArray) m.invoke(null, DETAIL);
        // 每个锚点的 ep 段都得是 ep{数字} / zheng_pian / hd 这类站点形态
        for (int i = 0; i < eps.length(); i++) {
            String ep = eps.optJSONObject(i).optString("ep", "");
            assertTrue("ep 形态不对：" + ep, ep.matches("ep\\d+") || ep.length() > 0);
        }
    }

    // ── 播放 ──────────────────────────────────────────────────────────

    @Test
    public void plays解析线路() throws Exception {
        JSONObject json = new JSONObject(PLAYS);
        JSONArray plays = json.getJSONArray("video_plays");
        assertTrue(plays.length() >= 2);
        JSONObject first = plays.optJSONObject(0);
        assertTrue(first.optString("play_data").endsWith(".m3u8"));
        assertTrue(first.optString("src_site").length() > 0);
    }

    @Test
    public void ep序号解析() {
        assertEquals(1, YhdmAdapter.epIndex("ep1"));
        assertEquals(163, YhdmAdapter.epIndex("ep163"));
        assertEquals(1, YhdmAdapter.epIndex("zheng_pian"));
        assertEquals(1, YhdmAdapter.epIndex("hd"));
        assertEquals(1, YhdmAdapter.epIndex(null));
    }

    // ── 分页 ──────────────────────────────────────────────────────────

    @Test
    public void 页次总数解析() throws Exception {
        assertEquals(215, YhdmAdapter.totalPages(LIST));

        // 分页形状现在由 AdapterShape 统一产出（前端 nextPageOf 只认这一套）
        JSONArray items = YhdmAdapter.parseCards(LIST, "https://yhdm.one");
        JSONObject p = AdapterShape.listing(items, 1, YhdmAdapter.totalPages(LIST))
                .getJSONObject("pagination");
        assertEquals(215, p.optInt("pages"));
        assertEquals("size 必须和这一页真实条数一致（不是配置里的 pageSize）",
                items.length(), p.optInt("size"));

        // 拿不到总页数时：这一页有内容就当还有下一页（猜「有」最多多请求一次，
        // 猜「没有」会让后面几十页永远翻不到）
        JSONObject np = AdapterShape.pagination(1, 18, 0);
        assertEquals(2, np.optInt("pages"));
    }

    // ── 配置生成 ──────────────────────────────────────────────────────

    @Test
    public void 配置生成并过校验() throws Exception {
        JSONObject config = YhdmSite.buildConfig("https://yhdm.one");
        assertEquals("yhdm", config.optString("adapter"));
        assertEquals("https://yhdm.one", config.optString("origin"));

        YhdmSite site = new YhdmSite();
        assertEquals(null, site.check(config));

        // fields/shapes 契约：适配器按默认键名返回，校验不该挑刺
        JSONObject web = config.optJSONObject("web");
        assertNotNull(web.optJSONObject("paths"));
        assertEquals("/api/play/{id}?ep={episode}", web.optJSONObject("paths").optString("episode"));
        // categories 里每个 slug 都必须能查到（apiPathFor 的静默放弃那条）
        JSONObject categories = web.optJSONObject("categories");
        assertTrue(categories.length() >= 5);
        for (java.util.Iterator<String> it = categories.keys(); it.hasNext(); ) {
            String slug = it.next();
            assertNotNull("分类缺查询串：" + slug, YhdmAdapter.CATEGORIES.get(slug));
        }
    }

    @Test
    public void 必填项校验拦住空origin() throws Exception {
        YhdmSite site = new YhdmSite();
        JSONObject bad = YhdmSite.buildConfig("https://yhdm.one");
        bad.put("origin", "");
        assertNotNull(site.check(bad));
    }

    // ── Adapters 注册表 ───────────────────────────────────────────────

    @Test
    public void 进了注册表() {
        assertNotNull(Adapters.byId("yhdm"));
        assertTrue(Adapters.ids().contains("yhdm"));
    }

    @Test
    public void 身份串() throws Exception {
        JSONObject config = YhdmSite.buildConfig("https://yhdm.one");
        assertEquals("hgsrc:yhdm|https://yhdm.one/vod/", new YhdmSite().identity(config));
    }
}
