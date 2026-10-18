package com.huangguo.mobile;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * AIIvideo —— 用户自带的「源」。
 *
 * 出厂的 APK 里不带任何站点配置：装完是空的，用户自己填一个源地址才有内容。
 * 这么设计是为了把「源提供者」和「工具提供者」分开 —— 我们只提供播放器，
 * 用户指向哪个站点是他自己的选择。
 *
 * 存储位置：
 *   /data/data/com.huangguo.mobile/files/user-source.json
 * 卸载即消失，不污染任何公共目录。
 *
 * ── 和内置配置的关系 ────────────────────────────────────────────────
 * assets/site-default.json 仍然在，但降级成**模板**：它定义了 schema 长什么样，
 * 用户照它填。运行时 SiteProfile 优先读 user-source.json，读不到就用模板 ——
 * 模板里的 origin 是空的，前端看到空的 origin 就弹「首次设置」页。
 *
 * ── 校验 ────────────────────────────────────────────────────────────
 * validate() 必须比 SiteProfile 的构造函数更早发现问题。SiteProfile 对坏配置的
 * 反应是抛异常 → MainActivity.onCreate 崩 → 用户看到闪退，完全不知道哪里填错了。
 * 所以这里做一次「人话版」校验，把问题在设置页就显示出来。
 */
public final class UserSource {

    /** 文件名。放 files/ 下，卸载即清。 */
    private static final String FILE_NAME = "user-source.json";
    /** 源清单。跟 user-source.json 分开存 —— 那个是「当前生效的配置」，这个是「存过哪些地址」。 */
    private static final String LIST_FILE = "user-sources.json";
    /** 每份拉回来的配置都在这儿存一份（按地址的 md5 命名）。换源不再等网络，见 applySource。 */
    private static final String CACHE_DIR = "source-cache";

    private static final int FETCH_TIMEOUT = 12;

    /** 桥指令前缀。前端拼成 __source:set?url=... 经 detail 桥送进来。 */
    public static final String PREFIX = "__source:";

    /**
     * load() 认出「源市场清单」时抛的固定串。
     *
     * 贴在设置页的地址可能是一份 {sources:[…]} 清单而不是一个源。这不是错误，
     * 只是「用户贴的东西比一个源大一层」。前端看到这个串就去渲染勾选列表；
     * 万一没人接住，它至少比「认不出这是什么配置」更接近事实。
     */
    public static final String MARKET_LIST = "MARKET_SOURCES";

    private UserSource() {}

    // ── 桥入口 ────────────────────────────────────────────────────────

    /**
     * 前端通过 detail 桥提交的设置指令。
     *
     * 指令格式（前端 encodeURIComponent 过）：
     *   __source:get                  读当前源
     *   __source:set?url=<编码后的地址>   拉取 + 校验 + 存盘
     *   __source:clear                清掉
     *
     * 为什么借用 detail 桥而不是新加一个 dispatch 动作：MainActivity 的 dispatch
     * 是 packed-switch 按编号跳的，索引和上面那张 sparse-switch 哈希表耦合 ——
     * 加一个动作要同时改两张表。fetchApi 传 /api/ 路径用的就是同一招。
     *
     * 返回值统一是 {ok, ...}，前端只看 ok 和 error：
     *   成功  {"ok":true,  "configured":bool, "origin":"...", "url":"..."}
     *   失败  {"ok":false, "error":"人话原因"}
     */
    /**
     * 播放进度上报的前缀。
     *
     * **为什么和换源挤同一条桥**：MainActivity 的 dispatch 是 packed-switch 按编号
     * 跳的，索引和上面那张稀疏哈希表耦在一起，加一个动作要同时改两张表 ——
     * 这是本工程里最容易出事的地方（`__source:` 当初也是因为这个才借的 detail）。
     *
     * 所以这里沿用同一招：`bridgeCall('detail', {id: '__progress:set?payload=<编码过的JSON>'})`。
     * 前端不用碰 smali，Java 这边只多一个分支。
     */
    public static final String PROGRESS_PREFIX = "__progress:";

