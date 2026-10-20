package com.huangguo.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.InetSocketAddress;
import java.net.Proxy;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.Authenticator;
import okhttp3.Credentials;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * AIIvideo —— HTTP 层（OkHttp）。
 *
 * 原来这里用 HttpURLConnection，为了加 DoH 折腾过一版，撞墙了：入口域名在
 * Cloudflare 后面，必须发对 SNI 才握手成功，而「连 A 地址、握手时报 B 域名」
 * 这件事 HttpURLConnection 做不到 —— 它只会拿 URL 里的 host 去建 SSLSocket，
 * 那个 host 被换成了 IP，Android 对 IP 字面量不发 SNI，事后用
 * SSLParameters.setServerNames() 覆盖也不生效（实测 verifier 收到的一直是 IP，
 * 证书全被判不匹配）。日志长这样：
 *
 *     SSLPeerUnverifiedException: Hostname 116.131.57.65 not verified
 *
 * OkHttp 把这两件事分开了：URL 保留域名 → 握手自然带对 SNI；只有**解析**这一步
 * 交给 Dns 接口替换。所以同一个 URL，DoH 解析和多 IP 重试都能挂上去，SNI 不受影响。
 *
 * ── 关于 DoH 到底有多大用 ────────────────────────────────────────────
 * 实测结论（2026-10-16，真机 Redmi 23113RKC6C）：
 *
 *   墙外 DoH   8.8.8.8 / 1.1.1.1 / 9.9.9.9   全部 6 秒超时，连不上
 *   墙内 DoH   阿里 223.5.5.5、腾讯 120.53.53.53 通
 *
 * 但墙内 DoH 绕不开污染 —— 它只保护「设备到 223.5.5.5」这一段，阿里自己向上
 * 递归那一段照样被污染，然后把毒答案通过加密通道完好地递过来。对 huangguoai.com
 * 阿里给 108.160.163.108（Dropbox 段）、腾讯给 104.244.46.63（Twitter 段），
 * 每次还不一样；而同一个服务器问 cab3f.nt17g20k.cc 就能正确返回那五个 IP。
 *
 * 所以 DoH 不是「绕过污染的银弹」，它的实际价值是：
 *   1. 拿到某个域名的**全部** IP 而不是系统给的那一个，坏一个能换下一个
 *   2. 为「入口域名被墙了换一个」留出可配置的接口
 * 而当前这个 origin（cab3f.nt17g20k.cc）在手机系统解析上是干净的，
 * 所以 enabled 默认就是 false —— 没有要修的东西，开着只是多一跳。
 *
 * ── 编译 ────────────────────────────────────────────────────────────
 * 这份用 Java 写，build.sh 负责编译成 classes5.dex：
 *   javac（android.jar + okhttp/okio/kotlin-stdlib）→ d8 → unknown/classes5.dex
 * OkHttp 4.10.0 是 Kotlin 写的，所以编译期需要 okio 与 kotlin-stdlib 的 jar；
 * 运行期这两个包里本来就有（红果源在用），不额外增加体积。
 */
public final class AiiHttp {

    private static final int CONNECT_TIMEOUT = 8;
    private static final int READ_TIMEOUT = 15;
    private static final int DOH_TIMEOUT = 5;
    private static final long TTL_OK = 10 * 60 * 1000L;
    private static final long TTL_FAIL = 60 * 1000L;

    /** 解析缓存：host → IP 列表 + 到期时刻。 */
    private static final class Entry {
        final List<String> ips;
        final long expireAt;
        Entry(List<String> ips, long expireAt) { this.ips = ips; this.expireAt = expireAt; }
    }

    private static final ConcurrentHashMap<String, Entry> CACHE = new ConcurrentHashMap<String, Entry>();
    private static volatile OkHttpClient client;

    private static volatile Context appContext;

    // ── 代理 ──────────────────────────────────────────────────────────
    //
    // 给有机场的用户用：填一个 host:port，所有请求走它。
    // 支持三种写法：host:port（HTTP 代理）/ http://host:port / socks5://host:port
    //
    // 为什么值得做：实测这条链路六次里四次 9~18 秒（cf-ray 落在 LAX），
    // 而这是客户端唯一能改变路由的手段 —— DoH、连接复用、预热都只是把
    // 已经确定的那段延迟挪个位置，换路由才是换一条路。
    private static final String NET_STORE = "aii-net";
    private static final String PROXY_KEY = "proxy";
    private static volatile String appliedProxy = null;

    /**
     * 真正拿去建客户端的那个代理串。跟 appliedProxy 的区别：后者只记「用户手填的」，
     * 因为 setProxy() 改的也是它 —— 两者混用的话，内置核心一启动，
     * 「用户填的代理」这个事实就丢了，阶段 4 的设置页读回 proxyRaw() 会读出一个
     * 用户从没填过的 127.0.0.1 端口号。
     */
    private static volatile String appliedEffective = null;

    // ── 代理来源的优先级 ──────────────────────────────────────────────
    //
    // 两个来源：内置核心起的本地 SOCKS5，和用户手填的外部地址。
    // 内置优先 —— 用户点「开启」就是明确要用它，手填的那条只是他没删掉的历史配置。
    //
    // **回落语义要想清楚再改：** 内置核心没跑（含启动失败）时这里回落，不是置空。
    // 用户填了外部代理就还走那条，什么都没填才是直连。所以「核心挂了」不等于
    // 「流量从本地 IP 出去」，除非他本来就没配过外部代理。
    // 失败时的报文由 UI 负责（ProxyCore.lastError()），这一层只决定走哪条管道路由。
    //
    // 反过来说，「只要内置、不许回落」是个合理的诉求 —— 那是阶段 4 的设置项，
    // 不是这里的判断。这里一旦硬编码成「内置挂了就直连」，用户会以为代理还生效着。
    //
    // 别把这段逻辑读进 proxyRaw()：它代表「用户填了什么」，是这个类对外唯一的
    // 那个事实来源，混进来之后阶段 4 就没法把两个概念分开显示了。
    /**
     * 优先级：**直连 > 内置核心 > 外部地址**。
     *
     * 之前的实现只有一句「内置非空就用内置，否则用手填的」—— 它压根不看模式，
     * 于是「直连」只是「把核心停掉」，盘上那个手填地址照样生效：
     * 设置页写着「所有请求都不走代理」，实际每条请求都在往 127.0.0.1:7897 上撞。
     * 拔了那台电脑 / 关掉那个客户端，全站请求失败，而界面还在说"直连"。
     *
     * 现在模式是开关，「直连」真的直连：
     *   direct   → 空串。用户按了直连，就不该还有东西偷偷走代理
     *   external → 手填地址
     *   builtin  → 内置核心；核心没起来回落到手填地址（见 EffectiveProxyTest：
     *              核心起不来不等于用户配的外部代理作废）
     * 读不到模式（init 之前 / 老调用点）时退回老规矩：内置优先、否则手填。
     */
    static String effectiveProxy(String mode, String builtin, String raw) {
        String core = builtin == null ? "" : builtin;
        String manual = raw == null ? "" : raw;
        if (ProxyConsole.MODE_DIRECT.equals(mode)) return "";
        if (ProxyConsole.MODE_EXTERNAL.equals(mode)) return manual;
        return core.length() > 0 ? core : manual;
    }

