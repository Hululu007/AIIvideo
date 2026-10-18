package com.huangguo.mobile;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.List;

/**
 * 内置代理核心（Xray）的控制台 —— 订阅解析、节点描述、测速批次、开机自启、模式迁移。
 *
 * ── 为什么单独一个类 ────────────────────────────────────────────────
 * 这 600 多行原来在 {@code UserSource}（2492 行）里，和源配置、播放器控制、
 * 进度上报挤在一起，唯一原因是它们历史上都借同一座 detail 桥。
 * 它和其余几块**没有一个共享字段**：自己的 prefs 表（aii-proxy-core）、
 * 自己的状态（coreBusy / lastSubUrl / 测速池），只跟 ProxyCore 和 XrayConfig 打交道。
 *
 * ── 三档模式 ────────────────────────────────────────────────────────
 * <pre>
 *   direct    直连，核心停掉
 *   builtin   内置核心（Xray），节点从订阅或分享链接来
 *   external  用户手填的地址（旧式 proxy:set），核心停掉
 * </pre>
 * 真正决定流量走向的是 {@code AiiHttp.effectiveProxy()}：核心在跑就用它的本地
 * SOCKS，否则用手填的那个。这个类只负责**让核心跑起来 / 停下来**。
 */
final class ProxyConsole {

    private ProxyConsole() {}

    // ── 常量 ──────────────────────────────────────────────────────────

    /** 桥上的前缀。MainActivity 的控制面入口会把它补回来。 */
    static final String PREFIX = "__proxy:";

    private static final String PROXY_STORE = "aii-proxy-core";
    private static final String CORE_MODE_KEY = "mode";
    private static final String CORE_LINK_KEY = "link";
    private static final String SUB_URL_KEY = "subUrl";

    static final String MODE_DIRECT = "direct";
    static final String MODE_BUILTIN = "builtin";
    static final String MODE_EXTERNAL = "external";

    /** 正在启停核心。UI 靠它显示「正在启动…」并禁掉按钮。 */
    static volatile boolean coreBusy = false;

    /** 上次订阅地址。失败重试时用，不落盘。 */
    static volatile String lastSubUrl = "";

    /** 测速并发。太大容易被机场限速，太小等得久。 */
    private static final int SPEED_POOL = 12;
    private static final int SPEED_BATCH = 3;
    private static final Object SPEED_LOCK = new Object();

