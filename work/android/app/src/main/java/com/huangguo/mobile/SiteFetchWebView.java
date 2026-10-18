package com.huangguo.mobile;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 站点侧的隐藏 WebView —— 在真浏览器内核里过 Cloudflare，再做同源 fetch。
 *
 * ══ 为什么需要它 ══════════════════════════════════════════════════════
 * missav.live 全站（页面和 /api/* 一起）挂在 Cloudflare 的 JS 挑战后面。
 * 2026-10-18 在本机实测过四条路，全部 403：
 *
 * <pre>
 *   curl + 完整浏览器头                        → 403 Just a moment
 *   curl + 换成安卓 Chrome 的 UA               → 403
 *   curl + 从真 Chrome 里偷出来的 cf_clearance  → 403
 *   okhttp（本 App 的默认出口）                 → 403
 * </pre>
 *
 * <b>把浏览器 cookie 喂给 okhttp 依然过不去</b>，这是最容易走错的一步：
 * {@code cf_clearance} 不只是个 token，它绑的是<b>发出请求那台的 TLS 指纹</b>
 * （JA3/JA4 —— ClientHello 里的密码套件、扩展、曲线顺序）。curl 和 okhttp 的
 * ClientHello 和 Chrome 不一样，云端算出来的指纹对不上就再挑战一次，
 * 而且是每次请求都重新判。所以「先拿 cookie 再走 okhttp」这条路在代码上写得通，
 * 在协议上是死的 —— 别再往那个方向试了。
 *
 * 唯一能过盾的执行环境就是真 WebView：那个挑战本身就是靠跑一段 JS 过的。
 *
 * ══ 为什么不复用 MainActivity 那个 WebView ════════════════════════════
 * 主 WebView 的 origin 是 {@code https://appassets.androidplatform.net}
 * （见 MainActivity onCreate 里的 WebViewAssetLoader），那是我们自己的页面。
 * missav 的页面必须<b>同源</b>才能读到自己的 cookie、能免 CORS 地 fetch ——
 * 而它和 appassets 不同源。所以只能另起一个 WebView，独立 origin。
 *
 * ══ 这东西怎么活 ══════════════════════════════════════════════════════
 * 一个 WebView 必须 attach 到一个 window token 上才能跑，所以不能凭空 new：
 * 我们把它挂进 Activity 的 decor View 里（<b>1×1 像素、GONE、不参与布局</b>），
 * 拿到 window 就有了。Activity onDestroy 时必须 {@link #shutdown()}，
 * 否则渲染进程会一直挂着，定时器和视频缓冲都不退。
 *
 * ══ 线程模型 ═══════════════════════════════════════════════════════════
 * 调用方在 {@code AiiHttp} 的工作线程上（后台线程），所以：
 * <ul>
 *   <li>所有 WebView/UI 操作 → post 到主线程</li>
 *   <li>等待结果 → 在调用线程上 CountDownLatch 阻塞</li>
 *   <li>永不反向阻塞主线程（主线程正忙着跑那个 WebView，堵它等于自己等自己）</li>
 * </ul>
 *
 * ══ 用法 ══════════════════════════════════════════════════════════════
 * 只从后台线程调用：
 * <pre>
 *   String html = SiteFetchWebView.fetchPage("https://missav.live/cn/new");
 * </pre>
 */
final class SiteFetchWebView {

    private static final String TAG = "AIIvideo";
    private static final String LOG = "[site-wv] ";

    /**
     * 加载站点首页用于「过盾」的地址。
     *
     * 为什么是 {@code /dm539/cn/new}：这是站点的「最近更新」列表页，实测是
     * 全站里最轻的一页（12 张卡片，约 400KB），而详情页要塞 recommbee 追踪
     * 和一堆广告组件 —— 拿详情页去过盾明显更慢。
     */
    private static final String WARMUP_URL = "https://missav.live/dm539/cn/new";

    /**
     * 「已经过了盾」的判定。
     *
     * <p><b>不能用状态码或标题</b> —— Cloudflare 挑战页返回的是 403 + 标题
     * 「Just a moment...」，两者都出现在挑战页上，不能当「还没过盾」的证据。
     *
     * <p><b>不能用 {@code .thumbnail}（2026-10-18 真机踩到）</b>：那是卡片容器，
     * 而卡片里第一样东西就是 {@code <img>}。这个 WebView 为了省流量开了
     * {@code setBlockNetworkImage(true)}，被拦掉的图片会让 Chromium 连卡片的
     * 插入都推迟 —— 于是网络其实通了（广告脚本 wheelroot 都加载出来了）、
     * 页面其实也过了盾，但 {@code document.querySelector('.thumbnail')} 一直返回
     * null，最后表现成「过盾 20 秒超时」。判据选在一个会被自己关掉的资源上，
     * 等于自己骗自己。
     *
     * <p>现在用两个判据（<b>都是对着抓下来的真实页面核过的</b>）：
     * <ul>
     *   <li>{@code html[lang]} —— 站点是 {@code <html lang="zh-Hans" data-immersive-translate-page-theme="light">}；
     *       挑战页的 {@code <html>} 上没有 lang。它最稳，因为 html 元素一定先被解析。</li>
     *   <li>{@code .grid} —— 列表页外层栅格（实测 1 个），纯 div，不依赖任何子资源。</li>
     * </ul>
     *
     * <p>一开始还想用 {@code html[data-missav]} 当「站点自己打的标记」，核 fixture
     * 才发现那个属性根本不存在（真实 html 标签是 {@code data-immersive-translate-page-theme}）。
     * 判据这种东西不能凭印象写，<b>必须对着抓下来的页面核一遍</b>。
     *
     * <p>仍然留着 {@code .thumbnail} 吗？不留。它就是被 blockNetworkImage 坑到的那一个，
     * 留着只会让人以为「没命中 .thumbnail 说明没过去」——而实际上它可能已经过去了。
     */
    private static final String PASSED_MARKER = "html[lang], .grid";

