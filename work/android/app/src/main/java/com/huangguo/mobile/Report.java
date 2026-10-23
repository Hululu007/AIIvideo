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
 * ── 用户能关掉 ──────────────────────────────────────────────────────
 * 设置里关掉之后只留 crash。崩了之后连崩在哪都不知道，那这个 App 就没法维护了；
 * 除此之外的行为统计一律不发。
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
     */
    static void crash(Throwable error, String where) {
        JSONObject props = new JSONObject();
        try {
            props.put("stage", where);
            props.put("code", error.getClass().getSimpleName());
            props.put("msg", shorten(String.valueOf(error.getMessage()), 200));
            props.put("trace", shorten(stackOf(error), 4000));
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
        try {
            props.put("stage", command);
            props.put("code", error.getClass().getSimpleName());
            props.put("msg", shorten(String.valueOf(error.getMessage()), 200));
            props.put("ms", (int) Math.min(elapsedMs, 600000L));
        } catch (Exception ignored) {
        }
        event("bridge", "command_error", props);
    }

    /** 前端主动报：{kind, name, source, props:{...}}。 */
    static void fromFrontend(JSONObject payload) {
        if (payload == null) {
            return;
        }
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
            if (source != null && !source.isEmpty()) {
                event.put("source", source);
            }
            if (props != null) {
                event.put("props", props);
            }
        } catch (Exception ignored) {
        }
        return event;
    }

    private static void enqueue(JSONObject event) {
        if (queue == null) {
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

    private static String stackOf(Throwable error) {
        StringBuilder text = new StringBuilder();
        StackTraceElement[] frames = error.getStackTrace();
        int limit = Math.min(frames.length, 40);
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
        return text.toString();
    }

    private static String shorten(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }
}
