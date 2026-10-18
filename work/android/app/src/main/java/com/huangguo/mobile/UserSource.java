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
        // 每站「id 翻到哪儿」的规矩归适配器自己，这里只管问 + 兜底。
        SiteAdapter impl = Adapters.byId(config.optString("adapter", "").trim());
        if (impl != null) {
            String space = impl.identity(config);
            if (space != null && space.length() > 0) return space;
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

    static String trimSlash(String url) {
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
        // 第一轮：按地址认，**认在 fetch 之前**。AGE动漫 / hanime1 / mrds66 给的
        // 都是 HTML，先 fetch 一次只会拿到几十万字节的页面，然后报「认不出这是什么
        // 配置」。谁负责认、认完怎么生成配置，全在各自那个 SiteAdapter 里。
        for (int i = 0; i < Adapters.ALL.size(); i++) {
            SiteAdapter adapter = Adapters.ALL.get(i);
            if (adapter.matchesUrl(url)) return adapter.probe(url);
        }

        JSONObject fetched = fetch(url);
        if (fetched == null) throw new IOException("响应为空");

        // 第二轮：按响应形状认。苹果CMS 没有固定域名，只能看字段 —— 两次探测、
        // XML 补层级那些活也在 CmsSite 里，这里只负责转交。
        for (int i = 0; i < Adapters.ALL.size(); i++) {
            SiteAdapter adapter = Adapters.ALL.get(i);
            if (adapter.matchesBody(fetched)) return adapter.probe(url, fetched);
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


    // ── 配置里的动作名 ────────────────────────────────────────────────

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
    /** 上次导入的订阅地址。重进设置页不用再粘一遍。 */
    private static final String SUB_URL_KEY = "subUrl";

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
     *   sub?url=<订阅地址>            拉订阅 + 测速
     *   speed?batch=N                分批测速，第 N 批
     *   use?index=N                  连上第 N 个已测通的节点
     *   nodes[?do=refresh|reset]     内置节点池的状态 / 热更新 / 复位
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
            if ("sub".equals(action)) return proxySubscribe(context, param(query, "url"), out);
            if ("subclear".equals(action)) return proxySubClear(context, out);
            if ("speed".equals(action)) return proxySpeed(context, param(query, "batch"), out);
            if ("use".equals(action)) return proxyUse(context, param(query, "index"), out);
            if ("nodes".equals(action)) return proxyNodes(context, param(query, "do"), out);
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
    /**
     * 老配置迁移：模式还是默认的 direct、盘上却存着手填地址。
     *
     * 那是「外部地址」这个模式出现之前的用法 —— 那时候手填一个地址就等于在用代理。
     * 「直连」改成真直连之后，不迁移等于把他们的代理静默关掉，而设置页还会显示
     * 「直连」，用户只会以为站点坏了。
     *
     * 迁移成 external：行为不变，地址也终于能在界面上看见。
     * 由 AiiHttp.init() 在进程起来时调一次（不是等用户打开设置页才调）。
     */
    static void migrateProxyMode(Context context) {
        if (context == null) return;
        try {
            if (!MODE_DIRECT.equals(proxyMode(context))) return;
            if (AiiHttp.proxyRaw().length() == 0) return;
            setProxyMode(context, MODE_EXTERNAL);
            AiiHttp.invalidateClients();
            log("代理：盘上有手填地址但模式是直连，迁移成「外部地址」");
        } catch (Throwable e) {
            // 迁移失败不该拦住任何东西：大不了这次还是直连，用户去设置页点一下就好。
        }
    }

    private static JSONObject proxyBoot(Context context, JSONObject out) throws Exception {
        migrateProxyMode(context);

        if (MODE_BUILTIN.equals(proxyMode(context))
                && !ProxyCore.get(context).isRunning()
                && proxyLink(context).length() > 0) {
            proxyStart(context, "", "", new JSONObject());
        }
        // 节点池热更新：过期了就在后台拉一次。不阻塞这条指令 ——
        // boot 是设置页打开时第一条请求，它必须立刻返回。
        // 拉到的下一轮测速自然用上；这一轮用旧的也没问题，旧的最多差几个小时。
        if (MODE_BUILTIN.equals(proxyMode(context))) autoRefreshNodes(context);
        return proxyStatus(context, out);
    }

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
    private static volatile List<SpeedTest.Item> lastSpeedTest;
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
    private static java.util.TreeMap<Integer, SpeedTest.Item> speedAccum;
    private static final Object SPEED_LOCK = new Object();

    /**
     * 拉订阅并解析，返回节点列表（**不测速**）。
     *
     * 为什么和测速分成两条命令：拉订阅是必成的（几秒），测速是可选的
     * （20 秒上限）。合在一起的话用户想「先看看有多少个节点」也得等 20 秒。
     */
    private static JSONObject proxySubscribe(Context context, String url, JSONObject out) throws Exception {
        String target = url == null ? "" : url.trim();
        if (target.length() == 0) {
            // 没给地址就用上次的。设置页重进一次不用再粘一遍。
            target = proxyStore(context).getString(SUB_URL_KEY, "");
            if (target == null) target = "";
        }
        if (target.length() == 0) {
            return fail(out, "还没有订阅地址");
        }

        Subscription.Result parsed;
        try {
            parsed = Subscription.fetch(target, 20);
        } catch (Throwable e) {
            String m = e.getMessage();
            out.put("ok", false);
            out.put("error", m == null || m.isEmpty() ? "订阅拉取失败" : m);
            out.put("url", target);
            return out;
        }

        if (parsed.size() == 0) {
            out.put("ok", false);
            out.put("error", parsed.firstError == null
                    ? "订阅里没有可用的节点" : parsed.firstError);
            out.put("url", target);
            return out;
        }

        proxyStore(context).edit().putString(SUB_URL_KEY, target).apply();
        lastSubUrl = target;
        // 换了订阅，旧的测速结果整套作废 —— 下标对不上了，留着只会让 use 用错节点。
        lastSpeedTest = null;

        log("订阅 " + target.substring(0, Math.min(50, target.length()))
                + " → " + Subscription.summary(parsed));

        out.put("ok", true);
        out.put("url", target);
        out.put("count", parsed.size());
        out.put("failed", parsed.failed);
        out.put("summary", Subscription.summary(parsed));
        // 只回「哪台机器 + 协议」，不回 uuid/密码。
        out.put("nodes", describeNodes(parsed));
        return out;
    }

    /** 节点列表的脱敏形态。这份答复会进 logcat，所以不带凭据。 */
    private static JSONArray describeNodes(Subscription.Result parsed) throws Exception {
        JSONArray list = new JSONArray();
        for (int i = 0; i < parsed.size(); i++) {
            JSONObject item = new JSONObject();
            item.put("index", i);
            item.put("node", parsed.nodes.get(i).describe());
            item.put("protocol", parsed.nodes.get(i).protocol);
            list.put(item);
        }
        return list;
    }

    /**
     * 对上次拉到的订阅里的节点批量测速。
     *
     * 结果按下标返回，`use?index=N` 用的就是这个下标。
     * **不用排序后的下标** —— 排序只是给 UI 看的建议顺序，下标一旦被排序
     * 打乱，前端拿它回查就会错位。
     */
    /**
     * 忘掉导入过的订阅，回到「用内置免费节点」。
     *
     * 和 clear 分开：clear 清的是**节点分享链接**（用户手填的那条），
     * 这条清的是**订阅地址**。两者存在不同键里，混用会让「改回用免费节点」
     * 顺手把用户辛苦挑的那个节点也删了。
     */
    private static JSONObject proxySubClear(Context context, JSONObject out) throws Exception {
        proxyStore(context).edit().remove(SUB_URL_KEY).apply();
        lastSpeedTest = null;
        out.put("ok", true);
        out.put("source", "builtin");
        return out;
    }

    /**
     * 内置节点池的热更新。
     *
     *   nodes                    只报状态，不联网（几毫秒）
     *   nodes?do=refresh         拉一次远端，成功则落盘
     *   nodes?do=reset           删缓存，回到 APK 自带那份
     *
     * 分成三条而不是「查状态时顺手刷新」：状态查询在进设置页时每次都会调，
     * 把它变成一次网络请求会让设置页打开就卡几秒。刷新必须由用户明确触发，
     * 或者由 proxyBoot 里的后台线程在过期时默默做（那条路径不阻塞任何人）。
     */
    private static JSONObject proxyNodes(Context context, String what, JSONObject out) throws Exception {
        String action = what == null ? "" : what.trim();
        NodesFeed.Status status;
        if ("refresh".equals(action)) {
            status = NodesFeed.refresh(context);
            // 节点池换了，上一轮测出来的结果就作废了 —— 下标指向的已经不是
            // 同一个节点，留着它会让用户点一行连到别的地方去。
            if (status.ok) {
                synchronized (SPEED_LOCK) {
                    speedAccum = null;
                    lastSpeedTest = null;
                }
            }
        } else if ("reset".equals(action)) {
            status = NodesFeed.reset(context);
            synchronized (SPEED_LOCK) {
                speedAccum = null;
                lastSpeedTest = null;
            }
        } else {
            status = NodesFeed.status(context);
        }
        out.put("ok", status.ok);
        out.put("nodes", status.toJson());
        return out;
    }

    /**
     * 启动时在后台悄悄检查一次节点池。
     *
     * 只在「离上次成功/失败超过 6 小时」时才发请求，所以正常使用下一天
     * 最多几次。放在线程里跑是因为设置页的 boot 指令要立刻返回状态，
     * 不能陪着等网络。
     */
    private static void autoRefreshNodes(final Context context) {
        if (!NodesFeed.due(context)) return;
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    NodesFeed.Status status = NodesFeed.refresh(context);
                    if (status.ok) {
                        log("节点池已更新：" + status.nodes + " 条（" + status.updated + "）");
                    } else {
                        log("节点池更新失败：" + status.error);
                    }
                } catch (Throwable e) {
                    log("节点池更新异常：" + e.getClass().getSimpleName());
                }
            }
        }, "nodes-refresh");
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 测速。**默认测内置的那份**，用户导入过订阅才测订阅的。
     *
     * 这个优先级是刻意的：没机场的人不该被要求「先去弄个订阅地址」——
     * 他们打开设置页点一下就该看到几个能连的节点。订阅是给有机场的人
     * 加的那条路，不是前置条件。
     *
     * 判断依据是 SUB_URL_KEY 有没有值，不是 mode：用户可能导入过订阅但
     * 后来又切回直连，那时候测速仍然该测他导入的那份。
     */
    private static JSONObject proxySpeed(Context context, String batchText, JSONObject out) throws Exception {
        Subscription.Result parsed;
        String target = proxyStore(context).getString(SUB_URL_KEY, "");
        boolean fromSubscription = target != null && target.length() > 0;
        try {
            parsed = fromSubscription ? Subscription.fetch(target, 20) : Subscription.builtin(context);
        } catch (Throwable e) {
            String m = e.getMessage();
            out.put("ok", false);
            out.put("error", m == null || m.isEmpty() ? "节点列表读不出来" : m);
            out.put("source", fromSubscription ? "subscription" : "builtin");
            return out;
        }
        if (parsed.size() == 0) {
            String reason = parsed.firstError;
            return fail(out, reason != null && reason.length() > 0 ? reason
                    : (fromSubscription ? "订阅里没有可用的节点" : "内置节点列表是空的"));
        }

        // 数量必须限量，因为测速是**串行**的（并发会互相踩断，见 SpeedTest 的注释）。
        // 12 个 × 3 秒封顶 = 36 秒，落在 20 秒上限内能测完的最多是 6~7 个，
        // 但大多数节点会提前返回（通的 1~2 秒、死的立刻 connection refused），
        // 实际能覆盖到 10 个上下。
        //
        // 为什么不是全测：400 个串行要十几分钟。用户要的是「几个能用的」，
        // 不是一份完整体检报告。取前 12 个已经能覆盖绝大多数情况。
        // 分批发测，每批 3 个。前端循环调用，每批回来就刷新一次界面 ——
        // 一次测 12 个要 20 多秒，中途什么都不显示，用户会以为卡死了。
        //
        // 为什么要后端分批而不是后端一次算完再推进度：桥是「一问一答」的，
        // 有个 Promise 对应一次请求，没有服务端推送。分批是这套桥下最省事的
        // 做法，而且天然带断点 —— 用户中途退出，已测的结果不会白费。
        int limit = Math.min(parsed.size(), SPEED_POOL);
        int batch;
        try {
            batch = batchText == null || batchText.trim().isEmpty() ? 0 : Integer.parseInt(batchText.trim());
        } catch (NumberFormatException e) {
            batch = 0;
        }
        // batch == 0 表示新一轮开始 —— 清掉上一轮的累计。
        // 不清的话，第二次测速会把第一次的结果一起算进去，
        // 表现是「测了 12 个，列表里冒出 20 多个」。
        if (batch == 0) {
            synchronized (SPEED_LOCK) {
                speedAccum = null;
                lastSpeedTest = null;
            }
        }

        int from = batch * SPEED_BATCH;
        if (from >= limit) {
            // 已经测完，把累计结果原样返回。
            return speedResult(out, true, fromSubscription, parsed.size());
        }
        int to = Math.min(from + SPEED_BATCH, limit);

        List<XrayConfig.Node> nodes = parsed.nodes.subList(from, to);
        List<String> links = parsed.links.subList(from, to);

        List<SpeedTest.Item> items = SpeedTest.run(nodes, links, null, null, from);

        // 累计到一个 map 里，按 index 存 —— 分批之间靠它拼回完整结果。
        synchronized (SPEED_LOCK) {
            if (speedAccum == null) speedAccum = new java.util.TreeMap<Integer, SpeedTest.Item>();
            for (SpeedTest.Item item : items) speedAccum.put(Integer.valueOf(item.index), item);
            lastSpeedTest = new java.util.ArrayList<SpeedTest.Item>(speedAccum.values());
            SpeedTest.sortBySpeed(lastSpeedTest);
        }

        boolean done = to >= limit;
        if (done) log("测速完成，本轮共测 " + listSize());
        return speedResult(out, done, fromSubscription, parsed.size());
    }

    private static int listSize() {
        synchronized (SPEED_LOCK) {
            return lastSpeedTest == null ? 0 : lastSpeedTest.size();
        }
    }

    /**
     * 把累计结果拼成答复。
     *
     * done 表示这一轮测完了 —— 前端据此决定还要不要继续调下一批。
     * 每批都回完整列表（不是增量），前端拿到就整块重画，不用自己拼。
     */
    private static JSONObject speedResult(JSONObject out, boolean done,
                                          boolean fromSubscription, int pool) throws Exception {
        List<SpeedTest.Item> items;
        synchronized (SPEED_LOCK) {
            items = lastSpeedTest == null
                    ? new java.util.ArrayList<SpeedTest.Item>()
                    : new java.util.ArrayList<SpeedTest.Item>(lastSpeedTest);
        }
        int usable = 0;
        JSONArray arr = new JSONArray();
        for (SpeedTest.Item item : items) {
            if (item.ok()) usable++;
            JSONObject o = new JSONObject();
            o.put("index", item.index);
            o.put("node", item.node.describe());
            o.put("protocol", item.node.protocol);
            o.put("ms", item.ms);
            o.put("ok", item.ok());
            o.put("error", item.error);
            arr.put(o);
        }
        out.put("ok", true);
        out.put("done", done);
        out.put("tested", items.size());
        out.put("total", Math.min(pool, SPEED_POOL));
        out.put("usable", usable);
        out.put("nodes", arr);
        out.put("source", fromSubscription ? "subscription" : "builtin");
        out.put("pool", pool);
        return out;
    }

    /**
     * 用测速结果里某个下标的节点起核心。
     *
     * 下标是 `speed` 返回的那个 `index`，不是排序位置。
     * 找不到就明说，别静默什么都不做 —— 用户点了没反应最难受。
     */
    private static JSONObject proxyUse(Context context, String indexText, JSONObject out) throws Exception {
        List<SpeedTest.Item> items = lastSpeedTest;
        if (items == null || items.isEmpty()) {
            return fail(out, "还没有测速结果，先测一次");
        }
        int index;
        try {
            index = Integer.parseInt(indexText == null ? "" : indexText.trim());
        } catch (NumberFormatException e) {
            return fail(out, "节点下标不是数字");
        }
        SpeedTest.Item hit = null;
        for (SpeedTest.Item item : items) {
            if (item.index == index) {
                hit = item;
                break;
            }
        }
        if (hit == null) return fail(out, "这个下标不在测速结果里");
        if (!hit.ok()) return fail(out, "这个节点没测通，换一个");
        if (hit.link == null || hit.link.length() == 0) return fail(out, "这个节点没有原始链接");

        return proxyStart(context, hit.link, null, out);
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

    static void log(String message) {
        try { android.util.Log.d("AIIvideo", "[source] " + message); } catch (Throwable ignored) {}
    }
}