    /**
     * 单次请求超时。
     *
     * 20 秒的由来：过盾本身 1.5~4 秒（慢的话十几秒），加上首屏加载。给太少会把
     * 「网络本来就慢」误判成「被墙了」；给太多会让用户在「设置源」那个按钮上
     * 干等 —— 那一步是「先探通再存」，慢到 40 秒以上用户会以为死机了。
     */
    private static final long TIMEOUT_MS = 20_000L;

    /**
     * in-page fetch 等待的上限，比 TIMEOUT_MS 短 —— 盾已经过了，剩下是普通
     * 同源 XHR，不该继续占着过盾那份额外预算。
     */
    private static final long FETCH_TIMEOUT_MS = 15_000L;

    /** 过盾尝试次数。第二次几乎总能过 —— 挑战过一次 Cloudflare 就发了 clearance。 */
    private static final int PASS_ATTEMPTS = 2;

    /** 回包体积上限。超过就当失败 —— 挑战页/错误页可以很大，真页面用不着。 */
    private static final int MAX_BODY = 4 * 1024 * 1024;

    // ── 单例 ──────────────────────────────────────────────────────────

    /**
     * 唯一实例。<b>必须单例</b> —— 一个进程只有一个 WebView 渲染宿主，
     * 建第二个会在部分 ROM 上直接 crash 或静默不渲染。
     *
     * 它跨 origin 复用：WebView 只有一个渲染宿主，但可以先后加载不同 origin
     * 的页面（cookie jar 按域隔离，导航一次换一次）。所以它服务的是「所有需要
     * 盾的站点」，不是某一个站。两个需要盾的源同时要数据时在这把锁上排队。
     */
    private static SiteFetchWebView instance;

    private static synchronized SiteFetchWebView get() {
        if (instance != null && instance.alive) return instance;
        if (instance != null) {
            // 上一个已经没用了（宿主 Activity 换了）。真正销毁它 ——
            // 只在 field 上置 null 是不够的，渲染进程不会退。
            final SiteFetchWebView old = instance;
            instance = null;
            old.alive = false;
            onMain(new Runnable() {
                @Override
                public void run() {
                    old.destroyNow();
                }
            });
        }
        instance = new SiteFetchWebView();
        return instance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());

    /**
     * 宿主 Activity。**弱引用** —— 强引用会把这个 Activity 拖过它自己的生命周期。
     */
    private WeakReference<Activity> hostRef = new WeakReference<Activity>(null);

    private WebView webView;
    private View holder;
    private volatile boolean alive;
    private volatile boolean passed;

    /** 串行化 fetch：页面状态只有一份，并发请求会互相污染。 */
    private final Semaphore fetchLock = new Semaphore(1, true);

    /**
     * 串行化「过盾」。
     *
     * 单独一把锁而不是复用 fetchLock：过盾是<b>有状态</b>的，中途不能有别的
     * fetch 打进来把页面搅了；而且首次调用要跑完整套挑战流程，比普通 fetch 慢得多。
     */
    private final Semaphore passLock = new Semaphore(1, true);

    /** 在飞的 fetch，按 id 匹配回包。 */
    private final Map<String, Pending> waiting = new ConcurrentHashMap<String, Pending>();
    private final java.util.concurrent.atomic.AtomicInteger seq =
            new java.util.concurrent.atomic.AtomicInteger();

    private SiteFetchWebView() {}

    private static final class Pending {
        Reply reply;
        final CountDownLatch latch = new CountDownLatch(1);
        /**
         * 二进制请求专用：页面那边按块回调 {@code bin()}，这里累加。
         *
         * 不走 {@link #reply} 的原因很实在 —— 那条路是「JSON 里塞 body 字符串」，
         * 二进制过一轮 responseText 就被当成 UTF-8 解了，字节全毁。视频分片
         * 必须原样，所以单开一条只搬 base64、不碰文本的通道。
         */
        final java.io.ByteArrayOutputStream bin = new java.io.ByteArrayOutputStream();
        int binStatus;
    }

    /** 回包信封。字段对应页面内 fetch 的四个回包键。 */
    private static final class Reply {
        final boolean ok;
        final int status;
        final String body;
        final String error;

