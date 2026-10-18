package com.huangguo.mobile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 网络路由的**唯一决策点** —— 「这个地址该走代理还是直连」只在这里回答。
 *
 * ── 为什么要有这个类 ────────────────────────────────────────────────
 * 2026-10-18 接樱花动漫，播放全线 403 / TLS 被掐。根因不是适配器：内置核心
 * 开在荷兰节点，而这个站的播放 CDN 全在国内 —— 走外网节点反而被掐。当时的
 * 修法是往 {@link DirectHosts} 里逐个手填域名，一共填了 16 个。
 *
 * 填完立刻看出这是错的方向：**站点的采集站域名天天换**，白名单永远追不上，
 * 而且下一家站点还得再填一遍。真正的规律是反向的 ——
 *
 *   墙只挡「站点的入口域」，播放 CDN 九成在境内，直连最快。
 *
 * 所以路由应该跟着**源**走，缺省直连：
 *
 * <pre>
 *   "net": { "proxy": ["yhdm.one"] }
 * </pre>
 *
 * 有 {@code net.proxy} → 只有表里这些域走代理，其余（封面、分片、新的 CDN）全直连。
 * 没有 → 维持老行为：{@link DirectHosts} 白名单命中直连，其余跟全局开关。
 *
 * 后者必须留着：AGE动漫、91porn 那些源是在这套机制之前接的，它们依赖
 * 「全局代理 + 白名单例外」的语义。
 *
 * ── 谁问它 ────────────────────────────────────────────────────────
 * 和 {@link DirectHosts} 一样是三个出口，但只回答一个问题：
 *
 * <pre>
 *   AiiHttp     rawWith / getWith / getOnce / plainClient   → 要不要挂代理
 *   VlcRelay    wrap()                                       → 要不要绕中继
 *   XrayConfig  routing()                                    → 不走这条（核心启动时生成）
 * </pre>
 *
 * Xray 那条出口**故意不接**：核心的 routing 是启动时固化的，加进来只会造成
 * 「配置改了但核心不知道」的新漂移。不需要它 —— 走 SOCKS 的只有 OkHttp 判定
 * 该走代理的流量，判定在 OkHttp 那一步已经做完了。
 */
final class NetRoute {

    /** 必须走代理的域名。来自源的 net.proxy，子域自动命中。 */
    private static volatile List<String> PROXY_HOSTS = new ArrayList<String>();

    /** 当前源是否声明了 net 块。声明了就不再参考全局开关与 DirectHosts。 */
    private static volatile boolean declared = false;

    private NetRoute() {}

    /**
     * 装载一个源的路由声明。{@link SiteProfile} 构造时调 —— 那是整个 App
     * 里唯一一处「当前源长什么样」的组装点，别在别处再造第二份。
     *
     * @param net    源配置里的 net 块，可以为 null（null = 不声明，用老规则）
     * @param origin 站点根，兜底加进去：接口域永远该跟源同路，忘写就是全站连不上
     */
    static void configure(org.json.JSONObject net, String origin) {
        List<String> hosts = new ArrayList<String>();
        boolean has = false;
        if (net != null) {
            org.json.JSONArray list = net.optJSONArray("proxy");
            if (list != null) {
                has = true;
                for (int i = 0; i < list.length(); i++) {
                    String h = list.optString(i, "").trim().toLowerCase(Locale.ROOT);
                    if (h.length() > 0) hosts.add(h);
                }
            }
            if (net.optBoolean("declared", false)) has = true;
        }
        // origin 一定要在表里。这一条防的是「适配器忘了写 net」这种低级错误：
        // 忘了写就整站直连，而站点入口恰好是被墙的那个 —— 症状是加源直接超时，
        // 用户完全看不出原因。
        if (has && origin != null) {
            String host = hostOf(origin);
            if (host.length() > 0 && !hosts.contains(host)) hosts.add(host);
        }
        PROXY_HOSTS = hosts;
        declared = has;
    }

    /**
     * 从 URL 取小写 host。取不到返回空串。
     *
     * 这一段是本类唯一的字符串活，所以就地写 —— 不借用任何适配器的静态方法。
     * 之前这里调的是 {@code YhdmSite.hostOf}：路由层反过来依赖一个具体适配器，
     * 意味着「删掉樱花动漫」会连带编译不过，而它们本来毫无关系。
     */
    private static String hostOf(String url) {
        try {
            String h = new java.net.URI(url.trim()).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    /** 当前源有没有声明路由。没声明时调用方退回老规则。 */
    static boolean declared() {
        return declared;
    }

    /**
     * 这个 host 是否必须直连（摘掉代理、绕开中继）。
     *
     * <p>判定顺序：
     * <ol>
     *   <li>源声明了 net.proxy → 在表里才走代理，其余全部直连</li>
     *   <li>源没声明 → 命中 {@link DirectHosts} 直连，其余按全局开关</li>
     * </ol>
     *
     * @param host 小写域名
     */
    static boolean directOnly(String host) {
        if (host == null || host.length() == 0) return false;
        String h = host.toLowerCase(Locale.ROOT).trim();
        boolean direct;
        if (declared) {
            direct = !matches(PROXY_HOSTS, h);
        } else {
            direct = DirectHosts.isDirect(h);
        }
        trace(h, direct);
        return direct;
    }

    /**
     * 每个 host 只打一次路由日志。
     *
     * 这一行看着多余，但今天排查那 75 秒超时全靠它：日志里只有
     * 「Failed to connect to yhdm.one/[IPv6]」，看不出是路由判错了还是代理没生效。
     * 有了这一行，「这个域走的是直连还是代理」就是明确的。
     *
     * 用 ConcurrentHashMap 而不是 synchronized Set：这条路径是热路径
     * （每个请求都走），锁争用会拖慢首屏。
     */
    private static final java.util.Map<String, Boolean> LOGGED =
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>();

    private static void trace(String host, boolean direct) {
        if (LOGGED.putIfAbsent(host, direct) == null) {
            UserSource.log("路由 " + host + " → " + (direct ? "直连" : "代理"));
        }
    }

    /**
     * 这个 host 是否**必须**走代理（源声明了 net.proxy 且在表里）。
     *
     * <p>VlcRelay 用它：只有明确该走代理的地址才值得绕一趟本地中继，
     * 直连的直接把原始地址交给 VLC，省一跳。
     */
    static boolean mustProxy(String host) {
        if (host == null || host.length() == 0) return false;
        String h = host.toLowerCase(Locale.ROOT).trim();
        if (declared) return matches(PROXY_HOSTS, h);
        // 没声明：沿用老语义 —— 只要全局代理开着，非直连域就该走代理。
        if (AiiHttp.effectiveProxy().length() == 0) return false;
        return !DirectHosts.isDirect(h);
    }

    /** 子域后缀匹配。表里写 yhdm.one，www.yhdm.one 也算。 */
    private static boolean matches(List<String> list, String host) {
        for (int i = 0; i < list.size(); i++) {
            String entry = list.get(i);
            if (host.equals(entry) || host.endsWith("." + entry)) return true;
        }
        return false;
    }

    /** 给日志用：当前路由的一句话摘要。 */
    static String describe() {
        if (!declared) return "未声明（老规则：DirectHosts + 全局开关）";
        return "代理域 " + PROXY_HOSTS.size() + " 个，其余直连：" + PROXY_HOSTS;
    }
}