    /** 当前模式。拿不到 context 返回空串，让上面那条退回老规矩。 */
    static String proxyModeOrEmpty() {
        Context context = appContext;
        if (context == null) return "";
        try {
            return ProxyConsole.proxyMode(context);
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 进程级代理覆盖。**只有 :spider 服务进程会用到**（SpiderService.call 每次请求
     * 前设置）：内置核心的本地 SOCKS 地址是主进程内存里的值，SharedPreferences
     * 里没有它，独立进程自己读永远读出空 —— 不传这个覆盖，「源声明要走代理」
     * 的源在隔离进程里全部退化成直连。主进程不设置（null），行为与原来完全一致。
     */
    private static volatile String processProxyMode;
    private static volatile String processProxyEffective;

    /** 给 SpiderService 用的注入点。两个值都来自主进程调用那一刻的快照。 */
    static void applyProcessProxyOverride(String mode, String effective) {
        processProxyMode = mode == null ? "" : mode;
        processProxyEffective = effective == null ? "" : effective;
    }

    /**
     * 当前的代理串。每次建客户端时问一次，所以核心启停之后必须
     * invalidateClients()，否则旧客户端会一直用着上一个代理。
     */
    static String effectiveProxy() {
        // 进程覆盖优先：:spider 进程没有核心、SharedPreferences 里也没有内置地址，
        // 主进程传来的快照是那里唯一的代理事实来源。语义与 effectiveProxy(mode,…)
        // 一致：direct 就是空串，否则用主进程算好的那条。
        if (processProxyEffective != null) {
            if (ProxyConsole.MODE_DIRECT.equals(processProxyMode)) return "";
            return processProxyEffective;
        }
        return effectiveProxy(proxyModeOrEmpty(), builtinProxy(), proxyRaw());
    }

    /**
     * 内置核心的地址，空串表示没跑或起不来。
     *
     * 取不到 context 就直接当没跑：init() 之前不会有请求，真走到了说明调用顺序
     * 出了问题，这时候把「内置不可用」当结论比抛异常安全 —— 代理配置永远不该
     * 成为整个 App 的启动阻塞点。
     */
    private static String builtinProxy() {
        Context context = appContext;
        if (context == null) return "";
        try {
            return ProxyCore.get(context).socksUrl();
        } catch (Throwable ignored) {
            return "";
        }
    }

    // ── 预热（②） ─────────────────────────────────────────────────────
    //
    // 解决的问题：冷启动第一屏要走「DoH 解析 → TLS 握手 → 首字节」这条链，
    // 实测 TLS 单段就是 0.4~3.0 秒。而这几段跟我们自己的启动流程**没有依赖关系**，
    // 完全可以重叠：读完配置就在后台开火，等 WebView 起来、源配置闸门过完、
    // goHome() 真去要数据的时候，多半已经拿回来了。
    //
    // 只预热 /api/home 一条 —— 那是首屏唯一必须的。别的都不确定用户会不会点。
    private static final String WARMUP_PATH = "/api/home";
    private static final long PREFETCH_TTL = 60 * 1000L;

    private static final class Prefetch {
        final byte[] body;
        final long at;
        Prefetch(byte[] body) { this.body = body; this.at = System.currentTimeMillis(); }
        boolean fresh() { return System.currentTimeMillis() - at < PREFETCH_TTL; }
    }
    private static final ConcurrentHashMap<String, Prefetch> PREFETCH = new ConcurrentHashMap<String, Prefetch>();

    // 正在飞的那一次预热。前端来要同一条时**等它**，而不是另开一次请求。
    //
    // 第一版没这个，结果是两路并发：前端发一次真的、预热发一次，谁先回来用谁的。
    // 实测前端的真请求在预热开始后约 300ms 就发出去了（闸门和 __source:web 那几步
    // 很快），而预热要跑 1080ms —— 于是真请求先完成，预热白跑一趟，字节丢掉。
    // 等它的话总共只发一次请求，拿到的还是最早开始的那次。
    private static final class Warmup {
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final long at = System.currentTimeMillis();
        volatile byte[] body;
        /** 跟 Prefetch 一个道理：这份字节只在 PREFETCH_TTL 内算数。 */
        boolean fresh() { return System.currentTimeMillis() - at < PREFETCH_TTL; }
    }
    private static final ConcurrentHashMap<String, Warmup> WARMING = new ConcurrentHashMap<String, Warmup>();
    private static final int WARMUP_WAIT_SECONDS = 12;

    /**
     * 换源计数器。预热线程开工时记下代次，回来对不上就把字节丢掉。
     *
     * 预热是后台线程，跑完的时候源可能已经换了（换源只重建 Activity，进程还活着）。
     * 不做这一下的话，旧源那几百毫秒后落进 PREFETCH，新源的首页正好拿去用。
     */
    private static final java.util.concurrent.atomic.AtomicInteger GENERATION =
            new java.util.concurrent.atomic.AtomicInteger(0);

    // ── DoH 结果的磁盘缓存（③） ───────────────────────────────────────
    //
    // 内存里那份 TTL 十分钟，但**冷启动就没了**，每次都要重问一遍 DoH —— 而 DoH
    // 在整条链的最前面，它慢 300ms 后面全跟着慢。落到 SharedPreferences 之后
    // 冷启动直接拿上次的地址开连，同时把这次的结果写回去。
    //
    // TTL 给 30 分钟：Cloudflare 的 anycast 地址不会几分钟就换，但也不是永久 ——
    // 域名换 CDN、运营商的就近路由变了都会让旧地址变差。过了期就重新问。
    private static final String DNS_STORE = "aii-dns";
    private static final long DISK_TTL = 30 * 60 * 1000L;
    private static final String SEP = "\u0001";


    private AiiHttp() {}

    // ── 对外入口 ──────────────────────────────────────────────────────

    /**
     * 取回 url 的全部响应体。
     * 签名与改造前的 SiteRepository.fetch 对齐，异常语义也一样（抛 Exception）。
     *
     * 2026-10-19：适配器分流那一段（{@code /api/...} 交给各站自己的 Java 实现）
     * 整段撤掉。现在剩下的两种源是：手写 REST 配置（origin + paths，直接拼地址）
     * 与 spider 源（前端经 spider 桥取内容，根本不经过这里）。
     */
    public static byte[] get(String url, SiteProfile profile) throws Exception {
        // 每次请求都记下目标主机。SiteProfile 的 origin 到底有没有被用户配置换掉，
        // 看这一行比往 smali 里插探针可靠 —— 探针写错会把整个类搞成 VerifyError，
        // 而且探针本身没验证过，输出不能当证据。
        // 有预热在飞就等它 —— 那一次比这次早开始，等它比另开一次快，而且总量上
        // 只发一次请求。等不到（失败/超时）就照常自己发。
        final String hotKey = prefetchKey(url);
        Warmup inflight = WARMING.get(hotKey);
        if (inflight != null) {
            long waited = System.currentTimeMillis();
            boolean settled = false;
            try {
                settled = inflight.done.await(WARMUP_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (settled && inflight.body != null && inflight.fresh()) {
                log("等到预热结果 " + pathOfUrl(url) + " " + inflight.body.length + " 字节，等了 "
                        + (System.currentTimeMillis() - waited) + "ms");
                return inflight.body;
            }
            log("预热没等到（settled=" + settled + "），自己发一次");
        }

        Prefetch hot = PREFETCH.remove(hotKey);
        if (hot != null) {
            if (hot.fresh()) {
                log("预热结果直接命中 " + pathOfUrl(url) + " " + hot.body.length + " 字节");
                return hot.body;
            }
            log("预热结果已过期，照常请求 " + pathOfUrl(url));
        }
        return fetchUncached(url, profile);
    }

    /**
     * 判定「这条连接被中途掐断」。2026-10-17 实测：api.agedm.io 走 Cloudflare，
     * 手机网络上 HTTP/2 会在 1.5 秒左右被重置（unexpected end of stream），而同一条
     * 网络里 HTTP/1.1 完全正常 —— 典型是中间设备不认 h2。这类错误重试同一协议
     * 没有意义，只能换协议。
     */
    static boolean isStreamBreak(Throwable error) {
        Throwable t = error;
        while (t != null) {
            // SocketTimeoutException 也算：h2 的连接池里挂着一条其实已经死掉的链路时，
            // 表现就是读完超时（实测 AGE 的播放解析被这个卡住，同一条网络 curl 0.2 秒）。
            if (t instanceof java.net.SocketTimeoutException) return true;
            String m = t.getMessage() == null ? "" : t.getMessage().toLowerCase();
            if (m.contains("unexpected end of stream") || m.contains("stream was reset")
                    || m.contains("connection reset") || m.contains("broken pipe")
                    || m.contains("protocol error") || m.contains("timeout")) return true;
            t = t.getCause();
        }
        return false;
    }

    /** 真正的网络取数。get() 负责分流、预热命中、然后调它。 */
    private static byte[] fetchUncached(String url, SiteProfile profile) throws Exception {
        log("GET " + hostOfUrl(url) + pathOfUrl(url));
        try {
            return getWith(client(profile), url, profile);
        } catch (Exception e) {
            // 这里必须是 Exception，不能是 IOException。
            //
            // getWith → rawWith 的签名是 `throws Exception`，它会把 OkHttp 的错误
            // 包成 java.lang.Exception 再抛出来。原来 catch IOException 永远不命中，
            // 于是「降级重试」这条路径**一次都没执行过** —— 日志里只有
            // `cap=home fail: unexpected end of stream`，从来没有「降级 HTTP/1.1」。
            // 快速切页时每个请求都撞一次、每次都直接失败，用户看到的就是卡住。
            if (!isStreamBreak(e)) throw e;
            log("HTTP/2 被重置，降级 HTTP/1.1 重试 " + pathOfUrl(url));
        }
        // 降级之后再给两次机会。
        //
        // 2026-10-19 真机实测：`unexpected end of stream` 在 5G 上会连着出现
        // （一次测试里 4 个请求中了 3 个）。只降级一次的话，第二次又撞上同一条
        // 死链路就直接报「AGE动漫 请求失败」，用户看到的是内容停在缓存不再更新 ——
        // 体感就是「点了没反应」。
        //
        // 中间让出 120ms：坏连接要一点时间才会被 OkHttp 的连接池剔掉，
        // 立刻重试大概率还是它。
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return getWith(http1Client(profile), url, profile);
            } catch (Exception e) {
                boolean last = attempt == 1;
                log("HTTP/1.1 重试 " + (attempt + 1) + " 失败：" + e.getMessage() + (last ? "（放弃）" : ""));
                if (last || !isStreamBreak(e)) throw e;
                try {
                    Thread.sleep(120);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw new IOException("请求失败：" + pathOfUrl(url));
    }

    private static byte[] getWith(OkHttpClient http, String url, SiteProfile profile) throws Exception {
        return getWith(http, url, null, profile);
    }

    /**
     * 带调用方自定义头的 GET。**走和适配器同一条路由**（{@link NetRoute} + 自洽性兜底）。
     *
     * <p>存在的理由：spider 运行时（{@code __spider:net}）要能带 Referer / UA 抓页面，
     * 而 {@code getRaw} 那一族用的是 {@link #effectiveRoute()}（全局代理设置），
     * 完全不知道「当前源声明哪些域该走代理」。2026-10-19 真机就栽在这上面：
     * 内置核心还没起来的那 0.7 秒里，spider 的 net 按全局设置判成直连，直连撞上
     * 被污染的 IPv6，一个 60 秒的超时串烧（{@code failed to connect to
     * yhdm.one/2001::caa0:…}）。
     *
     * <p>所以这条走 {@link #client(SiteProfile)} + {@code NetRoute.mustProxy} 的
     * 自洽补丁，和适配器取数据一模一样 —— 路由只该有一个真相。
     */
    static byte[] getRaw(String url, java.util.Map<String, String> headers, int timeoutSeconds,
                         SiteProfile profile) throws Exception {
        return getRaw(url, headers, timeoutSeconds, profile, null);
    }

    /**
     * 走源路由的带自定义头 GET，**额外把 {@code Set-Cookie} 收进 {@code cookieOut}**。
     *
     * <p>三参版本原样保留成一行转发（行为不变），真正干活的是这里：多出来的只有
     * 那个 {@code cookieOut}，路由、超时、h2→h1 降级一个字没动。
     *
     * <p>为什么必须走这条而不是 {@code getRaw(url, headers, timeout, cookieOut)}：
     * 后者问的是全局代理设置，不懂「当前源声明哪些域该走代理」。spider 的
     * {@code net} 在两个重载之间选，选错就是「能拿到 cookie 的那次请求走了错误的
     * 路由」——91porn 这种站每次请求都要带标记，那次请求挂了等于整页都挂了。
     */
    static byte[] getRaw(String url, java.util.Map<String, String> headers, int timeoutSeconds,
                         SiteProfile profile, java.util.Map<String, String> cookieOut) throws Exception {
        log("GET " + hostOfUrl(url) + pathOfUrl(url));
        OkHttpClient http = client(profile);
        if (timeoutSeconds > 0) {
            OkHttpClient.Builder timed = new OkHttpClient.Builder()
                    .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .readTimeout(timeoutSeconds, TimeUnit.SECONDS);
            if (http.dns() != null) timed.dns(http.dns());
            if (http.proxy() != null) timed.proxy(http.proxy());
            if (http.proxyAuthenticator() != null) timed.proxyAuthenticator(http.proxyAuthenticator());
            http = timed.build();
        }
        // h2 被中途掐断就降级 h1 重试一次 —— 和 fetchUncached 同一条规矩。
        // 出口节点抖的时候实测就是这个形态：18 秒后 Connection reset，重试才过。
        try {
            return getWith(http, url, headers, profile, cookieOut);
        } catch (IOException e) {
            if (!isStreamBreak(e)) throw e;
            log("HTTP/2 被重置，降级 HTTP/1.1 重试 " + pathOfUrl(url));
            OkHttpClient.Builder h1 = new OkHttpClient.Builder()
                    .connectTimeout(http.connectTimeoutMillis(), TimeUnit.MILLISECONDS)
                    .readTimeout(http.readTimeoutMillis(), TimeUnit.MILLISECONDS);
            if (http.dns() != null) h1.dns(http.dns());
            if (http.proxy() != null) h1.proxy(http.proxy());
            if (http.proxyAuthenticator() != null) h1.proxyAuthenticator(http.proxyAuthenticator());
            h1.protocols(java.util.Collections.singletonList(okhttp3.Protocol.HTTP_1_1));
            return getWith(h1.build(), url, headers, profile, cookieOut);
        }
    }

    private static byte[] getWith(OkHttpClient http, String url,
                                  java.util.Map<String, String> headers, SiteProfile profile) throws Exception {
        return getWith(http, url, headers, profile, null);
    }

    /**
     * 同上，外加把 {@code Set-Cookie} 收进 {@code cookieOut}（null 就是不要）。
     *
     * <p>四参版本原样保留成一行转发，行为不变；真正干活的在这里。加 {@code cookieOut}
     * 只多了一句 {@link #harvestCookies}，请求的构造、状态码判据全部照旧，
     * 分流与失败自愈统一走 {@link #routeAndSend}。
     */
    private static byte[] getWith(OkHttpClient http, String url, java.util.Map<String, String> headers,
                                  SiteProfile profile, java.util.Map<String, String> cookieOut) throws Exception {
        Request.Builder builder = new Request.Builder().url(url);
        applyHeaders(builder, profile, url);
        if (headers != null) {
            for (java.util.Map.Entry<String, String> e : headers.entrySet()) builder.header(e.getKey(), e.getValue());
        }
        // 不要压缩。这条链路本来就慢，多一次解压不值当，也让响应体大小可预期。
        builder.header("Accept-Encoding", "identity");
        return routeAndSend(http, builder.build(), url, cookieOut, profile);
    }


    /**
     * 记住 application context —— 磁盘缓存要用它。幂等，每次启动调一次即可。
     */
    public static void init(Context context) {
        if (context != null && appContext == null) {
            appContext = context.getApplicationContext();
            // 路由记忆的落盘位置。两个进程（主 + :spider）都各自 init 一遍，
            // 文件 + mtime 检查保证两边读到同一份。
            RouteMemory.init(new java.io.File(appContext.getFilesDir(), "route-memory.json"));
            // 进程起来就把老的「手填地址 + 默认直连」迁成 external。
            // 不能等用户打开设置页才迁 —— 那之前所有请求都会变成直连，
            // 用户看到的是「升级完就全站连不上了」。
            ProxyConsole.migrateProxyMode(appContext);
        }
    }

    /**
     * 后台预热首屏。
     *
     * 在源配置刚读出来的时候调用（UserSource.handle 的 get 分支），这时 WebView
     * 还在载入、设置页闸门还没过完、goHome() 还没发请求。等那一切就绪，这边
     * 「DoH → TLS → 首字节」那条链多半已经跑完了。
     *
     * 拿到的字节留着，前端真来要 /api/home 时直接给（60 秒内有效）。
     * 失败什么都不影响 —— 前端会照常发一次真请求。
     */
    public static void warmup(final SiteProfile profile) {
        if (profile == null || appContext == null) return;
        final String hotKey = prefetchKey(warmPath(profile));
        final int gen = GENERATION.get();
        Prefetch cached = PREFETCH.get(hotKey);
        if (cached != null && cached.fresh()) return;            // 刚预热过，别重复
        Warmup previous = WARMING.get(hotKey);
        if (previous != null) {
            if (previous.fresh()) return;                        // 在飞的 / 刚完成的
            WARMING.remove(hotKey, previous);                    // 过期的腾位子
        }
        final Warmup warm = new Warmup();
        if (WARMING.putIfAbsent(hotKey, warm) != null) return;   // 已经有人在预热了
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    long started = System.currentTimeMillis();
                    byte[] body = prefetchBody(profile);
                    // 代次对不上 = 跑到一半换源了，这份字节属于上一个源，丢掉。
                    // 不丢的话它几百毫秒后落进 PREFETCH，新源的首页正好拿去用。
                    if (body != null && body.length > 0 && gen == GENERATION.get()) {
                        warm.body = body;
                        PREFETCH.put(hotKey, new Prefetch(body));
                        log("预热完成 " + warmPath(profile) + " " + body.length + " 字节 "
                                + (System.currentTimeMillis() - started) + "ms");
                    } else if (body != null && body.length > 0) {
                        log("预热作废（期间换过源）" + body.length + " 字节");
                    }
                } catch (Throwable e) {
                    log("预热失败（前端会照常发真请求）：" + e.getClass().getSimpleName() + " " + e.getMessage());
                } finally {
                    // 无论成败都要放行，否则等在这条上的人会一直等到超时。
                    warm.done.countDown();
                }
            }
        }, "aii-warmup");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 换源之后必须调一次：把**绑在源上**的进程内状态全部作废。
     *
     * 为什么必须有这一步：换源走的是 `MainActivity.recreate()` —— 只重建 Activity，
     * **进程活着**。而 WARMING / PREFETCH / 连接池都是 static，于是新源会拿到旧源的东西。
     * 实测症状：从 cms-xml 切回黄果，首页立刻弹「内容更新失败，正在显示缓存」，日志是
     *
     *     [http] 等到预热结果 /api/home 5164 字节，等了 0ms
     *
     * 那 5164 字节是上一个源的首页 —— WARMING 里躺着的、永远不过期的那一份。
     * （WARMING 里放的是 Warmup 对象，本来只当「完成信号」用，但它带着 body，
     *   而 get() 先查 WARMING 再查 PREFETCH —— 结果它事实上成了一层没有 TTL、
     *   也不区分来源的缓存。）
     *
     * 连接池同理：client() 里 `new SmartDns(profile)` 把 profile 捕获进去了，
     * 缓存下来的那个客户端是绑在旧源上的。
     */
    public static void resetForSourceChange() {
        int wasWarming = WARMING.size();
        int wasPrefetch = PREFETCH.size();
        GENERATION.incrementAndGet();
        WARMING.clear();
        PREFETCH.clear();
        invalidateClients();
        log("换源：清掉预热 " + wasPrefetch + " 条、在飞 " + wasWarming + " 条，连接池作废");
    }

    /**
     * 预热的键 —— 把「完整 URL」和「内部路径」归一到同一个。
     *
     * 这一步踩过：预热存的是 /api/home（内部路径），而前端来要的是
     * https://cab3f.nt17g20k.cc/api/home（SiteRepository 拼过 origin 的完整地址）。
     * 两边键不一样，于是 WARMING.get() 永远返回 null，等预热的逻辑一次都没走到 ——
     * 表现是两路并发，日志里两条 GET，预热白跑。
     */
    private static String prefetchKey(String url) {
        if (url == null) return "";
        if (url.startsWith("/api/")) return url;
        int scheme = url.indexOf("://");
        if (scheme < 0) return url;
        int slash = url.indexOf('/', scheme + 3);
        return slash < 0 ? url : url.substring(slash);
    }

    /**
     * 预热要取的那一坨。
     *
     * 不能直接把 /api/home 丢给 fetchUncached —— 那是个**内部路径**，不是 URL，
     * 所以这里拼成完整地址再取：
     *   · rest：origin + 路径
     *   · spider 源没有 origin，下面那条闸会直接拒（前端根本不走预热这条路）
     */
    private static byte[] prefetchBody(SiteProfile profile) throws Exception {
        String path = warmPath(profile);
        // ⚠️ **只预热首页。** 有些站的播放地址带一次性签名、会过期，预热出来的
        //    地址等用户真去点的时候可能已经废了 —— 那种地址必须实时取。
        //    首页没有这个问题（它只是元数据），预热是安全的。
        // 绝对地址直接用，相对路径才拼 origin —— 和 SiteRepository.detail 那条规矩一致。
        if (path.startsWith("https://")) return fetchUncached(path, profile);
        String origin = profile == null ? null : profile.origin;
        if (origin == null || origin.length() == 0) throw new IOException("配置里没有 origin，不预热");
        return fetchUncached(origin + path, profile);
    }

    /**
     * 预热哪一条：**配置里 paths.home 写的是什么，就预热什么**。
     *
     * 上一版这里写死 `/api/home`。对内置那套和黄果源凑巧是对的（两个源的
     * paths.home 都叫 /api/home），但它押的是一个巧合 —— 配置里把 home 指到
     * 别处（或者干脆是一条绝对地址），预热就会去敲一个不存在的地址，白打一次，
     * 前端等不到预热结果还是得自己再发一次，预热等于没有。
     *
     * 键的归一化在 prefetchKey 里做，两边（预热存的、get() 来取的）走的是同一个
     * 函数，所以路径写什么形式都对得上。
     */
    private static String warmPath(SiteProfile profile) {
        try {
            JSONObject web = profile == null ? null : profile.object("web");
            JSONObject paths = web == null ? null : web.optJSONObject("paths");
            String home = paths == null ? "" : paths.optString("home", "");
            if (home.length() > 0) return home;
        } catch (Throwable ignored) {}
        return WARMUP_PATH;
    }

    // ── DoH 结果的磁盘缓存 ────────────────────────────────────────────

    private static SharedPreferences store() {
        Context context = appContext;
        return context == null ? null : context.getSharedPreferences(DNS_STORE, Context.MODE_PRIVATE);
    }

    private static void remember(String host, List<String> ips) {
        SharedPreferences store = store();
        if (store == null || ips == null || ips.isEmpty()) return;
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ips.size(); i++) {
                if (sb.length() > 0) sb.append(';');
                sb.append(ips.get(i));
            }
            store.edit().putString(host, System.currentTimeMillis() + ";" + sb).apply();
        } catch (Throwable ignored) {}
    }