        Reply(boolean ok, final int status, String body, String error) {
            this.ok = ok;
            this.status = status;
            this.body = body == null ? "" : body;
            this.error = error == null ? "" : error;
        }
    }

    // ── 对外入口 ──────────────────────────────────────────────────────

    /**
     * 在站点页面里发一次同源 GET，把响应体原样带回。
     *
     * 调用线程被阻塞到拿到响应或超时。<b>只从后台线程调用</b>。
     *
     * @param absoluteUrl 站点内的绝对地址，例如 {@code https://missav.live/cn/fct-210}
     */
    static String fetchPage(String absoluteUrl) throws Exception {
        MainActivity host = MainActivity.host;
        if (host == null) {
            throw new IOException("界面还没起来，站点侧请求发不出去（稍等一下再试）。");
        }
        SiteFetchWebView wv = get();
        wv.bindHost(host);
        return wv.doFetch(absoluteUrl);
    }

    /** 站点 URL 是否属于「需要真 WebView 兜着」的站点。 */
    static boolean handles(String url) {
        if (url == null) return false;
        String host = hostOf(url);
        return host.length() > 0 && (host.equals("missav.live") || host.endsWith(".missav.live"));
    }

    /**
     * 在站点页面里发一次跨源 GET，把<b>响应字节</b>原样带回。
     *
     * <p>存在的唯一理由是视频：{@code surrit.com} 那个 m3u8 / 分片域名用 OkHttp
     * 请求永远是 Cloudflare 的 {@code Attention Required}（2026-10-18 真机：
     * {@code [relay] 上游 403 https://surrit.com/…/playlist.m3u8}），而同一个
     * 地址在 WebView 里 fetch 是 200 —— 差的不是 cookie（实测跨源、不带
     * credentials 也是 200），是 TLS 指纹/UA 那一层。OkHttp 改不了，只能借
     * 浏览器的栈。
     *
     * <p>为什么一次拿完而不是流式：分片实测 236KB（720p，4 秒），1080p 也就
     * 几百 KB。整块 base64 过 bridge 一次到位，比维护背压简单得多，也不会因为
     * VLC 暂时不读就把 WebView 的渲染线程卡住。
     *
     * <p><b>只从后台线程调用。</b>超时和 {@link #fetchPage} 共用一套常量。
     */
    static byte[] fetchBytes(String absoluteUrl) throws Exception {
        MainActivity host = MainActivity.host;
        if (host == null) {
            throw new IOException("界面还没起来，站点侧请求发不出去（稍等一下再试）。");
        }
        SiteFetchWebView wv = get();
        wv.bindHost(host);
        return wv.doFetchBytes(absoluteUrl);
    }

    /**
     * 释放。<b>必须在 Activity onDestroy 里调用。</b>
     *
     * 漏掉的后果不是「下次再建一个」那么轻：渲染进程留着，页面里的定时器和
     * 视频缓冲都不退，耗电和内存都在涨。
     */
    static void shutdown() {
        final SiteFetchWebView self;
        synchronized (SiteFetchWebView.class) {
            self = instance;
            instance = null;
        }
        if (self == null) return;
        self.alive = false;
        onMain(new Runnable() {
            @Override
            public void run() {
                self.destroyNow();
            }
        });
    }

    // ── 真正干活 ──────────────────────────────────────────────────────

    private void bindHost(Activity activity) {
        if (hostRef.get() != activity) {
            hostRef = new WeakReference<Activity>(activity);
            // 宿主换了，之前那个页面上的 cookie 不再可信（IP 可能变了，
            // 而 clearance 是绑 IP + TLS 指纹的）。重新过一次。
            passed = false;
        }
    }

