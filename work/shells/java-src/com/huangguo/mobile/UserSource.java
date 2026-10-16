package com.huangguo.mobile;

import android.content.Context;
import android.content.Intent;
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
                String problem = applySource(context, url);
                if (problem != null) return fail(out, problem);
                remember(context, url);
                // 换源只重建 Activity，**进程不死** —— 而预热结果和连接池都是 static，
                // 不清的话新源会拿到旧源的首页字节（实测：切完直接弹「内容更新失败」）。
                AiiHttp.resetForSourceChange();
                JSONObject saved = read(context);
                log("已设置用户源 → " + (saved == null ? "" : saved.optString("origin", "")));
                out.put("ok", true);
                out.put("configured", true);
                out.put("origin", saved == null ? "" : saved.optString("origin", ""));
                out.put("url", url);
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
     * 拉一个地址、校验、干跑一次 SiteProfile，全过了就存成当前配置。
     * 返回 null 表示成功，否则是一句给人看的错。
     *
     * 「干跑」那段是必须的：SiteProfile 在 Activity.onCreate 里构造，它自己那堆校验
     * （origin 是不是 https、封面 AES 密钥够不够长、allowedHosts 空不空）抛异常的表现
     * 是**一装完就闪退**。而配置存在 files/ 下，App 起不来就进不了设置页 ——
     * 用户被一份填错的配置永久锁在外面，只能清数据。所以在这里先把那条路走一遍。
     */
    private static String applySource(Context context, String url) {
        JSONObject config;
        try {
            config = load(url);
        } catch (Exception e) {
            return "拉取失败：" + e.getMessage();
        }
        String problem = validate(config);
        if (problem != null) return problem;
        try {
            new SiteProfile(context, config);
        } catch (Throwable e) {
            String why = e.getMessage();
            if (e.getCause() != null && e.getCause().getMessage() != null) why = e.getCause().getMessage();
            return "这份配置会让应用启动失败，已拒绝：" + why;
        }
        try {
            // 把来源地址一并存下 —— 设置页要回显「当前用的是哪个源」，
            // 而缓存作用域（前端 hgcache: 的键前缀）也是按这个地址分的。
            config.put("__url", url);
            save(context, config);
        } catch (Exception e) {
            return "保存失败：" + e.getMessage();
        }
        return null;
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
                entry.put("name", row == null ? guessName(url) : row.optString("name", guessName(url)));
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

    /** 把一个地址记进清单（已存在就刷新时间戳）。名字优先用配置里的 name，其次从地址猜。 */
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

            JSONArray next = new JSONArray();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null || url.equals(item.optString("url", ""))) continue;
                next.put(item);
            }
            JSONObject entry = new JSONObject();
            entry.put("url", url);
            entry.put("name", name);
            entry.put("at", System.currentTimeMillis());
            next.put(entry);

            store.put("saved", next);
            writeJson(listFile(context), store);
        } catch (Exception e) {
            log("源清单写入失败：" + e);
        }
    }

    /**
     * 从清单里划掉一个地址。
     *
     * 如果划掉的正好是当前生效的那个，连 user-source.json 一起删 —— 留着它会让
     * App 下次启动仍然读那份配置，而设置页里的清单已经没有它了，两边对不上。
     * 删完等于回到「没配源」，用户下次打开会看到设置页。
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
        // 认得的适配器只有 "cms" 一个。别的值不当适配器看 —— 会掉进下面 REST 那套
        // 校验里，缺什么报什么。那是对的（未知适配器本来就该被挡），但报「缺少 origin」
        // 容易让人以为是地址填错了，所以这里先点名。
        String adapter = config.optString("adapter", "");
        if (adapter.length() > 0 && !"cms".equals(adapter) && !"agedm".equals(adapter)) {
            return "不认识的适配器：" + adapter + "（目前有 cms 和 agedm）";
        }
        if ("cms".equals(adapter) || "agedm".equals(adapter)) {
            String api = config.optString("api", "").trim();
            if (api.length() == 0) return "缺少 api 地址";
            if (!api.startsWith("https://")) return "api 地址必须是 https 开头";
            JSONObject cmsWeb = config.optJSONObject("web");
            if (cmsWeb == null || cmsWeb.optJSONObject("paths") == null) return "缺少 web.paths";
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
