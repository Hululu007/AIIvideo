package com.huangguo.mobile;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * spider 通道的主进程侧：把一次能力调用变成一次跨进程 Binder 往返。
 *
 * <p>══════ 为什么存在（2026-10-19 根治记录）════════
 * spider 宿主搬进了 {@code :spider} 独立进程（见 {@link SpiderService} 的类注释），
 * 主进程这边需要一个客户端：绑服务、发请求、等应答。原来
 * {@code SpiderRuntime.call} 里的排队/世代语义<strong>原样搬到这里</strong>：
 *
 * <ul>
 *   <li>{@link #CALL_LOCK}：同一时刻最多一个在途调用。服务进程里的 SpiderRuntime
 *       还有自己的一把锁，这里是第一道 —— 把「排队浪费」挡在跨进程之前；</li>
 *   <li>{@link #GENERATION}：连点时排队的那几次拿到锁就自杀（前端 routeToken
 *       本来就会丢掉它们的结果）。</li>
 * </ul>
 *
 * <p>══════ 线程模型 ══════
 * {@link #call} 必须在后台线程（Binder 同步调用会阻塞这条线程到 spider 跑完，
 * 最长 timeoutMs）。绑定是异步的，首次调用会等绑定完成，最多等 3 秒 ——
 * 绑不上按超时报错，措辞和 spider 超时区分开。
 *
 * <p>══════ 服务生命周期 ══════
 * 首次调用 {@code bindService(BIND_AUTO_CREATE)} 拉起 :spider 进程，之后常驻
 * （渲染进程跟着常驻，省掉每次调用的新建开销）。{@link #shutdown} 在主界面
 * onDestroy 时解绑 + stopService，进程随之回收，不占后台内存。
 */
final class SpiderClient {

    private static final String TAG = "HG.Spider";

    /** 绑定服务最多等这么久。正常的 startService 级绑定是毫秒级，3 秒是十倍余量。 */
    private static final long BIND_TIMEOUT_MS = 3_000L;

    private static final Semaphore CALL_LOCK = new Semaphore(1, true);
    private static final AtomicLong GENERATION = new AtomicLong();

    private static volatile ISpiderChannel channel;
    private static volatile CountDownLatch bound = new CountDownLatch(1);
    private static ServiceConnection connection;
    private static Context appContext;
    private static boolean bindAttempted;

    private SpiderClient() {
    }

    /**
     * 执行一次能力调用。<b>后台线程调用</b>。
     *
     * @return spider 的 payload（契约 §三）
     * @throws IOException 排队超时、被新请求取代、绑定失败、远端报错 —— message
     *                     都是能直接给用户/日志看的那一句
     */
    static JSONObject call(Context ctx, String source, String cap,
                            JSONObject args, int timeoutMs) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("SpiderClient.call 必须在后台线程调用。");
        }
        final long budget = timeoutMs > 0 ? timeoutMs : 15_000L;
        final long gen = GENERATION.incrementAndGet();

        if (!CALL_LOCK.tryAcquire(budget, TimeUnit.MILLISECONDS)) {
            throw new IOException("spider 排队超时（另一个调用还占着宿主）。");
        }
        try {
            if (gen != GENERATION.get()) {
                throw new IOException("被更新的请求取代");
            }
            final ISpiderChannel ch = channelOf(ctx, budget);
            final JSONObject req = new JSONObject()
                    .put("source", source)
                    .put("cap", cap)
                    .put("args", args == null ? new JSONObject() : args)
                    .put("timeoutMs", budget)
                    // 代理快照。内置核心的地址只在主进程内存里，必须显式带过去；
                    // mode 也带上，服务侧用它对齐「用户按了直连」这类全局开关。
                    .put("proxyMode", AiiHttp.proxyModeOrEmpty())
                    .put("effectiveProxy", AiiHttp.effectiveProxy())
                    // 盾后站点的 clearance：主进程有窗口、过盾只要 1~2 秒，
                    // :spider 进程的 WebView 没有窗口，同一个挑战 45 秒都过不去。
                    // 每次调用把主进程的 cookie 带过去，子进程就不用再挑战。
                    // 非盾后站点这里拿到空串，代价只是多一次 ensureWarm 的空判断。
                    .put("cookieHint", cookieHintFor(ctx));
            final String reply = ch.call(req.toString());
            final JSONObject out = new JSONObject(reply);
            if (!out.optBoolean("ok", false)) {
                throw new IOException(out.optString("error", "spider 调用失败"));
            }
            return out.has("payload") ? out.getJSONObject("payload") : new JSONObject();
        } finally {
            CALL_LOCK.release();
        }
    }

    /** 当前源是不是盾后站点；是就顺手把主进程的 clearance 取出来。 */
    private static String cookieHintFor(Context ctx) {
        try {
            SiteProfile profile = SiteProfile.current(ctx);
            String origin = profile == null || profile.origin == null ? "" : profile.origin;
            if (origin.length() == 0 || !SiteFetchWebView.isShielded(origin)) return "";
            return SiteFetchWebView.cookieSnapshot();
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 主界面退出时调：解绑 + 停服务，:spider 进程随之被系统回收。
     * 在飞调用会被服务侧 {@code onDestroy → SpiderRuntime.shutdown()} 打断。
     */
    static void shutdown() {
        final ServiceConnection c = connection;
        connection = null;
        channel = null;
        bound = new CountDownLatch(1);
        bindAttempted = false;
        if (c != null && appContext != null) {
            try {
                appContext.unbindService(c);
                appContext.stopService(new Intent(appContext, SpiderService.class));
            } catch (Throwable ignored) {
            }
        }
    }

    // ── 内部：绑定 ────────────────────────────────────────────────────

    private static ISpiderChannel channelOf(Context ctx, long budgetMs) throws IOException {
        final ISpiderChannel ready = channel;
        if (ready != null) return ready;
        bind(ctx.getApplicationContext());
        try {
            if (!bound.await(Math.min(budgetMs, BIND_TIMEOUT_MS), TimeUnit.MILLISECONDS)) {
                throw new IOException("spider 服务绑定超时（" + BIND_TIMEOUT_MS + "ms）。");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("spider 调用被中断。");
        }
        final ISpiderChannel ch = channel;
        if (ch == null) throw new IOException("spider 服务绑定失败。");
        return ch;
    }

    private static synchronized void bind(final Context app) {
        if (bindAttempted && connection != null) return;
        appContext = app;
        bindAttempted = true;
        bound = new CountDownLatch(1);
        connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                channel = ISpiderChannel.Stub.asInterface(service);
                bound.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                // 进程被杀：置空让下一次调用重新走绑定。bound 保持原值没关系 ——
                // 它只在 ensure 的等待窗口里被消费，断连时那个窗口早已结束。
                channel = null;
            }
        };
        final Intent intent = new Intent(app, SpiderService.class);
        // BIND_AUTO_CREATE：让系统替我们维护 :spider 的生命周期；
        // BIND_IMPORTANT 之外不加任何提升 —— 它是后台工作者，不该抢前台调度。
        if (!app.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            // 绑定请求本身被拒（多为进程处于错误状态）：把闩锁放开，让上面的
            // channelOf 抛「绑定失败」而不是干等到超时。
            bound.countDown();
        }
    }
}
