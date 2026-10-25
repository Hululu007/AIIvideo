package com.huangguo.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * 埋点与崩溃上报的门面。全 App 只有这一个入口。
 *
 * ── 一条事件长什么样 ────────────────────────────────────────────────
 * {"id":"uuid","at":1729680000123,"session":"uuid","kind":"play",
 *  "name":"play_error","source":"51cg",
 *  "props":{"stage":"resolve","code":"timeout","ms":8000}}
 *
 * kind 是粗分类（app / bridge / play / crash / js），name 是具体事件名。
 * source / stage / code / ms 四个字段服务端会抽成列，其余进 props(JSONB)。
 *
 * ── 采集边界 ────────────────────────────────────────────────────────
 * 采：机型、系统版本、App 版本、源 id、阶段、错误码、耗时、内容 id、分类。
 * 不采：账号、邮箱、手机号、搜索原文（只存关键词的哈希）、内容标题全文。
 *
 * ── 源身份 ──────────────────────────────────────────────────────────
 * 每条事件都带 source：桥指令（封面、spider、详情）自己不知道当前是哪个源，
 * 所以由 SiteProfile 在装配时调 setSource() 挂一份，build() 时补上。
 * 不带的话后台那一列全是空的 —— 2.0.20 就是这样，只能靠报错文案倒推是哪个源。
 *
 * ── 唯一一条绕开 request("report") 的路：kind=js ────────────────────
 * 前端未捕获异常和未处理 rejection 由 assets/js-errors.js 直调
 * Bridge.reportJs(@JavascriptInterface) 进来，**故意不走桥**。原因是桥要回包，
 * 回包会调 window.NativeCallbacks.resolve —— 那个对象在 app.js 里，app.js 整份
 * 没执行时回包自己就抛 TypeError，钩子接住再报就是死循环。而「app.js 没跑起来」
 * 恰恰是这条链最该抓到的故障，所以它不能有回程。
 *
 * 两条配套约束，改前端那块之前先读 js-errors.js 的文件头：
 *   · 堆栈的键必须叫 trace（本类 fit() 只收缩 trace 和 msg，其余键它不认识）；
 *   · 前端不能带 source（不传才回落到下面这份 currentSource，传了就是假源）。
 */
final class Report {

    private static final String TAG = "AIIvideo.Report";

    /**
     * 与服务端 /srv/track/.env 的 TRACK_SECRET 必须一致。
     *
     * 值不写在源码里 —— 仓库是公开的。它从本地 keystore.properties 的 trackSecret
     * 经 buildConfigField 注入。没配就空串，服务端一律 401，等于这条链静默关掉。
     * 换密钥 = 改服务器 .env + 改本机 properties + 重新发版，三处一起动。
     */
    private static final String SECRET = BuildConfig.TRACK_SECRET;

    private static final String PREFS = "report";
    private static final String KEY_DEVICE = "device_id";
    /** 早期版本设置页有「使用统计」开关，关掉会写这个键。现在开关撤了，读到就删。 */
    private static final String KEY_OPT_OUT_LEGACY = "opt_out";

    private static ReportQueue queue;
    private static SharedPreferences prefs;
    private static String deviceId = "";
    private static String sessionId = "";
    /** 当前内容源的短名字（51cg / mrds66 / hanime1…），由 SiteProfile 装配时挂上。 */
    private static volatile String currentSource = "";

    private Report() {
    }

