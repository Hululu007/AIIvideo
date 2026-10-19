package com.huangguo.mobile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 某个源「在 Cloudflare 盾后面」这件事的全部声明。
 *
 * ══ 为什么要有这个类（2026-10-19，把壳里的硬编码收回来）══
 *
 * <p>过盾那套东西（隐藏 WebView、挑战等待、cookie、视频 CDN 借道）是能力，
 * 但「哪个域要过盾」「拿哪个页面去过盾」是<b>源的事实</b>。这两件事原来混在
 * {@code SiteFetchWebView} 里，代价很具体：
 *
 * <ul>
 *   <li>{@code WARMUP_URL} 写死 {@code missav.live/dm539/cn/new}；</li>
 *   <li>{@code SHIELDED_HOSTS} 写死 {@code missav.live} / {@code surrit.com}；</li>
 *   <li>{@code handles()} 里又写死 {@code fourhoi.com} 那个封面图床。</li>
 * </ul>
 *
 * <p>后果不是「不好看」：接第二个盾后站点必须改 Java 发版，而源本身
 * 明明就是一个 json 就能说清的事。现在改成源自己声明，壳只读：
 *
 * <pre>
 * "web": {
 *   "spider": "https://…/missav.js",
 *   "shield": {
 *     "warmup": "https://missav.live/dm539/cn/new",
 *     "hosts": ["missav.live", "surrit.com", "fourhoi.com"],
 *     "cookie": "https://missav.live/"
 *   }
 * }
 * </pre>
 *
 * <p>没有 {@code shield} 块的源，这里所有判断一律 false —— 它们的抓取
 * 走 okhttp，一行行为都不变。
 *
 * <p>════ 为什么是静态单例 ════
 *
 * 调用方分布在三层：{@code SpiderRuntime}（抓页面）、{@code VlcRelay}（取分片）、
 * {@code SiteRepository}（取封面），它们都拿不到同一个 profile 实例 ——
 * 有的在 :spider 进程、有的在主进程。而 {@link SiteProfile} 每次读配置都会
 * 在构造时 {@link #install} 一次，所以「当前源是谁」这个事实天然是进程级单例。
 * 两个进程各装各的，互不影响。
 */
final class ShieldProfile {

    /** 空档：没有 shield 块的源用它，所有判断返回 false。 */
    private static final ShieldProfile NONE = new ShieldProfile(null, null, null);

    private final String warmup;
    private final List<String> hosts;
    private final String cookie;

    private static volatile ShieldProfile current = NONE;

    private ShieldProfile(String warmup, List<String> hosts, String cookie) {
        this.warmup = warmup == null ? "" : warmup.trim();
        this.hosts = hosts == null ? new ArrayList<String>() : hosts;
        this.cookie = cookie == null ? "" : cookie.trim();
    }

    /**
     * 从合并后的源配置里读出 {@code web.shield}。
     *
     * <p>写坏了不抛：过盾是「能救就救」的功能，配置里一个字段写错不该让
     * 整个源装不上。读不出来的部分按空处理，抓取退回 okhttp。
     */
    static ShieldProfile parse(JSONObject merged) {
        try {
            JSONObject web = merged == null ? null : merged.optJSONObject("web");
            JSONObject shield = web == null ? null : web.optJSONObject("shield");
            if (shield == null) return NONE;

            String warmup = shield.optString("warmup", "").trim();
            String cookie = shield.optString("cookie", "").trim();

            List<String> hosts = new ArrayList<String>();
            JSONArray arr = shield.optJSONArray("hosts");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                String h = arr.optString(i, "").trim().toLowerCase(Locale.ROOT);
                if (h.length() == 0) continue;
                // 只收主机名。写成完整 URL 是常见笔误，这里顺手改正而不是拒收。
                int slash = h.indexOf("://");
                if (slash >= 0) h = h.substring(slash + 3);
                int cut = h.indexOf('/');
                if (cut > 0) h = h.substring(0, cut);
                if (h.length() > 0) hosts.add(h);
            }

            if (warmup.length() == 0 && hosts.isEmpty()) return NONE;
            return new ShieldProfile(warmup, hosts, cookie);
        } catch (Throwable broken) {
            UserSource.log("shield 配置读不出来，这个源按「不需要过盾」处理：" + broken);
            return NONE;
        }
    }

    /** 进程级安装。{@link SiteProfile} 构造时调，每次读配置都会覆盖一次。 */
    static void install(ShieldProfile profile) {
        current = profile == null ? NONE : profile;
    }

    static ShieldProfile current() {
        return current;
    }

    /** 这个地址的响应要不要借浏览器栈（页面、图床、分片都算）。 */
    static boolean handles(String url) {
        return current.matches(url);
    }

    /** 这个地址能不能用 okhttp 直接拿（false = 必须走真 WebView）。 */
    static boolean isShielded(String url) {
        return current.matches(url);
    }

    /** 过盾用的页面。空串表示这个源没声明，调用方该报错而不是猜。 */
    static String warmup() {
        return current.warmup;
    }

    /** cookie 归属的 origin（导给别的进程时要用）。 */
    static String cookieOrigin() {
        return current.cookie;
    }

    /** 有没有声明过盾这件事。 */
    static boolean declared() {
        return current != NONE && (current.warmup.length() > 0 || !current.hosts.isEmpty());
    }

    private boolean matches(String url) {
        if (url == null || url.length() == 0 || hosts.isEmpty()) return false;
        String host = hostOf(url);
        if (host.length() == 0) return false;
        for (int i = 0; i < hosts.size(); i++) {
            String h = hosts.get(i);
            if (host.equals(h) || host.endsWith("." + h)) return true;
        }
        return false;
    }

    private static String hostOf(String url) {
        try {
            String h = new java.net.URI(url).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (Throwable e) {
            return "";
        }
    }
}
