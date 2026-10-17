package com.huangguo.mobile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 批量测速：从一堆节点里挑出真正能用的。
 *
 * ── 为什么需要它 ──
 * 公开的免费订阅里「TCP 可达」和「真能翻墙」是两回事。实测过一批 423 条的：
 * 上游用 TCP connect 判出 313 条可达，而真实 HTTPS 请求能过的只有一成多。
 * 差的那些是「有服务在监听但已经被墙」的节点 —— 用户点上去只会转圈。
 *
 * 所以给用户的列表必须**现场测**，不能信订阅自带的标签。
 *
 * ── 怎么测 ──
 * 逐个换节点启动核心会互相打架（一个进程只有一个核心实例），而给 400 个节点
 * 每个都 start/stop 一轮要十几分钟。所以：
 *
 *   1. 用 ProxyCore.measureOutboundDelay 的**无副作用版本** —— 它拿配置和 URL
 *      单独发起一次连接，不碰正在跑的核心。见 ProxyCore.probe(node, url)。
 *   2. 并发跑，线程数受限于设备（手机上 6 个已经很凶了）。
 *   3. **总耗时有硬上限**，到点就返回已测出来的那部分，剩下的标成未测。
 *
 * 上限的存在比测全更重要：用户点「测速」是要在几秒内看到结果，不是等五分钟。
 */
final class SpeedTest {

    /**
     * 并发线程数。
     *
     * **现在恒为 1（串行）。** 这个常量留着只是为了说明「曾经试过并发，不行」——
     * 见 run() 里的注释：并发会让 measureOutboundDelay 互相踩断（closed pipe）。
     * 谁要是想改回并发，先解决那个问题。
     */
    private static final int THREADS = 1;

    /**
     * 单个节点的超时。串行跑，这个值直接决定「一轮能测几个」。
     * 3 秒：慢但能用的节点一般 1~2 秒返回，3 秒还回不来的基本是死的。
     */
    private static final int PER_NODE_MS = 3000;

    /** 整轮硬上限。到点返回已完成的，剩下的标 untested。 */
    static final int WALL_CLOCK_MS = 30000;

    private SpeedTest() {}

    /** 一条测速结果。 */
    static final class Item {
        final int index;
        final XrayConfig.Node node;
        final String link;
        /** >0 是往返毫秒；<=0 表示失败或没测。 */
        final long ms;
        /** 空串表示成功；否则是给人看的原因。 */
        final String error;

        Item(int index, XrayConfig.Node node, String link, long ms, String error) {
            this.index = index;
            this.node = node;
            this.link = link;
            this.ms = ms;
            this.error = error == null ? "" : error;
        }

        boolean ok() {
            return ms > 0;
        }

        /** 给人看的一行。**不含凭据** —— 这份结果会进 logcat。 */
        String describe() {
            return (ok() ? ms + "ms" : "不通") + "  " + node.describe();
        }
    }

    interface Progress {
        /** done/total 已出结果。在测速线程上回调，实现方自己切主线程。 */
        void onProgress(int done, int total);
    }

    /**
     * 跑一轮。返回的顺序和传入的一一对应，方便前端按下标取回原文链接。
     *
     * 不抛异常：测速失败是常态，把它当成结果的一部分返回。
     */
    static List<Item> run(List<XrayConfig.Node> nodes, List<String> links, String probeUrl, Progress progress) {
        int total = nodes.size();
        List<Item> out = new ArrayList<Item>(total);
        if (total == 0) return out;

        final String url = probeUrl == null || probeUrl.isEmpty()
                ? ProxyCore.PROBE_URL : probeUrl;
        final long deadline = System.currentTimeMillis() + WALL_CLOCK_MS;
        final AtomicInteger done = new AtomicInteger();

        // ── 必须串行 ──────────────────────────────────────────────────
        // 2026-10-18 实测：6 线程并发跑 measureOutboundDelay，15 个节点全部报
        //   proxyerror ... io: read/write on closed pipe
        // 一个都没测出来。AAR 里那次测量走的是核心内部共享的一条管道，
        // 多个调用同时进行会互相把对方的连接关掉。
        //
        // 串行之后每个 1~2 秒（不通的 6 秒封顶），所以数量必须限量 ——
        // 20 秒上限内大约能测 10~12 个。这不是遗憾，是正确的取舍：
        // 用户要的是「几个能用的」，不是「400 个都体检一遍」。
        for (int i = 0; i < total; i++) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                out.add(untested(i, nodes.get(i), linkOf(links, i)));
                continue;
            }
            out.add(probeOne(i, nodes.get(i), linkOf(links, i), url, deadline));
            int d = done.incrementAndGet();
            if (progress != null) {
                try {
                    progress.onProgress(d, total);
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    private static String linkOf(List<String> links, int i) {
        return links != null && i < links.size() ? links.get(i) : "";
    }

    private static Item probeOne(int index, XrayConfig.Node node, String link, String url, long deadline) {
        long left = deadline - System.currentTimeMillis();
        if (left <= 0) return untested(index, node, link);
        try {
            long ms = ProxyCore.probe(node, url, (int) Math.min(left, PER_NODE_MS));
            if (ms > 0) return new Item(index, node, link, ms, "");
            return new Item(index, node, link, -1, "不通");
        } catch (Throwable e) {
            String m = e.getMessage();
            if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
            return new Item(index, node, link, -1, trim(m));
        }
    }

    private static Item untested(int index, XrayConfig.Node node, String link) {
        return new Item(index, node, link, 0, "没测完（超时）");
    }

    private static String trim(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    /** 按延迟升序，不通的排最后。前端拿这个顺序直接渲染。 */
    static void sortBySpeed(List<Item> items) {
        Collections.sort(items, new java.util.Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                if (a.ok() != b.ok()) return a.ok() ? -1 : 1;
                return Long.compare(a.ms, b.ms);
            }
        });
    }
}
