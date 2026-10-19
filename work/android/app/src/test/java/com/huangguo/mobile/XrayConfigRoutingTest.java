package com.huangguo.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@link XrayConfig#routing} 的分流规则测试。
 *
 * <p>规则顺序是这份配置的行为契约：①api 直连 → ②源声明域走代理 → ③geosite:cn
 * 直连 → ④DirectHosts 白名单直连 → ⑤兜底走代理。次序错了的表现是「源声明了
 * 要代理的域被 geosite:cn 拦去直连」，而那种域恰恰常挂国内 CDN，错了很难看出。
 */
public class XrayConfigRoutingTest {

    @After
    public void resetRoute() {
        NetRoute.configure(null, null);
    }

    private static JSONObject routing(String netJson) throws Exception {
        NetRoute.configure(netJson == null ? null : new JSONObject(netJson), "https://example.org");
        return XrayConfig.build(node(), 20480).getJSONObject("routing");
    }

    private static XrayConfig.Node node() {
        XrayConfig.Node n = new XrayConfig.Node();
        n.protocol = "socks";
        n.server = "192.0.2.1";
        n.port = 1080;
        n.uuid = "x";
        return n;
    }

    /** 规则里第一条 outboundTag=tag 的 domain 规则的域列表。 */
    private static JSONArray firstDomainRule(JSONObject routing, String tag) throws Exception {
        JSONArray rules = routing.getJSONArray("rules");
        for (int i = 0; i < rules.length(); i++) {
            JSONObject r = rules.getJSONObject(i);
            if (r.has("domain") && tag.equals(r.getString("outboundTag"))) return r.getJSONArray("domain");
        }
        return null;
    }

    private static int indexOfNetworkRule(JSONObject routing, String tag) throws Exception {
        JSONArray rules = routing.getJSONArray("rules");
        for (int i = 0; i < rules.length(); i++) {
            JSONObject r = rules.getJSONObject(i);
            if (r.has("network") && tag.equals(r.getString("outboundTag"))) return i;
        }
        return -1;
    }

    @Test
    public void apiRuleIsAlwaysFirst() throws Exception {
        JSONObject routing = routing(null);
        JSONObject first = routing.getJSONArray("rules").getJSONObject(0);
        assertEquals("direct", first.getString("outboundTag"));
        assertEquals("api", first.getJSONArray("inboundTag").getString(0));
    }

    @Test
    public void geositeCnDirectRuleAlwaysPresent() throws Exception {
        JSONObject routing = routing(null);
        JSONArray domains = firstDomainRule(routing, "direct");
        assertNotNull(domains);
        boolean hit = false;
        for (int i = 0; i < domains.length(); i++) {
            if ("geosite:cn".equals(domains.getString(i))) hit = true;
        }
        assertTrue("缺 geosite:cn 直连规则", hit);
    }

    @Test
    public void declaredHostsGoProxyAndOverrideGeo() throws Exception {
        JSONObject routing = routing("{\"proxy\":[\"yhdm.one\",\"Entry.Example.com\"]}");
        JSONArray domains = firstDomainRule(routing, "proxy");
        assertNotNull("声明了 net.proxy 却没有 proxy 域名规则", domains);
        // origin（example.org）自动兜底加入，大小写归一。
        boolean origin = false, declared = false;
        for (int i = 0; i < domains.length(); i++) {
            String d = domains.getString(i);
            if (d.equals("domain:yhdm.one")) declared = true;
            if (d.equals("domain:example.org")) origin = true;
        }
        assertTrue("声明域缺失", declared);
        assertTrue("origin 没被兜底加入", origin);
        // 顺序契约：proxy 域规则必须出现在 geosite:cn 直连规则之前。
        JSONArray rules = routing.getJSONArray("rules");
        int proxyAt = -1, geoAt = -1;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject r = rules.getJSONObject(i);
            if (r.has("domain") && "proxy".equals(r.getString("outboundTag"))) proxyAt = i;
            if (r.has("domain") && "direct".equals(r.getString("outboundTag"))) {
                JSONArray d = r.getJSONArray("domain");
                for (int k = 0; k < d.length(); k++) {
                    if ("geosite:cn".equals(d.getString(k))) { geoAt = i; break; }
                }
            }
        }
        assertTrue("proxy 域规则必须先于 geosite:cn", proxyAt != -1 && geoAt != -1 && proxyAt < geoAt);
    }

    @Test
    public void noDeclaredHostsMeansNoProxyDomainRule() throws Exception {
        JSONObject routing = routing(null);
        assertTrue(firstDomainRule(routing, "proxy") == null);
    }

    @Test
    public void fallbackRuleSendsEverythingElseToProxy() throws Exception {
        JSONObject routing = routing(null);
        int at = indexOfNetworkRule(routing, "proxy");
        assertTrue("缺兜底规则", at >= 0);
        // 兜底必须是最后一条。
        assertEquals(routing.getJSONArray("rules").length() - 1, at);
        assertEquals("tcp,udp", routing.getJSONArray("rules").getJSONObject(at).getString("network"));
    }

    @Test
    public void declaredProxyHostsSnapshotIsACopy() throws Exception {
        NetRoute.configure(new JSONObject("{\"proxy\":[\"a.com\"]}"), "https://x.org");
        List<String> snapshot = NetRoute.declaredProxyHosts();
        snapshot.clear();
        assertTrue("返回的不是副本，调用方能改到内部表", NetRoute.declaredProxyHosts().size() > 0);
    }

    @Test
    public void buildRejectsBadPort() {
        try {
            XrayConfig.build(node(), 0);
            fail("端口 0 应当被拒");
        } catch (Exception expected) {
        }
    }
}