    /** 上一次测速结果（按批次累积）。进程内状态，不落盘。 */
    static volatile List<SpeedTest.Item> lastSpeedTest;
    static java.util.TreeMap<Integer, SpeedTest.Item> speedAccum;

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
    static JSONObject handle(Context context, String body, JSONObject out) {
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
                return proxyStart(context, UserSource.param(query, "link"), UserSource.param(query, "node"), out);
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
            if ("mode".equals(action)) return proxyMode(context, UserSource.param(query, "value"), out);
            if ("boot".equals(action)) return proxyBoot(context, out);
            if ("sub".equals(action)) return proxySubscribe(context, UserSource.param(query, "url"), out);
            if ("subclear".equals(action)) return proxySubClear(context, out);
            if ("speed".equals(action)) return proxySpeed(context, UserSource.param(query, "batch"), out);
            if ("use".equals(action)) return proxyUse(context, UserSource.param(query, "index"), out);
            if ("nodes".equals(action)) return proxyNodes(context, UserSource.param(query, "do"), out);
            return UserSource.fail(out, "不认识的代理指令：" + action);
        } catch (Throwable e) {
            return UserSource.fail(out, "代理指令失败：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }



    static android.content.SharedPreferences proxyStore(Context context) {
        return context.getSharedPreferences(PROXY_STORE, Context.MODE_PRIVATE);
    }


    static String proxyMode(Context context) {
        String mode = proxyStore(context).getString(CORE_MODE_KEY, MODE_DIRECT);
        if (MODE_BUILTIN.equals(mode) || MODE_EXTERNAL.equals(mode)) return mode;
        return MODE_DIRECT;
    }


    static void setProxyMode(Context context, String mode) {
        proxyStore(context).edit().putString(CORE_MODE_KEY, mode).apply();
    }
    /** 存分享链接原文。比存解析后的 JSON 少一个「字段名谁说了算」的问题。 */
    static String proxyLink(Context context) {
        String link = proxyStore(context).getString(CORE_LINK_KEY, "");
        return link == null ? "" : link.trim();
    }




    static void setProxyLink(Context context, String link) {
        proxyStore(context).edit().putString(CORE_LINK_KEY, link == null ? "" : link.trim()).apply();
    }
    /**
     * 合成一份状态。前端只读这一份。
     *
     * `effective` 是页面顶上那行「现在走哪条」的唯一来源：内核在跑 → 本地 SOCKS5；
     * 不在跑但用户填过外部地址 → 那个地址；都没有 → 空串（直连）。
     * 这和 AiiHttp.effectiveProxy() 是同一个结论，但这边额外知道「为什么没在跑」。
     */
    static JSONObject proxyStatus(Context context, JSONObject out) throws Exception {
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




    static JSONObject proxyStart(Context context, String link, String nodeJson, JSONObject out)
            throws Exception {
        String text = link == null ? "" : link.trim();
        boolean hasNodeJson = nodeJson != null && nodeJson.trim().length() > 0;
        if (text.length() > 0) setProxyLink(context, text);
        // 存过的那条留到**解析成功之后**才用：一条粘贴错的链接如果先落盘再解析，
        // 用户下次点「保存并启动」会拿到上次那条坏链接，报的错还跟这次无关。
        // 所以这里只读不写，落盘在下面的解析成功处。
        if (text.length() == 0 && !hasNodeJson) text = proxyLink(context);
        if (text.length() == 0 && !hasNodeJson) {
            return UserSource.fail(out, "还没有节点配置，先粘一条 vmess:// 或 ss:// 分享链接");
        }

        XrayConfig.Node node;
        try {
            node = text.length() > 0
                    ? XrayConfig.parseShareLink(text)
                    : nodeFromJson(new JSONObject(nodeJson));
        } catch (IllegalArgumentException e) {
            // 解析失败不动已存的那条 —— 好链接不该被一条手误覆盖掉。
            return UserSource.fail(out, e.getMessage() == null ? "节点配置看不懂" : e.getMessage());
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
                UserSource.log("内置核心启动失败：" + failed.optString("error"));
                return failed;
            }
            // 核心起来了 → 立刻让旧客户端作废。只改标志不够：OkHttp 的连接池和
            // TLS 会话挂在已建好的实例上，不换实例的话它们还连着原来的出口。
            AiiHttp.invalidateClients();
            setProxyMode(context, MODE_BUILTIN);
            UserSource.log("内置核心已启动 → " + node.describe());
            return proxyStatus(context, out);
        } finally {
            coreBusy = false;
        }
    }


    static JSONObject proxyStop(Context context, JSONObject out) throws Exception {
        ProxyCore.get(context).stop();
        // 停了也必须作废：否则下一次请求还在用那条已经关掉的本地端口。
        AiiHttp.invalidateClients();
        // 停核心不改模式 —— 用户点「停止」多半只是想临时断一下，
        // 模式保持 builtin，下次启动还会自己拉起来。
        return proxyStatus(context, out);
    }


    static JSONObject proxyTest(Context context, JSONObject out) {
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


    static JSONObject proxyMode(Context context, String value, JSONObject out) throws Exception {
        String mode = value == null ? "" : value.trim();
        if (!MODE_DIRECT.equals(mode) && !MODE_BUILTIN.equals(mode) && !MODE_EXTERNAL.equals(mode)) {
            return UserSource.fail(out, "模式只能是 direct / builtin / external");
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
            UserSource.log("代理：盘上有手填地址但模式是直连，迁移成「外部地址」");
        } catch (Throwable e) {
            // 迁移失败不该拦住任何东西：大不了这次还是直连，用户去设置页点一下就好。
        }
    }
    /**
     * 冷启动。设置页第一次加载时调一次，按盘上的模式恢复内置核心。
     *
     * 起不来不算失败 —— 回 status 让页面把原因写在状态行上，而不是弹一个崩溃。
     * 切后台不停核心：后台可能正在播音频，一停就是断流。进程被杀核心自然没了，
     * 不需要 onTerminate（它本来也不保证被调）。
     */


    static JSONObject proxyBoot(Context context, JSONObject out) throws Exception {
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
    /**
     * 拉订阅并解析，返回节点列表（**不测速**）。
     *
     * 为什么和测速分成两条命令：拉订阅是必成的（几秒），测速是可选的
     * （20 秒上限）。合在一起的话用户想「先看看有多少个节点」也得等 20 秒。
     */
    static JSONObject proxySubscribe(Context context, String url, JSONObject out) throws Exception {
        String target = url == null ? "" : url.trim();
        if (target.length() == 0) {
            // 没给地址就用上次的。设置页重进一次不用再粘一遍。
            target = proxyStore(context).getString(SUB_URL_KEY, "");
            if (target == null) target = "";
        }
        if (target.length() == 0) {
            return UserSource.fail(out, "还没有订阅地址");
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

        UserSource.log("订阅 " + target.substring(0, Math.min(50, target.length()))
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
    static JSONArray describeNodes(Subscription.Result parsed) throws Exception {
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
     * 忘掉导入过的订阅，回到「用内置免费节点」。
     *
     * 和 clear 分开：clear 清的是**节点分享链接**（用户手填的那条），
     * 这条清的是**订阅地址**。两者存在不同键里，混用会让「改回用免费节点」
     * 顺手把用户辛苦挑的那个节点也删了。
     */
    static JSONObject proxySubClear(Context context, JSONObject out) throws Exception {
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
    static JSONObject proxyNodes(Context context, String what, JSONObject out) throws Exception {
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
    static void autoRefreshNodes(final Context context) {
        if (!NodesFeed.due(context)) return;
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    NodesFeed.Status status = NodesFeed.refresh(context);
                    if (status.ok) {
                        UserSource.log("节点池已更新：" + status.nodes + " 条（" + status.updated + "）");
                    } else {
                        UserSource.log("节点池更新失败：" + status.error);
                    }
                } catch (Throwable e) {
                    UserSource.log("节点池更新异常：" + e.getClass().getSimpleName());
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
    static JSONObject proxySpeed(Context context, String batchText, JSONObject out) throws Exception {
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
            return UserSource.fail(out, reason != null && reason.length() > 0 ? reason
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
        if (done) UserSource.log("测速完成，本轮共测 " + listSize());
        return speedResult(out, done, fromSubscription, parsed.size());
    }
    /**
     * 对上次拉到的订阅里的节点批量测速。
     *
     * 结果按下标返回，`use?index=N` 用的就是这个下标。
     * **不用排序后的下标** —— 排序只是给 UI 看的建议顺序，下标一旦被排序
     * 打乱，前端拿它回查就会错位。
     */








    static int listSize() {
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
    static JSONObject speedResult(JSONObject out, boolean done,
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
    static JSONObject proxyUse(Context context, String indexText, JSONObject out) throws Exception {
        List<SpeedTest.Item> items = lastSpeedTest;
        if (items == null || items.isEmpty()) {
            return UserSource.fail(out, "还没有测速结果，先测一次");
        }
        int index;
        try {
            index = Integer.parseInt(indexText == null ? "" : indexText.trim());
        } catch (NumberFormatException e) {
            return UserSource.fail(out, "节点下标不是数字");
        }
        SpeedTest.Item hit = null;
        for (SpeedTest.Item item : items) {
            if (item.index == index) {
                hit = item;
                break;
            }
        }
        if (hit == null) return UserSource.fail(out, "这个下标不在测速结果里");
        if (!hit.ok()) return UserSource.fail(out, "这个节点没测通，换一个");
        if (hit.link == null || hit.link.length() == 0) return UserSource.fail(out, "这个节点没有原始链接");

        return proxyStart(context, hit.link, null, out);
    }
    /** 前端的 node JSON → XrayConfig.Node。字段名和 XrayConfig 前向生成时一致。 */
    static XrayConfig.Node nodeFromJson(JSONObject json) {
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
}
