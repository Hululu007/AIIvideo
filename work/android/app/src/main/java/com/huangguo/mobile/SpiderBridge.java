package com.huangguo.mobile;

import android.util.Log;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

/**
 * 宿主页和 Java 之间的唯一通道。
 *
 * <p>它<b>不持状态</b>：会话、白名单、闩锁全在 {@link SpiderRuntime} 里。这里只做两件
 * 事 —— op 分发和 JSON 编解码。分开的理由很实际：这个类的方法会被 WebView 在多条
 * 线程上调，协议层一旦同时兼职状态层，出错时就分不清是「页面还活着」还是「Java 没清」。
 *
 * <p>三个 op 的形状见 {@code docs/2026-10-19-spider阶段0契约.md} §三。
 *
 * <p><b>编码只有一层</b>：宿主页的 {@code __spider.call(op, args)} 负责把 args
 * 序列化一次再调进来（契约 §二），所以这里收到的一定是 JSON 文本，不需要再猜
 * 「是对象还是字符串」。
 */
final class SpiderBridge {

    private static final String TAG = "HG.Spider";

    /**
     * 页面调用的入口。
     *
     * <p>这个方法在 WebView 的 Java 桥线程上跑，会被同步等待 —— 页面里的
     * {@code ctx.call('net', …)} 就在这一行上等结果。所以它可以阻塞（网络就是这么
     * 走的），但绝不做跟这次调用无关的事。
     *
     * @return JSON 字符串。宿主页负责 parse（{@code @JavascriptInterface} 只能同步
     *         返回基本类型/String，返回对象在老 WebView 上是代理对象，行为不一致）。
     */
    @JavascriptInterface
    public String call(String op, String argsJson) {
        if (op == null || op.length() == 0) return error("bridge 缺 op");

        final JSONObject args;
        try {
            args = (argsJson == null || argsJson.length() == 0)
                    ? new JSONObject() : new JSONObject(argsJson);
        } catch (Throwable e) {
            Log.w(TAG, "bridge 参数不是 JSON：op=" + op);
            return error("bridge 收到的参数不是 JSON");
        }

        final SpiderRuntime.Session s = SpiderRuntime.currentSession();
        // done 之后到下一次 call 之间的任何 op 都丢弃。这是刻意的：一次能力调用
        // 只允许一个返回值（契约 §三），spider「返回之后再偷偷发一个请求」属于协议
        // 违规，记一笔日志就够，不把错误明细回给页面 —— 那只会让违规写法看起来能用。
        if (s == null || s.finished) {
            Log.w(TAG, "丢弃 op=" + op + "（这次调用已经结束）");
            return error("调用已结束");
        }

        try {
            if ("net".equals(op)) {
                return SpiderRuntime.net(s, args).toString();
            }
            if ("log".equals(op)) {
                // 直接用 Log.i 而不是套一层缓冲：spider 的日志就是给 adb logcat 看的，
                // tag 统一成 HG.Spider 之后 `logcat -s HG.Spider` 一条命令就能跟完整条链路。
                Log.i(TAG, "cap=" + s.cap + " log: " + args.optString("msg", ""));
                return ok();
            }
            if ("done".equals(op)) {
                // 编解码在这层：payload 可能是对象、null（spider 没 return），error 可能是空串。
                // 「空 error 按失败处理」由 SpiderRuntime.finish 兜底成一句可读的话，
                // 这样日志里不会出现一条只有 "fail: " 的空白记录。
                SpiderRuntime.finish(s, args.optBoolean("ok", false),
                        args.opt("payload"), args.optString("error", ""));
                return ok();
            }
            Log.w(TAG, "未知 op=" + op + " cap=" + s.cap);
            return error("未知 op：" + op);
        } catch (Throwable e) {
            // AiiHttp 的异常 message 已经是给人看的那一句（"HTTP 404 @ …" 之类），
            // 原样往回带，页面那边 String(e.message) 出来就是能直接进日志的文本。
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            Log.w(TAG, "op=" + op + " cap=" + s.cap + " 失败：" + msg);
            return error(msg);
        }
    }

    private static String ok() {
        return "{\"ok\":true}";
    }

    /** 失败信封。手拼而不是 JSONObject：这条路径要保证<b>永远</b>能返回一个字符串，
     *  连 OOM 之外什么都别再抛（抛出去页面会拿到 null，然后报「bridge 返回的不是 JSON」，
     *  真正的原因就丢了）。 */
    private static String error(String message) {
        try {
            return new JSONObject().put("ok", false).put("error", message).toString();
        } catch (Throwable e) {
            return "{\"ok\":false,\"error\":\"bridge 内部错误\"}";
        }
    }
}
