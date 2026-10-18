package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * OpenList 适配器的纯函数用例（不联网）。
 *
 * 联网那部分在 {@link OpenListLiveTest}，默认跳过。
 */
public class OpenListAdapterTest {

    /** id 是 base64url 的路径 —— 斜杠、中文、空格都得能原样回来。 */
    @Test
    public void id能原样往返() {
        String[] paths = {
            "/asmr2",
            "/asmr2/野上菜月/[RJ111491]和み隠れ家 あまえた楼 夏希",
            "/asmr/橙澄子汉化组/[橙澄子汉化组][RJ147133]糖作 吸血鬼女友的危险求爱~",
            "/asmr2/MOMOKA。/[RJ117843]時代(とき)を超えた乙女～珠姫～/mp3版/mp3版FILE1 時代を超えた乙女.mp3"
        };
        for (String path : paths) {
            String id = OpenListAdapter.encodeId(path);
            assertFalse("id 里不该有斜杠：" + id, id.contains("/"));
            assertEquals(path, OpenListAdapter.decodeId(id));
        }
    }

    /** 不是 base64 的 id 当裸路径处理 —— 手敲调试用。 */
    @Test
    public void 非法id原文返回() {
        assertEquals("/asmr2", OpenListAdapter.decodeId("/asmr2"));
        assertEquals("", OpenListAdapter.decodeId(""));
        assertEquals("", OpenListAdapter.decodeId(null));
    }

    @Test
    public void 只认音视频扩展名() {
        assertTrue(OpenListAdapter.isMedia("1 時代を超えた乙女.mp3"));
        assertTrue(OpenListAdapter.isMedia("a.MP3"));
        assertTrue(OpenListAdapter.isMedia("pv.mp4"));
        assertTrue(OpenListAdapter.isMedia("src.flac"));
        assertFalse(OpenListAdapter.isMedia("イラスト大.jpg"));
        assertFalse(OpenListAdapter.isMedia("readme.txt"));
        assertFalse(OpenListAdapter.isMedia("noext"));
        assertFalse(OpenListAdapter.isMedia(null));
    }

    @Test
    public void 路径拼接与取名() {
        assertEquals("/asmr2", OpenListAdapter.join("/", "asmr2"));
        assertEquals("/asmr2/x", OpenListAdapter.join("/asmr2", "x"));
        assertEquals("/asmr2/x", OpenListAdapter.join("/asmr2/", "x"));
        assertEquals("/x", OpenListAdapter.join("", "x"));   // 空父级 = 根，路径一律带前导斜杠

        assertEquals("x", OpenListAdapter.leaf("/a/b/x"));
        assertEquals("x", OpenListAdapter.leaf("/a/b/x/"));
        assertEquals("", OpenListAdapter.leaf("/"));

        assertEquals("b", OpenListAdapter.parentName("/a/b/x"));
        assertEquals("", OpenListAdapter.parentName("/x"));
    }

    @Test
    public void 文件名去扩展名() {
        assertEquals("1 第一轨", OpenListAdapter.stripExt("1 第一轨.mp3"));
        assertEquals("noext", OpenListAdapter.stripExt("noext"));
        assertEquals("", OpenListAdapter.stripExt(""));
    }

    /** 查询串：cate_id 是 encodeURIComponent 过的路径，必须解回来。 */
    @Test
    public void 查询串解析() {
        assertEquals("/asmr2", HtmlKit.param("cate_id=%2Fasmr2&page=2", "cate_id"));
        assertEquals("/asmr2/MOMOKA。", HtmlKit.param("x=1&cate_id=%2Fasmr2%2FMOMOKA%E3%80%82", "cate_id"));
        assertEquals("", HtmlKit.param("x=1", "cate_id"));
        assertEquals("", HtmlKit.param(null, "cate_id"));
        // 整条动作路径也要能取（真实调用点传的是 path，不是 query）
        assertEquals("/asmr2", HtmlKit.param("/api/videos?cate_id=%2Fasmr2&page=2", "cate_id"));
        assertEquals("MOMOKA", HtmlKit.param("/api/search?q=MOMOKA&page=1", "q"));
        assertEquals("2", HtmlKit.param("/api/videos/L2Zvbw/play?ep=2", "ep"));
        assertEquals(2, HtmlKit.page("cate_id=x&page=2"));
        assertEquals(1, HtmlKit.page("cate_id=x"));
        assertEquals(1, HtmlKit.page("page=abc"));
    }