    private static List<String> recall(String host) {
        SharedPreferences store = store();
        if (store == null) return null;
        try {
            String raw = store.getString(host, null);
            if (raw == null) return null;
            String[] parts = raw.split(";");
            if (parts.length < 2) return null;
            if (System.currentTimeMillis() - Long.parseLong(parts[0]) > DISK_TTL) return null;
            List<String> ips = new ArrayList<String>();
            for (int i = 1; i < parts.length; i++) if (parts[i].length() > 0) ips.add(parts[i]);
            return ips.isEmpty() ? null : ips;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 磁盘里那份直接拿来用，同时**后台重问一次**。
     *
     * 不这么做的话，一旦地址真的变了，用户会一直连到旧地址上失败 —— 磁盘这份
     * 是「省一次 DoH」，不是「永远信它」。后台刷新保证下一次冷启动拿到的是新的。
     */
    private static void refreshInBackground(final String host, final SiteProfile profile) {
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    List<String> fresh = lookupDoh(host, profile);
                    if (fresh.isEmpty()) return;
                    CACHE.put(host, new Entry(fresh, System.currentTimeMillis() + TTL_OK));
                    remember(host, fresh);
                    log("DoH 后台刷新 " + host + " → " + fresh.size() + " 个地址");
                } catch (Throwable ignored) {}
            }
        }, "aii-doh-refresh");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 朴素的 GET，不带 profile、不走 DoH、不套 headers 模板。
     *
     * 给 CMS 适配器用：那边要拼自己的查询参数，也不该继承「我们站点」的请求头。
     * DNS 走系统 —— CMS 地址是用户自己填的，我们没有它的 DoH 配置。
     */
    /**
     * 强行走 HTTP/1.1 的朴素 GET，**不尝试 HTTP/2**。
     *
     * 给探针用：mrds66 的 HTTP/2 会被反复重置，OkHttp 降级过程要花掉整个超时窗口，
     * 导致探针在 20 秒内永远完不成。直接走 h1 省掉这一轮折腾。
     */
    public static byte[] getRawHttp1(String url, int timeoutSeconds) throws Exception {
        log("GET(h1) " + hostOfUrl(url) + pathOfUrl(url));
        Request request = new Request.Builder()
                .url(url)
                .header("Accept-Encoding", "identity")
                .build();
        return rawWith(plainHttp1Client(timeoutSeconds), request, url);
    }

    public static byte[] getRaw(String url, int timeoutSeconds) throws Exception {
        log("GET " + hostOfUrl(url) + pathOfUrl(url));
        Request request = new Request.Builder()
                .url(url)
                .header("Accept-Encoding", "identity")
                .build();
        try {
            return rawWith(plainClient(timeoutSeconds), request, url);
        } catch (IOException e) {
            if (!isStreamBreak(e)) throw e;
            log("HTTP/2 被重置，降级 HTTP/1.1 重试 " + pathOfUrl(url));
            return rawWith(plainHttp1Client(timeoutSeconds), request, url);
        }
    }

    private static byte[] rawWith(OkHttpClient http, Request request, String url) throws Exception {
        return routeAndSend(http, request, url, null, null);
    }

    // ── 路由判定 + 失败自愈（方案 A，2026-10-19）──────────────────────
    //
    // 优先级：NetRoute 强制分支（directOnly / 声明源 mustProxy，作者显式，失败也不
    // 换路）> RouteMemory 记忆 > 老规则兜底。第一次尝试用初始路由；连接层失败
    // （超时 / reset / TLS / 域名解析）换另一条路重试一次，成功即 RouteMemory.learn。
    // HTTP 4xx/5xx 是站点自己的问题，不换路不学习。

    /**
     * 这个 URL 的初始路由。
     *
     * <p>声明的源里 {@code mustProxy=false} 的域是作者要的直连，所以声明源只认
     * forced 两支；未声明的源记忆优先于「全局代理开 → 走代理」的老兜底。
     */
    private static String initialRoute(String url) {
        final String host = hostOfUrl(url);
        if (NetRoute.directOnly(host)) return RouteMemory.ROUTE_DIRECT;
        final boolean declared = NetRoute.isDeclared();
        if (declared && NetRoute.mustProxy(host)) return RouteMemory.ROUTE_PROXY;
        final String mem = RouteMemory.opinion(host);
        if (mem != null) return mem;
        if (!declared && NetRoute.mustProxy(host)) return RouteMemory.ROUTE_PROXY;
        return RouteMemory.ROUTE_DIRECT;
    }

    /** direct ↔ proxy 互换。其他值原样返回（调用方拿它判「有没有另一条路」）。 */
    static String flipRoute(String route) {
        if (RouteMemory.ROUTE_DIRECT.equals(route)) return RouteMemory.ROUTE_PROXY;
        if (RouteMemory.ROUTE_PROXY.equals(route)) return RouteMemory.ROUTE_DIRECT;
        return route;
    }

    /**
     * 是不是「换个路由可能就好」的失败。
     *
     * <p>判据是排除式的：{@code rawWith} / {@code getWith} 对非 2xx 抛的
     * {@code IOException("HTTP <code> @ …")} 说明<strong>请求已经到达了对方</strong>
     * —— 路由是通的，失败是站点的，换路没有意义。剩下的（超时、连接被拒/重置、
     * TLS、域名解析不出来）大概率是「这条路上不去」。
     */
    static boolean isConnectFailure(IOException e) {
        if (e == null) return false;
        final String m = e.getMessage();
        return m == null || !m.startsWith("HTTP ");
    }

    /** 把路由落到 client 上：direct 现拼无代理 client（保留 SmartDns），proxy 兜 proxiedClient。 */
    private static OkHttpClient clientFor(OkHttpClient http, String route, SiteProfile profile) {
        if (RouteMemory.ROUTE_DIRECT.equals(route)) {
            return new OkHttpClient.Builder()
                    .connectTimeout(http.connectTimeoutMillis(), TimeUnit.MILLISECONDS)
                    .readTimeout(http.readTimeoutMillis(), TimeUnit.MILLISECONDS)
                    .dns(dnsFor(http, profile))
                    .build();
        }
        if (RouteMemory.ROUTE_PROXY.equals(route) && http.proxy() == null) {
            return proxiedClient(profile, http);
        }
        return http;
    }

    /**
     * 统一的「选路 → 发请求 → 失败换路重试一次 → 学习」。
     *
     * <p>两条老路径（getWith / rawWith）的分流分支收拢到这里之后行为对齐：
     * 以前 getWith 缺「代理路不可用时兜底直连」之外的一切自愈，rawWith 亦然。
     */
    private static byte[] routeAndSend(OkHttpClient http, Request request, String url,
                                       java.util.Map<String, String> cookieOut,
                                       SiteProfile profile) throws Exception {
        final String host = hostOfUrl(url);
        String route = initialRoute(url);
        IOException first = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            final OkHttpClient client = clientFor(http, route, profile);
            try {
                Response response = client.newCall(request).execute();
                try {
                    harvestCookies(response, cookieOut);
                    int code = response.code();
                    if (code < 200 || code >= 300) throw new IOException("HTTP " + code + " @ " + url);
                    ResponseBody body = response.body();
                    return body == null ? new byte[0] : body.bytes();
                } finally {
                    response.close();
                }
            } catch (IOException e) {
                final String flipped = flipRoute(route);
                final boolean canFlip = !flipped.equals(route)
                        && (!RouteMemory.ROUTE_PROXY.equals(flipped) || effectiveProxy().length() > 0);
                if (!isConnectFailure(e) || !canFlip) {
                    // 站点级失败，或者没有另一条路可换：原样上抛。
                    throw e;
                }
                if (first == null) first = e;
                log("路由自愈 " + host + "：" + route + " 失败（"
                        + (e.getMessage() != null ? e.getMessage() : e.toString()) + "），改走 " + flipped + " 重试");
                // 换路要用「新鲜」的请求体：上一次 execute 消费不掉的话 OkHttp 自己
                // 会重建，Request 本身无状态，直接复用是安全的。
                route = flipped;
            }
        }
        // 两条路都失败到「换路也上不去」：学不了（不知道哪条对），把可能过时的
        // 记忆清掉回落到规则层，抛第一个错。
        RouteMemory.forget(host);
        throw first != null ? first : new IOException("请求失败：" + url);
    }

    /**
     * 同上，外加把响应里的 `Set-Cookie` 收进 `cookieOut`（键=名字，值=值）。
     *
     * 有的站「游客标记」只在响应头里下发，正文里一个字节都没有 —— 91porn 的 `ga`
     * 就是这样：没有它，同一个 viewkey 每次请求换一部片（这套「游客标记」原来写在
     * 已删除的 Porn91Adapter 里，现在归 spider 自己处理）。这类站没别的拿法，
     * 只能从响应头剥。
     */
    private static byte[] rawWith(OkHttpClient http, Request request, String url,
                                  java.util.Map<String, String> cookieOut) throws Exception {
        return rawWith(http, request, url, cookieOut, null);
    }

    /**
     * 真正干活的那个 {@code rawWith}：自定义头之外，再带上两个跟「路由」有关的输入。
     *
     * <ul>
     *   <li>{@code cookieOut} 收 {@code Set-Cookie}（见上一段）。</li>
     *   <li>{@code profile} <b>当前源配置</b>，只为一件事存在：路由兜底要拿它建
     *       {@link SmartDns}。传 null 就等于「这次请求不知道源声明了什么」，
     *       兜底会退回 {@code like.dns()} —— 见 {@link #dnsFor}。</li>
     * </ul>
     *
     * <p><b>为什么 profile 是参数而不是静态字段</b>：{@code AiiHttp} 是给整个 App 用的，
     * 同时可能有两个源在跑（主内容 + 播放解析）。路由恰好是最不该有第二份真相的东西 ——
     * 一个静态 {@code currentProfile} 会让「A 源的请求用上 B 源的 hosts 钉死」变成
     * 一个只在并发下复现的 bug，而症状是「偶尔连到错的 IP」，日志上看不出来。
     *
     * <p>分流与失败自愈统一在 {@link #routeAndSend}（2026-10-19 方案 A）——
     * 它和 getWith 的老内联分支就「该不该换 client」做过同样的事，收拢成一处
     * 之后两个出口的行为不会再分叉。
     */
    private static byte[] rawWith(OkHttpClient http, Request request, String url,
                                  java.util.Map<String, String> cookieOut,
                                  SiteProfile profile) throws Exception {
        return routeAndSend(http, request, url, cookieOut, profile);
    }

    /**
     * 把响应里的 {@code Set-Cookie} 收进 {@code cookieOut}（键=名字，值=值，不含属性段）。
     *
     * <p>从 {@link #rawWith} 里搬出来的：GET 和 POST 都需要这段，而它的边界条件
     * （没有等号的行、名字为空的行）值得只写一遍。搬的时候<strong>逻辑一个字没动</strong>
     * —— 顺序、trim、取第一个分号前的那截，全都和原来那条内联循环一样：
     * 同名的多条 {@code Set-Cookie} 后者覆盖前者，{@code Path} / {@code Max-Age}
     * 这些属性一律不要（调用方拼请求头时只认 {@code name=value}）。
     *
     * @param cookieOut 为 null 就什么都不做（不需要 cookie 的调用方不用为此付出代价）
     */
    private static void harvestCookies(Response response, java.util.Map<String, String> cookieOut) {
        if (cookieOut == null || response == null) return;
        for (String line : response.headers("Set-Cookie")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String name = line.substring(0, eq).trim();
            String rest = line.substring(eq + 1);
            int semi = rest.indexOf(';');
            String value = (semi >= 0 ? rest.substring(0, semi) : rest).trim();
            if (name.length() > 0) cookieOut.put(name, value);
        }
    }

    /**
     * 带自定义头的朴素 GET。播放层解析要用它取播放页（对面按 User-Agent 分流）。
     */
    /**
     * 一次性拉取（订阅用）。**不复用连接池**。
     *
     * 2026-10-18 实测：同一条网络，设备上 curl 1.5 秒拿到 52KB，而走
     * plainClient 的请求卡满 20 秒后 Read timed out。普通 GET 都正常，
     * 只有这个 CDN 的地址会挂 —— 高度怀疑是 OkHttp 连接池里挂着一条
     * 已死的链路（那个域名的 keep-alive 很短），复用它就一直等。
     *
     * 所以这里每次 new 一个 client、并且禁掉连接池复用。代价是一次额外的
     * TLS 握手，换的是「一定不会踩到死连接」。订阅一天拉不了几次，
     * 这点开销无所谓。
     */
    public static byte[] getOnce(String url, int timeoutSeconds) throws Exception {
        return getOnce(url, timeoutSeconds, false);
    }

    /**
     * 代理子系统自己的下载：订阅、内置节点池。
     *
     * 这两件事**必须不受当前内容源的路由声明影响**。2026-10-20 真机踩到：
     * 源里写着 `net.proxy: []`（显式全直连），于是 `NetRoute.directOnly()` 对
     * 任何域名都返回 true，连「拉订阅」都被摘掉代理去直连 —— 而机场域名正在
     * 墙外，表现就是拉订阅一直等到超时。
     *
     * 内容源的 net 说的是「这个站的页面/CDN 怎么走」，跟代理设置里拉订阅没有
     * 半点关系。所以这条入口固定先走代理，代理不通再退回直连（自建服务、内网
     * 订阅地址走代理本来就不对）。
     */
    public static byte[] getOnceRouted(String url, int timeoutSeconds) throws Exception {
        return getOnce(url, timeoutSeconds, true);
    }

    private static byte[] getOnce(String url, int timeoutSeconds, boolean ignoreSourceRoute)
            throws Exception {
        Request request = new Request.Builder().url(url)
                .header("Accept-Encoding", "identity")
                .header("Connection", "close")
                .build();
        ProxyRoute route = ignoreSourceRoute
                ? effectiveRoute()
                : (NetRoute.directOnly(hostOfUrl(url)) ? null : effectiveRoute());
        try {
            return executeOnce(request, route, timeoutSeconds, url);
        } catch (IOException e) {
            // 代理不通就退回直连试一次：订阅地址可能是国内的自建服务，
            // 走机场节点反而绕远甚至被拒。
            if (route == null) throw e;
            log("订阅下载走代理失败（" + e.getMessage() + "），改直连重试");
            return executeOnce(request, null, timeoutSeconds, url);
        }
    }

    private static byte[] executeOnce(Request request, ProxyRoute route, int timeoutSeconds,
                                      String url) throws Exception {
        OkHttpClient.Builder oneBuilder = new OkHttpClient.Builder()
                .connectTimeout(Math.min(timeoutSeconds, 15), TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                // callTimeout 是**整条请求**的上限（DNS + 连接 + 读完 body）。
                // 只有 connect/read 两个超时的话，对方慢慢吐字节就永远读不完：
                // 真机上见过机场订阅 TTFB 9.7 秒、总耗时 10.1 秒，而桥的上限是 30 秒，
                // 中间再有点抖动就整条卡死，用户看到的就是「拉订阅 timeout」。
                .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .dns(IPV4_FIRST);
        // **必须挂用户配的代理。** 之前这里 new 完就直接 build，整条订阅/节点池的
        // 下载都绕过了代理去直连 GitHub —— 国内基本不通，表现是「节点池更新失败：
        // Read timed out」和「订阅拉取失败：failed to connect ... from 192.168.x.x」。
        // 这两个功能恰恰是最需要代理的：它们下载的地址就在墙外。
        if (route != null) route.applyTo(oneBuilder);
        OkHttpClient one = oneBuilder.build();
        Response response = one.newCall(request).execute();
        try {
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code() + " @ " + hostOfUrl(url));
            ResponseBody body = response.body();
            return body == null ? new byte[0] : body.bytes();
        } finally {
            response.close();
            one.dispatcher().executorService().shutdown();
            one.connectionPool().evictAll();
        }
    }

    public static byte[] getRaw(String url, java.util.Map<String, String> headers, int timeoutSeconds) throws Exception {
        Request.Builder builder = new Request.Builder().url(url).header("Accept-Encoding", "identity");
        if (headers != null) {
            for (java.util.Map.Entry<String, String> e : headers.entrySet()) builder.header(e.getKey(), e.getValue());
        }
        log("GET " + hostOfUrl(url) + pathOfUrl(url));
        Request request = builder.build();
        try {
            return rawWith(plainClient(timeoutSeconds), request, url);
        } catch (IOException e) {
            if (!isStreamBreak(e)) throw e;
            log("HTTP/2 被重置，降级 HTTP/1.1 重试 " + pathOfUrl(url));
            return rawWith(plainHttp1Client(timeoutSeconds), request, url);
        }
    }

    /**
     * 带自定义头的朴素 GET，**顺手把响应的 `Set-Cookie` 收进 `cookieOut`**。
     *
     * 与三参那个重载行为完全一致（同样的代理、同样的 h2→h1 降级），只是多回一份
     * cookie。给「需要先拿一个站点下发的标记，再带着它重新请求」的适配器用。
     */
    public static byte[] getRaw(String url, java.util.Map<String, String> headers, int timeoutSeconds,
                                java.util.Map<String, String> cookieOut) throws Exception {
        Request.Builder builder = new Request.Builder().url(url).header("Accept-Encoding", "identity");
        if (headers != null) {
            for (java.util.Map.Entry<String, String> e : headers.entrySet()) builder.header(e.getKey(), e.getValue());
        }
        log("GET " + hostOfUrl(url) + pathOfUrl(url));
        Request request = builder.build();
        try {
            return rawWith(plainClient(timeoutSeconds), request, url, cookieOut);
        } catch (IOException e) {
            if (!isStreamBreak(e)) throw e;
            log("HTTP/2 被重置，降级 HTTP/1.1 重试 " + pathOfUrl(url));
            return rawWith(plainHttp1Client(timeoutSeconds), request, url, cookieOut);
        }
    }

    /**
     * 表单体的朴素 POST。
     *
     * 播放层的解析接口要带 Video-Parse-Sign / -Time / -Uuid / -Version 这几个自定义头，
     * 而直连会被浏览器的 CORS 预检拦下 —— 对面只回 access-control-allow-origin，
     * 没有 access-control-allow-headers，自定义头过不去。OkHttp 不受同源策略管，走这里。
     */
    public static byte[] postFormRaw(String url, String formBody,
                                     java.util.Map<String, String> headers, int timeoutSeconds) throws Exception {
        okhttp3.RequestBody payload = okhttp3.RequestBody.create(
                formBody == null ? "" : formBody,
                okhttp3.MediaType.parse("application/x-www-form-urlencoded; charset=UTF-8"));
        Request.Builder builder = new Request.Builder().url(url).post(payload).header("Accept-Encoding", "identity");
        if (headers != null) {
            for (java.util.Map.Entry<String, String> e : headers.entrySet()) builder.header(e.getKey(), e.getValue());
        }
        log("POST " + hostOfUrl(url) + pathOfUrl(url));
        Request request = builder.build();
        try {
            return rawWith(plainClient(timeoutSeconds), request, url);
        } catch (IOException e) {
            if (!isStreamBreak(e)) throw e;
            log("HTTP/2 被重置，降级 HTTP/1.1 重试 " + pathOfUrl(url));
            return rawWith(plainHttp1Client(timeoutSeconds), request, url);
        }
    }

    /**
     * 表单体的朴素 POST，**外加把响应的 `Set-Cookie` 收进 `cookieOut`**。
     *
     * 与四参那个重载行为完全一致（同样的表单 MediaType、同样的 h2→h1 降级），
     * 只多回一份 cookie。给「POST 一次就能拿到游客标记」的站用 —— 91porn 那种
     * 首次不带标记的 GET 会回一份<strong>随机内容</strong>，白白多跑一次请求；
     * 换成 POST 拿标记就不用为了 headers 再取一遍正文。
     */
    public static byte[] postFormRaw(String url, String formBody,
                                     java.util.Map<String, String> headers, int timeoutSeconds,
                                     java.util.Map<String, String> cookieOut) throws Exception {
        return postFormRaw(url, formBody, headers, timeoutSeconds, cookieOut, null);
    }

    /**
     * 表单体的朴素 POST，**带上当前源配置**（{@code profile}），外加 cookie 回传。
     *
     * <p>{@code profile} 只在一处起作用：请求命中「源声明要走代理」而手里的 client
     * 没有代理时，兜底建 client 需要它才能挂上 {@link SmartDns}（DoH + 配置里钉死的
     * 地址）。传 null 不是错误，只是退回普通 DNS —— 但 spider 的 POST 全部在这条路上
     * （OpenList 三个接口都是 POST），所以源声明的东西必须能传进来。
     *
     * <p>不另加「只有 profile 没有 cookieOut」的重载：cookie 是可选的，多一个 null
     * 参数比多一个重载好读，也避免调用点因为参数个数不同而悄悄选了另一条路。
     */
    public static byte[] postFormRaw(String url, String formBody,
                                     java.util.Map<String, String> headers, int timeoutSeconds,
                                     java.util.Map<String, String> cookieOut, SiteProfile profile) throws Exception {
        okhttp3.RequestBody payload = okhttp3.RequestBody.create(
                formBody == null ? "" : formBody,
                okhttp3.MediaType.parse("application/x-www-form-urlencoded; charset=UTF-8"));
        Request.Builder builder = new Request.Builder().url(url).post(payload).header("Accept-Encoding", "identity");
        if (headers != null) {
            for (java.util.Map.Entry<String, String> e : headers.entrySet()) builder.header(e.getKey(), e.getValue());
        }
        log("POST " + hostOfUrl(url) + pathOfUrl(url));
        Request request = builder.build();
        try {
            return rawWith(plainClient(timeoutSeconds), request, url, cookieOut, profile);
        } catch (IOException e) {
            if (!isStreamBreak(e)) throw e;
            log("HTTP/2 被重置，降级 HTTP/1.1 重试 " + pathOfUrl(url));
            return rawWith(plainHttp1Client(timeoutSeconds), request, url, cookieOut, profile);
        }
    }

    /**
     * 带 Range 的朴素 GET。用来验直链：音频动辄几十 MB，只要前 1KB 判断格式。
     */
    public static byte[] getRawRange(String url, long from, long to,
                                     java.util.Map<String, String> headers, int timeoutSeconds) throws Exception {
        Request.Builder builder = new Request.Builder().url(url)
                .header("Accept-Encoding", "identity")
                .header("Range", "bytes=" + from + "-" + to);
        if (headers != null) {
            for (java.util.Map.Entry<String, String> e : headers.entrySet()) builder.header(e.getKey(), e.getValue());
        }
        log("GET(range) " + hostOfUrl(url) + pathOfUrl(url));
        return rawWith(plainHttp1Client(timeoutSeconds), builder.build(), url);
    }

    /**
     * JSON 体的朴素 POST。
     *
     * AList / OpenList 那套接口（`/api/fs/list`、`/api/fs/get`、`/api/fs/search`）
     * 只收 `application/json` —— 表单体过去它会当成空对象，返回「path 不能为空」。
     */
    public static byte[] postJsonRaw(String url, String json,
                                     java.util.Map<String, String> headers, int timeoutSeconds) throws Exception {
        okhttp3.RequestBody payload = okhttp3.RequestBody.create(
                json == null ? "{}" : json,
                okhttp3.MediaType.parse("application/json; charset=UTF-8"));
        Request.Builder builder = new Request.Builder().url(url).post(payload)
                .header("Accept-Encoding", "identity");
        if (headers != null) {
            for (java.util.Map.Entry<String, String> e : headers.entrySet()) builder.header(e.getKey(), e.getValue());
        }
        log("POST " + hostOfUrl(url) + pathOfUrl(url));
        Request request = builder.build();
        try {
            return rawWith(plainClient(timeoutSeconds), request, url);
        } catch (IOException e) {
            if (!isStreamBreak(e)) throw e;
            log("HTTP/2 被重置，降级 HTTP/1.1 重试 " + pathOfUrl(url));
            return rawWith(plainHttp1Client(timeoutSeconds), request, url);
        }
    }

    /**
     * JSON 体的朴素 POST，**外加把响应的 `Set-Cookie` 收进 `cookieOut`**。
     *
     * 与四参那个重载行为完全一致（同样的 `application/json` MediaType、同样的
     * h2→h1 降级），只多回一份 cookie。AList/OpenList 的接口本身不带 cookie，
     * 但同一条链路上偶尔会有前置的鉴权/限流网关往里塞标记，统一走这条口径
     * 能给 spider 侧省掉「POST 拿不到 cookie」这个特例。
     */
    public static byte[] postJsonRaw(String url, String json,
                                     java.util.Map<String, String> headers, int timeoutSeconds,
                                     java.util.Map<String, String> cookieOut) throws Exception {
        return postJsonRaw(url, json, headers, timeoutSeconds, cookieOut, null);
    }

    /**
     * JSON 体的朴素 POST，**带上当前源配置**（{@code profile}），外加 cookie 回传。
     *
     * <p>理由和 {@code postFormRaw} 的六参版本一样：{@code profile} 决定兜底 client
     * 挂哪个 DNS。OpenList 的 {@code /api/fs/list}、{@code /api/fs/get}、
     * {@code /api/fs/search} 全是这条路径，而这些站的地址恰恰可能需要 DoH 解析。
     */
    public static byte[] postJsonRaw(String url, String json,
                                     java.util.Map<String, String> headers, int timeoutSeconds,
                                     java.util.Map<String, String> cookieOut, SiteProfile profile) throws Exception {
        okhttp3.RequestBody payload = okhttp3.RequestBody.create(
                json == null ? "{}" : json,
                okhttp3.MediaType.parse("application/json; charset=UTF-8"));
        Request.Builder builder = new Request.Builder().url(url).post(payload)
                .header("Accept-Encoding", "identity");
        if (headers != null) {
            for (java.util.Map.Entry<String, String> e : headers.entrySet()) builder.header(e.getKey(), e.getValue());
        }
        log("POST " + hostOfUrl(url) + pathOfUrl(url));
        Request request = builder.build();
        try {
            return rawWith(plainClient(timeoutSeconds), request, url, cookieOut, profile);
        } catch (IOException e) {
            if (!isStreamBreak(e)) throw e;
            log("HTTP/2 被重置，降级 HTTP/1.1 重试 " + pathOfUrl(url));
            return rawWith(plainHttp1Client(timeoutSeconds), request, url, cookieOut, profile);
        }
    }

    // ── 客户端工厂（唯一一处）────────────────────────────────────────

    /**
     * 客户端缓存。key 里带齐所有会影响实例行为的参数：
     * <b>协议版本 + 超时 + 路由 + DNS 实现</b>。
     *
     * <p>以前是四个字段四份判断（{@code client} / {@code http1Client} /
     * {@code plainClient} / {@code plainHttp1}，各自配一个 {@code appliedXxx}
     * 记录「上次是按什么参数建的」）。四份代码干同一件事，而且各自漏过不同的边：
     * plainClient 忘了比超时（不同超时的请求复用同一个实例，传进来的
     * timeoutSeconds 等于没生效），http1Client 忘了比 DNS。
     *
     * <p>改成「参数算 key」之后，这类漏判在结构上不存在 —— 参数不同，key 就不同，
     * 一定是新实例。
     */
    private static final java.util.Map<String, OkHttpClient> CLIENTS =
            new java.util.concurrent.ConcurrentHashMap<String, OkHttpClient>();

    /**
     * 建一个客户端。
     *
     * @param http1     只谈 HTTP/1.1（h2 被中间设备重置时的兜底）
     * @param dns       null 表示用 OkHttp 默认解析；和别的实现不共用缓存
     * @param route     null 表示直连
     */
    private static OkHttpClient clientFor(boolean http1, int connectSeconds, int readSeconds,
                                          Dns dns, ProxyRoute route) {
        String key = (http1 ? "h1" : "h2")
                + "|" + connectSeconds + "/" + readSeconds
                + "|" + (route == null ? "direct" : route.key())
                + "|" + (dns == null ? "system" : dns.getClass().getSimpleName());
        OkHttpClient hit = CLIENTS.get(key);
        if (hit != null) return hit;

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(connectSeconds, TimeUnit.SECONDS)
                .readTimeout(readSeconds, TimeUnit.SECONDS);
        if (http1) builder.protocols(java.util.Collections.singletonList(okhttp3.Protocol.HTTP_1_1));
        if (dns != null) builder.dns(dns);
        if (route != null) {
            route.applyTo(builder);
            log("走代理 " + route.describe());
        }
        OkHttpClient built = builder.build();
        CLIENTS.put(key, built);
        return built;
    }

    /** 主客户端：带 SmartDns（DoH + 配置钉死的地址）。 */
    private static OkHttpClient client(SiteProfile profile) {
        return clientFor(false, CONNECT_TIMEOUT, READ_TIMEOUT, new SmartDns(profile), effectiveRoute());
    }

    /** 主客户端的 HTTP/1.1 变体。 */
    private static OkHttpClient http1Client(SiteProfile profile) {
        return clientFor(true, CONNECT_TIMEOUT, READ_TIMEOUT, new SmartDns(profile), effectiveRoute());
    }

    /** 朴素客户端：指定超时、不带 SmartDns。 */
    private static OkHttpClient plainClient(int timeoutSeconds) {
        return clientFor(false, timeoutSeconds, timeoutSeconds, null, effectiveRoute());
    }

    /** 朴素客户端的 HTTP/1.1 变体。 */
    private static OkHttpClient plainHttp1Client(int timeoutSeconds) {
        return clientFor(true, timeoutSeconds, timeoutSeconds, null, effectiveRoute());
    }

    /**
     * 按当前路由现拼一个 client，**照抄传进来那个的超时和 DNS**。
     *
     * 用在「路由说要代理、但手里的 client 没有代理」这唯一一种自洽性破洞上。
     * 这不是分流逻辑，是纠错：分流只该在 {@link NetRoute} 里判一次。
     */
    private static OkHttpClient proxiedClient(SiteProfile profile, OkHttpClient like) {
        Dns dns = profile != null ? new SmartDns(profile) : like.dns();
        return clientFor(false,
                (int) (like.connectTimeoutMillis() / 1000),
                (int) (like.readTimeoutMillis() / 1000),
                dns, routeForProxyHost());
    }

    /** 冷启动时等核心就绪的上限。够核心拉起来，又不至于让用户以为卡死。 */
    private static final long PROXY_READY_WAIT_MS = 2500;

    /**
     * 给「路由说这个 host 要走代理」的请求取代理；内置核心还没起来时**等一会儿**。
     *
     * ── 这个方法是为什么存在的 ────────────────────────────────────────
     * 2026-10-19 真机：spider 在启动 0.4 秒后跑，日志是
     *
     * <pre>
     * [source] net yhdm.one → 代理
     * [http] DoH yhdm.one → 4 个地址          ← 还在做本地 DNS
     * failed to connect to yhdm.one/2001::caa0:…（被污染的 IPv6）
     * </pre>
     *
     * 两个真相：{@link NetRoute} 判的是「这个 host 该走代理」（按源声明），而建
     * 客户端用的是 {@link #effectiveRoute()}（全局设置）。启动头一两秒内置核心还没
     * 监听，{@code effectiveProxy()} 返回空串 —— 那是「还没好」，不是「不走代理」。
     * 把它们当同一件事，结果就是拿直连去撞被 DNS 污染的地址，一个请求烧掉 15 秒。
     *
     * <p>所以这条路只做一件事：模式是内置核心、代理串还是空的时候，最多等
     * {@link #PROXY_READY_WAIT_MS}；等到就用，等不到就照旧直连（比一直卡着好，
     * 而且日志里能看出是核心没起来）。
     */
    private static ProxyRoute routeForProxyHost() {
        ProxyRoute route = effectiveRoute();
        if (effectiveProxy().length() > 0) return route;
        if (!ProxyConsole.MODE_BUILTIN.equals(proxyModeOrEmpty())) return route;
        long until = System.currentTimeMillis() + PROXY_READY_WAIT_MS;
        while (System.currentTimeMillis() < until) {
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (effectiveProxy().length() > 0) {
                log("等内置核心就绪 " + (PROXY_READY_WAIT_MS - (until - System.currentTimeMillis())) + "ms");
                return effectiveRoute();
            }
        }
        log("内置核心迟迟没起来，这手先按直连发（源声明的是走代理）");
        return route;
    }

    /**
     * IPv4 优先的 DNS。
     *
     * ── 为什么需要它 ──────────────────────────────────────────────────
     * 2026-10-18 实测：这台机器（MIUI / Android 16 / 双卡）的网络上报给 App 的
     * 默认路由是 **IPv6 单栈**（`::/0`，没有 IPv4 默认路由），而系统 DNS 仍然
     * 同时返回 A 和 AAAA 记录。于是 OkHttp 拿到一个 AAAA 会去连 IPv6，本机没有
     * IPv6 出口 → 连接一直挂到超时。
     *
     * 同一时刻 `curl -4` 3 秒拿到 200，`curl -6` 立刻 000 —— curl 有 Happy
     * Eyeballs 会自动降级到 IPv4，而 OkHttp 这一版在单栈环境下不会。
     *
     * 表现是「订阅一直拉不下来，报 Read timed out」，看起来像网速问题，
     * 实际上一个字节都没传。这种问题时有时无（换 WiFi 就好了），极难复现，
     * 所以在这里钉死：**解析结果里只要有 IPv4，就只用 IPv4**。
     *
     * 只有 IPv6 的站点依然是可用的（列表为空时才退回原样），不影响纯 v6 环境。
     */
    private static final Dns IPV4_FIRST = new Dns() {
        @Override
        public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            List<InetAddress> all = Dns.SYSTEM.lookup(hostname);
            if (all == null || all.isEmpty()) return all;
            List<InetAddress> v4 = new ArrayList<InetAddress>(all.size());
            for (int i = 0; i < all.size(); i++) {
                InetAddress address = all.get(i);
                if (address instanceof Inet4Address) v4.add(address);
            }
            // 一个 IPv4 都没有（纯 IPv6 站点 / v6-only 网络）就原样返回。
            return v4.isEmpty() ? all : v4;
        }
    };

    /**
     * 「直连表命中、要现拼一个不带代理的 client」时，那个新 client 该用哪个 DNS。
     *
     * <p>两条分支（{@link #rawWith} 和 {@link #getWith}）以前各写各的，于是长出了
     * 两种毛病：
     * <ul>
     *   <li>{@code rawWith} 无条件 {@code .dns(IPV4_FIRST)} —— 把传进来 client 的
     *       {@link SmartDns}（DoH + 配置里钉死的地址）盖掉了。GET/POST 全族都走这条，
     *       所以「直连表里的域名突然解析不出配置钉死的那个 IP」随时可能发生，
     *       而症状是「这个站有时候能连有时候不能」，看起来像站点抽风。</li>
     *   <li>{@code getWith} 只在 {@code instanceof SmartDns} 时才保留，否则连
     *       {@code IPV4_FIRST} 都不给，等于新 client 回到 OkHttp 默认 DNS。</li>
     * </ul>
     *
     * <p>决策就一条，按优先级：
     * <ol>
     *   <li>手里的是 {@link SmartDns} → <b>原样保留</b>。它已经包含 DoH、配置钉死的
     *       地址，比任何外层包装都更懂这个源。</li>
     *   <li>手里传了 {@code profile} 而 client 身上却不是 SmartDns → 现建一个
     *       {@link SmartDns}。这就是 POST 那条路上原来的洞：{@code plainClient} 的 DNS
     *       是 OkHttp 默认的，源声明的钉死地址从来没生效过。</li>
     *   <li>其它情况 → {@link #IPV4_FIRST}。<b>不能省</b>：IPv6 单栈网络上 OkHttp 不会
     *       Happy Eyeballs 降级，拿到 AAAA 就一路连到超时（见 IPV4_FIRST 那段实测）。</li>
     * </ol>
     *
     * <p>包级可见是为了单测：这个函数不碰网络也不改状态，输入输出都是引用比较。
     */
    static Dns dnsFor(OkHttpClient http, SiteProfile profile) {
        Dns current = http == null ? null : http.dns();
        if (current instanceof SmartDns) return current;
        // profile 给了就按源的解析规则来 —— DoH 和 hosts 钉死都挂在这上面。
        if (profile != null) return smartDnsFor(profile);
        // 连 profile 都没有（老调用方 / 探针）才退回 IPv4 优先。不是默认值，是兜底。
        return IPV4_FIRST;
    }

    /**
     * 造一个 {@link SmartDns}。**唯一目的是让包外能拿到这个类型的实例** ——
     * {@code SmartDns} 是私有嵌套类，而 {@link #dnsFor} 的判据是
     * {@code instanceof SmartDns}，单测不造一个真的就没法断言「保留」那条路径。
     *
     * <p>不把 {@code SmartDns} 改成包级可见：它的 {@code lookup} 会走 DoH 和磁盘缓存，
     * 是个有副作用的实现类，暴露出去等于邀请别处直接 new。一个工厂方法足够。
     *
     * <p>构造本身不碰网络也不碰 SharedPreferences —— profile 只是存着，
     * 真正的解析发生在 {@code lookup} 里。
     */
    static Dns smartDnsFor(SiteProfile profile) {
        return new SmartDns(profile);
    }

    // ── 代理的读写与解析 ──────────────────────────────────────────────

    private static SharedPreferences netStore() {
        Context context = appContext;
        return context == null ? null : context.getSharedPreferences(NET_STORE, Context.MODE_PRIVATE);
    }

    /** 当前配置的代理串，空串表示直连。 */
    public static String proxyRaw() {
        SharedPreferences store = netStore();
        if (store == null) return "";
        String raw = store.getString(PROXY_KEY, "");
        return raw == null ? "" : raw.trim();
    }

    /**
     * 让下一次请求重建全部客户端。
     *
     * 为什么不是「改代理串再重建」：内置核心启停时用户的代理串一个字都没变，
     * 变的只是生效来源。所以这里只作废缓存，让各个 client() 下次自己
     * 重新问一遍 effectiveProxy() —— 四个构造点本来就都有这层判据。
     *
     * 只置缓存字段不够的地方：OkHttp 的连接池和 TLS 会话都挂在已建好的实例上，
     * 不换实例的话旧连接会继续指向已经关掉的端口。
     */
    static void invalidateClients() {
        CLIENTS.clear();
        // 媒体代理是另一条链路，但它读的是同一个 effectiveProxy()。
        MediaProxy.invalidate();
    }

    /** 写入代理串并让客户端重建。返回 null 表示成功，否则是一句给人看的错。 */
    public static String setProxy(String value) {
        SharedPreferences store = netStore();
        if (store == null) return "存储不可用";
        String clean = value == null ? "" : value.trim();
        ProxyRoute.Parsed parsed = ProxyRoute.parse(clean);
        if (!parsed.ok()) return parsed.error;
        store.edit().putString(PROXY_KEY, clean).apply();
        invalidateClients();           // 逼下一次请求重建
        return null;
    }

    /**
     * 当前生效的 {@link java.net.Proxy}，供 {@link SiteFetchWebView} 用。
     *
     * <p><b>为什么 WebView 也要问这里</b>（2026-10-18 加 missav 时才发现的洞）：
     * 隐藏 WebView 是**另一个网络栈**，OkHttp 的 {@code proxy()} 对它一点用没有。
     * 而需要过 Cloudflare 的站点恰恰都是「国内直连不通、必须走代理」的那种
     * （missav / mrds66 / hanime1 在源市场里都标着「需代理」）。所以 WebView
     * 忘了挂代理 = 过盾那一步必然超时，而日志只会说「过盾超时」，看不出是网络不通。
     *
     * <p>返回 null 表示当前是直连，调用方就别去碰 {@code ProxyController}。
     *
     * <p>不把 {@link Route} 整个暴露出去的原因：它带账号密码，
     * 而这个方法的返回值会进 logcat 附近的调试路径。
     */
    static java.net.Proxy effectiveJavaProxy() {
        ProxyRoute route = effectiveRoute();
        return route == null ? null : route.javaProxy();
    }

    /**
     * 当前生效的代理路由。**null 表示直连。**
     *
     * 这是「代理」在这套代码里的唯一形态：字符串只在 {@link #setProxy} 进、
     * {@link #proxyRaw} 出的时候出现，中间所有消费方拿的都是这个对象。
     */
    static ProxyRoute effectiveRoute() {
        ProxyRoute.Parsed parsed = ProxyRoute.parse(effectiveProxy());
        return parsed.route;
    }

    /**
     * 把代理串挂到 builder 上。给 MediaProxy 这类「自己建客户端但该跟随同一套
     * 代理语义」的调用方用 —— 认格式和带认证的规则只该有一份实现。
     *
     * @return null 表示没代理（直连）或挂上了；非 null 是那句认不出来的原串，调用方拿去打日志
     */
    static String attachProxy(OkHttpClient.Builder builder, String proxyText) {
        ProxyRoute.Parsed parsed = ProxyRoute.parse(proxyText);
        if (parsed.direct()) return null;
        if (parsed.route == null) return proxyText;   // 认不出来，调用方拿去打日志
        parsed.route.applyTo(builder);
        return null;
    }




    /**
     * 请求头模板来自 site-default.json 的 headers，{@code {origin}} 换成<b>源自己</b>的域名。
     *
     * <p>2026-10-19 真机踩到：这里原来换的是<b>目标 URL</b> 的 origin。取封面时目标
     * 变成图床 {@code fourhoi.com}，于是 {@code Referer} 发的是 {@code https://fourhoi.com/}，
     * 而图床的防盗链只认站点域 —— 实测 {@code Referer: https://missav.live/…} → 200，
     * {@code https://fourhoi.com/} → 403，{@code Referer: /} → 403。okhttp 报的就是这个 403，
     * 看着像「被墙」，其实是自己把源发错了。
     *
     * <p>源没声明 origin（spider 源就是这样，站点域写在 js 里）时退回目标 URL 的 origin。
     */
    private static void applyHeaders(Request.Builder builder, SiteProfile profile, String url) {
        if (profile == null) return;
        try {
            JSONObject headers = profile.object("headers");
            if (headers == null) return;
            String siteOrigin = profile.origin == null ? "" : profile.origin.trim();
            String origin = siteOrigin.length() > 0 ? siteOrigin : originOf(url);
            Iterator<String> keys = headers.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = headers.optString(key, "");
                if (value == null || value.length() == 0) continue;
                String filled = value.replace("{origin}", origin);
                // 模板写了 "{origin}/" 而 origin 是空串时会得到 "/"，防盗链一律 403。
                // 这种头没有意义，不如不发 —— 缺 Referer 是 200。
                if (filled.length() == 0 || filled.equals("/")) continue;
                builder.header(key, filled);
            }
        } catch (Exception e) {
            log("请求头模板读取失败，按空头发送：" + e);
        }
    }

    private static String hostOfUrl(String url) {
        try { return new java.net.URL(url).getHost(); } catch (Exception e) { return "(无法解析)"; }
    }

    private static String pathOfUrl(String url) {
        try { return new java.net.URL(url).getPath(); } catch (Exception e) { return url; }
    }


    private static String originOf(String url) {        try {
            java.net.URL parsed = new java.net.URL(url);
            String port = parsed.getPort() > 0 ? (":" + parsed.getPort()) : "";
            return parsed.getProtocol() + "://" + parsed.getHost() + port;
        } catch (Exception e) {
            return url;
        }
    }

    // ── DNS：系统解析 或 DoH ──────────────────────────────────────────

    /**
     * OkHttp 的解析钩子。返回**多个**地址时 OkHttp 会自己轮着试 ——
     * 这是选 OkHttp 而不是 HttpURLConnection 的另一个理由：后者只试第一个。
     */
    private static final class SmartDns implements Dns {
        private final SiteProfile profile;
        SmartDns(SiteProfile profile) { this.profile = profile; }

        @Override public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            // ── 配置里钉死的地址优先 ──────────────────────────────────────
            // 有些入口域名，三条解析路径给出的都是不能用的答案：
            //   · 运营商 DNS  → 一个证书已过期的 IP
            //   · 阿里 DoH    → 0.0.0.0
            //   · 腾讯 DoH    → 5 个真地址，但只有一两个偶尔能连上
            // 这种时候任何「解析」都是碰运气，唯一能做的是把能用那个写死。
            // 配置顶层写：  "hosts": { "例子.com": ["1.2.3.4", "5.6.7.8"] }
            List<String> pinned = pinned(hostname, profile);
            // 钉 IP 是「优先提示」不是「唯一答案」（2026-10-19 教训）：
            // 之前 pinned 命中就直接 return —— 钉的 IP 哪天过期（站点换 CDN 是
            // 常态），域名就安静地死掉，没有任何回落。现在解析出全部候选后把
            // 钉住的地址排在**最前面**，OkHttp 按顺序试：钉 IP 活着就是最快路径，
            // 死了自动落回 DoH/系统解析，慢一点但不会再死。
            List<InetAddress> pinnedAddrs = new ArrayList<InetAddress>();
            if (!pinned.isEmpty()) {
                for (int i = 0; i < pinned.size(); i++) {
                    try { pinnedAddrs.add(InetAddress.getByName(pinned.get(i))); } catch (Exception ignored) {}
                }
            }
            if (pinnedAddrs.isEmpty()) {
                // 没有可用钉 IP：走原有路径（DoH/系统）并原样返回。
                List<InetAddress> plain = resolveFallback(hostname, profile);
                if (plain != null) return plain;
                return Dns.SYSTEM.lookup(hostname);
            }
            List<InetAddress> resolved = resolveFallback(hostname, profile);
            if (resolved == null || resolved.isEmpty()) {
                log("钉住 " + hostname + " → " + pinnedAddrs.size() + " 个地址（解析无结果，直接用钉 IP）");
                return pinnedAddrs;
            }
            // 钉 IP 在前，解析结果在后；去重后交给 OkHttp 按序试。
            List<InetAddress> out = new ArrayList<InetAddress>(pinnedAddrs.size() + resolved.size());
            out.addAll(pinnedAddrs);
            for (InetAddress addr : resolved) {
                if (!out.contains(addr)) out.add(addr);
            }
            log("钉住 " + hostname + " → " + pinnedAddrs.size() + " 个优先 + 解析 "
                    + resolved.size() + " 个候选");
            return out;
        }
    }

    /**
     * DoH/系统解析的老路径，从 lookup 里拆出来给「钉 IP 优先 + 回落」复用。
     * 返回 null 表示 DoH 没开（调用方走系统解析）；空表 = DoH 无结果（同样回落）。
     */
    private static List<InetAddress> resolveFallback(String hostname, SiteProfile profile) throws UnknownHostException {
        if (!dohEnabled(profile)) return null;
        List<String> ips = resolve(hostname, profile);
        if (ips.isEmpty()) return null;
        List<InetAddress> out = new ArrayList<InetAddress>(ips.size());
        for (int i = 0; i < ips.size(); i++) {
            try {
                out.add(InetAddress.getByName(ips.get(i)));
            } catch (Exception e) {
                log("地址无法构造：" + ips.get(i));
            }
        }
        return out;
    }

    /**
     * 配置里有没有给这个域名钉死地址。
     *
     * 读的是顶层 `hosts`：`{ "域名": ["1.2.3.4", …] }`。空/没写都返回空表，
     * 走原来的 DoH 那条路 —— 这是个例外通道，不该影响别的源。
     */
    private static List<String> pinned(String host, SiteProfile profile) {
        List<String> out = new ArrayList<String>();
        if (profile == null || host == null) return out;
        try {
            JSONObject hosts = profile.object("hosts");
            if (hosts == null) return out;
            JSONArray list = hosts.optJSONArray(host);
            if (list == null) return out;
            for (int i = 0; i < list.length(); i++) {
                String ip = list.optString(i, "").trim();
                if (ip.length() > 0 && usable(ip)) out.add(ip);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    static List<String> resolve(String host, SiteProfile profile) {
        Entry cached = CACHE.get(host);
        if (cached != null && System.currentTimeMillis() < cached.expireAt) return cached.ips;

        // 内存里没有就翻磁盘。冷启动那一次 DoH 在整条链的最前面，
        // 省掉它等于把首屏的起跑线往前挪 200~500ms。
        List<String> fromDisk = recall(host);
        if (fromDisk != null) {
            CACHE.put(host, new Entry(fromDisk, System.currentTimeMillis() + TTL_OK));
            log("DoH 磁盘命中 " + host + " → " + fromDisk.size() + " 个地址");
            refreshInBackground(host, profile);
            return fromDisk;
        }

        List<String> fromDoh = lookupDoh(host, profile);
        long ttl = fromDoh.isEmpty() ? TTL_FAIL : TTL_OK;
        CACHE.put(host, new Entry(fromDoh, System.currentTimeMillis() + ttl));
        if (!fromDoh.isEmpty()) remember(host, fromDoh);
        return fromDoh;
    }

    private static List<String> lookupDoh(String host, SiteProfile profile) {
        JSONArray servers = dohServers(profile);
        LinkedHashSet<String> merged = new LinkedHashSet<String>();
        if (servers == null) return new ArrayList<String>(merged);

        // 多问一家再合并：实测阿里和腾讯在同一时刻会给不同答案（就近路由），
        // 只信一家的结果集可能不全，漏掉的那个恰好能用就亏了。
        for (int i = 0; i < servers.length(); i++) {
            JSONObject server = servers.optJSONObject(i);
            if (server == null) continue;
            try {
                merged.addAll(queryOne(server, host, profile));
            } catch (Exception e) {
                log("DoH " + server.optString("name", "?") + " 失败：" + e);
            }
            if (merged.size() >= 3) break;
        }
        return new ArrayList<String>(merged);
    }

    /**
     * 查一家 DoH。这里走的是**另一条 Dns**：把该服务商的域名钉死到配置里的 IP。
     *
     * 这正是 OkHttp 好用的地方 —— URL 写的是 https://dns.alidns.com/resolve，
     * 域名还在，所以 SNI 和证书校验都是对的；只有「解析」被换成了配置里的地址。
     * 换成 HttpURLConnection 就得自己拼 SNI，也就是之前失败的那条路。
     */
    private static List<String> queryOne(final JSONObject server, final String host, SiteProfile profile)
            throws Exception {
        String url = server.optString("url", "");
        JSONArray ips = server.optJSONArray("ips");
        if (url.length() == 0 || ips == null || ips.length() == 0) throw new IOException("DoH 配置不完整");

        final List<InetAddress> pinned = new ArrayList<InetAddress>();
        for (int i = 0; i < ips.length(); i++) {
            String ip = ips.optString(i, "");
            if (ip.length() == 0) continue;
            try { pinned.add(InetAddress.getByName(ip)); } catch (Exception ignored) {}
        }
        if (pinned.isEmpty()) throw new IOException("DoH 的 ips 全是无效地址");

        // 钉死的解析器：只认配置里的地址，不查系统。DoH 服务器必须先把 IP 写好，
        // 否则「解析一个域名」的前提是「先解析 DoH 服务器的域名」，递归了。
        Dns pinnedDns = new Dns() {
            @Override public List<InetAddress> lookup(String hostname) {
                return pinned;
            }
        };

        OkHttpClient dohClient = new OkHttpClient.Builder()
                .dns(pinnedDns)
                .connectTimeout(DOH_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(DOH_TIMEOUT, TimeUnit.SECONDS)
                .build();

        String endpoint = url + (url.indexOf('?') >= 0 ? "&" : "?") + "name=" + host + "&type=A";
        Request request = new Request.Builder()
                .url(endpoint)
                .header("accept", "application/dns-json")
                .build();

        Response response = dohClient.newCall(request).execute();
        try {
            if (!response.isSuccessful()) throw new IOException("DoH HTTP " + response.code());
            ResponseBody body = response.body();
            if (body == null) return new ArrayList<String>();
            return parseAnswers(body.string());
        } finally {
            response.close();
        }
    }

    /** 解析 RFC 8484 的 JSON 应答：只看 Answer[] 里 type==1 的。 */
    private static List<String> parseAnswers(String body) {
        List<String> out = new ArrayList<String>();
        try {
            JSONArray answer = new JSONObject(body).optJSONArray("Answer");
            if (answer == null) return out;
            for (int i = 0; i < answer.length(); i++) {
                JSONObject record = answer.optJSONObject(i);
                if (record == null) continue;
                if (record.optInt("type", 0) != 1) continue;
                String data = record.optString("data", "");
                // 只要 IPv4：这条链路上没通 v6，返回去反而让 OkHttp 白试一轮。
                if (data.length() == 0 || data.indexOf(':') >= 0) continue;
                if (!usable(data)) continue;
                if (!out.contains(data)) out.add(data);
            }
        } catch (Exception e) {
            log("DoH 应答解析失败：" + e);
        }
        return out;
    }

    /**
     * 这个 A 记录能不能拿去连。
     *
     * ── 为什么必须筛 ────────────────────────────────────────────────────
     * 实测阿里 DoH（dns.alidns.com）对 `cab3f.nt17g20k.cc` 的应答是
     * **`0.0.0.0`** —— 那不是「没查到」，那是运营商的毒答案，把它当地址用
     * 只会去连一个必然失败的地方。
     *
     * 而 lookupDoh 是「多问一家再合并」，阿里先回 0.0.0.0 会让它凑不满 3 个，
     * 于是继续问腾讯、把腾讯那几个真地址也加进来 —— 结果列表变成
     * `[0.0.0.0, 43.229.39.228, …]`，**毒答案排在第一个**，每次请求都先白试
     * 一遍它。筛掉之后 OkHttp 才会从能用那个开始试。
     */
    private static boolean usable(String ip) {
        if (ip == null) return false;
        String s = ip.trim();
        if (s.length() == 0) return false;
        if ("0.0.0.0".equals(s) || "127.0.0.1".equals(s)) return false;
        if (s.startsWith("0.") || s.startsWith("127.")) return false;
        // 255.255.255.255 之类：首字节全 1 的广播地址
        if (s.startsWith("255.")) return false;
        return true;
    }

    private static JSONArray dohServers(SiteProfile profile) {
        JSONObject doh = dohConfig(profile);
        return doh == null ? null : doh.optJSONArray("servers");
    }

    private static JSONObject dohConfig(SiteProfile profile) {
        if (profile == null) return null;
        try {
            return profile.object("doh");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * DoH 默认关闭。当前 origin（cab3f.nt17g20k.cc）在手机系统解析上是干净的，
     * 五个入口 IP 也全部可达，开着只是多一跳延迟。
     * 什么时候该打开：origin 换成被污染的域名（比如 huangguoai.com）之后，
     * 至少还能拿到完整的地址列表而不是系统给的那一个。
     */
    private static boolean dohEnabled(SiteProfile profile) {
        JSONObject doh = dohConfig(profile);
        return doh != null && doh.optBoolean("enabled", false);
    }

    /**
     * 包内可见 —— 适配器也要往这条流里写（比如抓取失败、采集站匹配不上，
     * 都要留痕）。用同一个 tag 是为了在 logcat 里能按时间顺序对照
     * 「适配器说了什么」和「HTTP 发了什么」，分成两个 tag 会乱。
     */
    static void log(String message) {
        try { android.util.Log.d("AIIvideo", "[http] " + message); } catch (Throwable ignored) {}
    }
}
