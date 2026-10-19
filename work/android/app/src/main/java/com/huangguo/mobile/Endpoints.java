package com.huangguo.mobile;

/**
 * 后端地址的唯一来源。
 *
 * 换域名只改这里，别在业务类里再写字符串。以前这些地址散在四个文件里
 * （HuangGuoApp、AccountService、UpdateManager 两处；热更那处随热更层一起删了），
 * 换一次要改五处，漏一处就是线上半瘫：可能热更拉得到、但账号登不上。
 *
 * ── 两条路径，走向不一样 ──
 *
 * API_BASE   账号 / 片单 / 头像。走自己的服务器（149.104.21.177.sslip.io），
 *            那是唯一还依赖自己机器的业务 —— 它必须有服务端逻辑，跑不了静态托管。
 *            Parse SDK 的 server 参数，后端是自写的 Parse REST 子集
 *            （见 server/account/），Caddy 剥掉 /api 前缀转发。
 *
 * 更新这类**纯静态分发**（热更清单 + 热更包 + APK）2026-10-18 起全部搬出
 * 自己的机器，改走 GitHub + gh 镜像。理由很简单：内容是静态的，没有理由
 * 让它受某台机器的欠费、被封、磁盘满影响。见 UPDATE_MANIFEST。
 *
 * ── 为什么 API 用 sslip.io ──
 * 便宜 VPS 没绑域名时，sslip.io 把 149.104.21.177.sslip.io 解析回 149.104.21.177，
 * 而 TLS 证书能签给这个域名（签给裸 IP 的话要 SAN 里带 IP，很多 CA 不做）。
 * 代价是换 IP 就等于换域名 —— 所以地址必须集中，见上。
 */
final class Endpoints {

    private Endpoints() {}

    /** 云服务器上的账号服务，路径前缀 /api。 */
    static final String API_BASE = "https://149.104.21.177.sslip.io/api/";

    /**
     * 前端热更的仓库目录。
     *
     * ── 为什么搬到 GitHub ──
     * 以前热更（签名清单 + 热更包）挂在香港那台服务器上。那是一条只有一台
     * 机器、只有一条链路的通道 —— 机器欠费、被封、磁盘满、甚至 Caddy 配置
     * 写错，全世界的前端热更就断了，而且没有第二条路。而热更恰恰是「出问题
     * 时唯一能救场的通道」，把它做成单点等于没有这个东西。
     *
     * 现在清单和包都在仓库的 hot/ 目录里（publish-hot.sh 发布），走 GitHub
     * 的 CDN。香港那台不再参与。
     *
     * 代价要讲清楚：raw.githubusercontent.com 有大约 5 分钟的 CDN 缓存，
     * 刚发布的一版可能要等几分钟才生效。热更本来就不是秒级需求（客户端
     * 30 分钟才查一次，而且拿到之后下次启动才应用），这个延迟可以接受。
     */
    // ── 热更清单的地址已经删了（2026-10-19）──
    //
    // 前端热更整块停用：它会越级盖住 APK 里的新界面，而版本号看不出来。
    // APK 自身的更新清单走 UPDATE_MANIFEST / manifestUrls()，那条没动。

    /**
     * 给一个 GitHub 上的地址派生镜像候选，第一个是最原始的。
     *
     * 热更包有 340KB，从 raw.githubusercontent 直接拉在国内基本要超时，
     * 所以下载时也走镜像。派生而不是让服务端下发一份候选列表：包地址写在
     * 签名信封里，改一次就要重签，而镜像随时会挂 —— 让客户端按固定规则
     * 派生，换镜像只需要改代码里的 AKAMS_A/B，不用重新发签名包。
     */
    static java.util.List<String> mirrorsOf(String url) {
        java.util.List<String> urls = new java.util.ArrayList<String>(3);
        if (url == null || url.isEmpty()) return urls;
        urls.add(url);
        // 只给「拉不到就完了」的那种地址加镜像。自己的服务器、非 GitHub 的
        // 地址原样返回，加了也没用。
        if (url.startsWith("https://raw.githubusercontent.com/")
                || url.startsWith("https://github.com/")) {
            urls.add(0, AKAMS_A + "/" + url);
            urls.add(1, AKAMS_B + "/" + url);
        }
        return urls;
    }

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
     * 三条都是同一个文件的不同入口 —— 不是笔误。gh 镜像的用法是「把 GitHub
     * 原始地址整段拼在镜像主机后面当路径」。
     *
     * ── 2026-10-18 起不再有「自建服务器」那一条 ──
     * 以前第一条是 https://149.104.21.177.sslip.io/updates/android-update.json。
     * 那条挂了不代表全挂，但它意味着「更新这件事仍然有一台机器可以掐断」。
     * 现在清单、APK、热更包全在 GitHub，一台自己的机器都不参与。
     *
     * 顺序：镜像 A → 镜像 B → raw。国内直连 raw 基本不通，所以放最后。
     * UpdateMirror.fetch() 拿到的是 JSON 数组字符串，逐个试到通为止。
     */
    static final String UPDATE_MANIFEST = "[\""
            + AKAMS_A + "/" + RAW_UPDATE + "\",\""
            + AKAMS_B + "/" + RAW_UPDATE + "\",\""
            + RAW_UPDATE + "\"]";

