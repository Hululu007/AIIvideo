package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 91porn 的离线用例（不联网）。
 *
 * 重点是 percentDecode：它是中文搜索能不能出结果的唯一开关。
 */
public class Porn91SiteTest {

    // 注：percentDecode / enc 的用例打在 HtmlKit 上 —— 2026-10-18 这两个函数
    // 从各适配器收进了 HtmlKit（三份逐字相同的实现）。行为的所有权变了，
    // 用例跟着走。

    /**
     * 回归：逐字节 `(char) byte` 拼出来的中文是 Latin-1 乱码，
     * 再编码回去就变成另一串字节，站点按那串搜 = 0 条。
     */
    @Test
    public void 百分号解码按UTF8还原中文() {
        assertEquals("自拍", HtmlKit.percentDecode("%E8%87%AA%E6%8B%8D"));
        assertEquals("自拍 视频", HtmlKit.percentDecode("%E8%87%AA%E6%8B%8D%20%E8%A7%86%E9%A2%91"));
        assertEquals("中文asdf", HtmlKit.percentDecode("%E4%B8%AD%E6%96%87asdf"));
        assertEquals("plain", HtmlKit.percentDecode("plain"));
        assertEquals("", HtmlKit.percentDecode(""));
        assertEquals("", HtmlKit.percentDecode(null));
    }

    /** 编码器也验一遍：中文、空格、符号都要变成 %XX，不能被吃掉。 */
    @Test
    public void 编码器不加号只出百分号() {
        assertEquals("%E8%87%AA%E6%8B%8D", HtmlKit.enc("自拍"));
        assertEquals("a%20b", HtmlKit.enc("a b"));
        assertEquals("abc-._~", HtmlKit.enc("abc-._~"));
    }

    /** 编码 → 解码必须回到原文，这是搜索链路的前提。 */
    @Test
    public void 编码解码往返() {
        String[] words = { "自拍", "中文 音声", "a b c", "日本/無修正", "100%纯" };
        for (String word : words) {
            assertEquals(word, HtmlKit.percentDecode(HtmlKit.enc(word)));
        }
    }

    @Test
    public void 域名匹配() {
        Porn91Site site = new Porn91Site();
        assertTrue(site.matchesUrl("https://901.26702.app/"));
        assertTrue(site.matchesUrl("https://901.26702.app/v.php"));
        assertTrue(site.matchesUrl("https://www.91porn.com/"));
        assertFalse(site.matchesUrl("https://www.mrds66.com/"));
        assertFalse(site.matchesUrl("https://not91porn.com/"));
        assertFalse(site.matchesUrl(null));
    }

    @Test
    public void 站点根只要scheme和host() {
        assertEquals("https://901.26702.app",
                Porn91Site.originOf("https://901.26702.app/v.php?category=hot"));
        assertEquals("", Porn91Site.originOf("http://901.26702.app/"));
        assertEquals("", Porn91Site.originOf("not a url"));
    }

    @Test
    public void 注册表里有porn91() {
        assertTrue(Adapters.ids().contains("porn91"));
        assertTrue(Adapters.byId("porn91") instanceof Porn91Site);
    }
}
