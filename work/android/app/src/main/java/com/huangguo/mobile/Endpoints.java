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


    // ── gh 镜像 ────────────────────────────────────────────────────────
    //
    // github.akams.cn 是一组公益 gh 代理的**导航站**（它自己不代理），真正的
    // 加速主机是它页面上列的那几个。用法统一：`<镜像主机>/<GitHub 完整地址>`。
    //
    // 为什么挑这两个而不是全部：镜像随时会挂、会限速，列表越长每次冷启动
    // 试错的次数越多。这两个是 2026-10-18 实测同时可用的，其余备用，需要时
    // 再往 AKAMS_HOSTS 里加。
    //
    // 注意：这些是**第三方公益服务**，不能用它们传任何有凭据的东西。
    // 所以镜像只用于「公开仓库里的静态文件」—— 节点池、更新清单。订阅
    // （含 uuid/密码）永远直连自己的服务器，见 Subscription.fetch。
    private static final String AKAMS_A = "https://gh.dpik.top";
    private static final String AKAMS_B = "https://gh-proxy.com";

    private static final String RAW_UPDATE =
            "https://raw.githubusercontent.com/zhaoruizhe81/AIIvideo/main/android-update.json";


    /**
     * 更新清单的多个镜像，按顺序尝试。
     *
     * 顺序有讲究：先自建（可控、能立刻发版），再 gh 代理（GitHub 在国内直连不稳），
     * 最后 GitHub 原始地址兜底。UpdateMirror.fetch() 拿到的是 JSON 数组字符串，
     * 逐个试到通为止。
     *
     * 注意后面几条都是同一个文件的不同入口 —— 不是笔误。gh 镜像的用法是
     * 「把 GitHub 原始地址整段拼在镜像主机后面当路径」。
     */
    static final String UPDATE_MANIFEST = "[\""
            + UPDATES_BASE + "/android-update.json\",\""
            + AKAMS_A + "/" + RAW_UPDATE + "\",\""
            + AKAMS_B + "/" + RAW_UPDATE + "\",\""
            + RAW_UPDATE + "\"]";

    /** 更新清单里没给 urls 时，APK 按这个前缀拼。 */
    static final String RELEASE_BASE = UPDATES_BASE + "/";

    /** 热更清单。 */
    static final String HOT_INDEX = HOT_BASE + "/hot-update.json";

    /**
     * 免费节点池的候选地址，按顺序试，第一个成功的就用。
     *
     * 顺序是「可控性优先」，不是「速度优先」：
     *
     *   1. 自己的香港服务器   同一台机器还挂着账号 API 和热更，它在正常状态时最快，
     *                        而且它一有问题我们立刻知道
     *   2. akams 镜像 A       HK 挂了 / 被墙时的第一条路（国内直连 raw 基本不通）
     *   3. akams 镜像 B       同一个服务商的不同主机，A 被限速时兜住
     *   4. GitHub 原始        在国外、或前三条全挂时的最后一条
     *
     * 为什么文件放在仓库的 assets 目录：它就是 APK 里那份的原件。
     * 维护者改节点 = 编辑 free-nodes.json + 提交 + 推送，发版和热更用的是同一份，
     * 不存在「线上数据和包里数据不一致」这种要额外对账的问题。
     */
    private static final String RAW_NODES =
            "https://raw.githubusercontent.com/zhaoruizhe81/AIIvideo/main"
                    + "/work/android/app/src/main/assets/free-nodes.json";

    /** 自建镜像。publish-nodes.sh 往这里传，Caddy 直接读盘。 */
    private static final String MIRROR_NODES =
            "https://149.104.21.177.sslip.io/nodes/free-nodes.json";

    static java.util.List<String> nodesUrls() {
        java.util.List<String> urls = new java.util.ArrayList<String>(4);
        urls.add(MIRROR_NODES);
        urls.add(AKAMS_A + "/" + RAW_NODES);
        urls.add(AKAMS_B + "/" + RAW_NODES);
        urls.add(RAW_NODES);
        return urls;
    }
}
