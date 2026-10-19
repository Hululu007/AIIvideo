package com.huangguo.mobile;

import java.io.IOException;

/**
 * 盾后站点「WebView 兜底也过不去」的信号（方案 1：过盾上收主进程，2026-10-19）。
 *
 * <p>旅行路线：{@code SpiderRuntime.net} 撞盾且 headless WebView 兜底失败时抛出 →
 * {@link SpiderBridge} 把它压平成 error 字符串带回页面（类型信息在那层丢失），
 * 所以 url 单独挂在 {@link SpiderRuntime.Session#shieldUrl} 上随会话走 →
 * {@code SpiderRuntime.call} 结束时重建本异常 → {@link SpiderService} 应答里带
 * {@code shieldUrl} → 主进程 {@code SpiderClient} 重建本异常抛给 bridge 命令层 →
 * 主进程用有窗口的 {@code SiteFetchWebView} 过盾取 HTML，塞进重试请求的
 * {@code __shieldBody} 回填。
 *
 * <p>两个进程编译同一份源码，这个类天然两边可用。
 */
class ShieldSignal extends IOException {

    /** 被盾挡住的原始请求地址（绝对 https URL）。 */
    final String url;

    ShieldSignal(String url, String message) {
        super(message);
        this.url = url == null ? "" : url;
    }
}
