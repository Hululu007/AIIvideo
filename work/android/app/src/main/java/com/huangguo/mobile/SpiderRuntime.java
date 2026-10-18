package com.huangguo.mobile;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Spider 运行时：一个隐藏 WebView 装一段 spider 源码，调一个能力，把结果的 payload 带回来。
 *
 * <p>阶段 0 只有一条链路：建页 → 注入 spider → 注入驱动 → 等 {@code done} → 销毁。
 * 没有缓存、没有页面复用、没有 crypto（设计文档 §4 里那个 op 留到后面）。
 *
 * <p>══ 为什么是隐藏 WebView，不是 JS 引擎 ══
 * 见 {@code 2026-10-19-spider运行时设计.md} §二：TVBox 生态的 spider 全部是按「有 DOM、
 * 有 Promise、有真实内核」写的，换 Rhino/QuickJS 得先补一层假 DOM，补出来的行为差异
 * 比换语言的收益大。宿主页那条 CSP（设计 §三）把它的能力削到只剩一个 bridge 调用，
 * 安全性由「不暴露」而不是「不执行」来保证。
 *
 * <p>══ 线程模型（和 SiteFetchWebView 同一套，理由也一样）══
 * <ul>
 *   <li>{@link #call} 在<b>后台线程</b>上阻塞执行；主线程只做 WebView 操作
 *       （{@link Handler#post} + {@link CountDownLatch}）。</li>
 *   <li>永不反向阻塞主线程：主线程正忙着跑那个 WebView，堵它等于自己等自己。</li>
 *   <li>bridge 的调用发生在 WebView 的 Java 桥线程上，那条线程可以被安全阻塞 ——
 *       页面里的 {@code ctx.call('net')} 本来就是同步语义，它就在等这份结果。</li>
 * </ul>
 *
 * <p>══ 一次调用一个页面生命周期 ══
 * 调用结束（成功、失败、超时都算）就把 WebView 销毁，下一次调用重新建。见
 * {@link #releasePage} 里的说明。
 */
final class SpiderRuntime {

    /** 阶段 0 的 ABI 号。壳把它和 spider 的 requires 比对。 */
    static final int ABI = 1;

    private static final String TAG = "HG.Spider";

    /**
     * 宿主页地址。<b>必须是 appassets origin</b>，和 {@code app.html} 同源。
     *
     * <p>为什么不能用 {@code loadDataWithBaseURL}：那个的 origin 是 {@code null}，
     * CSP 的行为和 cookie 都跟真 origin 不一样，宿主页那条 CSP 就成了摆设。
     * 用 {@link WebViewAssetLoader} 把 assets/ 挂到
     * {@code https://appassets.androidplatform.net/assets/}，拿到的就是一个正经 https origin。
     */
    private static final String HOST_PAGE =
            "https://appassets.androidplatform.net/assets/spider-host.html";

    /** {@code net} 的单次超时。和契约 §七 一致：AiiHttp 那族默认 15 秒。 */
    private static final int NET_TIMEOUT_S = 15;

    /**
     * 响应体上限 4MB。
     *
     * <p>超了<b>报错而不是截断</b>（设计 §四）：截断的 JSON 后面会在
     * {@code JSON.parse} 里炸，报出来是「解析失败」而不是「太大了」，
     * 查起来比超时还费劲。
     */
    private static final int MAX_NET_BODY = 4 * 1024 * 1024;

    /**
     * 看门狗之外，调用线程自己再等的一点余量。
     *
     * <p>看门狗在主线程上跑到点才会 {@code countDown}，主线程偶尔被别的事占住，
     * 那一下会晚个几十毫秒。等得比看门狗久一点，让超时的措辞永远出自看门狗
     * （用户看到的必须是「spider 超时（15s）」这一句，不能因为主线程抖一下
     * 就变成另一种说法）。
     */
    private static final long AWAIT_GRACE_MS = 2_000L;

    private static final String[] EMPTY_HOSTS = new String[0];

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * 串行化整次调用。
     *
     * <p>WebView 一个进程只有一套渲染宿主，页面状态也只有一份；两个 spider 同时
     * 往同一个页面上注入源码，{@code __spiderSite} 会互相覆盖，症状是「A 源返回了
     * B 源的卡片」，这种 bug 基本查不出来。排队比并发便宜得多。
     */
    private static final Semaphore CALL_LOCK = new Semaphore(1, true);

    // ── 单例状态（全部只在主线程写，读的时候容忍旧值）────────────────────

    private static WebView webView;
    private static View holder;
    private static WebViewAssetLoader loader;

    /** 当前这一次调用的会话。bridge 从这里取，见 {@link #currentSession()}。 */
    private static volatile Session session;

    private SpiderRuntime() {
    }

    /**
     * 一次能力调用的全部状态。<b>跨线程</b>，所以字段该 volatile 的 volatile、
     * 该加锁的加锁。
     *
     * <p>状态放在这里而不是 {@code SpiderBridge} 里：bridge 会被 WebView 在多条
     * 线程上调，让它持状态等于让协议层持状态，出问题时分不清是「页面还活着」还是
     * 「Java 侧没清」。
     */
    static final class Session {
        final String cap;
        final String spiderSource;
        final String argsJson;
        final long timeoutMs;
        final long beganAt = System.currentTimeMillis();
        final CountDownLatch done = new CountDownLatch(1);
        final Runnable watchdog;

        /** 注入后从 {@code __spiderSite.hosts} 读出来的一份快照，net 只认这份。 */
        volatile String[] hosts = EMPTY_HOSTS;

        /**
         * 调用开始那一刻的源配置。net 用它走 {@link NetRoute}（当前源声明的直连/代理
         * 规则），而不是全局代理设置 —— 两条路由真相并存的话，源声明了走代理而全局
         * 是直连时，适配器能取到、spider 取不到，症状是「这个源在网页里好好的，
         * 换成 spider 就打不开」。取不到（没配置）时退回全局，至少不比现在差。
         */
        volatile SiteProfile profile;

        /** onPageFinished 可能对同一页触发多次（子框架、重定向），注入只做一次。 */
        volatile boolean injected;

        /** done 回来（或超时/关闭）之后置位，之后 bridge 的一切 op 都丢弃。 */
        volatile boolean finished;

        volatile boolean timedOut;

        /** 成功时的 payload；失败时留在 {@link #error}。 */
        JSONObject result;
        String error;

        Session(String cap, String spiderSource, String argsJson, long timeoutMs) {
            this.cap = cap;
            this.spiderSource = spiderSource;
            this.argsJson = argsJson;
            this.timeoutMs = timeoutMs;
            this.watchdog = watchdog(this);
        }
    }

    // ── 对外入口 ──────────────────────────────────────────────────────

    /**
     * 把一个 spider 源码字符串装进宿主页并调用一次能力。<b>后台线程调用。</b>
     *
     * <p>调用线程被阻塞到 {@code done} 回来、或者看门狗把页面杀掉为止。
     *
     * @return spider 返回的 payload 原样（阶段 0 里 {@code home} 是
     *         {@code {"items":[...]}}）
     * @throws Exception message 就是给用户/日志看的那一句，例如
     *         {@code "spider 超时（15s）"}、{@code "spider 没有实现 home"}
     */
    static JSONObject call(Context ctx, String spiderSource, String cap,
                           JSONObject args, int timeoutMs) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // 主线程等闩锁 = 等自己把 WebView 跑完，必死锁。这条在开发期就该炸出来。
            throw new IllegalStateException("SpiderRuntime.call 必须在后台线程调用。");
        }
        if (spiderSource == null || spiderSource.length() == 0) {
            throw new IllegalArgumentException("spider 源码是空的。");
        }
        if (cap == null || cap.length() == 0) {
            throw new IllegalArgumentException("spider 调用缺少 cap。");
        }
        final long budget = timeoutMs > 0 ? timeoutMs : NET_TIMEOUT_S * 1000L;

        final Activity activity = activityOf(ctx);
        if (activity == null) {
            throw new IOException("界面还没起来，spider 跑不了（稍等一下再试）。");
        }
        if (activity.isFinishing() || activity.isDestroyed()) {
            throw new IOException("界面正在退出，spider 跑不了。");
        }

        final Session s = new Session(cap, spiderSource,
                args == null ? "{}" : args.toString(), budget);
        // 当前生效的源配置。取不到就留 null，net 退回全局路由（见 Session.profile）。
        try {
            s.profile = HotUpdateManager.get(activity.getApplicationContext()).currentSite();
        } catch (Throwable ignored) {
        }

        // 排队也算在预算里：等锁等掉的钱不该再从看门狗里另算一份，
        // 否则用户点一下要等 30 秒才看到「超时」。
        if (!CALL_LOCK.tryAcquire(budget, TimeUnit.MILLISECONDS)) {
            throw new IOException("spider 排队超时（另一个调用还占着宿主）。");
        }
        try {
            session = s;
            startPage(activity, s);
            if (!s.done.await(s.timeoutMs + AWAIT_GRACE_MS, TimeUnit.MILLISECONDS)) {
                // 走不到这里正常情况：看门狗到点会自己 countDown。只有主线程长时间
                // 被占住才会落到这条兜底上 —— 那也仍然是「超时」，处置一样：杀页。
                s.timedOut = true;
                fail(s, timeoutMessage(s));
            }
            if (s.error != null) throw new IOException(s.error);
            return s.result == null ? new JSONObject() : s.result;
        } finally {
            if (session == s) session = null;
            releasePage(s);
            CALL_LOCK.release();
        }
    }

    /**
     * 释放宿主。<b>Activity onDestroy 必须调</b>，漏了渲染进程常驻。
     *
     * <p>和 SiteFetchWebView 一样：只在 field 上置 null 是不够的，渲染进程不会退，
     * 页面里的定时器还在跑。另外这里顺手把在飞的调用打断，别让调用方干等到超时。
     */
    static void shutdown() {
        final Session s = session;
        session = null;
        if (s != null) fail(s, "spider 宿主已关闭。");
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                destroyOnMain();
            }
        });
    }

    // ── 给 SpiderBridge 用的口子（状态都在这一侧）──────────────────────

    /** 当前会话；没有就是 {@code null}（bridge 据此丢弃一切 op）。 */
    static Session currentSession() {
        return session;
    }

    /**
     * {@code net} 的实现。只放行 https + 当前 spider 自报的 hosts。
     *
     * <p>入参 {@code {url, method, headers, body, charset}}。{@code charset} 可选，
     * 默认 utf-8：既用来解响应体，也用来编码表单里的非 ASCII（老站 GBK 靠它）。
     *
     * <p>跑在 bridge 线程上，会阻塞到 {@link AiiHttp} 返回 —— 这是同步语义该有的样子：
     * 页面里的 {@code ctx.call('net', …)} 就在等这一份 body。
     */
    static JSONObject net(Session s, JSONObject args) throws Exception {
        final long began = System.currentTimeMillis();
        final String url = args.optString("url", "").trim();

        // 只认 https：http 在移动网络里会被运营商插页，spider 拿到的 body 就不是站点的了。
        if (!url.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new IOException("net 拒绝：只允许 https");
        }
        final String host = hostOf(url);
        if (!allowed(s, host)) {
            // 白名单是「spider 自己声明的 hosts」，不是全局表：一个源能碰哪些域名
            // 应该写在源文件里，用户看得见。
            throw new IOException("net 拒绝：主机不在白名单");
        }

        // 打一行路由。spider 这条路最容易出的诊断困境是「App 能取、spider 取不到」，
        // 而两者差的就是路由决策 —— 不把结论打出来，只能靠猜。
        UserSource.log("net " + host + " → " + (NetRoute.mustProxy(host) ? "代理" : "直连")
                + (NetRoute.directOnly(host) ? "（直连表命中）" : ""));

        Map<String, String> headers = headersOf(args);
        String method = args.optString("method", "GET").trim().toUpperCase(Locale.ROOT);
        String body = args.isNull("body") ? null : args.optString("body", "");
        // 老站里有一批 GBK/Big5 的（返回头还常常不带 charset，或者带成 ISO-8859-1），
        // 一律按 UTF-8 解就是整页乱码，而且 spider 侧没有补救手段。所以把解码字符集
        // 交给源自己声明，默认仍是 utf-8。

        try {
            java.nio.charset.Charset charset = charsetOf(args.optString("charset", ""));
            byte[] raw;
            if ("GET".equals(method)) {
                SiteProfile prof = s.profile;
                // 重试一次。出口节点抖的时候实测就是这个形态：第一次 15~30 秒后
                // Connection reset，第二次直接过（App 自己的 /api/home 也是靠
                // 第二次才成功的，见 00:23:33 那条「预热完成 … 32329ms」）。
                // 不重试的话 spider 比 App 内在路径更脆，而用户分不清两者的差别。
                raw = null;
                IOException last = null;
                for (int attempt = 0; attempt < 2 && raw == null; attempt++) {
                    try {
                        raw = prof == null
                                ? AiiHttp.getRaw(url, headers, NET_TIMEOUT_S)
                                : AiiHttp.getRaw(url, headers, NET_TIMEOUT_S, prof);
                    } catch (IOException e) {
                        last = e;
                        if (attempt == 0) {
                            android.util.Log.w("HG.Spider", "op=net 重试一次：" + e.getMessage());
                        }
                    }
                }
                if (raw == null) throw last == null ? new IOException("net 失败") : last;
            } else {
                // 阶段 0 只做表单 POST：设计 §四 的 net 只带 method/headers/body 三样，
                // 没有 content-type 字段，judge 它是不是 JSON 请求只能靠猜 —— 猜错的
                // 代价是对方把 JSON 当表单收了，返回一句「参数缺失」。真实需要 JSON 体
                // 的站（AList 那类）再加第四条 op，不在阶段 0 里发明。
                //
                // 非 UTF-8 的表单体没法靠 Content-Type 说清楚：AiiHttp.postFormRaw 里
                // MediaType 是写死的 `charset=UTF-8`，而 OkHttp 的 BridgeInterceptor
                // 会用 body 的 MediaType 覆盖同名头，外面塞 Content-Type 是无效的
                // （改它就得动 AiiHttp 的公开面，合同不允许）。所以这里退一步：
                // 把 body 里的非 ASCII 字符按目标字符集转成 %XX，实际发出的字节就对了 ——
                // 老服务器大多本来就按自己的默认编码解表单，不看头。
                raw = AiiHttp.postFormRaw(url, encodeFormBody(body == null ? "" : body, charset),
                        headers, NET_TIMEOUT_S);
            }
            if (raw.length > MAX_NET_BODY) {
                throw new IOException("net 失败：响应体 " + (raw.length / 1024)
                        + "KB 超过 4MB 上限（不截断，直接报错）");
            }
            JSONObject out = new JSONObject();
            out.put("ok", true);
            // AiiHttp.getRaw 族对非 2xx 直接抛 IOException（rawWith 里那句
            // `if (!response.isSuccessful()) throw`），所以走到这里的都是 2xx，
            // 而它不把 Response 交出来，真实状态码拿不到。契约 §三 已按这个现状改：
            // 成功记 200，非 2xx 走 ok:false 且没有 status 字段。
            out.put("status", 200);
            out.put("body", new String(raw, charset));
            Log.i(TAG, "op=net host=" + host + " ms=" + (System.currentTimeMillis() - began));
            return out;
        } catch (IOException e) {
            Log.w(TAG, "op=net host=" + host + " ms=" + (System.currentTimeMillis() - began)
                    + " fail=" + e.getMessage());
            throw e;
        }
    }

    /**
     * {@code done} 的落点：把结果写进会话并放闩锁。<b>一次调用只认第一个 done。</b>
     *
     * <p>参数在 bridge 里已经解成 Java 值（那边负责 JSON 编解码），这里只做状态迁移。
     */
    static void finish(Session s, boolean ok, Object payload, String error) {
        if (s == null) return;
        synchronized (s) {
            if (s.finished) return;
            s.finished = true;
            if (!ok) {
                s.error = (error == null || error.length() == 0)
                        ? "spider 调用失败（done 没带 error）" : error;
            } else if (payload == null || payload == JSONObject.NULL) {
                // 一个不返回东西的能力是合法的，别把它当错误。
                s.result = new JSONObject();
            } else if (payload instanceof JSONObject) {
                s.result = (JSONObject) payload;
            } else {
                // 契约把返回值定成 JSONObject（§三「成功：spider 返回的 payload 原样
                // （JSONObject）」），数组/字符串在这里说不出口 —— 不硬塞成 {"items":…}，
                // 那种静默转型以后一定会有人踩。
                s.error = "spider 返回的 payload 不是 JSON 对象（阶段 0 只收对象）";
            }
        }
        if (s.error == null) {
            Log.i(TAG, "cap=" + s.cap + " ok ms=" + (System.currentTimeMillis() - s.beganAt));
        } else {
            Log.w(TAG, "cap=" + s.cap + " fail: " + s.error);
        }
        s.done.countDown();
    }

    // ── 建页 / 注入 / 销毁 ────────────────────────────────────────────

    /** 主线程：建页、挂 bridge、loadUrl、上看门狗。 */
    @SuppressLint("SetJavaScriptEnabled")
    private static void startPage(final Activity activity, final Session s) throws Exception {
        final CountDownLatch created = new CountDownLatch(1);
        final IOException[] failed = new IOException[1];

        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    // 上一页不管是超时留下来的还是 shutdown 没清干净的，这里先兜一次。
                    // 必须执行在主线程上，且和下面的新建在同一个 runnable 里，
                    // 这样「销毁旧的 → 建新的」不会被另一次调用插队。
                    destroyOnMain();

                    WebView wv = new WebView(activity);
                    WebSettings ws = wv.getSettings();
                    ws.setJavaScriptEnabled(true);
                    // 宿主页由 WebViewAssetLoader 从 assets 里供给，不需要 file:// 访问；
                    // spider 也不该有 localStorage（设计 §五：能力集只有 ctx.net / ctx.log）。
                    // 关掉这两样，等于把「绕过 bridge 自己想办法拿数据」的边角再削一层。
                    ws.setAllowFileAccess(false);
                    ws.setAllowContentAccess(false);
                    ws.setDomStorageEnabled(false);

                    wv.setBackgroundColor(0);
                    wv.setWebViewClient(new WebViewClient() {
                        @Override
                        public WebResourceResponse shouldInterceptRequest(WebView view,
                                                                         WebResourceRequest req) {
                            // 和 MainActivity 那个 loader 是同一套：/assets/ 走 APK 里的
                            // 静态文件。这里单独建一份而不是复用 MainActivity 的 ——
                            // 那个是匿名 WebViewClient 里的局部变量，拿不到。loader 本身
                            // 无状态，domain 默认就是 appassets.androidplatform.net，
                            // 所以 origin 和 app.html 完全一致。
                            return loaderOf(view.getContext())
                                    .shouldInterceptRequest(req.getUrl());
                        }

                        @Override
                        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                            // 宿主页不需要导航到任何地方。设计 §三 明确要求这里一律拦掉：
                            // CSP 和老内核之间有能力覆盖的缺口，导航再上一把锁。
                            return true;
                        }

                        @Override
                        public void onPageFinished(WebView view, String url) {
                            inject(view, s);
                        }
                    });
                    wv.setWebChromeClient(new WebChromeClient() {
                        @Override
                        public boolean onCreateWindow(WebView view, boolean isDialog,
                                                      boolean isUserGesture, android.os.Message resultMsg) {
                            // window.open 一律拒绝：新窗口意味着一个我们没配过 CSP 的
                            // WebView，等于把暴露面翻倍。
                            return false;
                        }
                    });
                    // 桥的全局名写死成 __hgSpider：契约 §二 的宿主页代码里就是它。
                    wv.addJavascriptInterface(new SpiderBridge(), "__hgSpider");

                    // 1×1 + GONE：WebView 必须 attach 到 window 才能跑（SiteFetchWebView
                    // 已经验过这条路），但绝不能被看见、也不能参与布局。
                    FrameLayout box = new FrameLayout(activity);
                    box.setVisibility(View.GONE);
                    box.addView(wv, new FrameLayout.LayoutParams(1, 1));
                    ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                    decor.addView(box, new ViewGroup.LayoutParams(1, 1));

                    webView = wv;
                    holder = box;

                    // 看门狗在建页这一步就上，不能等 onPageFinished —— 卡在加载上的
                    // spider 一样是卡住，一样得杀。
                    MAIN.postDelayed(s.watchdog, s.timeoutMs);
                    wv.loadUrl(HOST_PAGE);
                } catch (Throwable e) {
                    failed[0] = new IOException("spider 宿主页建不起来：" + e);
                } finally {
                    created.countDown();
                }
            }
        });

        if (!created.await(s.timeoutMs + AWAIT_GRACE_MS, TimeUnit.MILLISECONDS)) {
            throw new IOException("spider 宿主页建不起来（等主线程超时）。");
        }
        if (failed[0] != null) throw failed[0];
    }

    /**
     * 主线程：注入 spider 源码 → 读一次 hosts → 注入驱动。
     *
     * <p>顺序是硬要求（契约 §三）：源码必须在驱动之前执行完，hosts 必须在驱动之前
     * 读到手 —— 否则第一个 {@code net} 打进来时白名单还是空的，会把合法请求拒掉。
     * JS 在渲染线程上是顺序执行的，所以「源码 → 读 hosts → 驱动」这样连着注入就够了。
     */
    private static void inject(final WebView wv, final Session s) {
        if (session != s || s.finished || s.injected) return;
        s.injected = true;

        // 直接执行，不包函数：spider 文件是 `__spiderSite = {...}` 这种顶层赋值，
        // 包进函数体虽然非严格模式下也能落到全局，但多一层作用域就多一种出错姿势。
        wv.evaluateJavascript(s.spiderSource, null);

        // hosts 取一次快照。让 JS 直接求值成数组，evaluateJavascript 会把它
        // JSON 序列化后交回来 —— 比 JSON.stringify 再解一层字符串干净。
        wv.evaluateJavascript(HOSTS_JS, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String raw) {
                s.hosts = parseHosts(raw);
                if (session == s && !s.finished) {
                    wv.evaluateJavascript(driverJs(s), null);
                }
            }
        });
    }

    /**
     * {@code __spiderSite.hosts} 的读取表达式。spider 没导出对象时给空数组，
     * 后面由 {@code __spiderRun} 报「spider 没有导出 __spiderSite」。
     */
    private static final String HOSTS_JS =
            "(function(){try{var e=window.__spiderSite;var h=e&&e.hosts;"
                    + "return Array.isArray(h)?h:[];}catch(x){return [];}})()";

    /**
     * 驱动脚本：跑一次能力，把结果送回 bridge 的 {@code done}。
     *
     * <p><b>编码只走一层</b>（契约 §二）：{@code done} 传<b>对象</b> {@code r}，
     * 由宿主页的 {@code __spider.call} 负责唯一的 {@code JSON.stringify}。
     * 这里再 stringify 一次的话，Java 收到的是「字符串字面量」，得 parse 两遍才解得开 ——
     * 那正是契约早期版本里的 bug。
     *
     * <p><b>能力名必须拼进来</b>，不能写死 'home'：阶段 0 只调 home，但
     * category/detail/search/play 接进来时不该回头改 Java。
     *
     * <p>为什么整段还包一层 try/catch：{@code __spiderRun} 是宿主页定义的，万一宿主页
     * 没加载成功（或版本不对没有这个函数），抛的是 ReferenceError —— 同步异常 Promise
     * 的 rejection 接不住，不接就成了「没人调 done」，用户看到 15 秒超时而不是
     * 「宿主页没起来」。多包一层，错误立刻变成一句可读的话。
     */
    private static String driverJs(Session s) {
        return "(function(){try{"
                + "__spiderRun(" + JSONObject.quote(s.cap) + "," + JSONObject.quote(s.argsJson) + ")"
                + ".then(function(r){__spider.call('done', r)});"
                + "}catch(e){__spider.call('done',{ok:false,error:String((e&&e.message)||e)});}})()";
    }

    /** 看门狗。跑在主线程上。 */
    private static Runnable watchdog(final Session s) {
        return new Runnable() {
            @Override
            public void run() {
                if (s.finished) return;
                s.timedOut = true;
                // 先杀页再报错：卡住的 spider 在渲染线程里，Java 侧没有任何办法中断它
                // （设计 §七 第 2 条）。所谓「超时处置」就是整页换掉。
                destroyOnMain();
                fail(s, timeoutMessage(s));
            }
        };
    }

    /**
     * 释放这一次调用的页面。
     *
     * <p>为什么不复用页面而是每次重建：页面里留着 {@code __spiderSite}、留着 spider
     * 自己挂的定时器。复用的话第二个源会继承第一个源的全局对象，而「继承」这种 bug
     * 在真机上表现为「A 源没内容但 B 源正常」，查起来极贵。设计 §七 第 1 条要求
     * 「一次调用一个页面生命周期」，销毁是这条要求最硬的实现。
     *
     * <p>代价是每次调用多一次 WebView 建页（实测 100~300ms）。阶段 0 的调用频次
     * 是「进首页一次」，换实现简单值得；真要优化时把这里换成
     * {@code loadUrl("about:blank")} 重载一条即可，上层不用动。
     */
    private static void releasePage(final Session s) {
        // 任何线程都能 removeCallbacks；闩锁和会话状态先落定，再让主线程收拾页面。
        MAIN.removeCallbacks(s.watchdog);
        synchronized (s) {
            s.finished = true;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                // 只销毁「这一次」的页面：如果更新的一次调用已经建好了新页，
                // 那是它的东西，不能动。
                if (session == null || session == s) destroyOnMain();
            }
        });
    }

    /** 真正销毁。**必须在主线程上**，且可重复调用。 */
    private static void destroyOnMain() {
        final WebView wv = webView;
        webView = null;
        try {
            if (wv != null) {
                // stopLoading + about:blank 是 SiteFetchWebView 验过的收尾顺序：
                // 先让渲染线程停下，再切走文档，最后 destroy，不然某些 ROM 上
                // destroy 会撞上还在跑的定时器。
                wv.stopLoading();
                wv.loadUrl("about:blank");
                wv.removeJavascriptInterface("__hgSpider");
                if (wv.getParent() instanceof ViewGroup) {
                    ((ViewGroup) wv.getParent()).removeView(wv);
                }
                wv.destroy();
            }
        } catch (Throwable ignored) {
            // 销毁路径上什么都别抛：它要么在 Activity 退出流程里，要么在超时处置里，
            // 抛出去只会把这两条路一起打断。
        }
        try {
            final View box = holder;
            holder = null;
            if (box != null && box.getParent() instanceof ViewGroup) {
                ((ViewGroup) box.getParent()).removeView(box);
            }
        } catch (Throwable ignored) {
        }
    }

    // ── 小工具 ────────────────────────────────────────────────────────

    /** 会话落定为失败。第一个原因生效，后面的（比如释放页面）不再改它。 */
    private static void fail(Session s, String message) {
        boolean first;
        synchronized (s) {
            first = !s.finished;
            if (first) {
                s.finished = true;
                s.error = message;
            }
        }
        if (!first) return;
        if (s.timedOut) {
            Log.w(TAG, "cap=" + s.cap + " timeout " + s.timeoutMs + "ms");
        } else {
            Log.w(TAG, "cap=" + s.cap + " fail: " + message);
        }
        s.done.countDown();
    }

    /** {@code spider 超时（15s）}。契约 §三 逐字要求的一句话。 */
    private static String timeoutMessage(Session s) {
        long seconds = Math.max(1L, Math.round(s.timeoutMs / 1000.0));
        return "spider 超时（" + seconds + "s）";
    }

    /**
     * 拿一个能挂 WebView 的 Activity。
     *
     * <p>契约的入参是 {@code Context}（调用方可能只有 Application），但 WebView
     * 必须挂到 window 上才能跑，所以这里往上找 Activity：先看传进来的那个，
     * 再退回 {@link MainActivity#host}。
     */
    private static Activity activityOf(Context ctx) {
        if (ctx instanceof Activity) return (Activity) ctx;
        return MainActivity.host;
    }

    /**
     * assets 供给器。惰性建一份就够 —— 它无状态（只持有 Handler 和路径表），
     * 每次请求都 new 一遍纯属浪费。
     *
     * <p>加锁是因为 {@code shouldInterceptRequest} 在部分 WebView 版本上跑在
     * 非主线程，而宿主页加载时可能有并发请求。
     */
    private static WebViewAssetLoader loaderOf(Context ctx) {
        synchronized (SpiderRuntime.class) {
            if (loader == null) {
                loader = new WebViewAssetLoader.Builder()
                        .addPathHandler("/assets/",
                                new WebViewAssetLoader.AssetsPathHandler(ctx.getApplicationContext()))
                        .build();
            }
            return loader;
        }
    }

    private static boolean allowed(Session s, String host) {
        if (s == null || host == null || host.length() == 0) return false;
        String[] hosts = s.hosts;
        if (hosts == null) return false;
        for (int i = 0; i < hosts.length; i++) {
            if (host.equals(hosts[i])) return true;
        }
        return false;
    }

    /**
     * evaluateJavascript 回包是 JSON：数组原样是 {@code ["a","b"]}。
     *
     * <p>包内可见是为了单测（{@code SpiderRuntimeTest}）—— 这是整个白名单链路里
     * 唯一不依赖 Android 的一段，而它错了的表现是「net 全被拒」或「什么都放行」，
     * 两种都不会崩，只会让人以为站点坏了。
     */
    static String[] parseHosts(String raw) {
        if (raw == null || raw.length() == 0 || "null".equals(raw)) return EMPTY_HOSTS;
        try {
            JSONArray arr = new JSONArray(raw);
            String[] out = new String[arr.length()];
            int n = 0;
            for (int i = 0; i < arr.length(); i++) {
                String h = arr.optString(i, "").trim().toLowerCase(Locale.ROOT);
                if (h.length() > 0) out[n++] = h;
            }
            if (n == out.length) return out;
            String[] trimmed = new String[n];
            System.arraycopy(out, 0, trimmed, 0, n);
            return trimmed;
        } catch (Throwable e) {
            // 读不出来就按空白名单算：net 全拒。宁可拒绝，不能放行。
            return EMPTY_HOSTS;
        }
    }

    private static Map<String, String> headersOf(JSONObject args) {
        JSONObject h = args.optJSONObject("headers");
        if (h == null || h.length() == 0) return null;
        Map<String, String> map = new LinkedHashMap<String, String>();
        Iterator<String> it = h.keys();
        while (it.hasNext()) {
            String k = it.next();
            map.put(k, h.optString(k, ""));
        }
        return map;
    }

    /**
     * 解析 {@code net} 的 {@code charset} 参数。空 = utf-8。
     *
     * <p>认不出来的名字直接报错，不做静默回退：静默退回 UTF-8 出来的是一屏乱码，
     * 而这种乱码从日志上完全看不出「是字符集错了还是站点改版了」。
     */
    private static java.nio.charset.Charset charsetOf(String name) throws IOException {
        String n = name == null ? "" : name.trim();
        if (n.length() == 0) return java.nio.charset.Charset.forName("UTF-8");
        try {
            return java.nio.charset.Charset.forName(n);
        } catch (IllegalArgumentException e) {
            throw new IOException("net 失败：不认识的 charset " + n);
        }
    }

    /**
     * 把表单体里的非 ASCII 字符按目标字符集转成 {@code %XX}。
     *
     * <p>为什么不是整串 {@code URLEncoder.encode}：那会把 {@code =} 和 {@code &} 也编码掉，
     * 表单结构直接没了。这里只动非 ASCII —— 它们才是编码会出错的部分，ASCII 原样放行
     * （源自己拼 body 时该转义的部分应该已经转义过了）。
     *
     * <p>UTF-8 不用处理：{@code AiiHttp.postFormRaw} 本来就用 UTF-8 把 String 转字节。
     */
    private static String encodeFormBody(String body, java.nio.charset.Charset cs) {
        if (body == null || body.length() == 0) return "";
        if ("UTF-8".equalsIgnoreCase(cs.name())) return body;
        StringBuilder sb = new StringBuilder(body.length() + 32);
        int i = 0;
        while (i < body.length()) {
            int cp = body.codePointAt(i);
            if (cp < 0x80) {
                sb.append((char) cp);
            } else {
                byte[] bytes = new String(Character.toChars(cp)).getBytes(cs);
                for (int k = 0; k < bytes.length; k++) {
                    sb.append('%');
                    int b = bytes[k] & 0xFF;
                    sb.append(HEX[b >>> 4]).append(HEX[b & 0x0F]);
                }
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    /** 大写十六进制表，避开 {@code String.format} 在每个字节上开一次 Formatter 的开销。 */
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private static String hostOf(String url) {
        try {
            String h = new java.net.URI(url).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (Throwable e) {
            return "";
        }
    }
}
