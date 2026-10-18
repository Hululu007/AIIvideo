package com.huangguo.mobile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 直连域名表 —— 代理分流的最小实现（2026-10-18）。
 *
 * ── 为什么要这张表 ─────────────────────────────────────────────────
 * 代理三档开关（直连/内置核心/外部地址）是全局的：一开内置核心，所有源的
 * 流量都走那条节点。91porn / ASMR / 每日大赛确实需要代理，但 AGE动漫 /
 * 黄果这些直连本来就通的源也被拖着走 —— 节点一抖就报
 * SSLHandshakeException / unexpected end of stream（实测 jx.wuzhoupai.com:8443
 * 直连 0.17s，走荷兰节点 4.6s 后 TLS 被掐）。
 *
 * Clash 那套按规则分流我们没有，Xray 侧也只有一条 api 规则。这张表是
 * 「显式域名表」版分流：表里的域名不管代理开不开，一律直连。没有
 * geoip/geosite（assets 里没带 .dat），所以只能写显式域名 —— 新接一个
 * 直连可用的源就往这儿加一条。
 *
 * ── 谁来读这张表 ──────────────────────────────────────────────────
 * 三个出口，共用同一份：
 *   1. AiiHttp      rawWith / getOnce 建 client 时按 host 查表，命中就不挂代理
 *   2. VlcRelay     wrap() 对命中域名直接返回 null，VLC 原始地址自己拉，省掉中继一跳
 *   3. XrayConfig   routing() 把表生成成 domain: 规则 → direct 出站
 *
 * 出口 1 和 2 覆盖外部代理模式（流量不经过 Xray）；出口 3 覆盖内置核心
 * 模式下从 Xray 本地 SOCKS 进来的流量。
 */
final class DirectHosts {

    /**
     * 值是给维护者看的一句理由，不参与匹配。后缀匹配：命中条目本身或其
     * 任意子域（endsWith("." + entry)）。
     */
    private static final Map<String, String> HOSTS = new LinkedHashMap<>();

    static {
        // AGE动漫：接口 + 播放解析（UserSource.HTTP_HOSTS 那两个）+ 备用 playback 源
        HOSTS.put("agedm.io",        "AGE动漫 全家（api./m./www.）");
        HOSTS.put("jx.wuzhoupai.com","AGE动漫 播放解析（:8443 自签，:443 可用）");
        HOSTS.put("xcdn.khfed.com",  "AGE动漫 解析出的播放器资源 CDN");
        HOSTS.put("bfzyapi.com",     "AGE动漫 playback 备用（苹果CMS 暴风）");
        // 黄果自己的服务器：账号/片单/头像 + 入口 origin
        HOSTS.put("149.104.21.177.sslip.io", "黄果 API_BASE（Endpoints.java）");
        HOSTS.put("cab3f.nt17g20k.cc",       "黄果 当前入口 origin");
        HOSTS.put("huangguoai.com",          "黄果 备用入口（被污染，DoH 兜着）");

        // ── 樱花动漫（yhdm）的第三方采集站 CDN ────────────────────────
        //
        // 2026-10-18 实测（真机 7f8f5254，内置核心开在荷兰节点）：
        // 这一族 CDN 全部是**国内直连更快、走外网节点反而 TLS 被掐**。
        //
        //   bfikuncdn.com      直连 200 / 走代理 200（但分片域 kkzycdn.com 走代理 000）
        //   hn.bfvvs.com       直连 200 / 走代理 VARIANT_FAIL
        //   hd.kuktxu.com      直连 200 / 走代理 VARIANT_FAIL
        //   v1.ppqrrs.com      直连 403（根路径拒，真实路径可用）/ 走代理 MASTER_FAIL
        //   v13.rstu6.com      直连 403 / 走代理 MASTER_FAIL
        //   vod12.wgslsw.com   直连 403 / 走代理 MASTER_FAIL
        //
        // 症状和 AGE动漫 那轮一模一样：接口通、列表出来、点开播放 403 或
        // SSLHandshakeException。区别只是这次是 12 条线路里 11 条被拖死。
        //
        // 站点换采集站比换域名勤，这张表注定不全 —— 原来适配器在挑线路时会把
        // **探测成功的分片域名**运行时补进来（YhdmAdapter#probeLine），那条补表
        // 机制随适配器一起删了，现在这里就是全部依据：表里没有的就落回
        // 「没声明 net 的时候跟全局开关」那条老规则。
        String[][] yhdmCdn = {
            { "bfikuncdn.com",  "樱花 线路 ikzy 主列表 + 分片" },
            { "kkzycdn.com",    "樱花 ikzy 的分片域（:65 端口）" },
            { "modujx17.com",   "樱花 线路 mdzy（主 + 分片）" },
            { "bfvvs.com",      "樱花 线路 hnzy" },
            { "kuktxu.com",     "樱花 线路 jyzy" },
            { "jisuzyv.com",    "樱花 线路 jszy" },
            { "lzcdn31.com",    "樱花 线路 lzzy" },
            { "ffzy-plays.com", "樱花 线路 ffzy" },
            { "xluuss.com",     "樱花 线路 xlzy" },
            { "gsuus.com",      "樱花 线路 gszy" },
            { "gszyi.com",      "樱花 gszy 的分片域（:999 端口）" },
            { "ukubf3.com",     "樱花 线路 ukzy" },
            { "ppqrrs.com",     "樱花 线路 wjzy2" },
            { "rstu6.com",      "樱花 线路 snzy" },
            { "wgslsw.com",     "樱花 线路 yhzy" },
            { "feifei-play.com","樱花 线路 ffzy 的镜像" },
        };
        for (String[] row : yhdmCdn) HOSTS.put(row[0], row[1]);
    }

