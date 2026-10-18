package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * HLS 播放列表改写的回归。
 *
 * ── 这段逻辑为什么必须有测试 ──────────────────────────────────────────
 * 2026-10-18 真机：missav 的视频挂在 {@code surrit.com}，Cloudflare 对 OkHttp
 * 一律 403（{@code [relay] 上游 403 …/playlist.m3u8}），只有 WebView 栈能过。
 * 于是取流改成「借 WebView 取字节」，随之而来的问题全在<b>改写</b>这一段：
 *
 *   · master 里子播放列表是相对路径（{@code 720p/video.m3u8}），
 *   · 子列表里 1147 个分片也是相对路径（{@code video0.jpeg}），
 *   · 而 VLC 拿到的地址是 {@code http://127.0.0.1:PORT/s?u=…}。
 *
 * VLC 会把相对路径挂到<b>那个</b>地址下面解析 —— query 直接丢失，中继只会回
 * 404。写错的表现不是「打不开」，是「能开播、一拖进度就断」，真机上极难二分。
 * 所以这里拿真实响应（fixtures/missav-*.m3u8）把每一条都钉死。
 *
 * ── fixture 来源 ──────────────────────────────────────────────────────
 * fct-210 的真实响应（2026-10-18，Chrome DevTools 里 copy 出来的原文）：
 * <ul>
 *   <li>{@code missav-master-fct210.m3u8} —— master，4 个码率，完整；</li>
 *   <li>{@code missav-media-720p.m3u8} —— 720p 媒体列表，<b>节选</b>：
 *       真实的有 1147 条分片（video0 … video1146），这里只留头三条和末两条，
 *       中间用同样格式补齐没有意义，还会让 fixture 变成 36KB 的噪音。</li>
 * </ul>
 * 分片在服务器上标的是 {@code image/jpeg}（伪装），实际首字节是 TS 同步字节
 * {@code 0x47} —— 这也是 relay 自己判 Content-Type 而不是照抄的原因。
 */
public class VlcRelayTest {

    private static final String DIR = "missav/";

    /** 真实 UUID，用来验证相对路径解析确实拼回了原目录。 */
    private static final String UUID = "18b4bbc2-c802-44a7-ab7a-78531695c531";
    private static final String MASTER = "https://surrit.com/" + UUID + "/playlist.m3u8";
    private static final String PREFIX = "http://127.0.0.1:40603/s?u=";

    private static String fixture(String name) throws Exception {
        InputStream in = VlcRelayTest.class.getClassLoader().getResourceAsStream(DIR + name);
        if (in == null) throw new IllegalStateException("缺少 fixture：" + DIR + name);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 每一行 URI 都必须是中继地址，且解码后能还原成原始绝对地址。 */
    private static java.util.List<String> uris(String rewritten) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (String line : rewritten.split("\n")) {
            String t = line.trim();
            if (t.length() > 0 && !t.startsWith("#")) out.add(t);
        }
        return out;
    }

    @Test
    public void master里的子播放列表全部指向中继() throws Exception {
        String[] out = VlcRelay.rewriteM3u8(fixture("missav-master-fct210.m3u8"), MASTER, PREFIX);
        java.util.List<String> lines = uris(out[0]);

        assertEquals("master 里正好 4 条 URItest", 4, lines.size());
        assertEquals("4", out[1]);
        for (String line : lines) {
            assertTrue("没包成中继地址：" + line, line.startsWith(PREFIX));
        }
        assertEquals("360p 的相对路径必须解析回原目录",
                "https://surrit.com/" + UUID + "/360p/video.m3u8",
                VlcRelay.decode(lines.get(0).substring(PREFIX.length())));
        assertEquals("1080p 同理",
                "https://surrit.com/" + UUID + "/1080p/video.m3u8",
                VlcRelay.decode(lines.get(3).substring(PREFIX.length())));
    }

    @Test
    public void 媒体列表的每个分片都指向中继() throws Exception {
        String media = "https://surrit.com/" + UUID + "/720p/video.m3u8";
        String[] out = VlcRelay.rewriteM3u8(fixture("missav-media-720p.m3u8"), media, PREFIX);
        java.util.List<String> lines = uris(out[0]);

        assertEquals("fixture 里 5 条分片", 5, lines.size());
        assertEquals("5", out[1]);
        assertEquals("分片是 .jpeg 伪装的 TS，路径同样要拼回原目录",
                "https://surrit.com/" + UUID + "/720p/video0.jpeg",
                VlcRelay.decode(lines.get(0).substring(PREFIX.length())));
        assertEquals("末尾那条也要改，别只改头几条",
                "https://surrit.com/" + UUID + "/720p/video1146.jpeg",
                VlcRelay.decode(lines.get(4).substring(PREFIX.length())));
    }

    /**
     * {@code #EXT-X-TOKEN} 必须原样留着。
     *
     * 它是站点自己加的标签（标准 HLS 里没有），VLC 会忽略；但如果改写时连带
     * 重排/清洗掉，分片就可能对不上站点那边的校验。这条断言很小，但正因为
     * 它「看起来无用」才最容易被后来的人顺手删掉。
     */
    @Test
    public void 注释与自定义标签原样保留() throws Exception {
        String[] out = VlcRelay.rewriteM3u8(fixture("missav-media-720p.m3u8"),
                "https://surrit.com/" + UUID + "/720p/video.m3u8", PREFIX);
        assertTrue(out[0].contains("#EXT-X-TOKEN=1EWZyAjZ4MjZ1MDZ2MGNhxHMygTNywXMyEjL0IjMuQjNuQjN"));
        assertTrue(out[0].contains("#EXT-X-TARGETDURATION:4"));
        assertTrue("结束标记不能被吃掉", out[0].contains("#EXT-X-ENDLIST"));
        // 每个 #EXTINF 后面必须跟一条改写过的 URI，两个都不能少。
        assertEquals(5, out[0].split("#EXTINF").length - 1);
    }

    /** 已经是绝对地址的不受影响（站点换 CDN 时会混排）。 */
    @Test
    public void 绝对地址原样解析() throws Exception {
        String text = "#EXTM3U\n#EXTINF:4,\nhttps://cdn.example.com/a/b/video0.ts\n";
        String[] out = VlcRelay.rewriteM3u8(text, MASTER, PREFIX);
        java.util.List<String> lines = uris(out[0]);
        assertEquals(1, lines.size());
        assertEquals("https://cdn.example.com/a/b/video0.ts",
                VlcRelay.decode(lines.get(0).substring(PREFIX.length())));
    }

    /**
     * 空行和未知标签不能导致「改写条数」虚高。
     *
     * 真机上 {@code [relay] 播放列表改写 N 条} 是判断这条链路好没好用的第一眼
     * 依据（master 应该 4，子列表应该 1147）。数错了会把排查带偏。
     */
    @Test
    public void 空行与注释不计入改写条数() throws Exception {
        String text = "\n\n#EXTM3U\n\n# a comment\n\nvideo0.jpeg\n\n";
        String[] out = VlcRelay.rewriteM3u8(text, MASTER, PREFIX);
        assertEquals("1", out[1]);
        assertEquals(1, uris(out[0]).size());
    }

    /** base64url 编码必须是无 padding、只含 [A-Za-z0-9_-]，否则拼进 query 会被 VLC 弄坏。 */
    @Test
    public void 中继地址的编码能无损还原() {
        String url = "https://surrit.com/" + UUID + "/720p/video1146.jpeg";
        String encoded = VlcRelay.encode(url);
        assertTrue("不能带 = padding：" + encoded, encoded.indexOf('=') < 0);
        assertTrue("不能出现 + 或 /：" + encoded, encoded.indexOf('+') < 0 && encoded.indexOf('/') < 0);
        assertEquals(url, VlcRelay.decode(encoded));
    }
}
