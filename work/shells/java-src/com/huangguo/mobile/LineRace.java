package com.huangguo.mobile;

import android.util.Log;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 同一部片的多条线路里挑最快的那条。
 *
 * ── 先说实测结论：多数源根本没有第二条真线路 ────────────────────────
 * 苹果 CMS 的 vod_play_url 用 `$$$` 分隔多组，看着像「多线路」，但扫了 18 个源
 * × 54 部片（2026-10-17）之后，**命中率是 0**：
 *
 *   hnyun    https://hn.bfvvs.com/play/e3197M9b              ← 分享页
 *   hnm3u8   https://hn.bfvvs.com/play/e3197M9b/index.m3u8   ← 同一个 id 的直链
 *
 * 每组里只有一条是 m3u8，而且所有组的 host 完全相同。也就是说那是
 * 「同一条流的两副面孔」，不是两条独立线路。过滤完只剩一个候选，本类直接返回。
 *
 * ── 那为什么还留着 ──────────────────────────────────────────────────
 * 有些源是真的两条（暴风系的 bfzym3u8 + 备用线、TVBox 仓里带 line2 的），
 * 一旦碰到就是白捡。代价为零：候选不足两个时 pick() 原样返回，不做任何请求。
 * 真正的速度差异在**跨源**（同一部片在 ikunzyapi 首片 960ms、hongniuzy2 1501ms），
 * 那需要标题匹配，是另一件事。
 *
 * ── 原来 pickLine() 解决的是什么 ────────────────────────────────────
 * 只挑「第一条是 m3u8 的」，解决的是「第一条是 /share/ 分享页，取来当直链用
 * 就会点了没反应」。那部分逻辑保留在 CmsAdapter.pickLine 里，本类不重复。
 *
 * ── 测什么，这步最容易做错 ──────────────────────────────────────────
 * 不测 m3u8 本身。m3u8 只有 20~50KB，四条线路量出来全是 300ms，分不出高下。
 * 要测**首个分片**：拉 m3u8 → 取第一个分片 → Range 256KB → 记 ttfb + 总耗时。
 * 这 256KB 不是白花，VLC 之后拉同一个分片会命中 CDN 边缘缓存。
 *
 * ── 必须用冷连接 ────────────────────────────────────────────────────
 * AiiHttp 的连接池是热的，用它测第一个候选会虚高；而 VLC 播放时是自己新建的
 * 连接。所以这里单独开一个 ConnectionPool(0) 的 client，每次都是新握手 ——
 * 不然四个候选永远是第一个赢，测了等于没测。
 *
 * ── 阻塞策略 ────────────────────────────────────────────────────────
 * pick() **从不等待**：缓存里有分就用最快的，没有就返回第一个并在后台补测。
 * 用户点播放不会有任何额外等待，第一集用原地址，从第二集开始享受结果。
 */
public final class LineRace {

    private static final String TAG = "HG.Race";

    /** 分数的有效期。CDN 的冷热是会变的，但 10 分钟内基本稳定。 */
    private static final long TTL_MS = 10 * 60 * 1000L;

    /** 只测首片的前 256KB。全下要 2 秒，四条线路就是 8 秒。 */
    private static final int SEG_PROBE_BYTES = 262144;

    /** 单个分片最多等这么久。慢线路直接判负，别拖累别的。 */
    private static final long SEG_TIMEOUT_MS = 4000L;

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36";