    /**
     * 运行时补一条直连域名。
     *
     * 为什么要动态：樱花动漫的播放地址来自十几个第三方采集站，域名随线路轮换，
     * 静态表永远追不上。适配器在挑线路时会实测每个分片域名「直连能不能拿到
     * 字节」，能就把域名扔进来 —— 之后 VlcRelay.wrap 见到它直接返回 null
     * （VLC 自己去连，连本地中继那一跳都省了），AiiHttp 也不再给它挂代理。
     *
     * 只加不减：一次探测通过不代表永远通，但不加一定慢/不通。判断成本远高于
     * 误判成本，所以宁可留着。
     *
     * 注意 **Xray 的 routing 规则是核心启动时生成的**，这里加的条目对内置核心
     * 那条出口不生效 —— 但没关系：VlcRelay 见到直连域名会绕开中继、VLC 直接连
     * 目标，本来就不过 Xray。
     */
    static void add(String host, String why) {
        if (host == null) return;
        String h = host.toLowerCase().trim();
        if (h.length() == 0) return;
        synchronized (HOSTS) {
            if (!HOSTS.containsKey(h)) HOSTS.put(h, why);
        }
    }

    private DirectHosts() {}

    /** host 命中直连表（含子域后缀匹配）。 */
    static boolean isDirect(String host) {
        if (host == null || host.length() == 0) return false;
        String h = host.toLowerCase().trim();
        // 快照遍历：适配器会在别的线程 add()，直接遍历 keySet 会撞
        // ConcurrentModificationException（这张表以前是只读的，现在不是了）。
        java.util.List<String> snapshot;
        synchronized (HOSTS) { snapshot = new java.util.ArrayList<>(HOSTS.keySet()); }
        for (String entry : snapshot) {
            if (h.equals(entry) || h.endsWith("." + entry)) return true;
        }
        return false;
    }

    /** 生成 Xray routing 用的 domain: 规则值列表（Xray 自带子域匹配语义）。 */
    static java.util.List<String> domainRules() {
        java.util.List<String> rules = new java.util.ArrayList<>();
        synchronized (HOSTS) {
            for (String entry : HOSTS.keySet()) rules.add("domain:" + entry);
        }
        return rules;
    }
}