    /** 站点根：只认 https，路径一律丢掉（用户可能粘的是某一层目录）。 */
    @Test
    public void 域名与站点根() {
        assertEquals("https://www.asmrgay.com",
                OpenListSite.originOf("https://www.asmrgay.com/asmr2/MOMOKA。"));
        assertEquals("https://asmrgay.com", OpenListSite.originOf("https://asmrgay.com"));
        assertEquals("", OpenListSite.originOf("http://www.asmrgay.com/"));
        assertEquals("", OpenListSite.originOf("not a url"));
    }

    @Test
    public void 域名匹配含子域且不误伤() {
        OpenListSite site = new OpenListSite();
        assertTrue(site.matchesUrl("https://www.asmrgay.com/"));
        assertTrue(site.matchesUrl("https://asmrgay.com"));
        assertTrue(site.matchesUrl("https://asmrgay.org/asmr2"));
        assertFalse(site.matchesUrl("https://www.mrds66.com/"));
        assertFalse(site.matchesUrl("https://notasmrgay.com/"));
        assertFalse(site.matchesUrl(null));
    }

    /** 注册表里要有它，否则 /api/ 分流和校验都找不到。 */
    @Test
    public void 注册表里有openlist() {
        assertTrue(Adapters.ids().contains("openlist"));
        assertTrue(Adapters.byId("openlist") instanceof OpenListSite);
        assertEquals("openlist", Adapters.byId("openlist").id());
    }

    /** origin 型校验：缺 origin / 不是 https 都得拒。 */
    @Test
    public void 配置校验() throws Exception {
        OpenListSite site = new OpenListSite();
        org.json.JSONObject config = new org.json.JSONObject();
        config.put("adapter", "openlist");
        assertEquals("缺少 origin（这个源要填站点地址）", site.check(config));

        config.put("origin", "http://a.com");
        assertEquals("origin 必须是 https 开头", site.check(config));

        config.put("origin", "https://www.asmrgay.com");
        assertEquals("缺少 web.paths", site.check(config));

        config.put("web", new org.json.JSONObject().put("paths",
                new org.json.JSONObject().put("home", "/api/home")));
        assertNull(site.check(config));
    }

    /**
     * 伪装扩展名：这个站把视频改名成 .jpg / .woff2，靠体积区分真假图片。
     * 实测视频 1.8~22MB，真封面一般几百 KB 以内。
     */
    @Test
    public void 伪装成图片的大文件按视频放行() {
        assertTrue(OpenListAdapter.isDisguised("000.jpg"));
        assertTrue(OpenListAdapter.isDisguised("000.woff2"));
        assertTrue(OpenListAdapter.isDisguised("a.WOFF2"));
        assertFalse(OpenListAdapter.isDisguised("a.mp3"));

        assertTrue(OpenListAdapter.isDisguisedMedia("000.jpg", 1861200L));
        assertTrue(OpenListAdapter.isDisguisedMedia("000.woff2", 22459232L));
        assertFalse("小图不该进选集", OpenListAdapter.isDisguisedMedia("cover.jpg", 120 * 1024L));
        assertFalse(OpenListAdapter.isDisguisedMedia("a.mp3", 9 * 1024 * 1024L));

        // 扩展名本身是音视频的，仍然按 isMedia 走
        assertTrue(OpenListAdapter.isMedia("a.m4a"));
        assertTrue(OpenListAdapter.isMedia("a.ts"));
        assertFalse(OpenListAdapter.isMedia("000.jpg"));
    }
}