    /**
     * 播放层解析的 HTTP 出口。`__http:get|post?payload=<urlencode 后的 JSON>`。
     *
     * ── 为什么要开这个口子 ────────────────────────────────────────────
     * 页面 CSP 的 connect-src 是 'none'，网络一律走 Java —— 这是刻意的收紧，
     * 所以这个出口也按同样的调子收着：只放行 https，且主机必须在下面的白名单里。
     *
     * 触发原因是 CORS 而不是 CSP：播放层的解析接口要带 Video-Parse-Sign 那几个
     * 自定义头，对面的预检响应只回 access-control-allow-origin，没有
     * access-control-allow-headers，浏览器根本不会把请求发出去。OkHttp 不受同源
     * 策略管，借这条桥转一手就绕开了。
     */
    public static final String HTTP_PREFIX = "__http:";
;

    /**
     * 播放器要原生帮忙的两件事。`__player:brightness?value=0.42` / `__player:variants?url=<编码过的 m3u8>`。
     *
     * ── 为什么是这两件 ────────────────────────────────────────────────
     *   亮度：WebView 里的页面改不了系统/窗口亮度。CSS 蒙一层黑只能变暗，
     *        而要「像系统播放器那样上下滑调亮」，得动 Window 的 screenBrightness。
     *   清晰度：主 m3u8 里那张码率表只有取回文本才看得见，而页面 CSP 的
     *        connect-src 是 'none' —— 拿不到文本就列不出「有哪些清晰度」。
     *
     * 两者都跟着 detail 桥走（和 __source: / __progress: / __http: 同一招），
     * 不碰 MainActivity 那两张 dispatch 表。
     */
    public static final String PLAYER_PREFIX = PlayerControl.PREFIX;

    /** 亮度下限。0 会把屏幕熄掉，用户再想调回来就得靠运气 —— 留一档看得见。 */

    static JSONObject progress(String body, JSONObject out) {
        return ProgressSink.report(body, out);
    }






    static String trimSlash(String url) {
        String out = url;
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }

    /** 一条桥指令的实现。前缀由 {@link #ROUTES} 剥掉，实现只收裸指令。 */
    private interface Route {
        JSONObject run(Context context, SiteProfile profile, String body, JSONObject out)
                throws Exception;
    }

    /**
     * 前缀 → 实现。<b>这是整个控制面的唯一一张路由表。</b>
     *
     * ── 为什么是 Map 而不是 if-chain ──────────────────────────────────
     * 2026-10-18 之前这里是一串 {@code if (command.startsWith(...))}，五个前缀
     * 依次判断，每个分支各自切字符串。加一个子系统要在这条链上挑个位置插进去，
     * 而位置对不对（先判谁）没有任何约束 —— 比如 {@code __source:} 必须放最后，
     * 否则它会吃掉别的。
     *
     * 现在顺序由插入顺序决定，而且只有一处；每条前缀对应一个类，
     * 实现之间没有一个共享字段（进度上报和源配置之间本来就没有）。
     *
     * ── 顺序 ──────────────────────────────────────────────────────────
     * 五条前缀互不为前缀（`__source:` / `__player:` / `__proxy:` /
     * `__progress:` / `__http:`），所以顺序其实无关紧要 —— 但 {@code __source:}
     * 放最后是历史习惯（它最老、命中率最高，放最后不亏）。
     */
    /** 内置代理核心的前缀。声明在 ROUTES 之前，否则静态初始化是非法前向引用。 */
    public static final String PROXY_PREFIX = ProxyConsole.PREFIX;

    private static final java.util.LinkedHashMap<String, Route> ROUTES =
            new java.util.LinkedHashMap<String, Route>();

    static {
        ROUTES.put(PROGRESS_PREFIX, (c, p, b, o) -> ProgressSink.report(b, o));
        ROUTES.put(HTTP_PREFIX,     (c, p, b, o) -> PlaybackHttpBridge.handle(b, o));
        ROUTES.put(PLAYER_PREFIX,   (c, p, b, o) -> PlayerControl.handle(c, b, o));
        ROUTES.put(PROXY_PREFIX,    (c, p, b, o) -> ProxyConsole.handle(c, b, o));
        ROUTES.put(PREFIX,          (c, p, b, o) -> SourceConfig.dispatch(c, p, b, o));
    }

