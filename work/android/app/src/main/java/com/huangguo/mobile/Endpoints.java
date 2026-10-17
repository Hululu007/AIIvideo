package com.huangguo.mobile;

/**
 * 后端地址的唯一来源。
 *
 * 换域名只改这里，别在业务类里再写字符串。以前这些地址散在四个文件里
 * （HuangGuoApp、AccountService、HotUpdateManager、UpdateManager 两处），
 * 换一次要改五处，漏一处就是线上半瘫：可能热更拉得到、但账号登不上。
 *
 * ── 三条路径 ──
 * API_BASE   账号 / 片单 / 头像。Parse SDK 的 server 参数，后端是自己写的
 *            Parse REST 子集（见 server/account/），Caddy 剥掉 /api 前缀转发。
 * HOT_BASE   前端热更：清单 + 签名包。走自己的 VPS。
 * UPDATES    应用自更新：android-update.json + release APK。
 *
 * ── 为什么是 sslip.io ──
 * 便宜 VPS 没绑域名时，sslip.io 把 149.104.21.177.sslip.io 解析回 149.104.21.177，
 * 而 TLS 证书能签给这个域名（签给裸 IP 的话要 SAN 里带 IP，很多 CA 不做）。
 * 代价是换 IP 就等于换域名 —— 所以地址必须集中，见上。
 */
final class Endpoints {

    private Endpoints() {}

    /** 云服务器上的账号服务，路径前缀 /api。 */
    static final String API_BASE = "https://149.104.21.177.sslip.io/api/";

    /** 前端热更（ui/*.js、css）。publish-hot.sh 发的就是这里。 */
    static final String HOT_BASE = "https://149.104.21.177.sslip.io/hot";

    /** 应用自更新的清单与 APK 目录。 */
    static final String UPDATES_BASE = "https://149.104.21.177.sslip.io/updates";

    /**
     * 更新清单的多个镜像，按顺序尝试。
     *
     * 顺序有讲究：先自建（可控、能立刻发版），再 gh 代理（GitHub 在国内直连不稳），
     * 最后 GitHub 原始地址兜底。UpdateMirror.fetch() 拿到的是 JSON 数组字符串，
     * 逐个试到通为止。
     *
     * 注意第二、三条是同一个文件的两个入口 —— 不是笔误，ghgo.de5.net 是
     * 把 raw.githubusercontent.com 的地址整段拼在自己后面当路径的代理。
     */
    static final String UPDATE_MANIFEST = "[\""
            + UPDATES_BASE + "/android-update.json\",\""
            + "https://ghgo.de5.net/https://raw.githubusercontent.com/zhaoruizhe81/AIIvideo/main/android-update.json\",\""
            + "https://raw.githubusercontent.com/zhaoruizhe81/AIIvideo/main/android-update.json\"]";

    /** 更新清单里没给 urls 时，APK 按这个前缀拼。 */
    static final String RELEASE_BASE = UPDATES_BASE + "/";

    /** 热更清单。 */
    static final String HOT_INDEX = HOT_BASE + "/hot-update.json";
}