    /** 槽位 → 分数。槽位是 m3u8 的 host —— 同一部剧的 100 集在同一个 CDN 上。 */
    private static final ConcurrentHashMap<String, Score> CACHE =
            new ConcurrentHashMap<String, Score>();

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);

    /**
     * 专用 client：池容量 0 = 用完立刻断，保证每次测都是冷连接。
     * 这也是它和 AiiHttp 分开的唯一原因。
     */
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectionPool(new ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout((int) SEG_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)
            .build();

    private LineRace() {}

    // ── 对外 ──────────────────────────────────────────────────────────

    /**
     * 从候选里挑最快的。**永不阻塞**。
     *
     * @param candidates 同一集的多个播放地址（每条线路一个）
     * @return 选中的地址。没缓存时就是第一个候选（保持原来的行为）。
     */
    public static String pick(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        // 单候选是常态（见类注释：多数源的 $$$ 只是同一条流的分享页 + 直链）。
        // 这里不做任何网络请求，直接原样返回。
        if (candidates.size() == 1) return candidates.get(0);

        long now = System.currentTimeMillis();
        String best = null;
        long bestCost = Long.MAX_VALUE;
        boolean covered = true;

        for (int i = 0; i < candidates.size(); i++) {
            String url = candidates.get(i);
            Score score = CACHE.get(slotOf(url));
            if (score == null || now - score.at > TTL_MS) {
                covered = false;
                continue;
            }
            long cost = score.ok ? score.cost : Long.MAX_VALUE / 4;
            if (cost < bestCost) {
                bestCost = cost;
                best = url;
            }
        }

        if (covered && best != null) {
            if (!best.equals(candidates.get(0))) {
                Log.i(TAG, "选线路 " + slotOf(best) + "（" + bestCost + "ms）"
                        + "，原线路 " + slotOf(candidates.get(0)) + " 更慢");
            }
            return best;
        }

        // 还没测过：先按原样播这一集，后台补测，下一集就能用上。
        warm(candidates);
        return candidates.get(0);
    }

    /** 后台测一批候选。同一 host 10 分钟内只测一次。 */
    public static void warm(final List<String> candidates) {
        if (candidates == null || candidates.size() < 2) return;
        final long now = System.currentTimeMillis();
        for (int i = 0; i < candidates.size(); i++) {
            final String url = candidates.get(i);
            final String slot = slotOf(url);
            Score old = CACHE.get(slot);
            if (old != null && now - old.at <= TTL_MS) continue;
            POOL.execute(new Runnable() {
                @Override public void run() {
                    Score score = measure(url);
                    CACHE.put(slot, score);
                    Log.i(TAG, String.format(Locale.US, "%s ttfb=%dms cost=%dms %dKB %s",
                            slot, score.ttfb, score.cost, score.bytes / 1024,
                            score.ok ? "" : "FAIL"));
                }
            });
        }
    }

    /** 缓存里的最佳候选（没有就 null），给日志和调试用。 */
    public static String bestOf(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        long now = System.currentTimeMillis();
        String best = null;
        long bestCost = Long.MAX_VALUE;
        for (int i = 0; i < candidates.size(); i++) {
            String url = candidates.get(i);
            Score score = CACHE.get(slotOf(url));
            if (score == null || !score.ok || now - score.at > TTL_MS) continue;
            if (score.cost < bestCost) { bestCost = score.cost; best = url; }
        }
        return best;
    }

    // ── 测速 ──────────────────────────────────────────────────────────

    /** m3u8 的 host。同一 CDN 下的分片速度差不多，不必按 URL 缓存。 */
    static String slotOf(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? url : host.toLowerCase(Locale.US);
        } catch (Throwable e) {
            int start = url.indexOf("://");
            start = start < 0 ? 0 : start + 3;
            int slash = url.indexOf('/', start);
            return slash < 0 ? url.substring(start) : url.substring(start, slash);
        }
    }

    private static Score measure(String m3u8) {
        long started = System.currentTimeMillis();
        try {
            String text = fetch(m3u8, 0, 262144);
            if (text == null || text.length() == 0) return Score.fail(started);

            String seg = firstSegment(m3u8, text);
            if (seg == null) return Score.fail(started);

            // 分片：Range 拿前 256KB，记首字节和总耗时。
            Request request = new Request.Builder()
                    .url(seg)
                    .header("User-Agent", UA)
                    .header("Range", "bytes=0-" + (SEG_PROBE_BYTES - 1))
                    .build();
            Response response = CLIENT.newCall(request).execute();
            try {
                if (!response.isSuccessful()) return Score.fail(started);
                ResponseBody body = response.body();
                if (body == null) return Score.fail(started);
                InputStream in = body.byteStream();
                byte[] buf = new byte[16384];
                long total = 0;
                long ttfb = -1;
                while (total < SEG_PROBE_BYTES) {
                    int read = in.read(buf);
                    if (read <= 0) break;
                    if (ttfb < 0) ttfb = System.currentTimeMillis() - started;
                    total += read;
                }
                long cost = System.currentTimeMillis() - started;
                if (total <= 0) return Score.fail(started);
                // 总分 = 从发起到这 256KB 落地的全部时间。这就是用户「点播放到出画面」
                // 里网络那一段，比单看 ttfb 更贴近体感。
                return new Score(true, ttfb < 0 ? cost : ttfb, cost, total, cost);
            } finally {
                try { response.close(); } catch (Throwable ignored) {}
            }
        } catch (Throwable e) {
            return Score.fail(started);
        }
    }

    /** 主清单里第一个分片。相对路径要拼 —— 有的站回的是相对地址。 */
    static String firstSegment(String m3u8, String text) {
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0 || line.startsWith("#")) continue;
            try {
                return new URI(m3u8).resolve(line).toString();
            } catch (Throwable e) {
                return line;
            }
        }
        return null;
    }

    /** 读一段文本（m3u8 一般 20~60KB，用 256KB 兜住异常大的主清单）。 */
    private static String fetch(String url, int skip, int limit) {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .build();
        try {
            Response response = CLIENT.newCall(request).execute();
            try {
                if (!response.isSuccessful()) return null;
                ResponseBody body = response.body();
                if (body == null) return null;
                InputStream in = body.byteStream();
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(16384);
                byte[] buf = new byte[8192];
                int total = 0;
                while (total < limit) {
                    int read = in.read(buf);
                    if (read <= 0) break;
                    out.write(buf, 0, read);
                    total += read;
                }
                return new String(out.toByteArray(), "UTF-8");
            } finally {
                try { response.close(); } catch (Throwable ignored) {}
            }
        } catch (Throwable e) {
            return null;
        }
    }

    // ── 分数字 ────────────────────────────────────────────────────────

    private static final class Score {
        final boolean ok;
        final long ttfb;
        final long cost;
        final long bytes;
        final long at;

        Score(boolean ok, long ttfb, long cost, long bytes, long at) {
            this.ok = ok;
            this.ttfb = ttfb;
            this.cost = cost;
            this.bytes = bytes;
            this.at = at;
        }

        static Score fail(long started) {
            return new Score(false, 0, Long.MAX_VALUE / 4, 0, started);
        }
    }

    /** 拆多线路时给上层用：把一条 line 里的第 N 集取出来。 */
    static List<String> candidatesFrom(List<String> lines, int index) {
        List<String> out = new ArrayList<String>();
        if (lines == null) return out;
        for (int i = 0; i < lines.size(); i++) {
            List<String[]> eps = CmsAdapter.splitLine(lines.get(i));
            if (index < 0 || index >= eps.size()) continue;
            String url = eps.get(index)[1];
            if (url.length() == 0) continue;
            if (url.indexOf(".m3u8") < 0 && url.indexOf(".mp4") < 0) continue;
            if (!out.contains(url)) out.add(url);
        }
        return out;
    }
}
