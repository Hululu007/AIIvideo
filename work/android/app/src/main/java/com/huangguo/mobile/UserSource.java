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

    private static final String[] HTTP_HOSTS = {
        "jx.wuzhoupai.com",     // AGE动漫 的播放解析
        "xcdn.khfed.com",       // 它的播放器资源
    };

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
    public static final String PLAYER_PREFIX = "__player:";

    /** 亮度下限。0 会把屏幕熄掉，用户再想调回来就得靠运气 —— 留一档看得见。 */
    private static final float MIN_BRIGHTNESS = 0.05f;

    /**
     * 记一条观看进度，落到 AppStore 里，于是 api('library') 能读到它、
     * 进而被同步到账号后端。
     *
     * ── 为什么需要这一段 ──────────────────────────────────────────────
     * 播放器（feed-player.js 的 remember()）只把进度写进了 localStorage 的
     * `hgpref:progress`。那是**页面自己的**记忆，原生侧的 AppStore.progress 一直是空的，
     * 于是「我的 → 观看历史」永远没东西，也永远同步不上去。
     * 而 AppStore 里 `recordProgress(JSONObject)` 是**现成的**，只是零调用者 ——
     * 原版大概是从抓 HTML 那条路里调的，那条路整块删掉时把它一起带走了。
     *
     * 入参：{id, episode, position, duration, title, cover[, episodeCount]}
     * id 必须是纯数字 —— recordProgress 里有 matches("\\d+")。
     */
    private static JSONObject progress(String body, JSONObject out) {
        try {
            String query = body;
            int mark = body.indexOf('?');
            if (mark >= 0) query = body.substring(mark + 1);
            String payload = param(query, "payload");
            if (payload.length() == 0) return fail(out, "进度指令没有 payload");

            JSONObject record = new JSONObject(payload);
            String id = record.optString("id", "").trim();
            if (!id.matches("\\d+")) return fail(out, "视频 id 不是纯数字：" + id);

            MainActivity host = MainActivity.host;
            if (host == null) return fail(out, "Activity 不在，进度没地方存");
            AppStore store = host.store();
            if (store == null) return fail(out, "拿不到 AppStore");

            // 打出来：源身份决定了观看历史的过滤，传空的话记录就会变成「谁都算」。
            log("记进度 " + id + " source=["
                    + record.optString("source", "<没有这个字段>") + "]");
            store.recordProgress(record);
            out.put("ok", true);
            return out;
        } catch (Throwable e) {
            return fail(out, "记进度失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private static JSONObject http(String body, JSONObject out) {
        try {
            String query = body;
            int mark = body.indexOf('?');
            if (mark >= 0) query = body.substring(mark + 1);
            String payload = param(query, "payload");
            if (payload.length() == 0) return fail(out, "HTTP 指令没有 payload");

            JSONObject req = new JSONObject(payload);
            String url = req.optString("url", "");
            if (!url.startsWith("https://")) return fail(out, "只放行 https：" + url);
            String host = URI.create(url).getHost();
            boolean allowed = false;
            for (String h : HTTP_HOSTS) {
                if (h.equalsIgnoreCase(host)) { allowed = true; break; }
            }
            if (!allowed) return fail(out, "主机不在播放层白名单里：" + host);

            java.util.Map<String, String> headers = new java.util.LinkedHashMap<String, String>();
            JSONObject head = req.optJSONObject("headers");
            if (head != null) {
                for (Iterator<String> it = head.keys(); it.hasNext(); ) {
                    String k = it.next();
                    headers.put(k, head.optString(k, ""));
                }
            }

            byte[] raw = body.startsWith("post")
                    ? AiiHttp.postFormRaw(url, req.optString("body", ""), headers, 15)
                    : AiiHttp.getRaw(url, headers, 15);

            out.put("ok", true);
            out.put("body", new String(raw, "UTF-8"));
            return out;
        } catch (Throwable e) {
            return fail(out, "HTTP 失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    /**
     * 源身份 —— 这套配置把 `/api/videos/{id}` 翻成哪个地址（把 {id} 那一格挖掉）。
     *
     * ── 为什么不能用名字，也不能用配置地址 ──────────────────────────────
     * 观看历史的条目是 `{id, …}`，而 **id 只在源内部唯一**：红果的 117 和
     * AGE动漫 的 117 是两部不相干的片子。所以「这条历史能不能在当前源里打开」
     * 本质上是问「这里的 id 会被翻到哪儿去」。
     *
     *   名字     会被人改，而且同一个站可以有两个名字（源列表里就同时有
     *            agedm-decl 和 www.agedm.io）。用名字的话，改一次名历史全断。
     *   配置地址 更稳，但同一个站的两份配置地址天然不同 —— 而那两份**确实不是
     *            同一个源**：agedm-decl 是旧的零代码配置，没有 adapter，
     *            `/api/videos/{id}` 落到普通路径上根本解不出 id；www.agedm.io
     *            走 agedm 适配器，翻到 api.agedm.io/v2/detail/。分开是对的。
     *
     * 只有「id 往哪儿翻」决定 id 空间。翻到同一个地方 = 同一个空间 = 历史该互通；
     * 翻到不同地方 = 硬混就是张冠李戴（静默播错片子，比报错糟得多）。
     *
     * 返回的串一律以 hgsrc: 开头 —— 版本标记。早期版本存的是「源名字」，没有
     * 前缀；前端见到没前缀的按老数据处理（当「算」），否则升级之后所有人的
     * 历史会一次性消失。
     */
    private static String identity(JSONObject config) {
        if (config == null) return "";
        String adapter = config.optString("adapter", "").trim();
        String api = config.optString("api", "").trim();

        if ("agedm".equals(adapter)) {
            String base = api.length() > 0 ? api : "https://api.agedm.io";
            return "hgsrc:agedm|" + trimSlash(base) + "/v2/detail/";
        }
        if ("cms".equals(adapter)) {
            String cms = api.length() > 0 ? api : config.optString("playback", "").trim();
            if (cms.length() > 0) return "hgsrc:cms|" + cms;
        }

        String origin = config.optString("origin", "").trim();
        if (origin.length() > 0) {
            JSONObject web = config.optJSONObject("web");
            JSONObject paths = web == null ? null : web.optJSONObject("paths");
            String detail = paths == null ? "" : paths.optString("detail", "");
            return "hgsrc:rest|" + origin + trimId(detail);
        }

        // 说不出 id 往哪儿翻（配置没配全），就退回配置地址 —— 至少不会和别的源撞上。
        return "hgsrc:cfg|" + config.optString("__url", "");
    }

    /** 把路径模板里 `{id}` 那一格挖掉。挖掉之后剩下的就是「id 空间」的边界。 */
    private static String trimId(String template) {
        int at = template.indexOf("{id}");
        return at < 0 ? template : template.substring(0, at) + template.substring(at + 4);
    }

    private static String trimSlash(String url) {
        String out = url;
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }

    public static JSONObject handle(Context context, SiteProfile profile, String command) {
        JSONObject out = new JSONObject();
        try {
            if (command == null) return fail(out, "指令为空");
            // 播放进度上报也借这条桥。见 progress() 上面那段说明。
            if (command.startsWith(PROGRESS_PREFIX)) {
                return progress(command.substring(PROGRESS_PREFIX.length()).trim(), out);
            }
            // 播放层解析的 HTTP 出口，见 HTTP_PREFIX 上面那段。
            if (command.startsWith(HTTP_PREFIX)) {
                return http(command.substring(HTTP_PREFIX.length()).trim(), out);
            }
            // 播放器那两件要原生帮忙的事（亮度、清晰度）。同样走 detail 桥，理由和
            // __source: 一样：不去动 dispatch 那两张表。
            if (command.startsWith(PLAYER_PREFIX)) {
                return player(context, command.substring(PLAYER_PREFIX.length()).trim(), out);
            }
            // 内置代理核心。同样借 detail 桥，见 PROXY_PREFIX 上面那段。
            if (command.startsWith(PROXY_PREFIX)) {
                return proxyCore(context, command.substring(PROXY_PREFIX.length()).trim(), out);
            }
            if (!command.startsWith(PREFIX)) return fail(out, "不是源设置指令");

            String body = command.substring(PREFIX.length()).trim();
            String action = body;
            String url = "";
            String value = "";
            int mark = body.indexOf('?');
            if (mark >= 0) {
                action = body.substring(0, mark);
                String query = body.substring(mark + 1);
                url = param(query, "url");
                // 有的指令带的不是地址（比如 proxy:set 带的是代理串），
                // 参数名分开读，免得把代理串塞进 url 那个语义里。
                value = param(query, "value");
            }

            if ("get".equals(action)) {
                JSONObject current = read(context);
                // 这一行是给冒烟脚本认的：没有它会分不清「用户还没配源」和
                // 「前端没起来」—— 两种情况下都没有接口调用，外观却完全不同。
                if (current == null) {
                    log("未配源 —— 前端会停在设置页");
                } else {
                    // 配了源就立刻预热首屏。这里是最早能拿到 profile 的地方
                    // （WebView 还在载入、闸门还没过），离 goHome() 真发请求还有
                    // 好几百毫秒 —— 正好用来跑 DoH 和 TLS。
                    AiiHttp.init(context);
                    AiiHttp.warmup(profile);
                }
                out.put("ok", true);
                out.put("configured", current != null);
                out.put("origin", current == null ? "" : current.optString("origin", ""));
                out.put("url", current == null ? "" : current.optString("__url", ""));
                out.put("identity", identity(current));
                return out;
            }

            if ("clear".equals(action)) {
                clear(context);
                // 配置没了，进程里那份预热/连接池也得跟着走 —— 否则下次配了别的源，
                // 首页可能拿到「清空之前那个源」的字节。
                AiiHttp.resetForSourceChange();
                log("已清除用户源");
                out.put("ok", true);
                out.put("configured", false);
                return out;
            }

            if ("proxy:get".equals(action)) {
                out.put("ok", true);
                out.put("proxy", AiiHttp.proxyRaw());
                return out;
            }

            if ("proxy:set".equals(action)) {
                // 只写不重建连接：setProxy 会把客户端置空，下一次请求自然重建。
                // 这里不主动发请求验证 —— 代理通不通要等真流量才知道，而「填完
                // 立刻测一次」会在代理没起来时给一个吓人的报错。
                String problem = AiiHttp.setProxy(value);
                if (problem != null) return fail(out, problem);
                out.put("ok", true);
                out.put("proxy", AiiHttp.proxyRaw());
                return out;
            }

            if ("web".equals(action)) {
                // 把**当前生效**的 web 块交给前端。内置那份 site-web.js 是打包时
                // 从模板生成的，用户换源之后 paths/categories/fields 可能都不一样，
                // 前端必须拿到合并后的值而不是打包时那份。
                //
                // 合并早就在 SiteProfile 构造里做完了（merge(内置, 用户配置)），
                // 这里只是把结果取出来 —— 不重复实现一遍合并。
                JSONObject web = profile == null ? null : profile.object("web");
                if (web == null) {
                    return fail(out, "当前配置里没有 web 块");
                }
                out.put("ok", true);
                out.put("web", web);
                return out;
            }

            if ("set".equals(action) || "use".equals(action)) {
                if (url.length() == 0) return fail(out, "没有提供源地址");
                // use 优先吃本地缓存（换源不等网络）；set 是「刚填进来的地址」，
                // 缓存里不可能有它，走同一条路不会命中而已。计时是给页面显示用的 ——
                // 「本地缓存 · 180ms」和「拉了 2.4 秒」对用户是两件事。
                long started = System.currentTimeMillis();
                boolean[] usedCache = new boolean[1];
                String problem = applySource(context, url, "use".equals(action), usedCache);
                if (problem != null) return fail(out, problem);
                remember(context, url);
                // 换源只重建 Activity，**进程不死** —— 而预热结果和连接池都是 static，
                // 不清的话新源会拿到旧源的首页字节（实测：切完直接弹「内容更新失败」）。
                AiiHttp.resetForSourceChange();
                JSONObject saved = read(context);
                long spent = System.currentTimeMillis() - started;
                log("已设置用户源 → " + (saved == null ? "" : saved.optString("origin", ""))
                        + "  " + spent + "ms");
                out.put("ok", true);
                out.put("configured", true);
                out.put("origin", saved == null ? "" : saved.optString("origin", ""));
                out.put("url", url);
                out.put("cached", usedCache[0]);
                out.put("ms", spent);
                return out;
            }

            // 通用抓取：源市场清单、TVBox 单仓配置这类「不是源配置本身的 JSON」
            // 也要经原生 HTTP 拉回来 —— WebView 直接 fetch 会被 CORS 拦（页面
            // origin 是 appassets.androidplatform.net，第三方接口不会带 ACAO 头）。
            // __http: 那条通道有播放层主机白名单，不能挪用；这里单独开一个，
            // 只限 https、限大小、限超时，内容交回前端自己解析。
            if ("fetch".equals(action)) {
                if (url.length() == 0) return fail(out, "没有提供地址");
                if (!url.startsWith("https://")) return fail(out, "只放行 https：" + url);
                // 故意**不走 AiiHttp**：那条 client 挂着当前源的 DoH 解析器，
                // 对市场这种「和源无关的第三方域名」会解析出坏答案（实测 sslip.io
                // 在 DoH 下直接 UnknownHost）。这里用系统解析的裸连接，和热更新
                // 通道同样的路径。
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                try {
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(20000);
                    conn.setRequestProperty("User-Agent", "AIIvideo/1.0");
                    int code = conn.getResponseCode();
                    if (code < 200 || code >= 300) return fail(out, "HTTP " + code);
                    java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                    java.io.InputStream in = conn.getInputStream();
                    byte[] chunk = new byte[8192];
                    int read;
                    while ((read = in.read(chunk)) > 0) {
                        buffer.write(chunk, 0, read);
                        if (buffer.size() > 2_000_000) return fail(out, "内容超过 2MB，不像一份源清单");
                    }
                    out.put("ok", true);
                    out.put("body", new String(buffer.toByteArray(), "UTF-8"));
                    return out;
                } finally {
                    conn.disconnect();
                }
            }

            // 后台把某条地址的配置重新拉一遍，只更新**本地缓存**，不动当前生效那份 ——
            // 于是「用缓存秒切」不会让配置永远停在旧版本，而更新也不会触发第二次重启。
            // 下一次切到这个源时用的就是新的。前端也可以手动调（排障用）。
            if ("refresh".equals(action)) {
                if (url.length() == 0) return fail(out, "没有提供源地址");
                try {
                    JSONObject fresh = load(url);
                    String problem = validate(fresh);
                    if (problem != null) return fail(out, problem);
                    writeCache(context, url, fresh);
                    log("已刷新源缓存 " + cacheFile(context, url).getName()
                            + "（" + fresh.toString().length() + " 字节）");
                    out.put("ok", true);
                } catch (Exception e) {
                    String why = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
                    return fail(out, "刷新失败：" + why);
                }
                return out;
            }

            // 给一个源写备注（名字是给人看的，为空就退回自动取的那个名字）。
            if ("note".equals(action)) {
                if (url.length() == 0) return fail(out, "没有提供源地址");
                note(context, url, value);
                JSONObject listing = list(context);
                out.put("ok", true);
                out.put("items", listing.optJSONArray("saved"));
                return out;
            }

            if ("list".equals(action)) {
                JSONObject listing = list(context);
                out.put("ok", true);
                out.put("active", listing.optString("active", ""));
                out.put("items", listing.optJSONArray("saved"));
                // 当前生效那个源的身份。观看历史靠它判断「这条记录能不能在这个源里打开」。
                // 算的是**当前配置**而不是列表里那条 —— 用户可能手改过配置文件，
                // 列表里存的只是「从哪个地址拉的」，配置本身才是真相。见 identity()。
                String ident = identity(read(context));
                // 打出来看得见。源身份决定了观看历史能不能互通，出问题时
                // 「算出来是什么」比「应该是多少」有用得多。
                log("源身份 " + (ident.length() == 0 ? "(未配源)" : ident));
                out.put("identity", ident);
                return out;
            }

            if ("restart".equals(action)) {
                out.put("ok", true);
                restart(context);
                return out;
            }

            if ("forget".equals(action)) {
                if (url.length() == 0) return fail(out, "没有提供源地址");
                forget(context, url);
                out.put("ok", true);
                out.put("active", read(context) == null ? "" : url);
                return out;
            }

            // 恢复出厂设置。前端那个入口在「我的 → 设置」的最后一块，语义就是
            // 把这台设备变回刚装好的样子：没有片单、没有缓存、没有源。
            // 登录态不在这里退 —— 它必须由前端的 logout 先做掉，理由见 factoryReset。
            if ("reset".equals(action)) {
                return factoryReset(context, out);
            }

            return fail(out, "不认识的指令：" + action);
        } catch (Throwable e) {
            log("源设置异常：" + e);
            return fail(out, "内部错误：" + e);
        }
    }


    /**
     * 重启应用。换源之后必须走这一步。
     *
     * ── 为什么不能只 reload 页面 ─────────────────────────────────────
     * SiteProfile 是在 MainActivity.onCreate 里构造的，配置**只在那时候读一次**。
     * WebView 的 location.reload() 只重载页面，Activity 不重建 —— Java 侧那份
     * profile 还是旧的。表现是「切了源，但 __source:web 返回的还是上一个源的
     * web 块，首页也还是上一个源的内容」。
     *
     * 这个坑实测踩到过：源表已经切到 cms-xml、缓存作用域也换了，但分类 tab 还是
     * 黄果那两个、标题还是「AI专区」。
     *
     * 所以先叫起新的 launcher intent，再从主线程退出进程 —— 冷启动一次，
     * 配置、SiteProfile、连接池、预热缓存全部重来。
     */
    private static void restart(Context context) {
        // 让 MainActivity.recreate() —— 那会走 onDestroy → onCreate。SiteProfile
        // 在 onCreate 的链条里构造（beginSession → new SiteProfile），而它每构造
        // 一次都会重读 files/user-source.json，所以重建就等于换源生效。
        //
        // ── 两条硬约束，都是踩出来的 ──────────────────────────────────
        //
        // 1) 必须挪到主线程。从 API 28 起 Activity.recreate() 自己查线程：
        //        if (Looper.myLooper() != mMainThread.getLooper())
        //            throw new IllegalStateException("Must be called from main thread");
        //    这条指令是从 OkHttp 的线程进来的 —— 在那边直接调必然抛，抛了就被
        //    catch 吞掉，表现是「点了切换，什么都没发生，只能自己退出去重进」。
        //
        // 2) 退路不能只加 FLAG_ACTIVITY_NEW_TASK。任务本来就在前台，系统只是把
        //    原任务拎到前面 —— 没有新 Activity，配置也不会重读。要真重来一遍
        //    得带 CLEAR_TASK。
        //
        // 200ms 是留给桥的：让 {ok:true} 先回到前端，界面才来得及画出「正在
        // 切换…」，不然响应还在路上页面就被销毁了。
        final Context app = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                MainActivity host = MainActivity.host;
                if (host != null && !host.isFinishing() && !host.isDestroyed()) {
                    try {
                        host.restart();
                        log("已请求重建 Activity");
                        return;
                    } catch (Throwable e) {
                        log("重建失败：" + e);
                    }
                } else {
                    log("拿不到可用的 MainActivity，退回重新拉起");
                }
                try {
                    Intent launch = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        app.startActivity(launch);
                    }
                } catch (Throwable e) {
                    log("重新拉起失败：" + e);
                }
            }
        }, 200);
    }

    /**
     * 把一个源变成当前配置。返回 null 表示成功，否则是一句给人看的错。
     *
     * 三件事：拿到配置 → 校验 → 干跑一次 SiteProfile → 写进 user-source.json。
     *
     * 「干跑」那段是必须的：SiteProfile 在 Activity.onCreate 里构造，它自己那堆校验
     * （origin 是不是 https、封面 AES 密钥够不够长、allowedHosts 空不空）抛异常的表现
     * 是**一装完就闪退**。而配置存在 files/ 下，App 起不来就进不了设置页 ——
     * 用户被一份填错的配置永久锁在外面，只能清数据。所以在这里先把那条路走一遍。
     *
     * ── 2026-10-16：换源不再等网络 ────────────────────────────────────
     * 每次切源都重新拉一遍那个地址，代价是**一次完整的公网往返** —— 实测量子那种站
     * 1.3~4 秒，慢的时候十几秒（Cloudflare 落在 LAX）。而那份配置一个站在几周里
     * 基本不变：变的只有分类表。所以每份拉回来的配置都在 files/source-cache/ 里
     * 留一份，切源时先用它。
     *
     * **「失效的源会被当场拒绝」这条没丢**：缓存那份同样要过 validate + 干跑，
     * 过不了就丢掉、退回重新拉。丢掉的是「网络还能不能通」，留下的是「这份配置会不会
     * 让应用起不来」—— 而后者才是当初要挡的那个东西。
     *
     * 命中缓存之后会在后台把缓存刷新一遍（refreshCacheAsync），所以下一次切过来
     * 用的是新的；这次先按手上这份跑。
     *
     * @param usedCache 长度 ≥1 的数组，出参：这次是不是吃的缓存（页面要显示「本地缓存」）
     */
    private static String applySource(Context context, String url, boolean preferCache, boolean[] usedCache) {
        if (preferCache) {
            JSONObject cached = readJson(cacheFile(context, url));
            JSONObject config = cached == null ? null : cached.optJSONObject("config");
            if (config != null) {
                String problem = verify(context, config);
                if (problem == null) {
                    try {
                        config.put("__url", url);
                        save(context, config);
                    } catch (Exception e) {
                        return "保存失败：" + e.getMessage();
                    }
                    usedCache[0] = true;
                    log("换源用的是本地缓存，没有等网络");
                    refreshCacheAsync(context, url);
                    return null;
                }
                // 缓存坏了（旧版本的字段、被改坏的文件）—— 当它不存在，重新拉。
                log("缓存那份配置不能用了（" + problem + "），重新拉一次");
            }
        }
        JSONObject config;
        try {
            config = load(url);
        } catch (Exception e) {
            return "拉取失败：" + e.getMessage();
        }
        String problem = verify(context, config);
        if (problem != null) return problem;
        try {
            config.put("__url", url);
            save(context, config);
        } catch (Exception e) {
            return "保存失败：" + e.getMessage();
        }
        writeCache(context, url, config);
        return null;
    }

    /** 校验 + 干跑。返回 null 表示这份配置能用。 */
    private static String verify(Context context, JSONObject config) {
        String problem = validate(config);
        if (problem != null) return problem;
        try {
            new SiteProfile(context, config);
        } catch (Throwable e) {
            String why = e.getMessage();
            if (e.getCause() != null && e.getCause().getMessage() != null) why = e.getCause().getMessage();
            return "这份配置会让应用启动失败，已拒绝：" + why;
        }
        return null;
    }

    // ── 源配置的本地缓存 ──────────────────────────────────────────────

    private static File cacheDir(Context context) {
        File dir = new File(context.getFilesDir(), CACHE_DIR);
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    /**
     * 缓存文件名 = 地址的 md5。地址里可能有查询参数和百分号编码，
     * 直接拿来当文件名既不安全也没法一眼认出来 —— 反正没人读文件名。
     */
    private static File cacheFile(Context context, String url) {
        return new File(cacheDir(context), digest(url) + ".json");
    }

    private static String digest(String text) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(text.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (int i = 0; i < bytes.length; i++) {
                sb.append(Character.forDigit((bytes[i] >> 4) & 0xf, 16));
                sb.append(Character.forDigit(bytes[i] & 0xf, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // 拿不到 md5（理论上不会）就退回 hashCode：命中率低无所谓，别在这儿抛。
            return Integer.toHexString(text.hashCode());
        }
    }

    private static void writeCache(Context context, String url, JSONObject config) {
        try {
            JSONObject entry = new JSONObject();
            entry.put("url", url);
            entry.put("at", System.currentTimeMillis());
            entry.put("config", config);
            writeJson(cacheFile(context, url), entry);
        } catch (Exception e) {
            log("源缓存写入失败：" + e);
        }
    }

    /**
     * 后台把缓存刷新一遍。**只动缓存，不动当前生效那份** —— 动了就得再来一次重启，
     * 用戶刚看着界面回来又要被重启一次。下一次切到这个源用的就是新的。
     */
    private static void refreshCacheAsync(final Context context, final String url) {
        final Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject fresh = load(url);
                    if (validate(fresh) != null) { log("源缓存刷新：新配置没通过校验，保留旧的"); return; }
                    writeCache(app, url, fresh);
                    log("源缓存已刷新：" + url);
                } catch (Exception e) {
                    // 离线或站点挂了都很正常，缓存照旧用。
                    log("源缓存刷新失败（保留旧的）：" + (e.getMessage() == null ? String.valueOf(e) : e.getMessage()));
                }
            }
        }, "source-cache-refresh").start();
    }

    // ── 源清单 ────────────────────────────────────────────────────────

    private static File listFile(Context context) {
        return new File(context.getFilesDir(), LIST_FILE);
    }

    /** 当前生效的地址。取自 user-source.json 里的 __url —— 单一真相，不另存一份。 */
    private static String activeUrl(Context context) {
        JSONObject active = read(context);
        return active == null ? "" : active.optString("__url", "");
    }

    /**
     * 源清单。{active, saved:[{url, name, at, active}]}
     *
     * 存的是**地址**不是配置内容 —— 配置内容在 user-source.json 里，只有当前这一份。
     * 切换 = 重新拉那个地址 + 覆盖 user-source.json，跟第一次填一样走完整的校验。
     * 这样切到一个已经失效的源会被当场拒绝，而不是留一份坏配置在盘上等下次启动闪退。
     */
    public static JSONObject list(Context context) {
        JSONObject out = new JSONObject();
        JSONArray saved = new JSONArray();
        String active = activeUrl(context);
        try {
            out.put("active", active);
        } catch (Exception ignored) {}

        JSONObject store = readJson(listFile(context));
        JSONArray items = store == null ? null : store.optJSONArray("saved");
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<String>();
        if (active.length() > 0) seen.add(active);
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String url = item.optString("url", "");
                if (url.length() > 0) seen.add(url);
            }
        }
        for (String url : seen) {
            JSONObject row = find(items, url);
            JSONObject entry = new JSONObject();
            try {
                entry.put("url", url);
                // name 是自动的（配置里的 name，其次从地址猜），alias 是用户写的备注。
                // 分开存的原因只有一个：备注不能被自动名字洗掉 —— 每次切回来、
                // 每次配置里没写 name，都会重新算一次 name，合在一个字段里就丢了。
                entry.put("name", row == null ? guessName(url) : row.optString("name", guessName(url)));
                entry.put("alias", row == null ? "" : row.optString("alias", ""));
                entry.put("at", row == null ? 0L : row.optLong("at", 0L));
                entry.put("active", url.equals(active));
            } catch (Exception ignored) {}
            saved.put(entry);
        }
        try {
            out.put("saved", saved);
        } catch (Exception ignored) {}
        return out;
    }

    private static JSONObject find(JSONArray items, String url) {
        if (items == null) return null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && url.equals(item.optString("url", ""))) return item;
        }
        return null;
    }

    /**
     * 把一个地址记进清单（已存在就刷新时间戳）。名字优先用配置里的 name，其次从地址猜。
     *
     * **用户写的备注（alias）原样带着走。** 这个函数每次切换、每次添加都会被调一次，
     * 而它按 url 重建那一行 —— 忘了带 alias 的话，用户改完备注、切一次源，备注就没了。
     */
    private static void remember(Context context, String url) {
        try {
            JSONObject store = readJson(listFile(context));
            if (store == null) store = new JSONObject();
            JSONArray items = store.optJSONArray("saved");
            if (items == null) items = new JSONArray();

            String name = "";
            JSONObject config = read(context);
            if (config != null) name = config.optString("name", "");
            if (name.length() == 0) {
                JSONObject row = find(items, url);
                name = row == null ? "" : row.optString("name", "");
            }
            if (name.length() == 0) name = guessName(url);

            JSONObject row = find(items, url);
            String alias = row == null ? "" : row.optString("alias", "");

            JSONArray next = new JSONArray();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null || url.equals(item.optString("url", ""))) continue;
                next.put(item);
            }
            JSONObject entry = new JSONObject();
            entry.put("url", url);
            entry.put("name", name);
            entry.put("alias", alias);
            entry.put("at", System.currentTimeMillis());
            next.put(entry);

            store.put("saved", next);
            writeJson(listFile(context), store);
        } catch (Exception e) {
            log("源清单写入失败：" + e);
        }
    }

    /**
     * 写/清一个源的备注。传空串 = 清掉备注，退回自动取的那个名字。
     *
     * 备注只是给人看的一行字，不参与任何判断 —— 源身份（identity()）算的是
     * 「这个源的 id 会翻到哪儿去」，跟名字无关。别把它接到别的地方去。
     */
    private static void note(Context context, String url, String alias) {
        try {
            String clean = alias == null ? "" : alias.trim();
            if (clean.length() > 40) clean = clean.substring(0, 40);   // 一行放得下，别让它撑破布局
            JSONObject store = readJson(listFile(context));
            JSONArray items = store == null ? null : store.optJSONArray("saved");
            JSONArray next = new JSONArray();
            boolean hit = false;
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    if (url.equals(item.optString("url", ""))) {
                        hit = true;
                        item.put("alias", clean);
                    }
                    next.put(item);
                }
            }
            if (!hit) {
                // 清单里还没有它（比如当前生效的那个是手改配置弄出来的）：补一行。
                JSONObject entry = new JSONObject();
                entry.put("url", url);
                entry.put("name", guessName(url));
                entry.put("alias", clean);
                entry.put("at", System.currentTimeMillis());
                next.put(entry);
            }
            JSONObject out = new JSONObject();
            out.put("saved", next);
            writeJson(listFile(context), out);
            log("源备注：" + (clean.length() == 0 ? "(清空)" : clean));
        } catch (Exception e) {
            log("源备注写入失败：" + e);
        }
    }

    /**
     * 从清单里划掉一个地址。
     *
     * 如果划掉的正好是当前生效的那个，连 user-source.json 一起删 —— 留着它会让
     * App 下次启动仍然读那份配置，而设置页里的清单已经没有它了，两边对不上。
     * 删完等于回到「没配源」，用户下次打开会看到设置页。
     *
     * ── 2026-10-16：缓存也要一起删 ──────────────────────────────────────
     * 只划清单不删 files/source-cache/<md5>.json 会留一个很难看出来的坑：
     * 用户把同一个地址再加回来时，`use`/`set` 走的是 applySource 的 preferCache
     * 分支，**直接命中那份旧缓存，一次网络都不发**（实测 7ms 返回，日志是
     * 「换源用的是本地缓存，没有等网络」）。而删源重加这个动作，用户做的场合
     * 十有八九就是「服务器上配置改了，我要它重新读一遍」—— 结果恰恰相反。
     *
     * 后台确实会在 300ms 后把缓存刷新成新的（refreshCacheAsync），但那时
     * Activity 已经建完了，刷新出来的东西没有任何调用方。用户看到的就是
     * 「删了、重加了、还是老样子」。
     *
     * 缓存只是「省一次往返」的优化，源都不要了就没有保留它的理由。
     */
    private static void forget(Context context, String url) {
        try {
            JSONObject store = readJson(listFile(context));
            JSONArray items = store == null ? null : store.optJSONArray("saved");
            JSONArray next = new JSONArray();
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null || url.equals(item.optString("url", ""))) continue;
                    next.put(item);
                }
            }
            JSONObject out = new JSONObject();
            out.put("saved", next);
            writeJson(listFile(context), out);
            File cached = cacheFile(context, url);
            if (cached.isFile()) {
                log(cached.delete() ? "源缓存一并清掉 " + cached.getName()
                        : "源缓存删不掉（下次加回来会命中旧的那份）：" + cached.getName());
            }
            if (url.equals(activeUrl(context))) {
                clear(context);
                log("已删除当前源，回到未配置状态");
            }
        } catch (Exception e) {
            log("源清单删除失败：" + e);
        }
    }

    /** 从地址猜个显示名：最后一段去掉扩展名，没有就用域名。 */
    private static String guessName(String url) {
        try {
            java.net.URI uri = new java.net.URI(url.trim());
            String path = uri.getPath() == null ? "" : uri.getPath();
            int slash = path.lastIndexOf('/');
            String last = slash < 0 ? path : path.substring(slash + 1);
            int dot = last.lastIndexOf('.');
            if (dot > 0) last = last.substring(0, dot);
            if (last.length() > 0) return last;
            String host = uri.getHost();
            return host == null ? url : host;
        } catch (Exception e) {
            return url;
        }
    }

    private static JSONObject readJson(File target) {
        if (target == null || !target.isFile()) return null;
        InputStream in = null;
        try {
            in = new FileInputStream(target);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return new JSONObject(new String(out.toByteArray(), "UTF-8"));
        } catch (Exception e) {
            return null;
        } finally {
            close(in);
        }
    }

    private static void writeJson(File target, JSONObject value) throws IOException {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(target);
            out.write(value.toString().getBytes("UTF-8"));
            out.flush();
        } finally {
            close(out);
        }
    }

    // ── 识别 ──────────────────────────────────────────────────────────

    /**
     * 拉一个地址，认出它是什么，规范化成一份配置。
     *
     * 用户不需要知道「适配器」是什么 —— 贴上去能识别就行。识别不出来时给一句
     * 人话，别让他对着一个沉默的输入框猜。
     *
     * 认四种：
     *   {origin|web}         我们自己的 JSON 配置      → adapter 缺省（rest）
     *   {code,list,class}    苹果CMS 接口             → adapter=cms，从 class[] 生成 categories
     *   {urls[]}             TVBox 多仓               → 二期
     *   {sites[]}            TVBox 单仓               → 二期
     */
    public static JSONObject load(String url) throws Exception {
        // AGE动漫 先认，而且**认在 fetch 之前**：它的 www/m 都给 HTML（PC 是 Go 模板、
        // 移动是 Vue SPA），数据在另一个域名的 /v2/ 下面。所以按**域名**认，不按响应
        // 形状认 —— 走过去 fetch 一次只会拿到一坨 HTML，然后报「认不出这是什么配置」。
        if (isAgedm(url)) return wrapAgedm();
        // hanime1 同样按域名认，也**认在 fetch 之前** —— 它给的是 HTML，不是 JSON，
        // 走过去 fetch 只会拿到 100~400KB 的页面然后报「认不出这是什么配置」。
        // 而且它是本轮唯一一个「有适配器、但用户填的地址直接就是站点根」的源：
        // origin 就是 hanime1.me，适配器自己往上面拼路径。
        if (isHanime1(url)) return wrapHanime1(url);

        JSONObject fetched = fetch(url);
        if (fetched == null) throw new IOException("响应为空");

        // 苹果CMS：code + list + class 同时出现才算数。
        // 单看 code 不行 —— 我们自己的配置里将来也可能有别的 code 字段。
        if (fetched.has("code") && fetched.has("list") && fetched.has("class")) {
            // 两次探测的结果是**互补的**，实测（暴風源，2026-10-16）：
            //
            //   裸地址 / ?ac=list      list=20  class=49  封面域名=0
            //   ?ac=detail&pg=1        list=20  class= 0  封面域名=1
            //
            // class[]（分类表）只在 list 那侧出现，vod_pic（封面）只在 detail 那侧。
            // 少任何一边都不行：没有 class 就建不出分类表，没有 vod_pic 就提取不出
            // 封面域名 —— 而空的 allowedHosts 会在 SiteProfile 构造时抛
            // 「缺少封面域名」，那是 Activity.onCreate 里，表现是一装完就闪退。
            //
            // 所以拉两次合并：分类用第一份，列表用第二份。
            JSONObject merged = new JSONObject(fetched.toString());
            try {
                JSONObject rich = fetch(withParams(url, "ac=detail&pg=1"));
                if (rich.has("list")) merged.put("list", rich.getJSONArray("list"));
                if (!merged.has("class") && rich.has("class")) merged.put("class", rich.getJSONArray("class"));
            } catch (Exception e) {
                // 第二次拉不动就用第一份。后面提取不到封面域名会明确报错，
                // 不会把坏配置存进去。
            }

            // XML 那套接口（/at/xml/）的 <class> 里**只有 id 和名字，没有 type_pid**，
            // 分类树读不出来 —— 结果就是底部导航只能退化成一个入口。同一台机器的
            // JSON 接口有，而两者的地址只差一个 /at/xml/。
            //
            // 这里只把层级关系「补」进去，id 和名字还是用 XML 那份：两个接口本来就
            // 是同一张 type 表，id 对得上；万一它的 class 变了，补不上的那些也只是
            // 退回顶层，不会丢分类。整段失败无所谓 —— 没有 pid 就按一维处理。
            if (!hasHierarchy(merged) && url.contains("/at/xml/")) {
                try {
                    JSONObject sibling = fetch(withParams(url.replace("/at/xml/", "/"), "ac=list"));
                    int filled = mergeParents(merged.optJSONArray("class"), sibling.optJSONArray("class"));
                    log("XML 接口没有分类层级，从同门 JSON 接口补到 " + filled + " 条");
                } catch (Throwable e) {
                    log("补问同门 JSON 接口失败，分类按一维处理：" + e);
                }
            }
            return wrapCms(url, merged);
        }
        if (fetched.has("urls")) {
            throw new IOException("这是 TVBox 多仓配置（列出的是子配置地址），二期才支持");
        }
        if (fetched.has("sites")) {
            throw new IOException("这是 TVBox 单仓配置（86 个源里 79 个是 spider JAR），二期才支持");
        }
        if (fetched.has("origin") || fetched.has("web")) {
            return fetched;
        }
        throw new IOException("认不出这是什么配置 —— 既不像我们自己的 JSON，也不像苹果CMS 接口");
    }

    /** 地址是不是 AGE动漫（含 api./m./www. 几个子域）。 */
    private static boolean isAgedm(String url) {
        try {
            String host = new URI(url == null ? "" : url.trim()).getHost();
            return host != null && (host.equals("agedm.io") || host.endsWith(".agedm.io"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 地址是不是 hanime1（含 m./www. 这类子域）。
     *
     * 只认 hanime1.me 这一个域。站点的镜像有几个（hanimeone.me 是它的漫画站、
     * 别的镜像域不在我们的适配器覆盖范围内），**不跟着认** —— 认了一个对不上
     * DOM 形状的镜像域，表现是首页一片空白而不是明确报错，那比不认还糟。
     */
    private static boolean isHanime1(String url) {
        try {
            String host = new URI(url == null ? "" : url.trim()).getHost();
            if (host == null) return false;
            return host.equals("hanime1.me") || host.endsWith(".hanime1.me");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * hanime1 → 我们的配置。
     *
     * 和 agedm 不一样的地方：这个站**没有接口可探**（/api/* 全 404），所以
     * 「探通才存」这件事只能拿首页**页面本身**当探针 —— 拉一次 GET /，确认
     * 里面能抠出卡片链接，才认为这个源可用。
     *
     * 探针失败就抛，不留一份点了没反应的配置。这正是 UserSource 那套
     * 「切换走完整校验」的规矩要的效果。
     */
    private static JSONObject wrapHanime1(String url) throws Exception {
        String base = "https://hanime1.me";
        JSONArray probe = probeHanime1(base);
        if (probe == null) {
            throw new IOException("hanime1 首页里没找到任何影片卡片，站点可能改版或该地区被墙");
        }
        log("hanime1 探测通过：首页卡片 " + probe.length() + " 条");

        JSONObject config = new JSONObject();
        config.put("adapter", "hanime1");
        config.put("origin", base);

        JSONObject cover = new JSONObject();
        cover.put("mode", "plain");
        JSONArray hosts = new JSONArray();
        hosts.put("vdownload.hembed.com");
        cover.put("allowedHosts", hosts);
        cover.put("hostRewrites", new JSONObject());
        config.put("cover", cover);

        JSONObject web = new JSONObject();
        web.put("firstPageSize", 24);
        web.put("pageSize", 40);

        JSONObject paths = new JSONObject();
        paths.put("home", "/api/home");
        paths.put("detail", "/api/videos/{id}");
        paths.put("episode", "/api/videos/{id}/play?ep={episode}");
        paths.put("search", "/api/search?q={keyword}&page={page}");
        paths.put("category", "/api/videos?cate_id={cateId}&page={page}&size={size}");
        // ── tag-list 走 tag=，不走 cate_id ──────────────────────────────
        // 这两个参数在站点那边是**两套东西**：
        //   cate_id → genre（10 个大方向：裏番 / 3DCG / MMD …）
        //   tag     → tags[]（标签面板上那 240 个多选框：巨乳 / NTR / 校園 …）
        // 走错了不会有报错，只会「点标签出来的还是默认列表」，看着像没过滤。
        // Hanime1Adapter 那边按参数名分流，见它的 fetch()。
        paths.put("tag-list", "/api/videos?tag={slug}&page={page}&size={size}");
        paths.put("tags", "/api/tags");
        web.put("paths", paths);

        JSONObject categories = new JSONObject();
        JSONArray labels = new JSONArray();
        for (int i = 0; i < HANIME1_GENRES.length; i++) {
            categories.put(HANIME1_GENRES[i], HANIME1_GENRES[i]);
            labels.put(HANIME1_GENRES[i]);
        }
        web.put("categories", categories);

        // ── 底部导航 ────────────────────────────────────────────────────
        // 站点的十个 genre 里，前四个是量最大的（裏番 / 泡麵番 / 3DCG / 2D動畫），
        // 剩下的合成一个「更多」。这和 wrapCms 那套「按子分类数量排序取前 4 个」
        // 是同一个思路，只是这里的分类是固定的十个、没有层级，所以写死顺序。
        JSONObject ui = new JSONObject();
        JSONArray tabs = new JSONArray();
        String[][] plan = {
            {"裏番", "裏番", "film"},
            {"泡麵番", "泡麵番", "sparkle"},
            {"3DCG", "3DCG", "grid"},
            {"2D動畫", "2D動畫", "tv"},
            {"more", "更多", "grid"},
        };
        for (int i = 0; i < plan.length; i++) {
            JSONObject tab = new JSONObject();
            tab.put("key", plan[i][0]);
            tab.put("label", plan[i][1]);
            tab.put("icon", plan[i][2]);
            tabs.put(tab);
        }
        ui.put("tabs", tabs);

        JSONArray mainFour = new JSONArray();
        mainFour.put("裏番");
        mainFour.put("泡麵番");
        mainFour.put("3DCG");
        mainFour.put("2D動畫");
        JSONArray rest = new JSONArray();
        rest.put("Motion Anime");
        rest.put("2.5D");
        rest.put("AI生成");
        rest.put("MMD");
        rest.put("Cosplay");
        rest.put("新番預告");

        JSONObject cids = new JSONObject();
        cids.put("裏番", oneArray("裏番"));
        cids.put("泡麵番", oneArray("泡麵番"));
        cids.put("3DCG", oneArray("3DCG"));
        cids.put("2D動畫", oneArray("2D動畫"));
        cids.put("more", rest);
        ui.put("categoryIds", cids);

        JSONObject clabels = new JSONObject();
        clabels.put("裏番", oneArray("裏番"));
        clabels.put("泡麵番", oneArray("泡麵番"));
        clabels.put("3DCG", oneArray("3DCG"));
        clabels.put("2D動畫", oneArray("2D動畫"));
        clabels.put("more", rest);
        ui.put("categoryLabels", clabels);

        JSONObject ctitles = new JSONObject();
        ctitles.put("裏番", "裏番");
        ctitles.put("泡麵番", "泡麵番");
        ctitles.put("3DCG", "3DCG");
        ctitles.put("2D動畫", "2D動畫");
        ctitles.put("more", "更多分类");
        ui.put("categoryTitles", ctitles);

        JSONObject cleads = new JSONObject();
        cleads.put("裏番", "hanime1.me 主分类。");
        cleads.put("泡麵番", "短篇。");
        cleads.put("3DCG", "3DCG 作品。");
        cleads.put("2D動畫", "2D 动画。");
        cleads.put("more", "其余分类。");
        ui.put("categoryLeads", cleads);

        // ── 首页那排胶囊：从 240 个标签里挑 8 个有代表性的 ────────────────
        // 挑法不是「从头截前 8 个」（那会全是「無碼 / AI解碼 / 中文字幕」这种
        // 规格词，看着像一排设置项），而是**每个分区轮一个** —— 和 wrapCms
        // 那套「每个频道轮流挑一个」是同一个思路。
        //
        // 顺序也刻意打散了：规格词放第一个（它最常用），剩下按分区铺开。
        JSONArray popular = new JSONArray();
        String[][] picks = {
            { "中文字幕", "中文字幕" },   // 影片屬性里最常用的
            { "巨乳",     "巨乳" },
            { "JK",       "JK" },
            { "純愛",     "純愛" },
            { "NTR",      "NTR" },
            { "校園",     "校園" },
            { "後宮",     "後宮" },
            { "3P",       "3P" },
        };
        for (int i = 0; i < picks.length; i++) {
            JSONArray pair = new JSONArray();
            pair.put(picks[i][0]);
            pair.put(picks[i][1]);
            popular.put(pair);
        }
        ui.put("popularTags", popular);
        ui.put("publicPopularTags", new JSONArray());

        // ── 「全部类型」页的分区：7 个 ───────────────────────────────────
        // 这是这个站**最值钱的一层**。标签不分组的话，240 个挤在一页里没法看。
        //
        // ⚠️ 组名必须和适配器交上来的 `tag.group` 一字不差。对不上的组会被
        //    前端**整组丢掉**——不报错，只是页面上少一块（见站点配置参考）。
        //
        // 分区表原来是从适配器那边取（Hanime1Adapter.tagSections()），好处是
        // 「唯一来源」。但那个类现在不在仓库里（见 .gitignore），留着这行会让
        // 整个文件编译不过 —— 而分区表本来就是**配置的一部分**，放在这里
        // 反而更自洽：它是「这个源的界面长什么样」，不是「怎么解析响应」。
        //
        // 唯一的代价：改分区要同时动适配器的 TAG_SECTIONS。两处都留了指回来的
        // 注释，对不上时表现为「某个分区的标签消失」，好排查。
        JSONArray tagGroups = new JSONArray();
        String[][] sections = HANIME1_TAG_SECTIONS;
        for (int i = 0; i < sections.length; i++) {
            JSONArray g = new JSONArray();
            g.put(sections[i][1]);   // code，必须和 tag.group 一致
            g.put(sections[i][2]);   // 界面上的标题
            tagGroups.put(g);
        }
        // other 一定要留着：适配器认不出的分区名会折进这里（站点改字形就会发生）
        JSONArray other = new JSONArray();
        other.put("other");
        other.put("其他");
        tagGroups.put(other);
        ui.put("tagGroups", tagGroups);

        web.put("ui", ui);
        config.put("web", web);

        config.put("_note", "hanime1.me。数据全在 SSR 的 HTML 里（站点没有任何 JSON 接口，"
                + "/api/* 全 404），播放地址是页面内联的 ?secure= 签名直链，"
                + "**必须在点播放那一刻现抓详情页**，不能进缓存。"
                + "适配器见 Hanime1Adapter.java；那个站的逆向笔记不在仓库里"
                + "（内容是成人向，代码留、内容不留）。");
        return config;
    }

    /** 站点的十个分类。值就是中文原词 —— 它的 URL 里就是中文，没有代码。 */
    private static final String[] HANIME1_GENRES = {
        "裏番", "泡麵番", "Motion Anime", "3DCG", "2.5D",
        "2D動畫", "AI生成", "MMD", "Cosplay", "新番預告"
    };

    /**
     * 标签面板的七个分区：{ 站点分区名, 我们的组 code, 界面上的标题 }。
     *
     * ⚠️ **必须和适配器里的 TAG_SECTIONS 一字不差。** 那边负责把 240 个标签
     * 按分区打上 group，这边负责告诉前端「有哪些组要渲染」。两个表对不上的
     * 那一组会被**整组丢掉** —— 不报错，只是页面上少一块。
     *
     * 为什么会分成两份而不是共用：适配器不在仓库里（见 .gitignore），
     * 而这张表是**配置的一部分**（界面长什么样），不是解析逻辑。真要合并，
     * 得先把适配器放回来。
     */
    private static final String[][] HANIME1_TAG_SECTIONS = {
        { "影片屬性", "hanime-attr",  "影片属性" },
        { "人物關係", "relationship", "人物关系" },
        { "角色設定", "hanime-role",  "角色设定" },
        { "外貌身材", "style",        "外貌身材" },
        { "情境場所", "setting",      "情境场所" },
        { "故事劇情", "plot",         "故事剧情" },
        { "性交體位", "hanime-act",   "性交体位" },
    };

    private static JSONArray oneArray(String value) {
        JSONArray a = new JSONArray();
        a.put(value);
        return a;
    }

    /**
     * hanime1 的探针：拉一次首页，数里面的 watch 链接。
     *
     * **不用 JSON**（它没有），所以这里做的是「HTML 里有没有卡片」这个最基础的
     * 存在性检查 —— 不用 Hanime1Adapter 那套完整解析，因为探针失败的原因不需要
     * 区分（站点改版 / 被墙 / 返回错误页，对用户来说都是「这个源用不了」）。
     *
     * 数不出来返回 null。**不猜**：返回 0 条也当失败。
     */
    private static JSONArray probeHanime1(String base) {
        try {
            byte[] body = AiiHttp.getRaw(base + "/", 20);
            if (body == null || body.length == 0) return null;
            String html = new String(body, "UTF-8");
            JSONArray hits = new JSONArray();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "/watch\\?v=(\\d+)").matcher(html);
            java.util.HashSet<String> seen = new java.util.HashSet<String>();
            while (m.find() && hits.length() < 5) {
                if (seen.add(m.group(1))) hits.put(m.group(1));
            }
            return hits.length() == 0 ? null : hits;
        } catch (Throwable e) {
            log("hanime1 探针失败：" + e);
            return null;
        }
    }

    /**
     * AGE动漫 → 我们的配置。
     *
     * 这个站没法按响应形状识别（页面是 HTML），所以按域名认。认出来之后不去 fetch
     * 用户填的那个地址（那会拿到 HTML），直接去探它的数据接口 —— 探通了才存，
     * 免得留一份点了没反应的配置。
     */
    private static JSONObject wrapAgedm() throws Exception {
        final String api = "https://api.agedm.io";
        JSONObject probe = fetch(api + "/v2/home-list");
        if (probe.optJSONArray("latest") == null || probe.optJSONArray("recommend") == null) {
            throw new IOException("AGE动漫 的接口没有返回预期数据，可能改版了");
        }
        log("AGE动漫 探测通过：latest " + probe.optJSONArray("latest").length()
                + " 条 / recommend " + probe.optJSONArray("recommend").length() + " 条");

        // 播放走同名采集源 —— 站点自己的地址是 age_ 密文 + Go/WASM 签名门，解不了。
        // 默认给暴风（2026-10-16 实测同名作品在它上面是明文 m3u8）。匹配要求标题
        // 完全一致，对不上就明确报错，不猜。想换源改这一个字段就行。
        final String playback = "https://bfzyapi.com/api.php/provide/vod/";

        JSONObject config = new JSONObject();
        config.put("adapter", "agedm");
        config.put("api", api);
        config.put("playback", playback);
        config.put("_note", "AGE动漫。数据来自 api.agedm.io/v2/（裸请求可拉，无鉴权）。"
                + "播放地址站点自己是加密的，这里是拿标题去 playback 那个苹果CMS 采集站匹配同名作品"
                + "（标题必须完全一致，对不上会明确报错）。");

        JSONObject web = new JSONObject();
        web.put("firstPageSize", 24);
        web.put("pageSize", 24);

        JSONObject paths = new JSONObject();
        paths.put("home", "/api/home");
        paths.put("detail", "/api/videos/{id}");
        paths.put("episode", "/api/videos/{id}/play?ep={episode}");
        paths.put("search", "/api/search?q={keyword}&page={page}");
        paths.put("category", "/api/videos?cate_id={cateId}&order=time&page={page}&size={size}");
        paths.put("recommend", "/api/videos?order=hits&page={page}&size={size}");
        paths.put("newest", "/api/videos?order=time&page={page}&size={size}");
        paths.put("tags", "/api/tags");
        paths.put("tag-list", "/api/videos?cate_id={slug}&order=time&page={page}&size={size}");
        web.put("paths", paths);

        // 分类：AGE动漫 的「类型」是中文词（label=搞笑），没有数字 id。
        // slug 和值都直接用那个词 —— 值会被填进 {cateId}，适配器拿去当 label 用。
        JSONObject categories = new JSONObject();
        JSONArray slugs = new JSONArray();
        for (int i = 0; i < AgedmAdapter.LABELS.length; i++) {
            categories.put(AgedmAdapter.LABELS[i], AgedmAdapter.LABELS[i]);
            slugs.put(AgedmAdapter.LABELS[i]);
        }
        web.put("categories", categories);

        JSONObject ui = new JSONObject();
        JSONArray tabs = new JSONArray();
        tabs.put(tab("all", "分类", "grid"));
        ui.put("tabs", tabs);
        JSONObject ids = new JSONObject();
        ids.put("all", slugs);
        ui.put("categoryIds", ids);
        JSONObject labels = new JSONObject();
        labels.put("all", slugs);
        ui.put("categoryLabels", labels);
        JSONObject titles = new JSONObject();
        titles.put("all", "全部分类");
        ui.put("categoryTitles", titles);
        JSONObject leads = new JSONObject();
        leads.put("all", "46 个类型词，来自这个站自己的筛选字典。");
        ui.put("categoryLeads", leads);

        // 首页那排胶囊：挑几个常见的类型。
        JSONArray hot = new JSONArray();
        String[] picks = {"热血", "奇幻", "恋爱", "战斗", "搞笑", "校园", "悬疑", "治愈"};
        for (int i = 0; i < picks.length; i++) {
            JSONArray pair = new JSONArray();
            pair.put(picks[i]);
            pair.put(picks[i]);
            hot.put(pair);
        }
        ui.put("popularTags", hot);
        ui.put("publicPopularTags", new JSONArray());
        // tags 接口交给适配器合成（站点没有标签接口），分组名和它对上。
        JSONArray groups = new JSONArray();
        JSONArray one = new JSONArray();
        one.put("agedm");
        one.put("全部分类");
        groups.put(one);
        ui.put("tagGroups", groups);
        web.put("ui", ui);

        // 字段别名。**必须和适配器实际吐出来的键名对上** —— 前端 pick() 是按这张表
        // 找值的，表里没有的键就等于没有。（第一版这里全部写成角色名本身，
        // 而适配器吐的是 ep_num，于是剧集号解析成 undefined，集数按钮全是
        // data-episode="undefined"，播放根本没开始。）
        JSONObject fields = new JSONObject();
        fields.put("id", arr("id"));
        fields.put("title", arr("title"));
        fields.put("cover", arr("cover"));
        fields.put("score", arr("score"));
        fields.put("tags", arr("tags"));
        fields.put("description", arr("description"));
        fields.put("category", arr2("category", "cate_id"));
        fields.put("badge", arr("badge"));
        fields.put("episodeCount", arr("episode_count"));
        fields.put("episodeNumber", arr("ep_num"));
        fields.put("source", arr("video_url"));
        fields.put("duration", arr("duration"));
        web.put("fields", fields);
        config.put("web", web);

        JSONObject cover = new JSONObject();
        cover.put("mode", "plain");
        // 封面是百度图床的代理地址（gimg0-4.baidu.com/gimg/…?src=真图），
        // 白名单精确匹配、不支持通配符，所以五个都列上。
        JSONArray hosts = new JSONArray();
        hosts.put("gimg0.baidu.com");
        hosts.put("gimg1.baidu.com");
        hosts.put("gimg2.baidu.com");
        hosts.put("gimg3.baidu.com");
        hosts.put("gimg4.baidu.com");
        cover.put("allowedHosts", hosts);
        config.put("cover", cover);

        return config;
    }

    /**
     * 苹果CMS → 我们的配置。
     *
     * 存的只是「api 地址 + 从 class[] 生成出来的分类表」。真正的映射在 CmsAdapter 里 ——
     * 它负责让 CMS 的响应看起来像我们的站，于是前端 adapters.js 一行不用改。
     */
    private static JSONObject wrapCms(String url, JSONObject probe) throws Exception {
        JSONObject config = new JSONObject();
        config.put("adapter", "cms");
        String api = url.trim();
        int q = api.indexOf('?');
        config.put("api", q < 0 ? api : api.substring(0, q));

        JSONObject web = new JSONObject();
        JSONObject categories = CmsAdapter.categoriesOf(probe);
        if (categories.length() == 0) throw new IOException("CMS 的分类表是空的，这个接口可能不能用了");
        web.put("categories", categories);

        JSONObject paths = new JSONObject();
        paths.put("home", "/api/home");
        paths.put("detail", "/api/videos/{id}");
        paths.put("episode", "/api/videos/{id}/play?ep={episode}");
        paths.put("search", "/api/search?q={keyword}&page={page}");
        paths.put("category", "/api/videos?cate_id={cateId}&page={page}&size={size}");
        paths.put("recommend", "/api/videos?page={page}&size={size}");
        paths.put("newest", "/api/videos?sort=new&page={page}&size={size}");
        paths.put("tags", "/api/tags");
        // 标签走**分类**而不是搜索。CMS 的搜是标题匹配（wd=），拿「动作片」去搜
        // 一个都搜不到 —— 上一版就是 /api/search?q={keyword}，点标签等于白点。
        // 而标签的 slug 在 CMS 那边本来就是 type_id（见 CmsAdapter.tags），
        // 直接当 cate_id 用，一次到位。
        paths.put("tag-list", "/api/videos?cate_id={slug}&page={page}&size={size}");
        web.put("paths", paths);

        // CMS 每页固定 20 条（实测 limit=20），size 参数它不认 —— 这里写 20 只是
        // 让前端的分页逻辑算出正确的结果。
        web.put("firstPageSize", 20);
        web.put("pageSize", 20);

        // ── 底部导航那几格 ────────────────────────────────────────────
        //
        // 上一版是「两个 key 填同一份分类」：CMS 的分类是一维的，而界面只有两个
        // 入口，于是剧场和 AI 长得一模一样，名字还跟内容没关系 —— 源里根本没有
        // 「AI」这种东西。现在导航由配置出（web.ui.tabs），这里就按**源自己的
        // 分类树**生成：class[] 里 type_pid 指向父类的就是二级类型，顶层那几个
        // （电影片/连续剧/综艺片/动漫片）天然就是入口。
        JSONArray classes = probe.optJSONArray("class");
        LinkedHashMap<String, JSONObject> byId = new LinkedHashMap<String, JSONObject>();
        ArrayList<String> order = new ArrayList<String>();
        for (int i = 0; classes != null && i < classes.length(); i++) {
            JSONObject c = classes.optJSONObject(i);
            if (c == null) continue;
            String id = plain(c.opt("type_id"));
            if (id.length() == 0) continue;
            if (!byId.containsKey(id)) order.add(id);
            byId.put(id, c);
        }
        if (byId.isEmpty()) throw new IOException("CMS 的分类表是空的，这个接口可能不能用了");

        ArrayList<String> roots = new ArrayList<String>();
        LinkedHashMap<String, ArrayList<String>> children =
                new LinkedHashMap<String, ArrayList<String>>();
        for (String id : order) {
            String pid = plain(byId.get(id).opt("type_pid"));
            // 顶层 = pid 是 0、压根没有 pid、或者指到一个不存在的类。
            // 最后那条是给「pid 乱填」的源兜底的 —— 不然那些类会凭空消失。
            if (pid.length() == 0 || "0".equals(pid) || !byId.containsKey(pid)) {
                roots.add(id);
                children.put(id, new ArrayList<String>());
            }
        }
        for (String id : order) {
            String pid = plain(byId.get(id).opt("type_pid"));
            ArrayList<String> kids = children.get(pid);
            if (kids != null && !roots.contains(id)) kids.add(id);
        }

        // 顶层里**有子分类**的那些才是能当入口的频道。这个区分不是审美问题：
        // 苹果CMS 查父类不出内容（实测 t=<父id> 返回 0 条），所以一个入口要能用，
        // 必须把它下面的子分类列全；而本来就没有子分类的顶层（短剧/AI漫剧 这种）
        // 自己就是一条能查的叶子分类。
        ArrayList<String> channels = new ArrayList<String>();
        ArrayList<String> bare = new ArrayList<String>();
        for (String rootId : roots) {
            if (children.get(rootId).isEmpty()) bare.add(rootId);
            else channels.add(rootId);
        }
        // 子分类多的当入口。实测这比「取前几个」准：量子那个源按顺序第 5 个是
        // 「体育」（4 个子分类），而「综艺片」也是 4 个 —— 按内容量排能稳定地把
        // 电影/连续剧/动漫/综艺这四个主频道挑出来，体育、短剧那些进「其他」。
        Collections.sort(channels, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                return children.get(b).size() - children.get(a).size();
            }
        });

        JSONArray tabList = new JSONArray();
        JSONObject ids = new JSONObject();
        JSONObject labels = new JSONObject();
        JSONObject titles = new JSONObject();
        JSONObject leads = new JSONObject();

        if (!channels.isEmpty()) {
            // dock 一行塞得下的上限：这里 4 个 + 首页 + 我的 = 6 格，再加一个「其他」
            // 是 7 格。360dp 的机器上 365dp 宽的 dock 分成 7 份，每格 ~52dp ——
            // 再多 label 就要被压成两个字了。
            int picked = Math.min(4, channels.size());
            for (int i = 0; i < picked; i++) {
                String rootId = channels.get(i);
                JSONObject root = byId.get(rootId);
                JSONArray slugs = new JSONArray();
                JSONArray names = new JSONArray();
                for (String kid : children.get(rootId)) {
                    slugs.put(kid);
                    names.put(byId.get(kid).optString("type_name", "分类"));
                }
                String name = shortLabel(root.optString("type_name", "分类"));
                tabList.put(tab(rootId, name, iconFor(name)));
                ids.put(rootId, slugs);
                labels.put(rootId, names);
                titles.put(rootId, name);
                leads.put(rootId, "分类来自这个源自己的接口。");
            }
            // 没当上入口的频道 + 本来就没有子分类的顶层，合成「其他」。不是收拾残局 ——
            // 它们在 CMS 里都是有独立内容的分类，丢掉就是丢内容。
            ArrayList<String> rest = new ArrayList<String>();
            for (int i = picked; i < channels.size(); i++) {
                rest.addAll(children.get(channels.get(i)));
            }
            rest.addAll(bare);
            if (!rest.isEmpty()) {
                JSONArray slugs = new JSONArray();
                JSONArray names = new JSONArray();
                for (String kid : rest) {
                    slugs.put(kid);
                    names.put(byId.get(kid).optString("type_name", "分类"));
                }
                tabList.put(tab("more", "其他", "grid"));
                ids.put("more", slugs);
                labels.put("more", names);
                titles.put("more", "其他");
                leads.put("more", "这个源里剩下的那些分类。");
            }
        } else {
            // 一维分类（整棵树都没有 pid），或者顶层全是没有子分类的叶子：
            // 一个入口，全部类型排在子分类条里，那条是横向滚动的。
            JSONArray slugs = new JSONArray();
            JSONArray names = new JSONArray();
            for (String id : order) {
                slugs.put(id);
                names.put(byId.get(id).optString("type_name", "分类"));
            }
            tabList.put(tab("all", "影视库", "grid"));
            ids.put("all", slugs);
            labels.put("all", names);
            titles.put("all", "影视库");
            leads.put("all", "这个源没给出分类层级，全部类型都在下面这一条里。");
        }

        JSONObject ui = new JSONObject();
        ui.put("tabs", tabList);
        ui.put("categoryIds", ids);
        ui.put("categoryLabels", labels);
        // categoryTitles 是**一个字符串**（页面大标题），不是数组 —— 塞数组进去
        // 会被插值成「电影片,连续剧,综艺片,…」四十个名字连成一串。
        // 分类名由上面的 tab 按钮承载，大标题只要一个说法就行。
        ui.put("categoryTitles", titles);
        ui.put("categoryLeads", leads);

        // ── 首页那排标签 + 「全部类型」页 ─────────────────────────────
        //
        // CMS 没有独立的标签体系，它的 type 分类表就是标签表 —— CmsAdapter.tags
        // 把每个 type 打上 group="cms" 交上来。所以：
        //
        //   · tagGroups 必须留一组 "cms"。上一版把这几张表全清空了（那会儿是怕
        //     黄果的「都市/校园/古风」漏过来），结果「全部类型」页整页空白 ——
        //     首页那排胶囊也一个不剩。
        //   · popularTags 挑几个叶子分类当快捷入口。**不能拿顶层**：苹果CMS 查
        //     父类返回 0 条（见 docs 坑 19），点进去会是空的。
        //
        // 挑法是每个频道轮一个，不是从头截 8 个 —— 从头截出来的全是电影片的
        // 子类（动作/喜剧/爱情/科幻…），首页那排看着像只有电影。
        JSONArray tagGroups = new JSONArray();
        JSONArray cmsGroup = new JSONArray();
        cmsGroup.put("cms");
        cmsGroup.put("全部分类");
        tagGroups.put(cmsGroup);
        ui.put("tagGroups", tagGroups);
        ui.put("popularTags", hotTags(byId, channels, children, bare, order, 8));
        // 公共模式那份（红果用的）留空，别让内置的黄果标签漏进来。
        ui.put("publicPopularTags", new JSONArray());
        web.put("ui", ui);

        // ── 字段别名 ──────────────────────────────────────────────────
        //
        // 左边是我们内部的角色名（前端 pick() 用的），右边是**这个族的原始字段名**。
        //
        // 适配器不再认字段名了 —— 它只按这张表把命中的那个键**原样**搬过来，改名交给
        // 前端的 pick()（按顺序取第一个有值的）。所以**同一个族里换字段名的站，改这
        // 张表就行，不用碰 Java**。
        //
        // 上一版这里写的是 ["title"]，而 CmsAdapter 那边把 vod_name 改名成了 title ——
        // 同一个概念写两遍，而且其中一遍在编译期。现在只有这一份。
        //
        // 顺序有讲究：适配器会自己产出的角色（id / score / description）排前面，
        // 它没产出的时候才轮到原始字段兜底。
        JSONObject fields = new JSONObject();
        fields.put("id", arr2("id", "vod_id"));
        fields.put("title", arr2("title", "vod_name"));
        fields.put("cover", arr2("cover", "vod_pic"));
        fields.put("score", arr2("score", "vod_score"));
        fields.put("badge", arr2("badge", "vod_remarks"));
        fields.put("description", arr2("description", "vod_content"));
        fields.put("category", arr2("category", "type_id"));
        // 下面这几个 CMS 没有对应字段，写空数组会被 validate() 拒（候选键列表为空），
        // 所以给一个不会命中的名字 —— 拿不到就是 undefined，前端当空处理。
        fields.put("tags", arr("tags"));
        fields.put("episodeCount", arr("episode_count"));
        fields.put("episodeNumber", arr("ep_num"));
        fields.put("source", arr("video_url"));
        fields.put("duration", arr("duration"));
        web.put("fields", fields);

        config.put("web", web);

        // ── 封面 ──────────────────────────────────────────────────────
        //
        // 两块都必须显式覆盖，否则会从内置模板那边漏过来：
        //
        // ① mode：内置是 aes-cbc（黄果的封面是加密图），而 CMS 返回的是普通
        //    JPEG。照内置那套解密会把图毁掉 —— 表现是封面全是占位图。
        //
        // ② allowedHosts：SiteProfile.coverUrl() 会拿它逐个精确比对 host，
        //    不匹配直接抛「无效封面域名」。它是防 SSRF 的（拿固定密钥去解任意
        //    地址），但 CMS 的封面域名我们事先不知道，只能从探测响应里现取。
        //    实测每个源只用**一个**封面域名（三个源各 20/20 条一致），所以够用。
        //    真出现第二个域名的那天，那部分封面会降级成占位图，frontend 的
        //    日志里有域名，能看出来。
        JSONArray hosts = coverHostsOf(probe);

        // XML 接口（/at/xml/）的列表里**没有 <pic>** —— 封面只在详情响应里。
        // 所以上面那一次提取必然是空的，得单独探一条详情把域名捞出来。
        // 只探一条：同一个源的封面通常只用一个 CDN（实测三个源各 20/20 条一致）。
        if (hosts.length() == 0) {
            String firstId = firstVideoId(probe);
            if (firstId.length() > 0) {
                try {
                    JSONObject one = fetch(withParams(url, "ac=detail&ids=" + firstId));
                    hosts = coverHostsOf(one);
                } catch (Exception ignored) {}
            }
        }

        if (hosts.length() == 0) {
            // 提取不到就当场说清楚。放一个空数组过去的话，SiteProfile 构造时会抛
            // 「缺少封面域名」—— 那是在 Activity.onCreate 里，表现是一装完就闪退，
            // 用户完全不知道哪里错了。
            throw new IOException("从响应里提取不到封面域名 —— 这个接口的内容结构不标准，换一个源试试");
        }
        JSONObject cover = new JSONObject();
        cover.put("mode", "plain");
        cover.put("hostRewrites", new JSONObject());
        cover.put("allowedHosts", hosts);
        config.put("cover", cover);

        return config;
    }

    /** 从探测响应里把所有 https 链接的域名捞出来，去重，限量。 */
    /** 取探测响应里第一条的 id，用来单独探详情。 */
    private static String firstVideoId(JSONObject probe) {
        JSONArray list = probe == null ? null : probe.optJSONArray("list");
        if (list == null || list.length() == 0) return "";
        JSONObject first = list.optJSONObject(0);
        if (first == null) return "";
        String id = String.valueOf(first.opt("vod_id"));
        return "null".equals(id) || "0".equals(id) ? "" : id;
    }

    /** 往地址上补查询参数，已经在带查询的用 & 接。 */
    private static String withParams(String url, String query) {
        String base = url.trim();
        int q = base.indexOf('?');
        String head = q < 0 ? base : base.substring(0, q);
        return head + (head.indexOf('?') >= 0 ? "&" : "?") + query;
    }

    private static JSONArray coverHostsOf(JSONObject probe) {
        java.util.LinkedHashSet<String> hosts = new java.util.LinkedHashSet<String>();
        collectHosts(probe, hosts, 0);
        JSONArray out = new JSONArray();
        for (String h : hosts) {
            if (out.length() >= 24) break;
            out.put(h);
        }
        return out;
    }

    private static void collectHosts(Object node, java.util.LinkedHashSet<String> out, int depth) {
        if (node == null || depth > 6 || out.size() >= 24) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            java.util.Iterator<String> keys = o.keys();
            while (keys.hasNext()) collectHosts(o.opt(keys.next()), out, depth + 1);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collectHosts(a.opt(i), out, depth + 1);
        } else if (node instanceof String) {
            String text = (String) node;
            if (!text.startsWith("https://")) return;
            try {
                String host = new java.net.URI(text).getHost();
                if (host != null && host.length() > 0) out.add(host);
            } catch (Exception ignored) {}
        }
    }

    private static JSONArray arr(String value) {
        JSONArray a = new JSONArray();
        a.put(value);
        return a;
    }

    /**
     * 一个角色的候选键列表，按顺序取第一个有值的。
     * 苹果CMS 那边一个概念常有两三个名字，这张表就是那件事的唯一真相 ——
     * Java 不认字段名，只按它把命中的键原样搬过去。
     */
    private static JSONArray arr2(String first, String second) {
        JSONArray a = new JSONArray();
        a.put(first);
        a.put(second);
        return a;
    }

    // ── 分类表的小工具 ───────────────────────────────────────────────

    /** class[] 里有没有一条带 type_pid —— 有就说明这份接口能给出分类树。 */
    /** paths / shapes 里认得的动作名。shapes 的键只能从这里面挑。 */
    private static final String[] ACTIONS = {
        // list 不是 paths 里的动作名，但它是 shapes 里的一个合法段 ——
        // 列表类响应（category/search/recommend/newest）的 items 和分页都挂在它下面。
        "home", "list", "detail", "episode", "search", "category",
        "recommend", "newest", "tags", "tag-list"
    };

    private static boolean isAction(String name) {
        for (int i = 0; i < ACTIONS.length; i++) if (ACTIONS[i].equals(name)) return true;
        return false;
    }

    /** 一个形状位的合法形态：非空字符串（取值路径），或者 {div:[a,b]}。 */
    private static boolean isShapeValue(Object value) {
        if (value instanceof String) return ((String) value).length() > 0;
        if (value instanceof JSONObject) {
            JSONArray div = ((JSONObject) value).optJSONArray("div");
            return div != null && div.length() == 2;
        }
        return false;
    }

    private static boolean hasHierarchy(JSONObject config) {
        JSONArray classes = config == null ? null : config.optJSONArray("class");
        for (int i = 0; classes != null && i < classes.length(); i++) {
            JSONObject c = classes.optJSONObject(i);
            if (c != null && plain(c.opt("type_pid")).length() > 0) return true;
        }
        return false;
    }

    /**
     * 把 donor 里的 type_pid 按 type_id 补进 target，只补缺的。
     * 返回补上的条数 —— 0 多半意味着两边根本不是同一张表，那就不该硬套。
     */
    private static int mergeParents(JSONArray target, JSONArray donor) {
        if (target == null || donor == null) return 0;
        LinkedHashMap<String, String> pidById = new LinkedHashMap<String, String>();
        for (int i = 0; i < donor.length(); i++) {
            JSONObject c = donor.optJSONObject(i);
            if (c == null) continue;
            String id = plain(c.opt("type_id"));
            String pid = plain(c.opt("type_pid"));
            if (id.length() > 0 && pid.length() > 0) pidById.put(id, pid);
        }
        int filled = 0;
        for (int i = 0; i < target.length(); i++) {
            JSONObject c = target.optJSONObject(i);
            if (c == null || plain(c.opt("type_pid")).length() > 0) continue;
            String pid = pidById.get(plain(c.opt("type_id")));
            if (pid == null) continue;
            try { c.put("type_pid", pid); filled++; } catch (Exception ignored) {}
        }
        return filled;
    }

    /**
     * 一个导航入口。key 同时是 categoryIds / categoryLabels / categoryTitles 的键，
     * 也是前端 data-nav 的值 —— 三处必须一字不差，否则那个入口点进去是空的。
     */
    private static JSONObject tab(String key, String label, String icon) throws Exception {
        JSONObject t = new JSONObject();
        t.put("key", key);
        t.put("label", label);
        t.put("icon", icon);
        return t;
    }

    /**
     * JSONObject.opt() 拿不到时返回 null，String.valueOf(null) 是字符串 "null" ——
     * 那个会当成合法的 type_id 用下去，最后变成一个点了没反应的空入口。
     */
    private static String plain(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value).trim();
        return "null".equals(text) ? "" : text;
    }

    /**
     * 首页那排标签挑哪几个。每个频道轮流取一个，取满为止；还剩位置就补上那些没有
     * 子分类的顶层，最后再按原顺序兜底。
     *
     * 为什么不从头截 8 个：`class[]` 是按 type_id 排的，前 8 个大概率全属于同一个
     * 频道（电影片底下就一长串），首页那排看着像只有电影。轮流取出来的分布均匀。
     *
     * 返回 [[slug, 名字], …]。slug 在 CMS 那边就是 type_id —— 前端拿它填 tag-list
     * 路径里的 {slug}，直接当 cate_id 用。
     */
    private static JSONArray hotTags(LinkedHashMap<String, JSONObject> byId,
                                     ArrayList<String> channels,
                                     LinkedHashMap<String, ArrayList<String>> children,
                                     ArrayList<String> bare,
                                     ArrayList<String> order,
                                     int want) {
        JSONArray out = new JSONArray();
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        int round = 0;
        while (!channels.isEmpty() && out.length() < want && round < 64) {
            boolean added = false;
            for (int i = 0; i < channels.size() && out.length() < want; i++) {
                ArrayList<String> kids = children.get(channels.get(i));
                if (round >= kids.size()) continue;
                String id = kids.get(round);
                if (seen.add(id)) { out.put(pair(id, nameOf(byId, id))); added = true; }
            }
            if (!added) break;              // 所有频道的子分类都取完了
            round++;
        }
        for (int i = 0; i < bare.size() && out.length() < want; i++) {
            String id = bare.get(i);
            if (seen.add(id)) out.put(pair(id, nameOf(byId, id)));
        }
        // 整棵树都没有层级（或者顶层全没有子分类）：只能按原顺序取。
        for (int i = 0; i < order.size() && out.length() < want; i++) {
            String id = order.get(i);
            if (seen.add(id)) out.put(pair(id, nameOf(byId, id)));
        }
        return out;
    }

    private static String nameOf(LinkedHashMap<String, JSONObject> byId, String id) {
        JSONObject c = byId.get(id);
        return c == null ? id : c.optString("type_name", id);
    }

    /** [slug, 显示名] —— 标签表就是这个形状。 */
    private static JSONArray pair(String slug, String name) {
        JSONArray a = new JSONArray();
        a.put(slug);
        a.put(name);
        return a;
    }

    /** 导航上的字要短。「电影片/综艺片/动漫片」去掉尾巴那个片更好看，也更塞得下。 */
    private static String shortLabel(String name) {        String text = name == null ? "" : name.trim();
        if (text.length() > 2 && text.endsWith("片")) text = text.substring(0, text.length() - 1);
        return text.length() == 0 ? "分类" : text;
    }

    /**
     * 按名字猜个图标 —— 猜不中就用 grid，不影响功能，只是几个入口长得不一样好认。
     * 顺序有讲究：动漫片同时命中「动漫」和「片」，得先判动漫。
     */
    private static String iconFor(String name) {
        if (name.contains("动漫") || name.contains("动画") || name.contains("漫画")) return "sparkle";
        if (name.contains("综艺") || name.contains("娱乐") || name.contains("真人秀")) return "star";
        if (name.contains("电影")) return "film";
        if (name.contains("剧") || name.contains("电视")) return "tv";
        if (name.contains("短")) return "fire";
        return "grid";
    }

    private static String param(String query, String key) {
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
    public static final String PROXY_PREFIX = "__proxy:";

    /** 模式与节点存在这里。节点带 uuid/密码，和 aii-net 一样是应用私有目录下的明文 XML。 */
    private static final String PROXY_STORE = "aii-proxy-core";
    private static final String CORE_MODE_KEY = "mode";
    private static final String CORE_LINK_KEY = "link";

    static final String MODE_DIRECT = "direct";
    static final String MODE_BUILTIN = "builtin";
    static final String MODE_EXTERNAL = "external";

    /** 正在启停时的重入闸。核心自己 synchronized，这里挡的是前端连点两下那两次。 */
    private static volatile boolean coreBusy = false;

    /**
     * `__proxy:` 的分发。
     *
     *   status                       核心状态 + 当前生效的出口（合成结论）
     *   start?link=<分享链接>          解析并启动，返回 status
     *   start?node=<节点 JSON>         同上，节点已经是 JSON 时走这条
     *   stop                         停核心，返回 status
     *   test                         通过核心测一次延迟，返回 ms
     *   mode?value=direct|builtin|external   切换模式（切到 builtin 会顺手拉起核心）
     *   boot                         冷启动读盘并恢复内置核心（设置页首次加载时调）
     *
     * 所有分支都返回同一套字段：{ok, mode, running, port, socksUrl, host, effective,
     * external, busy, node, error, unavailable}，前端拿到就整块重画。
     */
    private static JSONObject proxyCore(Context context, String body, JSONObject out) {
        try {
            String action = body;
            String query = "";
            int mark = body.indexOf('?');
            if (mark >= 0) {
                action = body.substring(0, mark);
                query = body.substring(mark + 1);
            }
            if ("status".equals(action)) return proxyStatus(context, out);
            if ("start".equals(action)) {
                return proxyStart(context, param(query, "link"), param(query, "node"), out);
            }
            if ("stop".equals(action)) return proxyStop(context, out);
            // 清掉存过的节点。独立一条而不是让 start 收空串：空串在 start 的语义里
            // 是「用存过的那条」，两者混着会把「清空」变成「再启动一次」。
            if ("clear".equals(action)) {
                ProxyCore.get(context).stop();
                AiiHttp.invalidateClients();
                setProxyLink(context, "");
                return proxyStatus(context, out);
            }
            if ("test".equals(action)) return proxyTest(context, out);
            if ("mode".equals(action)) return proxyMode(context, param(query, "value"), out);
            if ("boot".equals(action)) return proxyBoot(context, out);
            return fail(out, "不认识的代理指令：" + action);
        } catch (Throwable e) {
            return fail(out, "代理指令失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private static android.content.SharedPreferences proxyStore(Context context) {
        return context.getSharedPreferences(PROXY_STORE, Context.MODE_PRIVATE);
    }

    static String proxyMode(Context context) {
        String mode = proxyStore(context).getString(CORE_MODE_KEY, MODE_DIRECT);
        if (MODE_BUILTIN.equals(mode) || MODE_EXTERNAL.equals(mode)) return mode;
        return MODE_DIRECT;
    }

    private static void setProxyMode(Context context, String mode) {
        proxyStore(context).edit().putString(CORE_MODE_KEY, mode).apply();
    }

    /** 存分享链接原文。比存解析后的 JSON 少一个「字段名谁说了算」的问题。 */
    private static String proxyLink(Context context) {
        String link = proxyStore(context).getString(CORE_LINK_KEY, "");
        return link == null ? "" : link.trim();
    }

    private static void setProxyLink(Context context, String link) {
        proxyStore(context).edit().putString(CORE_LINK_KEY, link == null ? "" : link.trim()).apply();
    }

    /**
     * 合成一份状态。前端只读这一份。
     *
     * `effective` 是页面顶上那行「现在走哪条」的唯一来源：内核在跑 → 本地 SOCKS5；
     * 不在跑但用户填过外部地址 → 那个地址；都没有 → 空串（直连）。
     * 这和 AiiHttp.effectiveProxy() 是同一个结论，但这边额外知道「为什么没在跑」。
     */
    private static JSONObject proxyStatus(Context context, JSONObject out) throws Exception {
        // effectiveProxy() 要 appContext，init 是幂等的，先保证它在。
        AiiHttp.init(context);
        ProxyCore core = ProxyCore.get(context);
        boolean running = core.isRunning();
        int port = running ? core.port() : 0;

        String effective = AiiHttp.effectiveProxy();
        String raw = AiiHttp.proxyRaw();

        out.put("ok", true);
        out.put("mode", proxyMode(context));
        out.put("running", running);
        out.put("port", port);
        out.put("socksUrl", running ? core.socksUrl() : "");
        out.put("host", running ? "127.0.0.1:" + port : "");
        out.put("effective", effective == null ? "" : effective);
        out.put("external", raw);
        out.put("busy", coreBusy);
        // 节点只回「哪台机器」，不带 uuid —— 这份答复会被 logcat 记下来。
        XrayConfig.Node node = core.currentNode();
        out.put("node", node == null ? "" : node.describe());
        // lastError 是「上一次失败」，成功之后 ProxyCore 自己会清空；
        // unavailableReason() 是一旦不可用就永远存在的结论，两者分开报。
        out.put("error", core.lastError() == null ? "" : core.lastError());
        out.put("unavailable", ProxyCore.unavailableReason() == null ? "" : ProxyCore.unavailableReason());
        out.put("link", proxyLink(context));
        return out;
    }

    private static JSONObject proxyStart(Context context, String link, String nodeJson, JSONObject out)
            throws Exception {
        String text = link == null ? "" : link.trim();
        boolean hasNodeJson = nodeJson != null && nodeJson.trim().length() > 0;
        if (text.length() > 0) setProxyLink(context, text);
        // 存过的那条留到**解析成功之后**才用：一条粘贴错的链接如果先落盘再解析，
        // 用户下次点「保存并启动」会拿到上次那条坏链接，报的错还跟这次无关。
        // 所以这里只读不写，落盘在下面的解析成功处。
        if (text.length() == 0 && !hasNodeJson) text = proxyLink(context);
        if (text.length() == 0 && !hasNodeJson) {
            return fail(out, "还没有节点配置，先粘一条 vmess:// 或 ss:// 分享链接");
        }

        XrayConfig.Node node;
        try {
            node = text.length() > 0
                    ? XrayConfig.parseShareLink(text)
                    : nodeFromJson(new JSONObject(nodeJson));
        } catch (IllegalArgumentException e) {
            // 解析失败不动已存的那条 —— 好链接不该被一条手误覆盖掉。
            return fail(out, e.getMessage() == null ? "节点配置看不懂" : e.getMessage());
        }
        if (text.length() > 0) setProxyLink(context, text);

        coreBusy = true;
        try {
            ProxyCore core = ProxyCore.get(context);
            // start() 返回 false 时状态已经复位（阶段 1 的约定），不用先 stop 再重试。
            if (!core.start(node)) {
                // 启动失败**不改路由**：既不动用户的代理串，也不碰 AiiHttp 的任何状态。
                // 把代理置空等于静默退回直连，用户会以为代理生效了。
                JSONObject failed = proxyStatus(context, out);
                if (failed.optBoolean("ok", false)) {
                    failed.put("ok", false);
                    failed.put("error", core.lastError() == null ? "核心没能启动" : core.lastError());
                }
                log("内置核心启动失败：" + failed.optString("error"));
                return failed;
            }
            // 核心起来了 → 立刻让旧客户端作废。只改标志不够：OkHttp 的连接池和
            // TLS 会话挂在已建好的实例上，不换实例的话它们还连着原来的出口。
            AiiHttp.invalidateClients();
            setProxyMode(context, MODE_BUILTIN);
            log("内置核心已启动 → " + node.describe());
            return proxyStatus(context, out);
        } finally {
            coreBusy = false;
        }
    }

    private static JSONObject proxyStop(Context context, JSONObject out) throws Exception {
        ProxyCore.get(context).stop();
        // 停了也必须作废：否则下一次请求还在用那条已经关掉的本地端口。
        AiiHttp.invalidateClients();
        // 停核心不改模式 —— 用户点「停止」多半只是想临时断一下，
        // 模式保持 builtin，下次启动还会自己拉起来。
        return proxyStatus(context, out);
    }

    private static JSONObject proxyTest(Context context, JSONObject out) {
        ProxyCore core = ProxyCore.get(context);
        if (!core.isRunning()) {
            try {
                out.put("ok", false);
                out.put("error", "核心没在跑，先启动再测");
                out.put("ms", -1);
            } catch (Exception ignored) {}
            return out;
        }
        long started = System.currentTimeMillis();
        long ms = core.measureDelay();
        try {
            if (ms <= 0) {
                out.put("ok", false);
                // AAR 里超时写死 12 秒，到这里还没回来的就是节点不通或本机没网。
                out.put("error", "测不通（" + (System.currentTimeMillis() - started) + "ms 内没有响应）");
                out.put("ms", -1);
            } else {
                out.put("ok", true);
                out.put("ms", ms);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static JSONObject proxyMode(Context context, String value, JSONObject out) throws Exception {
        String mode = value == null ? "" : value.trim();
        if (!MODE_DIRECT.equals(mode) && !MODE_BUILTIN.equals(mode) && !MODE_EXTERNAL.equals(mode)) {
            return fail(out, "模式只能是 direct / builtin / external");
        }

        if (MODE_DIRECT.equals(mode)) {
            ProxyCore.get(context).stop();
            AiiHttp.invalidateClients();
            setProxyMode(context, MODE_DIRECT);
            return proxyStatus(context, out);
        }

        if (MODE_EXTERNAL.equals(mode)) {
            ProxyCore.get(context).stop();
            AiiHttp.invalidateClients();
            setProxyMode(context, MODE_EXTERNAL);
            return proxyStatus(context, out);
        }

        // builtin：有存过的节点就立刻拉起；没有就先记下模式，等用户粘链接。
        setProxyMode(context, MODE_BUILTIN);
        if (proxyLink(context).length() == 0) return proxyStatus(context, out);
        return proxyStart(context, "", "", out);
    }

    /**
     * 冷启动。设置页第一次加载时调一次，按盘上的模式恢复内置核心。
     *
     * 起不来不算失败 —— 回 status 让页面把原因写在状态行上，而不是弹一个崩溃。
     * 切后台不停核心：后台可能正在播音频，一停就是断流。进程被杀核心自然没了，
     * 不需要 onTerminate（它本来也不保证被调）。
     */
    private static JSONObject proxyBoot(Context context, JSONObject out) throws Exception {
        if (MODE_BUILTIN.equals(proxyMode(context))
                && !ProxyCore.get(context).isRunning()
                && proxyLink(context).length() > 0) {
            proxyStart(context, "", "", new JSONObject());
        }
        return proxyStatus(context, out);
    }

    /** 前端的 node JSON → XrayConfig.Node。字段名和 XrayConfig 前向生成时一致。 */
    private static XrayConfig.Node nodeFromJson(JSONObject json) {
        XrayConfig.Node node = new XrayConfig.Node(
                json.optString("protocol", "vmess"),
                json.optString("server", ""),
                json.optInt("port", 443));
        node.uuid = json.optString("uuid", "");
        node.method = json.optString("method", "");
        node.alterId = json.optInt("alterId", 0);
        node.security = json.optString("security", "auto");
        node.network = json.optString("network", "tcp");
        node.tls = json.optString("tls", "none");
        node.path = json.optString("path", "/");
        node.host = json.optString("host", "");
        node.serviceName = json.optString("serviceName", "");
        node.flow = json.optString("flow", "");
        node.fingerprint = json.optString("fingerprint", "");
        return node;
    }

    // ── 播放器要原生帮忙的两件事 ──────────────────────────────────────

    /**
     * `__player:` 的分发。两条指令：
     *
     *   brightness?value=0.42   0~1 的窗口亮度；-1 表示交回系统
     *   variants?url=<m3u8>     把主列表里的清晰度档位列出来（页面自己 fetch 不了）
     */
    /**
     * 用外部播放器打开一个视频地址。
     *
     * 为什么需要：这个播放器是 WebView 里的 <video>，解码能力跟着 WebView 走。
     * 同一条 m3u8 在 A 手机上能放、B 手机上 MediaError 4（SRC_NOT_SUPPORTED），
     * 差别就在设备/编码：HEVC、10bit、或者系统 WebView 没带那个解码器。
     * 这种时候把地址交出去，让 MX Player / VLC 用自己的解码链放，比报错有用。
     */
    private static JSONObject externalPlayer(Context context, String url, JSONObject out) throws Exception {
        if (url == null || url.length() == 0) return fail(out, "没有可播放的地址");
        if (!url.startsWith("http://") && !url.startsWith("https://")) return fail(out, "只能打开 http(s) 地址");
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.parse(url), "video/*");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (Exception e) {
            // 没有能接管 video/* 的应用：退一步不限定类型再试一次
            Intent fallback = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(fallback);
            } catch (Exception again) {
                return fail(out, "这台设备没有能打开视频的应用");
            }
        }
        out.put("ok", true);
        return out;
    }

    /**
     * 拉起内置播放器（libVLC）。
     *
     * payload 是一份 JSON：{url, id, episode, title, cover, source, start, referer,
     * ua, decode, cache, rate}。字段全部可选，缺的按默认值走 —— 页面那边写这份
     * payload 的地方就一处（feed-player.js 的 nativePayload()）。
     *
     * 返回里带一个 fallback 标记：这台设备上没有内置播放器（32 位包没打进 so，
     * 或者装的是精简过的包）时，页面要能退回网页播放器而不是什么都不发生。
     */
    private static JSONObject nativePlayer(Context context, String raw, JSONObject out) throws Exception {
        if (raw == null || raw.length() == 0) return fail(out, "内置播放器没有收到参数");
        if (!VlcPlayerActivity.available(context)) {
            out.put("ok", false);
            out.put("fallback", true);
            out.put("error", "这个安装包没带内置播放器（只打了 " + VlcPlayerActivity.abi()
                    + " 的 so），可以继续用网页播放器");
            return out;
        }
        JSONObject config = new JSONObject(raw);
        String url = config.optString("url", "");
        if (url.length() == 0) return fail(out, "没有可播放的地址");
        VlcPlayerActivity.start(context, config);
        out.put("ok", true);
        return out;
    }

    /** 内置播放器退出时写下的那一次现场。读到就清，避免同一条记录被算两次。 */
    private static JSONObject lastNativeSession(Context context, JSONObject out) throws Exception {
        JSONObject record = VlcPlayerActivity.takeLastSession(context);
        out.put("ok", true);
        out.put("session", record == null ? JSONObject.NULL : record);
        return out;
    }

    private static JSONObject nativeCapability(Context context, JSONObject out) throws Exception {
        out.put("ok", true);
        out.put("native", VlcPlayerActivity.available(context));
        out.put("abi", VlcPlayerActivity.abi());
        return out;
    }

    private static JSONObject player(Context context, String body, JSONObject out) {
        try {
            String action = body;
            String query = "";
            int mark = body.indexOf('?');
            if (mark >= 0) { action = body.substring(0, mark); query = body.substring(mark + 1); }

            if ("brightness".equals(action)) return brightness(context, param(query, "value"), out);
            if ("variants".equals(action)) return variants(param(query, "url"), out);
            // 交给系统播放器（MX / VLC / 自带图库）：WebView 解不了的编码
            // （HEVC、10bit、冷门封装）只有设备自己的解码链能放，这条路是最后的兜底。
            if ("external".equals(action)) return externalPlayer(context, param(query, "url"), out);
            // 内置播放器（libVLC）。这是「别的手机放不了」的正面解法：解码链搬进
            // App，不再跟着 WebView 版本和设备的硬解能力走。
            if ("native".equals(action)) return nativePlayer(context, param(query, "payload"), out);
            // 从内置播放器回来时取一次现场（位置/是否播完），取完就清。
            if ("last".equals(action)) return lastNativeSession(context, out);
            // 这台设备上到底有没有内置播放器可用（APK 只带了 arm64-v8a）。
            if ("capability".equals(action)) return nativeCapability(context, out);
            return fail(out, "不认识的播放指令：" + action);
        } catch (Throwable e) {
            return fail(out, "播放指令失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    /**
     * 窗口亮度。0~1 是绝对亮度，-1 是「交回系统」，不传值是读当前值。
     *
     * 为什么不用 CSS 蒙一层黑：那只能变暗，而用户滑到顶时期待的是**变亮** ——
     * 系统播放器就是这么做的，靠的是 Window.screenBrightness。
     *
     * 必须回主线程：改窗口属性是 View 系统的事，在桥的工作线程上改，
     * Android 会直接抛（而且抛在用户手指正划着的时候）。
     *
     * 读的时候分两层：窗口上没覆盖过（screenBrightness < 0）就退到系统亮度
     * （0~255 → 0~1）—— 不这样的话，用户第一次上下滑会从我们瞎猜的 50% 起跳，
     * 屏幕先闪一下。读 Settings.System 不需要权限。
     */
    private static JSONObject brightness(Context context, String raw, JSONObject out) throws Exception {
        MainActivity host = MainActivity.host;
        if (host == null) return fail(out, "Activity 不在，亮度改不了");

        if (raw.length() == 0) {
            float current = -1f;
            boolean system = false;
            try {
                android.view.Window window = host.getWindow();
                if (window != null) current = window.getAttributes().screenBrightness;
            } catch (Throwable ignored) {}
            if (current < 0) {
                system = true;
                try {
                    int value = android.provider.Settings.System.getInt(
                            context.getContentResolver(),
                            android.provider.Settings.System.SCREEN_BRIGHTNESS, 128);
                    current = Math.max(MIN_BRIGHTNESS, Math.min(1f, value / 255f));
                } catch (Throwable e) {
                    current = 0.5f;                       // 问不出来就当中等亮度，别报错
                }
            }
            out.put("ok", true);
            out.put("value", current);
            out.put("system", system);
            return out;
        }

        final float value;
        if ("-1".equals(raw)) {
            value = -1f;                                   // 交回系统
        } else {
            float parsed;
            try {
                parsed = Float.parseFloat(raw);
            } catch (Exception e) {
                return fail(out, "亮度值看不懂：" + raw);
            }
            value = Math.max(MIN_BRIGHTNESS, Math.min(1f, parsed));
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try {
                    android.view.Window window = host.getWindow();
                    if (window == null) return;
                    android.view.WindowManager.LayoutParams attrs = window.getAttributes();
                    attrs.screenBrightness = value;
                    window.setAttributes(attrs);
                } catch (Throwable e) {
                    log("设置亮度失败：" + e);
                }
            }
        });
        out.put("ok", true);
        out.put("value", value);
        return out;
    }

    /**
     * 把主 m3u8 拆成清晰度档位。页面 CSP 的 connect-src 是 'none'，只能在这儿取文本。
     *
     * 两种列表都要认：
     *   · 主列表（有 #EXT-X-STREAM-INF）→ 每一档一行：下一行是它的播放地址（可能是相对路径）
     *   · 媒体列表（只有 #EXTINF + .ts）→ 没有档位，返回空 items。**这不是错误** ——
     *     站上本来就只有一路清晰度，前端据此说「这个源只有一路」，而不是假装能选。
     *
     * 标签取 RESOLUTION 的宽（1080x608 → 1080P），因为那是 CDN 自己声明的东西；
     * 另外把 BANDWIDTH 一起放进 note —— 实测有站写 RESOLUTION=1080x608 配
     * BANDWIDTH=800000，标成 1080P 是它自己写的，我们只负责照抄并把它摊开。
     */
    private static JSONObject variants(String url, JSONObject out) throws Exception {
        if (url.length() == 0) return fail(out, "没有提供播放地址");
        if (!url.startsWith("https://")) return fail(out, "只认 https 的播放地址");
        if (url.indexOf(".m3u8") < 0) return fail(out, "不是 m3u8：" + head(url, 60));

        String text = new String(AiiHttp.getRaw(url, null, 12), "UTF-8");
        JSONArray items = new JSONArray();
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue;
            String attrs = line.substring("#EXT-X-STREAM-INF:".length());
            // 地址在**下一行非空非注释**里 —— 中间允许插别的标签，所以往下找。
            String uri = "";
            for (int j = i + 1; j < lines.length; j++) {
                String next = lines[j].trim();
                if (next.length() == 0 || next.startsWith("#")) continue;
                uri = next;
                break;
            }
            if (uri.length() == 0) continue;

            String resolution = attr(attrs, "RESOLUTION");
            String bandwidth = attr(attrs, "BANDWIDTH");
            String name = qualityLabel(resolution, bandwidth);
            JSONObject item = new JSONObject();
            item.put("name", name);
            item.put("note", qualityNote(resolution, bandwidth));
            item.put("url", absolute(url, uri));
            items.put(item);
        }
        out.put("ok", true);
        out.put("items", items);
        // 打出来：出问题时「服务端到底给了几档」是最先要问的问题。
        log("清晰度探测 " + head(url, 48) + " → " + items.length() + " 档");
        return out;
    }

    /** `RESOLUTION=1920x1080,BANDWIDTH=2000000` 里取一个属性。 */
    private static String attr(String attrs, String key) {
        String[] parts = attrs.split(",");
        for (int i = 0; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq < 0) continue;
            if (key.equalsIgnoreCase(parts[i].substring(0, eq).trim())) {
                return parts[i].substring(eq + 1).trim().replace("\"", "");
            }
        }
        return "";
    }

    private static String qualityLabel(String resolution, String bandwidth) {
        if (resolution.length() > 0) {
            int x = resolution.toLowerCase().indexOf('x');
            if (x > 0) {
                try {
                    int width = Integer.parseInt(resolution.substring(0, x).trim());
                    if (width >= 720) return width + "P";
                    if (width > 0) return width + "宽";
                } catch (Exception ignored) {}
            }
        }
        if (bandwidth.length() > 0) {
            try {
                return Math.round(Integer.parseInt(bandwidth) / 10000f) / 100f + " Mbps";
            } catch (Exception ignored) {}
        }
        return "默认";
    }

    private static String qualityNote(String resolution, String bandwidth) {
        StringBuilder sb = new StringBuilder();
        if (resolution.length() > 0) sb.append(resolution);
        if (bandwidth.length() > 0) {
            if (sb.length() > 0) sb.append(" · ");
            try {
                sb.append(Math.round(Integer.parseInt(bandwidth) / 1000f)).append(" kbps");
            } catch (Exception e) {
                sb.append(bandwidth).append(" bps");
            }
        }
        return sb.toString();
    }

    /** 相对地址按主列表所在目录展开（实测 `2000k/hls/index.m3u8` 就是这么给的）。 */
    private static String absolute(String base, String uri) {
        if (uri.startsWith("http://") || uri.startsWith("https://")) return uri;
        try {
            java.net.URI b = new java.net.URI(base);
            return b.resolve(uri).toString();
        } catch (Exception e) {
            int slash = base.lastIndexOf('/');
            return slash < 0 ? uri : base.substring(0, slash + 1) + uri;
        }
    }

    /** 只截前 N 个字符给人看（日志和报错里贴整条地址会把有用的部分挤掉）。 */
    private static String head(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static JSONObject fail(JSONObject out, String message) {
        try {
            out.put("ok", false);
            out.put("error", message);
        } catch (Exception ignored) {}
        return out;
    }

    // ── 读写 ──────────────────────────────────────────────────────────

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    /** 有没有可用的用户配置。判断标准是「文件在」且「能解析成 JSON」。 */
    public static boolean has(Context context) {
        return read(context) != null;
    }

    /** 读用户配置。没有、读不动、或不是合法 JSON 一律返回 null。 */
    public static JSONObject read(Context context) {
        if (context == null) return null;
        File target = file(context);
        if (!target.isFile()) return null;
        InputStream in = null;
        try {
            in = new FileInputStream(target);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return new JSONObject(new String(out.toByteArray(), "UTF-8"));
        } catch (Exception e) {
            log("用户配置读取失败：" + e);
            return null;
        } finally {
            close(in);
        }
    }

    public static void save(Context context, JSONObject config) throws IOException {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file(context));
            out.write(config.toString().getBytes("UTF-8"));
            out.flush();
        } finally {
            close(out);
        }
    }

    public static void clear(Context context) {
        File target = file(context);
        if (target.isFile() && !target.delete()) log("配置删除失败：" + target);
    }

    // ── 恢复出厂设置 ──────────────────────────────────────────────────

    /**
     * 「我的 → 设置 → 恢复出厂设置」的执行体。回来之后这台设备该和刚装完一样。
     *
     * ── 为什么登录态不在这里退 ────────────────────────────────────────
     * 它得由前端先做（api('logout') → AccountService.logout）。因为**退出登录不是
     * 一件发生在盘上的事**：ParseUser 的当前用户活在内存里，写盘的那份只是它的影子，
     * 而这里的清盘（连同 cacheDir 里的 ParseKeyValueCache 一起删掉）只处理影子。
     * 内存那份还在的话，重建出来的 MainActivity 照样从 `ParseUser.getCurrentUser()`
     * 拿到 objectId、`store.activateUser()` 照样把它当登录用户 —— 界面还是登录态，
     * 只是片单空了。所以顺序是：前端先退，退干净了再进来清。
     *
     * ── 为什么清得掉的和清不掉的都要说出来 ────────────────────────────
     * 这个动作没有撤销，所以返回体里带上「清了几个 prefs、哪几个删不掉」——
     * 真机出问题时 logcat 里那句 `已恢复出厂设置` 是唯一能对账的东西。
     */
    private static JSONObject factoryReset(Context context, JSONObject out) throws Exception {
        StringBuilder failed = new StringBuilder();

        // ① 内容源：当前生效那份配置 + 存过的地址清单。
        clear(context);
        File listing = listFile(context);
        if (listing.isFile() && !listing.delete()) failed.append(listing.getName()).append(' ');
        // 进程不死（重启走的是 Activity.recreate），预热结果和连接池都是 static，
        // 不作废的话下一条源会拿到上一条源的字节。和 __source:clear 同一个理由。
        AiiHttp.resetForSourceChange();

        // ② 网络代理。setProxy 同时把两个 OkHttp 客户端置空，下一次请求按直连重建。
        String proxyProblem = AiiHttp.setProxy("");
        if (proxyProblem != null) failed.append("proxy ").append(proxyProblem).append(' ');

        // ②' 内置核心。**必须在这里显式停**：setProxy("") 清的是用户手填的那个串，
        //    而内置核心在跑的时候 AiiHttp.effectiveProxy() 根本不看它 —— 核心的
        //    socksUrl() 优先。不停的话，用户点完「恢复出厂设置」被告知已直连，
        //    实际流量还从节点出去。下面 clearPreferences 会连 aii-proxy-core 一起清，
        //    但清盘只改内存和 XML，停不了 native 那边已经在跑的 goroutine。
        try {
            ProxyCore.get(context).stop();
            AiiHttp.invalidateClients();
        } catch (Throwable e) {
            failed.append("core ").append(e.getClass().getSimpleName()).append(' ');
        }

        // ③ 本机数据：片单、头像索引、DoH 缓存、更新器的记录……（两份例外见
        //    clearPreferences 的说明：hot-updates 与 content-access 不清）
        int prefs = clearPreferences(context);

        // ④ 头像文件。按 objectId 命名，退不退出登录都不会被自己的生命周期清掉，
        //    不删的话换个人登录还能在相册里翻出上一个用户的脸。
        emptyDir(new File(context.getFilesDir(), "avatars"), failed);

        // ⑤ 缓存目录。封面的解码结果、接口响应缓存、WebView 的 HTTP 缓存，
        //    以及 ParseKeyValueCache（SDK 把「当前登录用户」写在这里）。
        emptyDir(context.getCacheDir(), failed);

        // ⑥ WebView 那边的存储。DOM storage 前端自己会清，但 **Cookie 只有这里
        //    清得掉** —— 页面看得见的那些能靠 document.cookie 删，HttpOnly 的看不见。
        try {
            android.webkit.CookieManager.getInstance().removeAllCookies(null);
            android.webkit.WebStorage.getInstance().deleteAllData();
        } catch (Throwable e) {
            failed.append("webview ").append(e).append(' ');
        }

        String notes = failed.toString().trim();
        log("已恢复出厂设置：清了 " + prefs + " 份偏好" + (notes.length() == 0 ? "" : "，未删掉 " + notes));
        out.put("ok", true);
        out.put("prefs", prefs);
        out.put("failed", notes);
        return out;
    }

    /**
     * 清掉这个应用的全部 SharedPreferences，返回清了几份。
     *
     * ── 为什么不能直接把 shared_prefs/ 目录删了 ─────────────────────────
     * 那只能删掉盘上那份 XML。SharedPreferences 是**一个名字一个内存实例**，
     * 进程活着它就在，而且之后任何一次 apply() 都会把内存里那张完整的表重新写回
     * 文件 —— 删掉的文件会自己长回来，还带着刚清掉的数据。必须走 API 清：
     * clear() 同时改内存和盘。
     *
     * ── 为什么是遍历目录，而不是列一张名字清单 ──────────────────────────
     * 清单会漏，而且漏了没有任何症状。今天在里面的是 huangguo-data（片单）、
     * aii-net（代理）、aii-dns（DoH 缓存）、huangguo-avatars（头像索引）、
     * updates（更新器），明天再加一个就静默漏一个 —— 目录里有什么就清什么，
     * 名字本身就是唯一真相。
     *
     * ── 两个例外，都不是用户数据，清了反而出事 ──────────────────────────
     *
     * ① hot-updates —— 它守着一位 `originMigrated`：应用还从 file:// 起源启动的
     *    年代留下的标志。清掉它，下一次启动会先载 file:///android_asset/app.html、
     *    跑完那次起源迁移再跳回 appassets —— 用户看到的是「刚恢复完出厂，启动还闪
     *    一下」，而这一位跟用户数据毫无关系。
     *
     * ② content-access —— 成人门槛与私密模式解锁。这个**不能清**，清完当场坏：
     *    ContentMode 的解锁状态是「内存一份、盘上一份」，清盘只会清掉后面那份，
     *    而 require() 恰好卡在这个夹角上 —— 内存里 active 还是 huangguo（进程不死），
     *    盘上 unlocked 已经 false，于是**每一个带 source 的桥指令**都抛
     *    「内容已切换，请重新打开」，包括设置页那次 __source:get。表现就是：刚恢复
     *    完出厂，设置源那一屏顶上多一行红字。而它压根不是用户数据 —— 前端每次冷启动
     *    都会自动 enter-private + confirm-private-age 把两位写回来（密码内置），
     *    清与不清，用户看到的完全一样。
     */
    private static int clearPreferences(Context context) {
        File[] files = new File(context.getDataDir(), "shared_prefs").listFiles();
        if (files == null) return 0;
        int count = 0;
        for (File file : files) {
            String name = file.getName();
            if (!name.endsWith(".xml")) continue;
            name = name.substring(0, name.length() - 4);
            // 上面①②那两个例外。
            if ("hot-updates".equals(name) || "content-access".equals(name)) continue;
            try {
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit();
                count++;
            } catch (Throwable e) {
                log("偏好清空失败 " + name + "：" + e);
            }
        }
        return count;
    }

    /** 把一个目录里的东西删干净，但保留目录本身 —— WebView 和 Parse 都会自己往里再找。 */
    private static void emptyDir(File dir, StringBuilder failed) {
        File[] children = dir == null ? null : dir.listFiles();
        if (children == null) return;
        for (File child : children) wipe(child, failed);
    }

    /**
     * 递归删。删不掉的记一笔就走，不抛 —— 恢复出厂设置里半途中断，留下的是一台
     * 「清了一半、还进不去设置页」的设备，比多留一个文件糟得多。
     */
    private static void wipe(File target, StringBuilder failed) {
        if (target == null || !target.exists()) return;
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) for (File child : children) wipe(child, failed);
        }
        if (!target.delete()) failed.append(target.getName()).append(' ');
    }

    // ── 下载 ──────────────────────────────────────────────────────────

    /**
     * 从一个地址拉配置。
     *
     * 这里不挂 DoH —— DoH 服务商列表本身就在配置里，鸡生蛋问题。
     * 源地址如果是被污染域名，用户会看到「拉取失败」，那是他和他的源之间的事。
     */
    public static JSONObject fetch(String url) throws Exception {
        String trimmed = url == null ? "" : url.trim();
        if (trimmed.length() == 0) throw new IOException("地址为空");
        // 明文 HTTP 拉不动：targetSdk >= 28 时 Android 默认 cleartextTrafficPermitted=false，
        // 请求会以 "CLEARTEXT communication not permitted by network security policy" 失败。
        // 这里提前拦下并说人话 —— 那条系统报错用户看不懂，也不知道该怎么办。
        // 不为此开 usesCleartextTraffic：源地址本来就是用户自己填的，要求 https 是合理的。
        if (trimmed.startsWith("http://")) {
            throw new IOException("只支持 https 地址（Android 默认禁止明文 HTTP，http:// 拉不下来）");
        }
        if (!trimmed.startsWith("https://")) {
            throw new IOException("地址要以 https:// 开头");
        }

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(FETCH_TIMEOUT, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(FETCH_TIMEOUT, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder().url(trimmed).build();

        Response response = client.newCall(request).execute();
        try {
            if (!response.isSuccessful()) throw new IOException("服务端返回 HTTP " + response.code());
            ResponseBody body = response.body();
            if (body == null) throw new IOException("响应为空");
            String text = body.string();
            if (text.length() == 0) throw new IOException("响应内容为空");
            // 体积闸门：正常配置几 KB。给到 512KB 是防呆，不是防攻击。
            if (text.length() > 512 * 1024) throw new IOException("配置过大（" + text.length() + " 字节）");
            // XML 接口（苹果CMS 的 /at/xml/，type 0 那种）解析成 JSON 同形结构，
                // 后面识别/映射/播放全部复用 JSON 那条路。
                if (CmsXml.looksLikeXml(text)) {
                    try {
                        return CmsXml.parse(text);
                    } catch (Exception e) {
                        throw new IOException("XML 解析失败：" + e.getMessage());
                    }
                }
                try {
                    return new JSONObject(text);
                } catch (Exception e) {
                    throw new IOException("返回的不是合法 JSON，也不是苹果CMS 的 XML：" + e.getMessage());
                }
        } finally {
            response.close();
        }
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
        String adapter = config.optString("adapter", "");
        if (adapter.length() > 0 && !"cms".equals(adapter) && !"agedm".equals(adapter)
                && !"hanime1".equals(adapter)) {
            return "不认识的适配器：" + adapter + "（cms / agedm / 单站 HTML 型）";
        }
        if ("cms".equals(adapter) || "agedm".equals(adapter)) {
            String api = config.optString("api", "").trim();
            if (api.length() == 0) return "缺少 api 地址";
            if (!api.startsWith("https://")) return "api 地址必须是 https 开头";
            JSONObject cmsWeb = config.optJSONObject("web");
            if (cmsWeb == null || cmsWeb.optJSONObject("paths") == null) return "缺少 web.paths";
            return null;
        }
        // 单站 HTML 型不需要 api 字段 —— 它没有接口，适配器自己去 origin 抓 HTML。
        // 用 origin 当站点地址，跟 REST 那套一样校验。
        if ("hanime1".equals(adapter)) {
            String origin = config.optString("origin", "").trim();
            if (origin.length() == 0) return "缺少 origin（这个源要填站点地址）";
            if (!origin.startsWith("https://")) return "origin 必须是 https 开头";
            JSONObject hWeb = config.optJSONObject("web");
            if (hWeb == null || hWeb.optJSONObject("paths") == null) return "缺少 web.paths";
            return null;
        }

        // web 块先看：origin 那几条校验要不要做，取决于 paths.home 长什么样。
        JSONObject web = config.optJSONObject("web");
        if (web == null) return "缺少 web 块（前端配置）";

        JSONObject paths = web.optJSONObject("paths");
        if (paths == null || paths.length() == 0) return "web.paths 为空（至少要有一条接口路径）";
        String home = paths.optString("home", "");
        if (home.length() == 0) return "web.paths.home 缺失（首页拿不到数据）";

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
        if (origin.length() == 0 && !hasAdapter) {
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
        if (categories == null || categories.length() == 0) return "web.categories 为空（分类栏会是空的）";
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
                if (!isAction(action)) {
                    return "web.shapes 里有个不认识的动作用作键：" + action;
                }
                JSONObject spec = shapes.optJSONObject(action);
                if (spec == null) return "web.shapes." + action + " 不是对象";
                Iterator<String> shapeKeys = spec.keys();
                while (shapeKeys.hasNext()) {
                    String shapeKey = shapeKeys.next();
                    if (shapeKey.startsWith("_")) continue;       // 同上
                    if (!isShapeValue(spec.opt(shapeKey))) {
                        return "web.shapes." + action + "." + shapeKey
                                + " 既不是取值路径，也不是 {div:[a,b]}";
                    }
                }
            }
        }

        Iterator<String> slugs = categories.keys();
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

    private static void close(java.io.Closeable closeable) {
        if (closeable != null) {
            try { closeable.close(); } catch (IOException ignored) {}
        }
    }

    private static void log(String message) {
        try { android.util.Log.d("AIIvideo", "[source] " + message); } catch (Throwable ignored) {}
    }
}