    /**
     * 控制面总入口。按前缀查表，剥掉前缀，交给对应实现。
     *
     * <p>这个方法现在只有十几行 —— 它曾经是一个 280 行的方法，装着五个子系统的
     * 路由和源配置的全部逻辑。
     */
    public static JSONObject handle(Context context, SiteProfile profile, String command) {
        JSONObject out = new JSONObject();
        if (command == null) return fail(out, "指令为空");
        for (java.util.Map.Entry<String, Route> entry : ROUTES.entrySet()) {
            String prefix = entry.getKey();
            if (!command.startsWith(prefix)) continue;
            try {
                return entry.getValue().run(context, profile,
                        command.substring(prefix.length()).trim(), out);
            } catch (Throwable e) {
                return fail(out, "指令失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
            }
        }
        // 说不认识的时候把前缀列出来 —— 老代码只说「不是源设置指令」，
        // 而调用方明明发的是别的子系统，那句话会把人往错的方向带。
        return fail(out, "认不出这条指令的前缀：" + command);
    }


    // ── 识别 ──────────────────────────────────────────────────────────




    // ── 配置里的动作名 ────────────────────────────────────────────────

    /** paths / shapes 里认得的动作名。shapes 的键只能从这里面挑。 */
    static final String[] ACTIONS = {
        // list 不是 paths 里的动作名，但它是 shapes 里的一个合法段 ——
        // 列表类响应（category/search/recommend/newest）的 items 和分页都挂在它下面。
        "home", "list", "detail", "episode", "search", "category",
        "recommend", "newest", "tags", "tag-list"
    };





    static String param(String query, String key) {
        String[] pairs = query.split("&");
        for (int i = 0; i < pairs.length; i++) {
            int eq = pairs[i].indexOf('=');
            if (eq < 0) continue;
            if (!key.equals(pairs[i].substring(0, eq))) continue;
            try {
                return java.net.URLDecoder.decode(pairs[i].substring(eq + 1), "UTF-8");
            } catch (Exception e) {
                return "";
            }
        }
        return "";
    }

    // ── 内置代理核心 ──────────────────────────────────────────────────
    //
    // 这一块把阶段 1 的 ProxyCore、阶段 2 的 AiiHttp.effectiveProxy() 接到前端。
    // 三件事：存用户的选择（模式 + 节点），启停核心，以及把所有状态合成一个
    // 「当前到底走哪条」的答复 —— 前端不自己拼这个结论，因为「模式是内置、但核心
    // 起不来、于是回落到外部代理」这种情况在页面上有三种写法，只该有一处判断。
    //
    // ── 为什么走 detail 桥而不是新增 dispatch 动作 ────────────────────
    // MainActivity.dispatch 是 packed-switch 按编号跳的，索引和稀疏哈希表耦合，
    // 加一个动作要同时改两张表（见本文件开头 PREFIX 那段）。这里沿用 __source:
    // 那一招，新增一个 __proxy: 前缀，MainActivity 一行都不用动。
    //
    // ── 三种模式的语义 ────────────────────────────────────────────────
    //   direct    直连。核心停掉。用户手填的地址**留着**不清 —— 那是他以前填的，
    //             不是这次选的模式，清掉之后从「外部」切到「直连」再切回去就丢了。
    //   builtin   内置核心。核心起不来就报错，**不回落**到外部地址：用户明确选了
    //             这条，静默改走另一条等于把出口换了还不说。
    //   external  用户手填的地址，沿用原来的 proxy:set。切过来时停核心 ——
    //             effectiveProxy() 让内置优先，两者同时在场的话，用户明明选了
    //             「外部」却仍旧从内置出去，这是最难解释的一种错。
    //
    // 真正决定流量走向的仍然是 AiiHttp.effectiveProxy()：核心在跑就用核心的，
    // 否则用用户手填的。模式只决定「切的时候要不要拉起/停掉核心」和「页面怎么显示」。

    /** 模式与节点存在这里。节点带 uuid/密码，和 aii-net 一样是应用私有目录下的明文 XML。 */
    private static final String PROXY_STORE = "aii-proxy-core";
    private static final String CORE_MODE_KEY = "mode";
    private static final String CORE_LINK_KEY = "link";
    /** 上次导入的订阅地址。重进设置页不用再粘一遍。 */
    private static final String SUB_URL_KEY = "subUrl";

    static final String MODE_DIRECT = "direct";
    static final String MODE_BUILTIN = "builtin";
    static final String MODE_EXTERNAL = "external";

    /** 正在启停时的重入闸。核心自己 synchronized，这里挡的是前端连点两下那两次。 */
    private static volatile boolean coreBusy = false;




    // ── 订阅与测速 ────────────────────────────────────────────────────
    //
    // 这一段是给「没有机场的人」准备的：粘一个订阅地址，App 拉下来自己测速，
    // 挑最快的连上。有机场的人直接用 start?link= 那条，不经这里。

    /**
     * 缓存下来的测速结果。
     *
     * 只放内存不放盘：节点是随时会失效的东西，昨天测出来的「12ms 最快」
     * 今天可能已经连不上了。持久化它只会让用户下次看到一个过期排序，
     * 然后点进去转圈。
     */

    private static volatile String lastSubUrl = "";

    /** 一轮测速一共测多少个。 */
    private static final int SPEED_POOL = 12;
    /**
     * 每批测几个。
     *
     * 3 个一批，配合 SpeedTest 里 4 秒/个的硬超时 ≈ 12 秒最坏。
     *
     * 曾经是 3 个一批但超时不生效（native 内部死等 12 秒），实测 36 秒一批，
     * 前端那期间只能干等 —— 体感问题就出在这。现在超时是真生效的，
     * 3 个一批既能每 12 秒刷新一次，又不会把整轮拖到两分钟。
     */
    private static final int SPEED_BATCH = 3;
    /** 分批累计。null 表示这一轮还没开始（或已结束）。 */

    private static final Object SPEED_LOCK = new Object();













    // ── 播放器要原生帮忙的两件事 ──────────────────────────────────────








    static JSONObject fail(JSONObject out, String message) {
        try {
            out.put("ok", false);
            out.put("error", message);
        } catch (Exception ignored) {}
        return out;
    }


    // ── 恢复出厂设置 ──────────────────────────────────────────────────









    // ── 下载 ──────────────────────────────────────────────────────────



    /** 把名单拼成「a / b / c」。报错文案用。 */
    private static String join(java.util.List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(" / ");
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    // ── 校验 ──────────────────────────────────────────────────────────

    /**
     * 检查一份配置能不能用。返回 null 表示通过，否则返回给用户看的中文原因。
     *
     * 规则是照着 SiteProfile 构造函数的要求反推的 —— 那里缺什么会崩，
     * 这里就查什么。宁可误报也不能漏报：漏了就是闪退，用户完全不知道哪里错了。
     */
    public static String validate(JSONObject config) {
        if (config == null) return "配置为空";

        // CMS 那类没有 origin，校验规则完全不同：只要一个 https 的 api 地址。
        // 认得的适配器只有 cms / agedm / 单站 HTML 型 三种。别的值不当适配器看 ——
        // 会掉进下面 REST 那套校验里，缺什么报什么。那是对的（未知适配器本来就
        // 该被挡），但报「缺少 origin」容易让人以为是地址填错了，所以这里先点名。
        //
        // ⚠️ 第三种适配器的实现类**不在仓库里**（见 .gitignore）：它的目标是成人
        //    内容站，代码和内容一起排除了。这行白名单留着是为了让**本地那份配置
        //    仍然能通过校验** —— 只有 adapter 名，不含任何站点信息。
        //    没有那个类的话，走到这一步的源会在 fetch 时分流失败，报「找不到符号」
        //    是编译期的事，运行时它就是一个认不出内容的源。
        // 适配器名单与必填项都问注册表要 —— 这里原本是第四份手抄的名单，
        // 已经漂过一次（porn91 有实现、有分流，却不在名单里，源根本加不进来）。
        String adapter = config.optString("adapter", "");
        if (adapter.length() > 0) {
            SiteAdapter impl = Adapters.byId(adapter);
            if (impl == null) {
                return "不认识的适配器：" + adapter + "（" + join(Adapters.ids()) + "）";
            }
            return impl.check(config);
        }

        // web 块先看：origin 那几条校验要不要做，取决于 paths.home 长什么样。
        JSONObject web = config.optJSONObject("web");
        if (web == null) return "缺少 web 块（前端配置）";

        // ── spider 源（2026-10-19 放宽）──────────────────────────────────
        // 一份源可以只写 "web": {"spider": "yhdm"}：内容全部由前端把 spider
        // 源码交给 SpiderStore 跑，Java 这边**不拼任何 /api 路径**。所以
        // paths / origin / categories 这三样对它都不是必需项 —— 它们描述的是
        // 「REST 源怎么发请求、怎么分栏」，spider 源这两件事都归 JS 自己。
        //
        // 判据故意写成「有 spider 才免」，不做无条件放宽：既没 spider 也没
        // origin 的配置仍然掉进下面那套 REST 校验，缺什么报什么。否则「什么都
        // 不写」也会通过 —— 那是装完就白屏的配置，比报错难查得多。
        boolean spiderSource = web.optString("spider", "").trim().length() > 0;

        JSONObject paths = web.optJSONObject("paths");
        String home = paths == null ? "" : paths.optString("home", "");
        if (!spiderSource) {
            if (paths == null || paths.length() == 0) return "web.paths 为空（至少要有一条接口路径）";
            if (home.length() == 0) return "web.paths.home 缺失（首页拿不到数据）";
        }

        // origin：SiteProfile 建 URI 时会校验 scheme / host / userInfo / query / fragment，
        // 任何一条不过都会走到「站点必须是 HTTPS 域名」那个分支。
        //
        // 但 origin 是**可以留空**的（2026-10-16 起）：它的唯一作用是拼在 paths 前面，
        // 而 **paths 里可以直接写 https 绝对地址**。两个用途：
        //   · 接口开在别的域名上（AGE动漫 的 api.agedm.io 和页面 m.agedm.io 不是一个域）
        //   · 写适配器时（苹果CMS 就是这样：origin 空，/api/... 原样交给适配器）
        // 留空的前提是 home 得是绝对地址，否则首页不知道去哪取 —— 这条必须查，
        // 漏了就是「App 起来但首页永远空白」，用户完全不知道哪里错了。
        String origin = config.optString("origin", "").trim();
        // 有适配器就豁免：适配器自己拼 URL（agedm 用 api、cms 用 api），
        // paths 那张表它根本不看 —— agedm.json 就是 origin 空 + 相对路径 + adapter。
        boolean hasAdapter = config.optString("adapter", "").trim().length() > 0;
        // spider 源同样豁免「没有 origin 就得有一串绝对地址」：它一条路径都不拼，
        // 那个前提（home 是绝对地址、detail 也是）对它不存在。
        if (origin.length() == 0 && !hasAdapter && !spiderSource) {
            if (!home.startsWith("https://")) {
                return "缺少 origin（站点入口地址）；要么补上，要么把 web.paths.home 写成 https 绝对地址";
            }
            // **整张表都要查，不能只查 home。**
            //
            // agedm-decl.json 就是只写了 home 的绝对地址、别的没写：校验放行、源能
            // 切过去、首页也能看，但一点详情就报
            //   Expected URL scheme 'http' or 'https' but no scheme was found for /api/v…
            // —— 没有 origin，纯路径拼不出地址。那种错要等用户点开某部片子才出现，
            // 归因特别难；不如在这一步就拦住，报一句人话。
            //
            // ⚠️ 只扫「配置里写了的键」是不够的 —— SiteProfile 会把 site-default.json
            // 递归合并进来，默认的 paths.detail = /api/videos/{id} 是那么补上的。
            // agedm-decl.json 就钻了这个空子：它只在 paths 里写了 home/recommend/
            // newest（都是绝对地址），detail 压根没提，于是扫过去一片干净 ——
            // 合并之后的 detail 却是条相对路径，一点详情就炸。
            //
            // 所以判据是「合并后会不会缺」，不是「配置里写没写」。detail 是必用的
            // （点任何一部片子都要），它必须被显式覆盖成绝对地址。
            String detail = paths.optString("detail", "").trim();
            if (!detail.startsWith("https://")) {
                return "没有 origin 也没有适配器时，web.paths.detail 必须写成 https 绝对地址"
                        + "（现在是：" + (detail.length() == 0 ? "没写，会回落到默认的相对路径" : detail) + "）";
            }
            // 其余写了的一并查掉。前缀 @ 的是适配器简写（@labels / @playlists），跳过。
            java.util.Iterator<String> pathNames = paths.keys();
            while (pathNames.hasNext()) {
                String key = pathNames.next();
                String value = paths.optString(key, "").trim();
                if (value.length() == 0 || value.startsWith("@")) continue;
                if (!value.startsWith("https://")) {
                    return "没有 origin 时，web.paths." + key + " 必须写 https 绝对地址（现在是："
                            + value + "）";
                }
            }
        } else if (origin.length() > 0) {
            try {
                URI uri = new URI(origin);
                if (!"https".equals(uri.getScheme())) return "origin 必须是 https 开头";
                if (uri.getHost() == null || uri.getHost().length() == 0) return "origin 里没有域名";
                if (uri.getUserInfo() != null) return "origin 不能带用户名密码";
                if (uri.getQuery() != null) return "origin 不能带 ?查询参数";
                if (uri.getFragment() != null) return "origin 不能带 #片段";
                String path = uri.getPath();
                if (path != null && path.length() > 1) return "origin 只能是域名，不能带路径";
            } catch (Exception e) {
                return "origin 不是合法地址：" + e.getMessage();
            }
        }

        JSONObject categories = web.optJSONObject("categories");
        // spider 源的分类由 spider 自己给（前端按 spider 的输出画），配置里不必非有。
        if (!spiderSource && (categories == null || categories.length() == 0)) return "web.categories 为空（分类栏会是空的）";
        // 分类 id 以前要求是正整数。AGE动漫 打破了这个假设 —— 它的「分类」是中文词
        // （label=搞笑），没有数字 id。所以放宽成「非空标量」，只拦住真正会让请求
        // 不带过滤发出去的情况：空值 / 0。apiPathFor 那边查不到就不发请求，
        // 那道闸还在（不带 cate_id 的 /api/videos 会返回全站，静默给错数据）。
        // shapes：形状映射。**拼错一个动作名不会报错，只会静默不生效** ——
        // 配置看着是对的，界面永远是空的。所以这里把键名和值形态都查一遍。
        JSONObject shapes = web.optJSONObject("shapes");
        if (shapes != null) {
            Iterator<String> names = shapes.keys();
            while (names.hasNext()) {
                String action = names.next();
                if (action.startsWith("_")) continue;             // _why 之类的注释键
                if ("envelope".equals(action)) continue;          // 信封名，不是动作
                if (!SourceConfig.isAction(action)) {
                    return "web.shapes 里有个不认识的动作用作键：" + action;
                }
                JSONObject spec = shapes.optJSONObject(action);
                if (spec == null) return "web.shapes." + action + " 不是对象";
                Iterator<String> shapeKeys = spec.keys();
                while (shapeKeys.hasNext()) {
                    String shapeKey = shapeKeys.next();
                    if (shapeKey.startsWith("_")) continue;       // 同上
                    if (!SourceConfig.isShapeValue(spec.opt(shapeKey))) {
                        return "web.shapes." + action + "." + shapeKey
                                + " 既不是取值路径，也不是 {div:[a,b]}";
                    }
                }
            }
        }

        Iterator<String> slugs = categories == null ? java.util.Collections.<String>emptyList().iterator() : categories.keys();
        while (slugs.hasNext()) {
            String slug = slugs.next();
            if (slug.startsWith("_")) continue;   // 自述键
            Object value = categories.opt(slug);
            String text = value == null ? "" : String.valueOf(value).trim();
            if (text.length() == 0 || "0".equals(text)) {
                return "web.categories['" + slug + "'] 是空的（点那个分类会算出空值）";
            }
        }

        // fields 里每个角色都要有非空的别名列表，否则 pick() 静默返回 undefined
        JSONObject fields = web.optJSONObject("fields");
        if (fields != null) {
            Iterator<String> roles = fields.keys();
            while (roles.hasNext()) {
                String role = roles.next();
                if (role.startsWith("_")) continue;   // 自述键
                JSONArray names = fields.optJSONArray(role);
                if (names == null || names.length() == 0) {
                    return "web.fields['" + role + "'] 的候选键列表为空";
                }
            }
        }

        // coverage：封面是 AES 加密的，密钥不对会全部降级成占位图
        JSONObject cover = config.optJSONObject("cover");
        if (cover != null) {
            String mode = cover.optString("mode", "auto");
            if ("aes-cbc".equals(mode)) {
                if (cover.optString("key", "").length() == 0) return "cover.key 缺失（封面解不开）";
                if (cover.optString("iv", "").length() == 0) return "cover.iv 缺失（封面解不开）";
            }
        }

        return null;
    }

    static void close(java.io.Closeable closeable) {
        if (closeable != null) {
            try { closeable.close(); } catch (IOException ignored) {}
        }
    }

    static void log(String message) {
        try { android.util.Log.d("AIIvideo", "[source] " + message); } catch (Throwable ignored) {}
    }
}