    /**
     * APK 在 GitHub Releases 上的地址。
     *
     * 只在清单里没给 `urls` 时用（正常发布的清单都会给）。以前这里拼的是
     * 自己服务器的前缀，现在改成 Releases —— Releases 的路径里带 tag，
     * 用前缀表达不出来，所以从一个常量变成一个方法。
     */
    static final String RELEASE_REPO =
            "https://github.com/zhaoruizhe81/AIIvideo/releases/download";

    static String releaseUrl(String tag, String apkName) {
        return RELEASE_REPO + "/" + tag + "/" + apkName;
    }


    /**
     * 免费节点池的候选地址，按顺序试，第一个成功的就用。
     *
     * 三条全是 GitHub 的不同入口，顺序是「国内可用性优先」：
     *
     *   1. akams 镜像 A   国内直连 raw 基本不通，镜像才是常态路径
     *   2. akams 镜像 B   同一个服务商的不同主机，A 被限速或挂掉时兜住
     *   3. GitHub 原始    在国外、或前两条全挂时的最后一条
     *
     * 以前第一条是自建的香港镜像。去掉了 —— 这是纯静态文件，没有理由让
     * 它受某台机器欠费、被封、磁盘满的影响。现在这台机器上只剩账号 API
     * 还依赖自己（那是真的需要服务端逻辑）。
     *
     * 为什么文件放在仓库的 assets 目录：它就是 APK 里那份的原件。
     * 维护者改节点 = 编辑 free-nodes.json + 提交 + 推送，发版和热更用的是同一份，
     * 不存在「线上数据和包里数据不一致」这种要额外对账的问题。
     */
    private static final String RAW_NODES =
            "https://raw.githubusercontent.com/zhaoruizhe81/AIIvideo/main"
                    + "/work/android/app/src/main/assets/free-nodes.json";

    static java.util.List<String> nodesUrls() {
        // 2026-10-18：刚发布一份新节点池，结果手机上还是旧的 178 条。
        // 查下来是 gh.dpik.top 这个镜像的缓存不认查询串（带 ?t= 也照样吐旧文件），
        // 而它是候选里的第一个 —— 客户端「第一个成功的就用」，于是永远拿到旧的那份。
        // gh-proxy.com 同一时刻已经是新的。
        //
        // 两处改动：
        //   ① 顺序换成 gh-proxy 优先、dpik 次之 —— 缓存策略更规矩的那个放前面。
        //   ② 每个地址带一个时间戳查询串：对认 query 的 CDN 能绕过缓存，
        //      对不认的也没坏处。清单只有 50KB，多这一次请求可以忽略。
        // 时间戳查询串**不要加**：镜像的用法是「镜像主机 + GitHub 完整地址」，
        // 往后面接 ?t= 会被当成内层地址的查询串，gh-proxy 直接回一个 HTML 错误页，
        // 客户端报「内容不像节点池」。试过一次，撤了。
        java.util.List<String> urls = new java.util.ArrayList<String>(3);
        urls.add(AKAMS_B + "/" + RAW_NODES);
        urls.add(AKAMS_A + "/" + RAW_NODES);
        urls.add(RAW_NODES);
        return urls;
    }
}