    private String doFetch(String absoluteUrl) throws Exception {
        ensureWarm();
        acquire(fetchLock, TIMEOUT_MS, "站点侧请求排队超时。");
        try {
            // 再确认一次盾还在。过期时站点不会报错，只会给一份 403 的挑战页 ——
            // 那时上层会把挑战页当列表解析，症状是「首页空着但不报错」，很难查。
            if (!passed) ensureWarm();

            String id = String.valueOf(seq.incrementAndGet());
            final Pending pending = new Pending();
            waiting.put(id, pending);

            final String b64 = Base64.encodeToString(
                    specJson(absoluteUrl).getBytes("UTF-8"), Base64.NO_WRAP);

            onMain(new Runnable() {
                @Override
                public void run() {
                    WebView wv = webView;
                    if (wv == null) {
                        deliver(id, new Reply(false, 0, "", "no-webview"));
                        return;
                    }
                    // 注入这一次的 id，再跑 fetch 脚本 —— 页面里的 __hgSiteDone
                    // 会带着同一个 id 回调 __hgSiteRecv。
                    wv.evaluateJavascript(
                            "window.__hgSiteId=" + quote(id) + ";"
                                    + String.format(FETCH_JS, quote(b64)),
                            null);
                }
            });

            boolean settled = pending.latch.await(FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            waiting.remove(id);

            if (!settled) {
                throw new IOException("站点页面里那一次请求超时了（"
                        + (FETCH_TIMEOUT_MS / 1000) + " 秒没回）。"
                        + "网络慢的时候它会先失败一次，再进一次通常就出内容。");
            }
            Reply reply = pending.reply;
            if (reply == null || !reply.ok) {
                throw new IOException("站点页面里那一次请求失败："
                        + (reply == null ? "没回话" :
                        (reply.error.length() > 0 ? reply.error : ("HTTP " + reply.status))));
            }
            if (reply.body.length() > MAX_BODY) {
                throw new IOException("站点回了一份 " + (reply.body.length() / 1024)
                        + "KB 的东西，超出上限 —— 多半是拿到了拦截页而不是内容。");
            }
            // 挑战页和真 403 都是 403，靠 body 区分：挑战页一定含这几个特征。
            if (looksLikeChallenge(reply.body)) {
                passed = false;          // 盾掉了，下次重新过
                throw new IOException("站点那边重新要了一次访问验证。"
                        + "网络抖动或者等得太久都会这样 —— 再进一次通常就过了。");
            }
            return reply.body;
        } finally {
            fetchLock.release();
        }
    }

    // ── 页面内 fetch ──────────────────────────────────────────────────

    /**
     * 页面内的 fetch 脚本。
     *
     * <b>为什么走页面内 fetch 而不是 Java 里发</b>：跨源会被 CORS 拦掉
     * （站点响应头有 {@code access-control-allow-origin: *}，但那只对<b>跨源</b>
     * XHR 有用，而我们要的恰恰是同源 —— 只有同源才自动带上 cf_clearance 和
     * user_uuid）。
     *
     * <b>为什么请求体传 base64</b>：列表页 HTML 可能几百 KB，拼进
     * {@code evaluateJavascript} 的源码要处理各种转义；base64 只含
     * {@code [A-Za-z0-9+/=]}，直接拼最安全。代价是 +33% 体积和一次解码，可忽略。
     *
     * <b>为什么必须走 XHR 而不是 fetch()</b>：页面里那条 CSP 禁止了 connect-src 之外的
     * 一些源，而 XHR 的 onload 拿到的 responseText 在这里够用；更重要的是 fetch()
     * 是 Promise，回包还得再包一层才能送回 Java。这里能省一层是一层。
     */
    private static final String FETCH_JS =
            "(function (b64) {"
            + "  var spec = JSON.parse(decodeURIComponent(escape(atob(b64))));"
            + "  var id = String(window.__hgSiteId || '0');"
            + "  var xhr = new XMLHttpRequest();"
            + "  xhr.open(spec.method || 'GET', spec.url, true);"
            + "  try { xhr.withCredentials = true; } catch (e) {}"
            + "  if (spec.headers) { for (var k in spec.headers) {"
            + "    try { xhr.setRequestHeader(k, spec.headers[k]); } catch (e) {} } }"
            + "  xhr.timeout = 15000;"
            + "  function done(r) {"
            + "    try {"
            + "      function b64enc(s) {"
            + "        return btoa(unescape(encodeURIComponent(s))); }"
            + "      window.__hgSiteRecv.recv(b64enc(id), b64enc(JSON.stringify(r)));"
            + "    } catch (e) {}"
            + "  }"
            + "  xhr.onload = function () {"
            + "    done({ ok: true, status: xhr.status, body: xhr.responseText }); };"
            + "  xhr.onerror = function () { done({ ok: false, status: 0, body: '', error: 'network' }); };"
            + "  xhr.ontimeout = function () { done({ ok: false, status: 0, body: '', error: 'timeout' }); };"
            + "  xhr.send(spec.body || null);"
            + "  return 1;"
            + "})(%s)";

    // ── 页面内 fetch（二进制） ────────────────────────────────────────

    /**
     * 二进制取流的执行体。
     *
     * <p>故意<b>不抢 {@link #fetchLock}</b>：播放时 VLC 会连着要好几个分片，
     * 串行化只会把开播拖到几秒以上。这条路是幂等的（只为拿字节，不写页面状态），
     * 并发安全；线程归属靠每次调用自带 id，不依赖 {@code window.__hgSiteId}
     * 那个全局变量（并发时会被覆盖）。
     */
    private byte[] doFetchBytes(String absoluteUrl) throws Exception {
        ensureWarm();

        String id = String.valueOf(seq.incrementAndGet());
        final Pending pending = new Pending();
        waiting.put(id, pending);

        final String b64 = Base64.encodeToString(
                absoluteUrl.getBytes("UTF-8"), Base64.NO_WRAP);
        final String js = "(function(b64,id){" + BIN_JS_BODY + "})("
                + quote(b64) + "," + quote(id) + ")";

        onMain(new Runnable() {
            @Override
            public void run() {
                WebView wv = webView;
                if (wv == null) {
                    deliver(id, new Reply(false, 0, "", "no-webview"));
                    return;
                }
                wv.evaluateJavascript(js, null);
            }
        });

        // 分片要等整块落地，给的时间比普通 fetch 宽一倍：236KB 实测 810ms，
        // 但代理抖动时会更久，而这里是播放链路上唯一一帧，卡住就是黑屏。
        long budget = FETCH_TIMEOUT_MS * 2;
        boolean settled = pending.latch.await(budget, TimeUnit.MILLISECONDS);
        waiting.remove(id);

        if (!settled) {
            throw new IOException("取流超时（" + (budget / 1000) + " 秒没回）：" + absoluteUrl);
        }
        Reply reply = pending.reply;
        if (reply != null && !reply.ok) {
            throw new IOException("取流失败：" + (reply.error.length() > 0
                    ? reply.error : ("HTTP " + reply.status)));
        }
        byte[] data = pending.bin.toByteArray();
        if (pending.binStatus >= 400) {
            throw new IOException("取流被拒：HTTP " + pending.binStatus);
        }
        if (data.length == 0) {
            throw new IOException("取流回来是空的：HTTP " + pending.binStatus);
        }
        return data;
    }

    /**
     * 页面侧二进制取流脚本（只含函数体，id/url 由外面拼进去）。
     *
     * <p>为什么不用 {@code responseText}：那会把二进制当 UTF-8 解，TS 分片
     * 过一遍就废了。这里读 {@code arrayBuffer} 的 reader，按块转 base64 回传。
     *
     * <p><b>不带 credentials</b>（2026-10-18 真机踩到）：第一版写了
     * {@code credentials:'include'}，结果每一次都 {@code TypeError: Failed to fetch}。
     * 原因是 surrit 的 CORS 回的是 {@code Access-Control-Allow-Origin: *}，
     * 而浏览器规定「带凭据的跨源请求」不接受通配 —— 通配只能配
     * {@code credentials:'omit'}。去掉之后立刻 200，说明这条路压根不需要 cookie：
     * 放行我们的是浏览器栈本身，不是某个令牌。
     *
     * <p>为什么分块而不是攒完再传：{@code String.fromCharCode.apply} 参数一多
     * 就爆栈（实测 8 万参数左右），所以每块只取 8192 字节拼一次 {@code btoa}。
     * 分块还能让 Java 侧边收边攒，内存峰值只有整块大小。
     */
    private static final String BIN_JS_BODY =
            "var url=decodeURIComponent(escape(atob(b64)));"
            + "var idb=btoa(id);"
            + "function send(k,p){try{window.__hgSiteRecv.bin(idb,k,p||'')}catch(e){}}"
            + "function enc(u8){var s='',CH=8192;"
            + "  for(var i=0;i<u8.length;i+=CH){"
            + "    s+=String.fromCharCode.apply(null,u8.subarray(i,i+CH));}"
            + "  return btoa(s);}"
            + "fetch(url).then(function(r){"
            + "  if(!r.ok){send('E'+r.status,'');return;}"
            + "  if(!r.body||!r.body.getReader){"
            + "    return r.arrayBuffer().then(function(b){"
            + "      send('S'+r.status,'');send('C',enc(new Uint8Array(b)));send('Z','');});}"
            + "  var rd=r.body.getReader();send('S'+r.status,'');"
            + "  (function pump(){rd.read().then(function(x){"
            + "    if(x.done){send('Z','');return;}"
            + "    send('C',enc(x.value));pump();"
            + "  },function(e){send('X'+e,'');});})();"
            + "}).catch(function(e){send('F'+e,'');});";


    final class SiteBridge {
        @JavascriptInterface
        public void recv(final String idB64, final String payloadB64) {
            Reply reply;
            try {
                String payload = new String(Base64.decode(payloadB64, Base64.DEFAULT), "UTF-8");
                JSONObject o = new JSONObject(payload);
                reply = new Reply(o.optBoolean("ok", false), o.optInt("status", 0),
                        o.optString("body", ""), o.optString("error", ""));
            } catch (Throwable e) {
                reply = new Reply(false, 0, "", "bad-payload");
            }
            String id;
            try {
                id = new String(Base64.decode(idB64, Base64.DEFAULT), "UTF-8");
            } catch (Throwable e) {
                id = "";
            }
            Pending p = id.length() == 0 ? null : waiting.get(id);
            if (p != null) {
                p.reply = reply;
                p.latch.countDown();
            }
        }

        /**
         * 二进制通道。页面那边每块调一次。
         *
         * <p>协议（{@code kind} 的首字符）：
         * <ul>
         *   <li>{@code S<code>} 响应头到了，<code>code</code> 是 HTTP 状态；</li>
         *   <li>{@code C} 一块数据，{@code payload} 是 base64；</li>
         *   <li>{@code Z} 正常结束；</li>
         *   <li>{@code E}/{@code X}/{@code F} 失败，{@code code}/{@code payload}
         *       里是状态码或异常名。</li>
         * </ul>
         *
         * <p><b>这个方法必须立刻返回。</b>它在 WebView 的 Java 桥线程上被调用，
         * 一阻塞就会把渲染线程一起卡住 —— 所以只做 {@code write} 到内存流，
         * 绝不碰 socket、不做 IO。
         */
        @JavascriptInterface
        public void bin(final String idB64, final String kind, final String payload) {
            if (kind == null || kind.length() == 0) return;
            String id;
            try {
                id = new String(Base64.decode(idB64, Base64.DEFAULT), "UTF-8");
            } catch (Throwable e) {
                return;
            }
            Pending p = id.length() == 0 ? null : waiting.get(id);
            if (p == null) return;
            char tag = kind.charAt(0);
            try {
                if (tag == 'S') {
                    p.binStatus = Integer.parseInt(kind.substring(1).trim());
                    return;
                }
                if (tag == 'C') {
                    if (payload == null || payload.length() == 0) return;
                    p.bin.write(Base64.decode(payload, Base64.DEFAULT));
                    return;
                }
                if (tag == 'Z') {
                    p.latch.countDown();
                    return;
                }
                // E / X / F：把状态码或异常名塞进 reply，让上层能报出原因。
                int code = 0;
                if (tag == 'E') {
                    try {
                        code = Integer.parseInt(kind.substring(1).trim());
                    } catch (Throwable ignored) {
                    }
                }
                p.reply = new Reply(false, code, "",
                        tag == 'E' ? ("HTTP " + code) : kind.substring(1));
                p.latch.countDown();
            } catch (Throwable e) {
                p.reply = new Reply(false, 0, "", "bin-bridge");
                p.latch.countDown();
            }
        }
    }

    private void deliver(String id, Reply reply) {
        Pending p = waiting.get(id);
        if (p != null) {
            p.reply = reply;
            p.latch.countDown();
        }
    }

    // ── 过盾 ──────────────────────────────────────────────────────────

    /**
     * 确保盾已过。幂等。
     *
     * 流程：建 WebView → 挂进宿主 decor → 载入站点页 → 轮询文档里有没有出现
     * 列表容器 —— 出现即过盾成功（Cloudflare 通过后会自己导航到目标 URL，
     * 不需要我们再动作一次）。
     */
    private void ensureWarm() throws Exception {
        if (passed && webView != null) return;
        acquire(passLock, TIMEOUT_MS, "过盾排队超时。");
        try {
            // 双重检查：等锁期间可能已经有人过好了。
            if (passed && webView != null) return;
            for (int attempt = 1; attempt <= PASS_ATTEMPTS; attempt++) {
                long began = System.currentTimeMillis();
                try {
                    ensureWebView();
                    boolean ok = loadAndAwait();
                    passed = ok;
                    log("过盾第 " + attempt + " 次" + (ok ? "成功" : "超时")
                            + "，耗时 " + (System.currentTimeMillis() - began) + "ms");
                    if (ok) return;
                } catch (Exception e) {
                    passed = false;
                    log("过盾第 " + attempt + " 次失败：" + e.getMessage());
                }
            }
            throw new IOException("没通过站点的访问验证（Cloudflare）。"
                    + "那道门要真浏览器内核跑一段 JS 才开得了 —— 网络不通、或者出口 IP "
                    + "被标记过，都过不去。换个网络或者开启代理再试一次。");
        } finally {
            passLock.release();
        }
    }

    /** 载入过盾页，等到列表容器出现。 */
    private boolean loadAndAwait() throws Exception {
        onMain(new Runnable() {
            @Override
            public void run() {
                WebView wv = webView;
                if (wv != null) wv.loadUrl(WARMUP_URL);
            }
        });
        return awaitPass();
    }

    /**
     * 等页面里出现列表容器。
     *
     * <b>轮询而不是只等 onPageFinished</b>：Cloudflare 通过后会<b>再导航一次</b>，
     * 第一次 onPageFinished 触发时可能还是挑战页。
     */
    private boolean awaitPass() throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            final String[] box = new String[1];
            final CountDownLatch latch = new CountDownLatch(1);
            onMain(new Runnable() {
                @Override
                public void run() {
                    WebView wv = webView;
                    if (wv == null) {
                        latch.countDown();
                        return;
                    }
                    // PASSED_MARKER 现在是一串逗号分隔的选择器，直接丢给 querySelector
                    // 即可（它认选择器列表，命中任意一个就返回元素）。
                    wv.evaluateJavascript(
                            "(document.querySelector('" + PASSED_MARKER + "') ? '1' : '0')",
                            new ValueCallback<String>() {
                                @Override
                                public void onReceiveValue(String v) {
                                    box[0] = v;
                                    latch.countDown();
                                }
                            });
                }
            });
            if (latch.await(2, TimeUnit.SECONDS)) {
                // evaluateJavascript 的返回值是 **JSON 序列化** 的：JS 里返回 '1'，
                // Java 侧拿到的是带引号的 "1"（三个字符）。直接 equals("1") 永远不等 ——
                // 2026-10-18 真机踩到：页面早就过盾了（12 张卡片都在），
                // 判定却轮询满 20 秒，症状就是「过盾超时」。剥掉引号再比。
                if ("1".equals(unquote(box[0]))) return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    // ── WebView 生命周期 ──────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private void ensureWebView() throws Exception {
        if (webView != null && alive) return;
        Activity host = hostRef.get();
        if (host == null) throw new IOException("界面已经不在了，站点侧请求发不出去。");
        if (host.isFinishing() || host.isDestroyed()) {
            throw new IOException("界面正在退出，站点侧请求发不出去。");
        }

        final Activity activity = host;
        final CountDownLatch latch = new CountDownLatch(1);
        final IOException[] failed = new IOException[1];

        onMain(new Runnable() {
            @Override
            public void run() {
                try {
                    ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();

                    WebView wv = new WebView(activity);
                    WebSettings s = wv.getSettings();
                    s.setJavaScriptEnabled(true);
                    s.setDomStorageEnabled(true);
                    // ★ UA 必须去掉 WebView 标记（2026-10-18 真机实测）
                    //
                    // 系统 WebView 默认 UA 里有 "; wv)"，Cloudflare 看到它就直接把
                    // 挑战判成「不支持的环境」，挑战页转圈永远不放行 —— 网络通、
                    // 代理挂上了、JS 也在跑，就是过不去。表现是 awaitPass 20 秒超时
                    // 连 html[lang] 都等不到（挑战页根本没渲染）。
                    //
                    // 换成不带 wv 的 Chrome 移动版 UA，挑战把它当普通 Chrome 处理，
                    // 正常放行。
                    s.setUserAgentString(
                            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36"
                                    + " (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36");
                    // 关掉用不到的能力：减少攻击面，也让加载快一点。
                    s.setAllowFileAccess(false);
                    s.setAllowContentAccess(false);
                    // ★ 不要设 setBlockNetworkImage(true) ★
                    //
                    // 2026-10-18 真机踩到：开了它之后，懒加载的 <img> 被拦，
                    // Chromium 连卡片容器的插入都推迟，document.querySelector('.thumbnail')
                    // 20 秒都返回 null —— 明明网络是通的、盾已经过了，判定却永远不成立。
                    //
                    // 代价是过盾那一下会真的下一批图（首屏 12 张封面，每张几十 KB），
                    // 换来的是判据可靠。这个交换值得：判据不可靠的代价是「这个源永远
                    // 加不进来」，而多下几百 KB 只是一次握手。

                    wv.setWebViewClient(new WebViewClient() {
                        @Override
                        public boolean shouldOverrideUrlLoading(WebView view,
                                                              android.webkit.WebResourceRequest r) {
                            // 站点内部的跳转（挑战通过后的重定向、年龄确认页）都不拦。
                            // 跳去别的域名也放过 —— 站点自己会处理，我们只关心
                            // 最终落在不在带 .thumbnail 的那个页面上。
                            return false;
                        }
                    });
                    wv.addJavascriptInterface(new SiteBridge(), "__hgSiteRecv");
                    applyProxy(wv);

                    // 1×1 + GONE：必须 attach 到 window 才能跑，但绝不能被看见、
                    // 也不能参与布局（不然会顶出一个 1 像素的洞）。
                    FrameLayout box = new FrameLayout(activity);
                    box.setVisibility(View.GONE);
                    box.addView(wv, new FrameLayout.LayoutParams(1, 1));
                    decor.addView(box, new ViewGroup.LayoutParams(1, 1));

                    webView = wv;
                    holder = box;
                    alive = true;
                } catch (Throwable e) {
                    failed[0] = new IOException("站点侧 WebView 建不起来：" + e);
                } finally {
                    latch.countDown();
                }
            }
        });

        if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IOException("站点侧 WebView 建不起来（等主线程超时）。");
        }
        if (failed[0] != null) throw failed[0];
    }