    /**
     * 在 Application.onCreate 里调一次。
     *
     * 上报没有开关 —— 设置页那一行已经撤掉，行为埋点常开。曾经点过关的那个键在
     * 这里顺手删掉：不删的话，装过老版本的用户会永远停在「关」上，而界面上再也
     * 找不到地方打开它。
     */
    static void install(Context context, boolean canFlush) {
        Context app = context.getApplicationContext();
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        deviceId = prefs.getString(KEY_DEVICE, "");
        if (deviceId == null || deviceId.isEmpty()) {
            deviceId = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_DEVICE, deviceId).apply();
        }
        if (prefs.getBoolean(KEY_OPT_OUT_LEGACY, false)) {
            prefs.edit().remove(KEY_OPT_OUT_LEGACY).apply();
            Log.i(TAG, "清掉老版本的「关掉使用统计」标记，埋点恢复常开");
        }
        sessionId = UUID.randomUUID().toString();
        queue = new ReportQueue(app, canFlush);
        // 上次没发出去的，开机补一发
        queue.maybeFlush(true);
    }

    /** 装配源的时候调一次（见 SiteProfile 构造）。空串 = 还不知道是哪个源。 */
    static void setSource(String label) {
        currentSource = label == null ? "" : label.trim();
    }

    static String source() {
        return currentSource;
    }

    /**
     * 标记：这个异常**已经报过一次**了，重试的时候别再报。
     *
     * 封面这类东西会被滚动反复重试，第一次失败值得上报，后面三十次同样的失败
     * 只是噪音 —— 后台那 1340 条「封面解密结果无效」里，绝大多数是这么来的。
     */
    interface Once {
    }

    static boolean isOnce(Throwable error) {
        return error instanceof Once;
    }

    /**
     * 取消不算失败：用户快速划过时上一个请求被取代、spider 宿主退出时掐掉在途调用、
     * 同一个宿主排队超时。这类以前和真失败混在一起上报，把失败率顶到了 75%。
     * 现在单列成 command_cancelled，**不带 code** —— 服务端的「失败」判据是
     * code 非空，所以它们会进事件流、但不进失败统计。
     */
    private static final String[] CANCEL_WORDS = {
            "被更新的请求取代", "宿主已关闭", "排队超时", "被中断", "已取消", "取消了"
    };

    /**
     * 取消判定。**顺序是有意义的**，别随手重排：
     *
     * `SocketTimeoutException extends InterruptedIOException`，而 okhttp 的
     * callTimeout 也抛 `InterruptedIOException("timeout")`。那两条都是**真超时**
     * —— 线上 26 条 SocketTimeoutException 均值 30 秒、全在 detail 阶段，把它们
     * 洗成「取消」就等于把「站点慢到读不出来」这个信号藏起来。所以先排掉超时，
     * 再看剩下的 InterruptedIOException。
     *
     * 只按类名判断不够：`DeadObjectException`（binder 对端进程死了）和
     * `ConnectionShutdownException`（连接被本机关掉）在 2.0.21 里合计 37 条，
     * 全被算成失败。它们本质都是「我们这边的生命周期结束了」。
     */
    static boolean isCancellation(Throwable error) {
        if (error == null) return false;
        if (error instanceof InterruptedException) return true;
        // binder 对端进程没了、连接被本机关掉：都是生命周期事件，不是故障。
        String simple = error.getClass().getSimpleName();
        if ("DeadObjectException".equals(simple) || "ConnectionShutdownException".equals(simple)) {
            return true;
        }
        String message = error.getMessage() == null ? "" : error.getMessage().trim();
        String lower = message.toLowerCase(Locale.US);
        // 先排真超时（含子类，别让 instanceof InterruptedIOException 把它们吃掉）。
        if (error instanceof java.net.SocketTimeoutException) return false;
        if ("timeout".equals(lower) || "timed out".equals(lower)) return false;
        if (error instanceof java.io.InterruptedIOException) {
            // 消息为空的 InterruptedIOException 是 okhttp 掐连接时的常态，算取消。
            return message.isEmpty()
                    || lower.contains("interrupt")
                    || lower.contains("cancel")
                    || lower.contains("socket closed");
        }
        for (String word : CANCEL_WORDS) {
            if (message.contains(word)) return true;
        }
        return false;
    }

    /** 自检口子要看的：还有多少条压在盘里没发出去。 */
    static JSONObject state() {
        JSONObject info = new JSONObject();
        try {
            info.put("pending", queue == null ? 0 : queue.pending());
        } catch (Exception ignored) {
        }
        return info;
    }

    // ── 事件入口 ──────────────────────────────────────────────────────

    static void event(String kind, String name, JSONObject props) {
        enqueue(build(kind, name, "", props));
    }

    static void event(String kind, String name, String source, JSONObject props) {
        enqueue(build(kind, name, source, props));
    }

    /**
     * 崩溃。同步落盘不发送 —— 进程马上就要死了，这时候发网络是给系统添乱，
     * 留着下次启动补发。
     *
     * 堆栈按**字节**裁，不按字符：服务端的上限是「序列化后 4096 字节」，
     * 一个中文字符占 3 字节，`shorten(…, 4000)` 那种按字符写的上限在真实
     * Android 堆栈（40 帧 × 70 字符）下会算到 4400+ 字节，整条被 413 掉。
     * 只有手搓的十帧 diag 崩溃才刚好过得去 —— 线上 crash 表里就那两条。
     */
    static void crash(Throwable error, String where) {
        JSONObject props = new JSONObject();
        try {
            props.put("stage", where);
            props.put("code", error.getClass().getSimpleName());
            props.put("msg", text(error.getMessage(), 200));
            props.put("trace", traceOf(error));
            props.put("thread", Thread.currentThread().getName());
        } catch (Exception ignored) {
        }
        enqueue(build("crash", "uncaught", "", props));
    }

    /**
     * 桥指令失败。stage = 指令名，这是「到底死在哪一环」最直接的一手数据 ——
     * 原来这些异常要么被前端的 catch 吞掉，要么变成一句「播放失败」。
     */
    static void bridgeFailure(String command, Exception error, long elapsedMs) {
        JSONObject props = new JSONObject();
        boolean cancelled = isCancellation(error);
        try {
            props.put("stage", command);
            // 取消类不带 code：服务端按 code 非空算失败，带了就等于换个名字继续污染。
            if (!cancelled) {
                props.put("code", error.getClass().getSimpleName());
            }
            props.put("msg", text(error.getMessage(), 200));
            props.put("ms", (int) Math.min(elapsedMs, 600000L));
        } catch (Exception ignored) {
        }
        event("bridge", cancelled ? "command_cancelled" : "command_error", props);
    }

    /** 前端主动报：{kind, name, source, props:{...}}。 */
    static void fromFrontend(JSONObject payload) {
        if (payload == null) {
            return;
        }
        // kind 的默认值是 "js"：2.0.23 之前这条分支没有任何调用方（只有 kind=play
        // 的 play_error 在走），默认值一直没被兑现。现在由 js-errors.js 兑现 ——
        // 它调 Bridge.reportJs，载荷里带全 kind/name/props，因此这条默认值仍然只是
        // 「万一没带 kind 就按 JS 异常算」的兜底。
        String kind = payload.optString("kind", "js");
        String name = payload.optString("name", "event");
        String source = payload.optString("source", "");
        JSONObject props = payload.optJSONObject("props");
        event(kind, name, source, props == null ? new JSONObject() : props);
    }

    static void flushNow() {
        if (queue != null) {
            queue.maybeFlush(true);
        }
    }

    // ── 组装 ──────────────────────────────────────────────────────────

    static JSONObject build(String kind, String name, String source, JSONObject props) {
        JSONObject event = new JSONObject();
        try {
            event.put("id", UUID.randomUUID().toString());
            event.put("at", System.currentTimeMillis());
            event.put("session", sessionId);
            event.put("kind", kind);
            event.put("name", name);
            String effective = (source != null && !source.isEmpty()) ? source : currentSource;
            if (effective != null && !effective.isEmpty()) {
                event.put("source", effective);
            }
            if (props != null) {
                event.put("props", props);
            }
        } catch (Exception ignored) {
        }
        return fit(event);
    }

    /**
     * 单条事件的字节预算：服务端 `MAX_EVENT_BYTES` 是 4096，这里先自己收住。
     *
     * 留 296 字节余量给服务端可能补的字段（device 由 enqueue 补，它算在内；
     * 但以后若加 kind 之外的列，别让它一脚踩过线）。超了就先砍 trace、再砍 msg ——
     * 丢几行堆栈远好过整条被 413，然后连带把同批的其它事件一起带走。
     */
    static final int EVENT_BUDGET = 3800;

    static int serializedSize(JSONObject event) {
        try {
            return event.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        } catch (Exception impossible) {
            return 0;
        }
    }

    /** 超预算就按顺序收缩，直到进线。改的是 props 里的 value，不新建对象。 */
    private static JSONObject fit(JSONObject event) {
        if (serializedSize(event) <= EVENT_BUDGET) {
            return event;
        }
        JSONObject props = event.optJSONObject("props");
        if (props == null) {
            return event;
        }
        // 堆栈是最占地方的，也是最能砍的：前十几帧就够定位了。
        for (int keep : new int[]{1200, 600, 200}) {
            try {
                String trace = props.optString("trace", "");
                if (!trace.isEmpty()) {
                    props.put("trace", shortenBytes(trace, keep));
                }
            } catch (Exception ignored) {
            }
            if (serializedSize(event) <= EVENT_BUDGET) {
                return event;
            }
        }
        try {
            String msg = props.optString("msg", "");
            if (!msg.isEmpty()) {
                props.put("msg", shortenBytes(msg, 200));
            }
        } catch (Exception ignored) {
        }
        return event;
    }

    private static void enqueue(JSONObject event) {
        if (queue == null) {
            // 以前这里直接 return：上报没起来的时候，事件就这么没了，连一行日志都没有。
            // 排查「为什么这条事件没到」时最想看到的就是这一句。
            Log.w(TAG, "队列没起来，丢掉一条：" + event.optString("kind") + "/" + event.optString("name"));
            return;
        }
        try {
            // device_id 每条都带，服务端按它切「同一台设备有没有变好」
            event.put("device", deviceId);
        } catch (Exception ignored) {
        }
        queue.enqueue(event);
    }

    /** 服务端不需要 device_id（从 header 或事件里都行），但带着方便对日志。 */
    static JSONObject appInfo(Context context) {
        JSONObject info = new JSONObject();
        try {
            PackageInfo meta = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            info.put("v", meta.versionName == null ? "" : meta.versionName);
            info.put("vc", Build.VERSION.SDK_INT >= 28 ? (int) meta.getLongVersionCode() : meta.versionCode);
        } catch (Exception ignored) {
        }
        try {
            info.put("os", Build.VERSION.SDK_INT);
            info.put("model", Build.MODEL == null ? "" : Build.MODEL);
            info.put("patch", 0);
        } catch (Exception ignored) {
        }
        return info;
    }

    static String versionName(Context context) {
        try {
            return String.valueOf(context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName);
        } catch (Exception error) {
            return "";
        }
    }

    static String sign(byte[] body) {
        return ReportQueue.hmacHex(SECRET, body);
    }

    static String deviceId() {
        return deviceId == null ? "" : deviceId;
    }

    static String dayKey(long at) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(at));
    }

    /**
     * 异常消息 → 字符串，null 安全。
     *
     * 旧代码这里是 `String.valueOf(error.getMessage())` —— `getMessage()` 为 null
     * 时它返回字面量 `"null"`，线上 156 条事件的 `props.msg` 就是那个字符串，
     * 后台看着像「有消息」，实际什么都没说。
     *
     * 这里按字符截（沿用原来的口径），字节预算由 {@link #fit} 兜底。
     */
    static String text(String value, int maxChars) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "…";
    }

    /**
     * 按**字节**截断，且只在字符边界切。
     *
     * 服务端算的是 UTF-8 字节数，中文字符占 3 字节。按字符截会让中文密集的
     * 内容超出预期两倍多 —— 崩溃堆栈就是这样超限的。
     */
    static String shortenBytes(String value, int maxBytes) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        byte[] raw = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (raw.length <= maxBytes) {
            return value;
        }
        int end = maxBytes;
        // 往回退到 UTF-8 的字符起点：0b10xxxxxx 是续字节。
        while (end > 0 && (raw[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(raw, 0, end, java.nio.charset.StandardCharsets.UTF_8) + "…";
    }

    /**
     * 崩溃堆栈：60 帧上限，再按字节收到 2400 —— 40 帧 × 70 字符的 Android 堆栈约 3.3KB。
     * 包可见是为了让单测能直接盯住「裁完还能定位」这件事。
     */
    static String traceOf(Throwable error) {
        StringBuilder text = new StringBuilder();
        StackTraceElement[] frames = error.getStackTrace();
        int limit = Math.min(frames.length, 60);
        for (int i = 0; i < limit; i++) {
            text.append(frames[i].toString()).append('\n');
        }
        Throwable cause = error.getCause();
        if (cause != null && cause != error) {
            text.append("caused by: ").append(cause.getClass().getName()).append('\n');
            StackTraceElement[] inner = cause.getStackTrace();
            for (int i = 0; i < Math.min(inner.length, 10); i++) {
                text.append("  at ").append(inner[i].toString()).append('\n');
            }
        }
        return shortenBytes(text.toString(), TRACE_BUDGET);
    }

    /** 堆栈的字节预算。整条事件 3800，剩下的给 id/session/kind/model 这些。 */
    static final int TRACE_BUDGET = 2400;
}
