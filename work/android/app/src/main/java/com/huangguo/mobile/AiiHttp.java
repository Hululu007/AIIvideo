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
    static String effectiveProxy(String builtin, String raw) {
        if (builtin != null && builtin.length() > 0) return builtin;
        return raw == null ? "" : raw;
    }

    /**
     * 当前的代理串。每次建客户端时问一次，所以核心启停之后必须
     * invalidateClients()，否则旧客户端会一直用着上一个代理。
     */
    static String effectiveProxy() {
        return effectiveProxy(builtinProxy(), proxyRaw());
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
     */
    public static byte[] get(String url, SiteProfile profile) throws Exception {
        // 按适配器分流。cms 那类源的地址是「CMS 接口」，不是「origin + 路径」，
        // 走不了下面的解析/DoH 那一套 —— 由 CmsAdapter 自己拼 URL、自己映射形状。
        // 它返回的必须是**我们内部的 JSON 形状**，这样前端的 adapters.js 不用改。
        // 只接 /api/ 开头的 —— 那是我们内部的动作词汇表，交给适配器翻译。
        // 别的 URL（比如封面图的绝对地址，SiteRepository.coverData 会带着它走到
        // 这里）必须走正常的取字节那条路，不然适配器会以「不认识的路径」拒掉，
        // 表现是封面全部加载失败。
        if (profile != null && "cms".equals(profile.rule("adapter")) && url.startsWith("/api/")) {
            return CmsAdapter.fetch(url, profile);
        }
        // AGE动漫 走同一条路：origin 空 → 整条 /api/... 原样转给适配器。
        // 为什么不用「绝对地址 + 前端转换」那条更干净的路：它的详情要把
        // `{源: [[集名,地址]]}` 重排成一维剧集数组，声明层表达不了结构重排，
        // 总得有一个地方写代码 —— 那就写在和苹果CMS 同一个位置上，别搞两套。
        if (profile != null && "agedm".equals(profile.rule("adapter")) && url.startsWith("/api/")) {
            return AgedmAdapter.fetch(url, profile);
        }
        // 第三个适配器（单站 HTML 型）走同一条路。它比前两个更极端：整个站
        // **没有 JSON 接口**（/api/* 全 404），数据全在 SSR 的 HTML 里，
        // 播放地址是页面内联的签名直链。放这里的理由和 agedm 一样 ——
        // 结构重排（HTML → 我们的形状）总得有个地方写代码。
        //
        // ⚠️ 那个适配器类**不在仓库里**（目标是成人内容站，代码和内容一起排除了，
        //    见 .gitignore 末尾）。所以：
        //      · clone 后编译会在这一行报「找不到符号 Hanime1Adapter」
        //      · 那是有意的，不是漏提交 —— 删掉这一整个 if 分支即可，
        //        cms / agedm 两条线完全不受影响
        //      · 下面那条 stripOrigin 兜底同样只服务于这个分支，但**它的思路是
        //        通用的**：任何 origin 非空的适配器源都会踩「detail 拼了 origin
        //        导致分流落空」这个坑，别跟着一起删
        if (profile != null && "hanime1".equals(profile.rule("adapter")) && url.startsWith("/api/")) {
            return Hanime1Adapter.fetch(url, profile);
        }
        // ── 真机抓到的 bug：详情页 404，而 URL 里带着 origin ─────────────
        //
        // 报错长这样：`HTTP 404 @ https://<origin>/api/videos/408707`。
        // 首页是好的（有卡片、分类栏也对），偏偏详情挂了 —— 因为**两条路不一样**：
        //
        //   首页  走 prefetchBody()，那里给适配器加了分支，路径原样进适配器
        //   详情  走 SiteRepository.detail()，那段 smali 会把**相对路径拼上 origin**
        //         再交给 AiiHttp.get() —— 于是进来的已经是绝对地址，
        //         上面几条 startsWith("/api/") 全部落空，请求真的发到了
        //         <origin>/api/videos/408707（站点上不存在的接口，当然 404）。
        //
        // 对 origin 为空的源（cms / agedm 那种）不会暴露，因为拼完还是 /api/...；
        // **只有 origin 非空的适配器源才踩得到**。所以这条不是某个站的问题，
        // 是「适配器分流」这套机制本身的洞。
        //
        // 修在这里而不是「让配置把 origin 留空」：那些配置多半是自动生成的，
        // 用户改不了；而且 origin 还有别的用处（封面白名单、别的相对路径）。
        // 归一化到相对路径再分流，是唯一能同时兜住两条路的地方。
        if (profile != null && "hanime1".equals(profile.rule("adapter")) && url.startsWith("/api/") == false) {
            String relative = stripOrigin(url, profile);
            if (relative != null && relative.startsWith("/api/")) {
                return Hanime1Adapter.fetch(relative, profile);
            }
        }

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
        } catch (IOException e) {
            if (!isStreamBreak(e)) throw e;
            log("HTTP/2 被重置，降级 HTTP/1.1 重试 " + pathOfUrl(url));
            return getWith(http1Client(profile), url, profile);
        }
    }

    private static byte[] getWith(OkHttpClient http, String url, SiteProfile profile) throws Exception {
        Request.Builder builder = new Request.Builder().url(url);
        applyHeaders(builder, profile, url);
        // 不要压缩。这条链路本来就慢，多一次解压不值当，也让响应体大小可预期。
        builder.header("Accept-Encoding", "identity");
        Response response = http.newCall(builder.build()).execute();
        try {
            int code = response.code();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code + " @ " + url);
            ResponseBody body = response.body();
            if (body == null) return new byte[0];
            return body.bytes();
        } finally {
            response.close();
        }
    }


    /**
     * 记住 application context —— 磁盘缓存要用它。幂等，每次启动调一次即可。
     */
    public static void init(Context context) {
        if (context != null && appContext == null) appContext = context.getApplicationContext();
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
     * 而且 CMS 源的地址根本不在 origin 上。所以这里把 get() 的分流重走一遍：
     *   · cms：路径本身就是适配器的入参，交给它
     *   · rest：origin + 路径 拼成完整地址
     */
    private static byte[] prefetchBody(SiteProfile profile) throws Exception {
        String path = warmPath(profile);
        if (profile != null && "cms".equals(profile.rule("adapter"))) {
            return CmsAdapter.fetch(path, profile);
        }
        if (profile != null && "agedm".equals(profile.rule("adapter"))) {
            return AgedmAdapter.fetch(path, profile);
        }
        // 单站 HTML 型适配器的首页预热同理：路径是内部词汇，适配器自己去抓页面
        // 再抠卡片。
        //
        // ⚠️ **只预热首页。** 有些站的播放地址带一次性签名、会过期，预热出来的
        //    地址等用户真去点的时候可能已经废了 —— 那种地址必须实时取。
        //    首页没有这个问题（它只是元数据），预热是安全的。
        //    这条判断对任何源都成立，别按「反正首页都预热」一刀切。
        if (profile != null && "hanime1".equals(profile.rule("adapter"))) {
            return Hanime1Adapter.fetch(path, profile);
        }
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
        Response response = http.newCall(request).execute();
        try {
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code() + " @ " + url);
            ResponseBody body = response.body();
            return body == null ? new byte[0] : body.bytes();
        } finally {
            response.close();
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
        Request request = new Request.Builder().url(url)
                .header("Accept-Encoding", "identity")
                .header("Connection", "close")
                .build();
        OkHttpClient one = new OkHttpClient.Builder()
                .connectTimeout(Math.min(timeoutSeconds, 15), TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .dns(IPV4_FIRST)
                .build();
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

    private static OkHttpClient client(SiteProfile profile) {
        final String want = effectiveProxy();
        OkHttpClient existing = client;
        // 代理改了必须重建 —— OkHttp 的 proxy 是构造期定下来的，改不了。
        if (existing != null && want.equals(appliedEffective)) return existing;
        synchronized (AiiHttp.class) {
            if (client == null || !want.equals(appliedEffective)) {
                OkHttpClient.Builder builder = new OkHttpClient.Builder()
                        .dns(new SmartDns(profile))
                        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
                        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS);
                Route route = parseProxy(want);
                if (route != null) {
                    builder.proxy(route.proxy);
                    attachAuth(builder, route);
                    log("走代理 " + describe(route));
                } else if (want.length() > 0) {
                    log("代理地址认不出来，按直连处理：" + want);
                }
                client = builder.build();
                appliedEffective = want;
            }
            return client;
        }
    }

    /** 只谈 HTTP/1.1 的同一套客户端。给「h2 被中间设备重置」的场景兜底用。 */
    private static volatile OkHttpClient http1Client;
    private static volatile String http1AppliedEffective = null;

    private static OkHttpClient http1Client(SiteProfile profile) {
        final String want = effectiveProxy();
        OkHttpClient existing = http1Client;
        if (existing != null && want.equals(http1AppliedEffective)) return existing;
        synchronized (AiiHttp.class) {
            if (http1Client == null || !want.equals(http1AppliedEffective)) {
                OkHttpClient.Builder builder = new OkHttpClient.Builder()
                        .dns(new SmartDns(profile))
                        .protocols(java.util.Collections.singletonList(okhttp3.Protocol.HTTP_1_1))
                        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
                        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS);
                Route route = parseProxy(want);
                if (route != null) {
                    builder.proxy(route.proxy);
                    attachAuth(builder, route);
                }
                http1Client = builder.build();
                http1AppliedEffective = want;
            }
            return http1Client;
        }
    }

    /**
     * CMS 适配器专用的朴素客户端：不套我们的 headers 模板、不用 DoH。
     *
     * 但**必须缓存**，而且**必须挂代理** —— 这两条第一版都漏了：
     *   · 每次 new 一个 OkHttpClient 等于每条请求都重来一遍连接池和 TLS 握手
     *   · 用户的代理设置在这里被完全忽略（CMS 源的请求根本没走代理）
     * 代理改了要重建，跟主客户端一个规矩。
     */
    private static volatile OkHttpClient plainClient;
    private static volatile String plainAppliedEffective = null;
    private static volatile int plainAppliedTimeout = -1;

    private static OkHttpClient plainClient(int timeoutSeconds) {
        final String want = effectiveProxy();
        OkHttpClient existing = plainClient;
        // 判据里必须带上超时：只比代理串的话，第一个请求用几秒建的实例会被后面
        // 所有不同超时的请求复用，传进来的 timeoutSeconds 等于没生效。
        if (existing != null && want.equals(plainAppliedEffective)
                && timeoutSeconds == plainAppliedTimeout) {
            return existing;
        }
        synchronized (AiiHttp.class) {
            if (plainClient == null || !want.equals(plainAppliedEffective)
                    || timeoutSeconds != plainAppliedTimeout) {
                OkHttpClient.Builder builder = new OkHttpClient.Builder()
                        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                        // [A/B] 临时去掉，对照是否 IPV4_FIRST 导致阻塞
                        ;
                Route route = parseProxy(want);
                if (route != null) {
                    builder.proxy(route.proxy);
                    attachAuth(builder, route);
                }
                plainClient = builder.build();
                plainAppliedEffective = want;
                plainAppliedTimeout = timeoutSeconds;
            }
            return plainClient;
        }
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

    /** plain 客户端的 HTTP/1.1 变体，用途同上：h2 被掐时的兜底。 */
    private static volatile OkHttpClient plainHttp1;
    private static volatile String plainHttp1AppliedEffective = null;

    private static OkHttpClient plainHttp1Client(int timeoutSeconds) {
        final String want = effectiveProxy();
        OkHttpClient existing = plainHttp1;
        if (existing != null && want.equals(plainHttp1AppliedEffective)) return existing;
        synchronized (AiiHttp.class) {
            if (plainHttp1 == null || !want.equals(plainHttp1AppliedEffective)) {
                OkHttpClient.Builder builder = new OkHttpClient.Builder()
                        .protocols(java.util.Collections.singletonList(okhttp3.Protocol.HTTP_1_1))
                        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                        .readTimeout(timeoutSeconds, TimeUnit.SECONDS);
                Route route = parseProxy(want);
                if (route != null) {
                    builder.proxy(route.proxy);
                    attachAuth(builder, route);
                }
                plainHttp1 = builder.build();
                plainHttp1AppliedEffective = want;
            }
            return plainHttp1;
        }
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
        synchronized (AiiHttp.class) {
            client = null;
            appliedProxy = null;
            appliedEffective = null;
            http1Client = null;
            http1AppliedEffective = null;
            plainClient = null;
            plainAppliedEffective = null;
            plainHttp1 = null;
            plainHttp1AppliedEffective = null;
        }
        // 媒体代理是另一条链路，但它读的是同一个 effectiveProxy()。
        MediaProxy.invalidate();
    }

    /** 写入代理串并让客户端重建。返回 null 表示成功，否则是一句给人看的错。 */
    public static String setProxy(String value) {
        SharedPreferences store = netStore();
        if (store == null) return "存储不可用";
        String clean = value == null ? "" : value.trim();
        if (clean.length() > 0 && parseProxy(clean) == null) {
            return "格式看不懂。可以填 host:port、socks5://host:port，带账号密码写成 user:pass@host:port";
        }
        store.edit().putString(PROXY_KEY, clean).apply();
        invalidateClients();           // 逼下一次请求重建
        return null;
    }

    /**
     * 代理串解析的结果：一根管道 + 可选的账号密码。
     *
     * 认这些写法（管道的类型看 scheme，不写默认 HTTP）：
     *   host:port
     *   socks5://host:port
     *   user:pass@host:port
     *   socks5://user:pass@host:port
     *   http://user:pass@host:port
     *
     * 认不出来返回 null，调用方按直连处理。
     */
    private static final class Route {
        final Proxy proxy;
        final String user;
        final String pass;
        Route(Proxy proxy, String user, String pass) {
            this.proxy = proxy; this.user = user; this.pass = pass;
        }
        boolean needsAuth() { return user != null; }
    }

    private static Route parseProxy(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.length() == 0) return null;

        Proxy.Type type = Proxy.Type.HTTP;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("socks5://") || lower.startsWith("socks://")) {
            type = Proxy.Type.SOCKS;
            text = text.substring(text.indexOf("://") + 3);
        } else if (lower.startsWith("http://")) {
            text = text.substring(7);
        } else if (lower.startsWith("https://")) {
            text = text.substring(8);
        }

        // 账号密码在 @ 之前。用 lastIndexOf：密码里带 @ 也不怕
        // （user:p@ss@host:port 要切成 user:p@ss 和 host:port）。
        String user = null;
        String pass = "";
        int at = text.lastIndexOf('@');
        if (at >= 0) {
            String credentials = text.substring(0, at);
            text = text.substring(at + 1);
            int sep = credentials.indexOf(':');
            if (sep < 0) {
                user = credentials;
            } else {
                user = credentials.substring(0, sep);
                pass = credentials.substring(sep + 1);
            }
            if (user.length() == 0) return null;
        }

        int colon = text.lastIndexOf(':');
        if (colon <= 0 || colon == text.length() - 1) return null;
        String host = text.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(text.substring(colon + 1).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        // host 里不能有斜杠和空白。少了这一条，「://1.2.3.4:1080」这种残片会被
        // 当成 host 收下（createUnresolved 不做校验），然后在发请求时炸成一句
        // 看不出所以然的错。宁可在这里拒绝。
        if (port <= 0 || port > 65535 || host.length() == 0) return null;
        if (host.indexOf('/') >= 0 || host.indexOf('\\') >= 0) return null;
        for (int i = 0; i < host.length(); i++) {
            if (Character.isWhitespace(host.charAt(i))) return null;
        }
        return new Route(new Proxy(type, InetSocketAddress.createUnresolved(host, port)), user, pass);
    }

    /**
     * 把代理串挂到 builder 上。给 MediaProxy 这类「自己建客户端但该跟随同一套
     * 代理语义」的调用方用 —— 认格式和带认证的规则只该有一份实现。
     *
     * @return null 表示没代理（直连）或挂上了；非 null 是那句认不出来的原串，调用方拿去打日志
     */
    static String attachProxy(OkHttpClient.Builder builder, String proxyText) {
        if (proxyText == null || proxyText.trim().length() == 0) return null;
        Route route = parseProxy(proxyText);
        if (route == null) return proxyText;
        builder.proxy(route.proxy);
        attachAuth(builder, route);
        return null;
    }

    /** 把凭据挂到客户端上。HTTP 走 proxyAuthenticator，SOCKS 走全局 Authenticator。 */
    private static void attachAuth(OkHttpClient.Builder builder, final Route route) {
        if (!route.needsAuth()) return;
        if (route.proxy.type() == Proxy.Type.HTTP) {
            builder.proxyAuthenticator(new Authenticator() {
                @Override public Request authenticate(okhttp3.Route r, Response response) {
                    if (response.request().header("Proxy-Authorization") != null) return null;
                    return response.request().newBuilder()
                            .header("Proxy-Authorization", Credentials.basic(route.user, route.pass))
                            .build();
                }
            });
        } else {
            applySocksAuth(route);
        }
    }

    /**
     * 日志用的描述。**不打印账号密码** —— 这行会进 logcat，而凭据是明文的。
     */
    private static String describe(Route route) {
        return route.proxy.type() + " " + route.proxy.address() + (route.needsAuth() ? "（带认证）" : "");
    }

    /**
     * SOCKS 代理的认证。
     *
     * OkHttp 只给了 HTTP 代理的钩子（proxyAuthenticator），SOCKS 那条路走的是
     * java.net.Socket 内部，认证要挂全局的 java.net.Authenticator —— 没有别的口子。
     * 设之前先看 requestorType，只回代理那一路的凭据，别把别的认证也一并接管了。
     */
    private static void applySocksAuth(final Route route) {
        try {
            java.net.Authenticator.setDefault(new java.net.Authenticator() {
                @Override protected java.net.PasswordAuthentication getPasswordAuthentication() {
                    // ⚠ Java 的 SOCKS 实现用的是 RequestorType.SERVER，**不是 PROXY**。
                    // 这里按直觉写成 `!= PROXY 就 return null` 的话，凭据永远回不去，
                    // 服务端只会收到空认证，报「SOCKS : authentication failed」——
                    // 而同一个密码用 curl -x socks5h:// 是完全通的，很容易怀疑到密码头上去。
                    // 实测日志：type=SERVER proto=SOCKS5 host=64.64.224.121:19108
                    //
                    // 判断条件改用「协议 + 目标地址」，比 RequestorType 可靠，
                    // 也不会顺手把别的认证请求接管了。
                    if (!"SOCKS5".equalsIgnoreCase(String.valueOf(getRequestingProtocol()))) return null;
                    java.net.SocketAddress raw = route.proxy.address();
                    if (!(raw instanceof InetSocketAddress)) return null;
                    InetSocketAddress address = (InetSocketAddress) raw;
                    if (!String.valueOf(getRequestingHost()).equals(address.getHostString())) return null;
                    if (getRequestingPort() != address.getPort()) return null;
                    return new java.net.PasswordAuthentication(route.user, route.pass.toCharArray());
                }
            });
        } catch (Throwable e) {
            log("SOCKS 认证设置失败：" + e);
        }
    }

    /** 请求头模板来自 site-default.json 的 headers，{origin} 换成实际域名。 */
    private static void applyHeaders(Request.Builder builder, SiteProfile profile, String url) {
        if (profile == null) return;
        try {
            JSONObject headers = profile.object("headers");
            if (headers == null) return;
            String origin = originOf(url);
            Iterator<String> keys = headers.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = headers.optString(key, "");
                if (value == null || value.length() == 0) continue;
                builder.header(key, value.replace("{origin}", origin));
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

    /**
     * 把「origin + 内部动作路径」还原成内部动作路径（带查询串）。
     *
     * 只对**同一个 origin** 的地址动手。别的域名原样返回 null —— 封面图的绝对
     * 地址、外站接口都从这儿走过去，不该被误伤。
     *
     * ── 它补的是哪个洞 ──────────────────────────────────────────────
     * `SiteRepository.detail()` 那段 smali 会**先把相对路径拼上 origin** 再交给
     * `AiiHttp.get()`，于是上面那几条适配器分流的 `startsWith("/api/")` 全部落空，
     * 请求真的发到 `https://<origin>/api/videos/408707` —— 一个站点上不存在的接口。
     *
     * 真机症状：首页正常、**详情页报 404 且 URL 里带着 origin**。
     *
     * 对 origin 为空的源（cms / agedm 那种）不会暴露，因为拼完还是 `/api/...`；
     * 只有 origin 非空的适配器源才踩得到。所以这条修复**跟具体站点无关** ——
     * 它属于「适配器分流」这个机制本身。
     *
     * 为什么修在这里而不是「让用户把 origin 留空」：配置多半是自动生成的，
     * 用户改不了；而且 origin 还有别的用处（封面白名单、将来别的相对路径）。
     * 归一化到相对路径再分流，是唯一能同时兜住两条路的地方。
     */
    private static String stripOrigin(String url, SiteProfile profile) {
        if (url == null || profile == null) return null;
        String origin = profile.origin;
        if (origin == null || origin.length() == 0) return null;
        if (!url.startsWith(origin)) return null;
        String rest = url.substring(origin.length());
        if (rest.length() == 0 || rest.charAt(0) != '/') return null;
        return rest;
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
            if (!pinned.isEmpty()) {
                List<InetAddress> list = new ArrayList<InetAddress>(pinned.size());
                for (int i = 0; i < pinned.size(); i++) {
                    try { list.add(InetAddress.getByName(pinned.get(i))); } catch (Exception ignored) {}
                }
                if (!list.isEmpty()) {
                    log("按配置钉死 " + hostname + " → " + list.size() + " 个地址");
                    return list;
                }
            }
            if (!dohEnabled(profile)) return Dns.SYSTEM.lookup(hostname);
            List<String> ips = resolve(hostname, profile);
            if (ips.isEmpty()) {
                log("DoH 没有结果，回落系统解析：" + hostname);
                return Dns.SYSTEM.lookup(hostname);
            }
            List<InetAddress> out = new ArrayList<InetAddress>(ips.size());
            for (int i = 0; i < ips.size(); i++) {
                try {
                    out.add(InetAddress.getByName(ips.get(i)));
                } catch (Exception e) {
                    log("地址无法构造：" + ips.get(i));
                }
            }
            if (out.isEmpty()) return Dns.SYSTEM.lookup(hostname);
            log("DoH " + hostname + " → " + out.size() + " 个地址");
            return out;
        }
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
