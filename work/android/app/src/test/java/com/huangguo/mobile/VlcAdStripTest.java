package com.huangguo.mobile;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 插播广告剔除的判据钉子。
 *
 * <p>规则移植自 LibreSpark/LibreTV 的 src/lib/m3u8.ts 与
 * ltxlong/cf-m3u8-filter-ad-api，用例里的秒数照抄它们在 dytt / phimgood /
 * rycjapi / 暴风 几个真实采集源上量到的形状。写错的代价是「正片被啃掉一块」
 * 或者「广告还在播」，两种在真机上只能靠看完一整集才发现。
 */
public class VlcAdStripTest {

    private static final String BASE = "https://play.example.com/play/abc/index.m3u8";

    private static void seg(StringBuilder sb, String url, double dur) {
        sb.append("#EXTINF:").append(dur).append(",\n").append(url).append('\n');
    }

    private static String seq(String host, String prefix, int from, int n, double dur) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < from + n; i++) {
            seg(sb, "https://" + host + "/hls/" + prefix + i + ".ts", dur);
        }
        return sb.toString();
    }

    /** 一条干净的播放列表：原样返回，别碰。 */
    @Test public void cleanPlaylistUntouched() {
        String text = "#EXTM3U\n#EXT-X-VERSION:3\n" + seq("p.hhwenjian.com:65", "plist", 0, 886, 3)
                + "#EXT-X-ENDLIST\n";
        String[] out = VlcRelay.stripAdRuns(text, BASE);
        assertEquals(text, out[0]);
        assertEquals("0", out[1]);
        assertTrue(out[3].contains("886 段"));
        assertTrue(out[3].contains("序号连续"));
    }

    /** 片头 10 片广告，换了域名，后面 discontinuity 隔开 —— 删掉。 */
    @Test public void dropsLeadingAdRun() {
        StringBuilder sb = new StringBuilder("#EXTM3U\n");
        for (int i = 0; i < 10; i++) seg(sb, "https://ad.casino-cdn.com/gg/v" + i + ".ts", 3);
        sb.append("#EXT-X-DISCONTINUITY\n");
        sb.append(seq("p.hhwenjian.com", "plist", 0, 600, 3));
        sb.append("#EXT-X-ENDLIST\n");
        String[] out = VlcRelay.stripAdRuns(sb.toString(), BASE);
        assertEquals("10", out[1]);
        assertEquals("30", out[2]);
        assertTrue(out[0].contains("hhwenjian"));
        assertFalse(out[0].contains("casino-cdn"));
    }

    /** 正片中间插 7 片广告，前后都 discontinuity —— 删掉，两头都留着。 */
    @Test public void dropsMidRollAdRun() {
        StringBuilder sb = new StringBuilder("#EXTM3U\n");
        sb.append(seq("p.hhwenjian.com", "plist", 0, 200, 3));
        sb.append("#EXT-X-DISCONTINUITY\n");
        for (int i = 0; i < 7; i++) seg(sb, "https://v3.ppqrrs.com/ad/spot" + i + ".ts", 3);
        sb.append("#EXT-X-DISCONTINUITY\n");
        sb.append(seq("p.hhwenjian.com", "plist", 200, 200, 3));
        String[] out = VlcRelay.stripAdRuns(sb.toString(), BASE);
        assertEquals("7", out[1]);
        assertTrue(out[0].contains("plist0.ts"));
        assertTrue(out[0].contains("plist399.ts"));
        assertFalse(out[0].contains("ppqrrs"));
    }

    /**
     * 序号断裂：同域名、同命名，广告插进来把 plist 号顶掉一段。
     * 这是 ltxlong 那个 worker 的核心判据 —— 换域名规则看不见这种。
     */
    @Test public void dropsSequenceBreakRun() {
        StringBuilder sb = new StringBuilder("#EXTM3U\n");
        sb.append(seq("p.hhwenjian.com", "plist", 0, 200, 3));
        sb.append("#EXT-X-DISCONTINUITY\n");
        sb.append(seq("p.hhwenjian.com", "plist", 9000, 12, 3));   // 36 秒，号对不上
        sb.append("#EXT-X-DISCONTINUITY\n");
        sb.append(seq("p.hhwenjian.com", "plist", 200, 200, 3));
        String[] out = VlcRelay.stripAdRuns(sb.toString(), BASE);
        assertEquals("12", out[1]);
        assertFalse(out[0].contains("plist9000"));
        assertTrue(out[0].contains("plist200.ts"));
    }

    /**
     * 两长夹一短：正片段 2000 秒级别，中间夹 15 秒的一小段。
     * 照 LibreTV 在 phimgood 上量到的形状（298/1198/2163 秒之间夹 15~19 秒）。
     */
    @Test public void dropsInterstitialShortRun() {
        StringBuilder sb = new StringBuilder("#EXTM3U\n");
        sb.append(seq("p.hhwenjian.com", "long", 0, 5, 200));
        sb.append("#EXT-X-DISCONTINUITY\n");
        sb.append(seq("p.hhwenjian.com", "short", 0, 5, 3));
        sb.append("#EXT-X-DISCONTINUITY\n");
        sb.append(seq("p.hhwenjian.com", "long2", 0, 5, 200));
        String[] out = VlcRelay.stripAdRuns(sb.toString(), BASE);
        assertEquals("5", out[1]);
        assertFalse(out[0].contains("short0.ts"));
        assertTrue(out[0].contains("long2"));
    }

    /** 路径特征：暴风采集云的 /video/adjump/。 */
    @Test public void dropsAdjumpByPath() {
        StringBuilder sb = new StringBuilder("#EXTM3U\n");
        sb.append(seq("p.hhwenjian.com", "plist", 0, 300, 3));
        sb.append("#EXT-X-DISCONTINUITY\n");
        sb.append(seq("p.hhwenjian.com", "adjump/time/", 0, 10, 3));
        sb.append("#EXT-X-DISCONTINUITY\n");
        sb.append(seq("p.hhwenjian.com", "plist", 300, 300, 3));
        String[] out = VlcRelay.stripAdRuns(sb.toString(), BASE);
        assertEquals("10", out[1]);
        assertFalse(out[0].contains("adjump"));
    }

    /** 采集站按固定块切分（每组 5 片）：片头那组不能当广告删。 */
    @Test public void keepsUniformGrouping() {
        StringBuilder sb = new StringBuilder("#EXTM3U\n");
        for (int g = 0; g < 10; g++) {
            if (g > 0) sb.append("#EXT-X-DISCONTINUITY\n");
            sb.append(seq("p.hhwenjian.com", "g" + g + "_", 0, 5, 20));
        }
        String[] out = VlcRelay.stripAdRuns(sb.toString(), BASE);
        assertEquals("0", out[1]);
    }

    /** 中途整体换 CDN，一直到结尾 —— 不是广告，一条都不许删。 */
    @Test public void keepsPermanentCdnSwitch() {
        StringBuilder sb = new StringBuilder("#EXTM3U\n");
        sb.append(seq("p.hhwenjian.com", "plist", 0, 100, 3));
        sb.append(seq("v2.other-cdn.com", "x", 0, 10, 3));
        String[] out = VlcRelay.stripAdRuns(sb.toString(), BASE);
        assertEquals("0", out[1]);
    }

    /** 两个 CDN 交替出现：轮换，不是广告。 */
    @Test public void keepsAlternatingCdns() {
        StringBuilder sb = new StringBuilder("#EXTM3U\n");
        for (int g = 0; g < 6; g++) {
            if (g > 0) sb.append("#EXT-X-DISCONTINUITY\n");
            String host = g % 2 == 0 ? "a.cdn-one.com" : "b.cdn-two.com";
            sb.append(seq(host, "p" + g + "_", 0, 5, 3));
        }
        String[] out = VlcRelay.stripAdRuns(sb.toString(), BASE);
        assertEquals("0", out[1]);
    }

    /** master 列表（没有 EXTINF）不参与判断。 */
    @Test public void masterPlaylistUntouched() {
        String text = "#EXTM3U\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=800000\n720p/index.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=2000000\n1080p/index.m3u8\n";
        String[] out = VlcRelay.stripAdRuns(text, BASE);
        assertEquals(text, out[0]);
        assertEquals("0", out[1]);
    }

    /** 删的是整段分片，不会只删 discontinuity 标记（后者会让 PTS 跳变失效、只闻其声）。 */
    @Test public void removesSegmentsNotJustDiscontinuity() {
        StringBuilder sb = new StringBuilder("#EXTM3U\n");
        for (int i = 0; i < 10; i++) seg(sb, "https://ad.casino-cdn.com/gg/v" + i + ".ts", 3);
        sb.append("#EXT-X-DISCONTINUITY\n");
        sb.append(seq("p.hhwenjian.com", "plist", 0, 600, 3));
        String[] out = VlcRelay.stripAdRuns(sb.toString(), BASE);
        assertFalse(out[0].contains("casino-cdn"));
        assertEquals(-1, out[0].indexOf("#EXT-X-DISCONTINUITY"));   // 首分片前不留空跳变点
    }

    /** 直连源的分片：播放列表进中继改写，但分片写绝对地址，不再多绕一跳本机 OkHttp。 */
    @Test public void smartRewriteKeepsDirectSegmentsAbsolute() {
        String text = "#EXTM3U\n#EXTINF:3.0,\nhttps://cdn.example.com/hls/a1.ts\n";
        String[] out = VlcRelay.rewriteM3u8Smart(text, "https://cdn.example.com/hls/index.m3u8",
                "http://127.0.0.1:9/s?u=");
        assertTrue(out[0].contains("https://cdn.example.com/hls/a1.ts"));
        assertFalse(out[0].contains("127.0.0.1"));
    }

    /** 播放列表按后缀识别（含大小写与 .m3u）。 */
    @Test public void playlistUrlDetection() {
        assertTrue(VlcRelay.isPlaylistUrl("https://a.example/v/index.m3u8?token=1"));
        assertTrue(VlcRelay.isPlaylistUrl("https://a.example/v/INDEX.M3U8"));
        assertFalse(VlcRelay.isPlaylistUrl("https://a.example/v/a1.ts"));
    }
}