    /**
     * 把当前生效的代理挂到这个 WebView 上。
     *
     * <p><b>这一步不能省</b>（2026-10-18 真机踩到）：隐藏 WebView 是**另一个网络栈**，
     * {@code AiiHttp} 挂在 OkHttp 上的 {@code proxy()} 对它毫无作用。而需要过 Cloudflare
     * 的站点恰恰全都是「国内直连不通、必须走代理」的那种 —— 不挂代理的表现是
     * 「过盾 20 秒超时」，日志只说超时、看不出是网络根本不通，白查半天。
     *
     * <p>用的是 {@link androidx.webkit.ProxyController}（AndroidX WebKit）而不是
     * {@code android.webkit.ProxyController}：后者 API 21+ 就有，但要求 WebView
     * 跑在独立进程里才生效（{@code setProcessData} 那套），同进程里调它会抛。
     * AndroidX 这份是对同进程场景的兼容封装，正是我们要的。
     *
     * <p>抛异常一律吞掉并退回直连 —— 代理配错不该让整个加源流程崩掉，
     * 表现退回成「过不去」而不是「崩」。
     */
    private void applyProxy(final WebView wv) {
        try {
            final java.net.Proxy proxy = AiiHttp.effectiveJavaProxy();
            androidx.webkit.ProxyController controller = androidx.webkit.ProxyController.getInstance();

            if (proxy == null || proxy == java.net.Proxy.NO_PROXY) {
                // 直连不是「不设置」，是显式清掉 —— 否则上一次设的代理会留在
                // WebView 的全局状态里，用户切回直连后请求还照样走代理。
                controller.clearProxyOverride(MAIN, RUNNER);
                log("直连（当前没有生效的代理）");
                return;
            }
            java.net.SocketAddress address = proxy.address();
            if (!(address instanceof java.net.InetSocketAddress)) {
                log("代理地址不是 IP:PORT 形态，跳过：" + address);
                return;
            }
            java.net.InetSocketAddress isa = (java.net.InetSocketAddress) address;
            // 方案要跟代理类型对上：socks5 的地址写成 http:// 会让 WebView
            // 按 HTTP CONNECT 去连一个 SOCKS 端口，连上也没有响应。
            String scheme = proxy.type() == java.net.Proxy.Type.SOCKS ? "socks5" : "http";
            String url = scheme + "://" + isa.getHostString() + ":" + isa.getPort();

            java.util.List<androidx.webkit.ProxyConfig.ProxyRule> rules =
                    java.util.Collections.singletonList(
                            new androidx.webkit.ProxyConfig.ProxyRule(
                                    androidx.webkit.ProxyConfig.MATCH_ALL_SCHEMES, url));
            androidx.webkit.ProxyConfig config = new androidx.webkit.ProxyConfig(
                    rules, java.util.Collections.<String>emptyList(), false);

            controller.setProxyOverride(config, MAIN, new Runnable() {
                @Override
                public void run() {
                    log("WebView 代理已挂上");
                }
            });
            log("WebView 走代理：" + proxy.type() + " " + url);
        } catch (Throwable e) {
            // 挂不上就退回直连 —— 代理配错不该让整个加源流程崩掉，
            // 表现退回成「过不去盾」，而不是崩在设置代理这一步。
            log("挂代理失败，退回直连：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    /** {@code setProxyOverride} 要求一个 Executor 和一个完成回调，两边都在主线程。 */
    private static final java.util.concurrent.Executor MAIN =
            new java.util.concurrent.Executor() {
                @Override
                public void execute(Runnable command) {
                    command.run();
                }
            };

    private static final Runnable RUNNER = new Runnable() {
        @Override
        public void run() {
        }
    };

    /** 真正销毁。**必须在主线程上**。 */
    private void destroyNow() {
        try {
            if (webView != null) {
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.removeJavascriptInterface("__hgSiteRecv");
                if (holder != null) {
                    if (holder instanceof ViewGroup) ((ViewGroup) holder).removeView(webView);
                }
                webView.destroy();
                webView = null;
            }
            if (holder != null && holder.getParent() instanceof ViewGroup) {
                ((ViewGroup) holder.getParent()).removeView(holder);
            }
            holder = null;
            passed = false;
        } catch (Throwable ignored) {
            // 销毁路径上什么都别做：抛出去只会打断 Activity 的销毁流程。
        }
    }

    // ── 小工具 ────────────────────────────────────────────────────────

    /** 剥掉 evaluateJavascript 回包的 JSON 引号。"1" → 1；null/空原样返回。 */
    private static String unquote(String v) {
        if (v == null) return "";
        String t = v.trim();
        if (t.length() >= 2 && t.charAt(0) == '"' && t.charAt(t.length() - 1) == '"') {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    private static void onMain(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }

    private static void acquire(Semaphore lock, long ms, String message) throws Exception {
        if (!lock.tryAcquire(ms, TimeUnit.MILLISECONDS)) throw new IOException(message);
    }

    private static String specJson(String url) throws Exception {
        JSONObject spec = new JSONObject();
        spec.put("url", url);
        spec.put("method", "GET");
        return spec.toString();
    }

    /** JS 字符串字面量。id 和 base64 都只含安全字符，但仍然统一走这里。 */
    private static String quote(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /** Cloudflare 挑战页的特征，命中任意一个就算挑战页。 */
    static boolean looksLikeChallenge(String html) {
        if (html == null) return false;
        return html.contains("Just a moment")
                || html.contains("challenges.cloudflare.com")
                || html.contains("cf-browser-verification")
                || html.contains("_cf_chl_opt");
    }

    static String hostOf(String url) {
        try {
            String h = new java.net.URI(url).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    private static void log(String message) {
        try {
            Log.d(TAG, LOG + message);
        } catch (Throwable ignored) {
        }
    }

    // ── 站点侧要过盾的域名 ────────────────────────────────────────────

    /** 要过盾的域名：站点本身，以及它的视频 CDN。 */
    private static final String[] SHIELDED_HOSTS = { "missav.live", "surrit.com" };

    static boolean isShielded(String url) {
        String host = hostOf(url);
        if (host.length() == 0) return false;
        for (int i = 0; i < SHIELDED_HOSTS.length; i++) {
            if (host.equals(SHIELDED_HOSTS[i]) || host.endsWith("." + SHIELDED_HOSTS[i])) return true;
        }
        return false;
    }
}
