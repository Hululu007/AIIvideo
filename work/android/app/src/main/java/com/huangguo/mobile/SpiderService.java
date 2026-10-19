package com.huangguo.mobile;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import org.json.JSONObject;

import android.util.Log;

/**
 * spider 宿主的独立进程（manifest 里 {@code android:process=":spider"}）。
 *
 * <p>══════ 为什么它必须是一个独立进程（2026-10-19 根治记录）════════
 * Android WebView 的所有实例共享<b>同一个渲染进程主线程</b>。之前 spider 宿主页
 * 挂在主进程里，yhdm 这类源一次抓取+解析要占住那条线程 1.7~2.4 秒，主页面的
 * rAF 在同一时刻整段断流 —— 用户看到的就是「点卡片、动画冻住、然后直接变大」。
 * 实测证据链在 {@code docs/2026-10-19-卡片动画卡顿排查.md}：spider detail 调用
 * 1781ms，主页面 rAF 断流 62→1730ms，完全重合，且渲染进程 JS 零长任务。
 *
 * <p>独立进程 = 独立渲染进程。spider 再怎么占线程，主 UI 的 WebView 有自己的
 * 渲染线程，动画满帧。代价是多一个进程的内存，和一次 Binder 往返（微秒级，
 * 相比秒级的网络调用可忽略）。
 *
 * <p>══════ 职责边界 ══════
 * 这个类只做三件事：收 JSON → 应用主进程传来的代理覆盖 → 调 {@link SpiderRuntime}。
 * 会话、白名单、复用、看门狗全在 SpiderRuntime 里，这个类不持状态 ——
 * 它是 binder 线程池上被调用的，持状态就要自己回答并发问题，而 SpiderRuntime
 * 已经回答过了（CALL_LOCK + GENERATION）。
 *
 * <p>══════ 代理为什么要跨进程传 ══════
 * 内置代理核心跑在主进程，它的「本地 SOCKS 地址」是主进程内存里的一个值，
 * :spider 进程读不到（SharedPreferences 里没有它）。所以主进程每次调用都把
 * 当时的 {@code proxyMode + effectiveProxy} 快照放进请求，服务侧用
 * {@link AiiHttp#applyProcessProxyOverride} 钉住 —— 否则「源声明要走代理」的
 * 源在独立进程里全部退化为直连，症状是「开隔离之后某些源打不开」。
 */
public class SpiderService extends Service {

    private static final String TAG = "HG.Spider";

    @Override
    public void onCreate() {
        super.onCreate();
        // WebView 的数据目录锁：同一 app 只允许一个进程用同一数据目录的 WebView
        // （crbug.com/558377，Android 9+ 直接抛 RuntimeException）。主进程的
        // WebView 已经占了默认目录，这里给 spider 进程挂一个独立后缀 —— 官方
        // 指定的多进程用法。必须在它第一次建 WebView 之前调用（onCreate 是
        // 进程内最早的时机）。spider 的宿主页本来就关了 localStorage/DOM storage
        // （设计 §五），两套数据目录互不可见没有影响。
        try {
            android.webkit.WebView.setDataDirectorySuffix("spider");
        } catch (Throwable e) {
            // API < 28 上没有这个方法（minSdk 26）：那种设备上退回旧行为
            // —— :spider 进程建 WebView 会失败，调用报错但不崩。
            Log.w(TAG, "setDataDirectorySuffix 不可用：" + e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new Channel();
    }

    private final class Channel extends ISpiderChannel.Stub {

        @Override
        public String call(String requestJson) {
            try {
                final JSONObject req = new JSONObject(requestJson == null ? "{}" : requestJson);
                // 代理快照必须在进 SpiderRuntime 之前钉好：net 的路由判断
                // （NetRoute / AiiHttp.effectiveProxy）在调用链深处读的就是这份值。
                AiiHttp.applyProcessProxyOverride(
                        req.optString("proxyMode", ""),
                        req.optString("effectiveProxy", ""));
                final JSONObject payload = SpiderRuntime.call(
                        getApplicationContext(),
                        req.optString("source", ""),
                        req.optString("cap", ""),
                        new JSONObject(req.optString("args", "{}")),
                        req.optInt("timeoutMs", 15_000));
                return new JSONObject()
                        .put("ok", true)
                        .put("payload", payload)
                        .toString();
            } catch (Throwable e) {
                final String message = e.getMessage() != null ? e.getMessage() : e.toString();
                Log.w(TAG, "远端调用失败：" + message);
                try {
                    return new JSONObject()
                            .put("ok", false)
                            .put("error", message)
                            .toString();
                } catch (Throwable ignored) {
                    // 连 JSONObject 都建不出来只可能是极端内存压力；给一个还能 parse
                    // 的应答，让主进程那边抛一句人话，而不是 Binder 层的RuntimeException。
                    return "{\"ok\":false,\"error\":\"spider 服务进程内部错误\"}";
                }
            }
        }
    }

    @Override
    public void onDestroy() {
        // 进程回收前的收尾：销毁宿主 WebView，渲染进程随进程退出一起消失。
        // 不做这一步的话，被主进程 stopService 时 WebView 的渲染线程可能多活几秒。
        SpiderRuntime.shutdown();
        super.onDestroy();
    }
}
