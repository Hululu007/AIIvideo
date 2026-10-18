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
    }

    private DirectHosts() {}

    /** host 命中直连表（含子域后缀匹配）。 */
    static boolean isDirect(String host) {
        if (host == null || host.length() == 0) return false;
        String h = host.toLowerCase().trim();
        for (String entry : HOSTS.keySet()) {
            if (h.equals(entry) || h.endsWith("." + entry)) return true;
        }
        return false;
    }

    /** 生成 Xray routing 用的 domain: 规则值列表（Xray 自带子域匹配语义）。 */
    static java.util.List<String> domainRules() {
        java.util.List<String> rules = new java.util.ArrayList<>();
        for (String entry : HOSTS.keySet()) rules.add("domain:" + entry);
        return rules;
    }
}
