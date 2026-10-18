package com.huangguo.mobile;

import android.content.Context;
import android.util.Base64;
import com.huangguo.mobile.Text;
import androidx.webkit.ProxyConfig;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

final class SiteProfile {
    final String fingerprint;
    final String origin;
    private final JSONObject rules;

    String rule(String str) {
        return this.rules.optString(str);
    }

    SiteProfile(Context context, JSONObject jSONObject) throws Exception {
        jSONObject = SourceConfig.has(context) ? SourceConfig.read(context) : jSONObject;
        JSONObject jSONObjectMerge = merge(new JSONObject(new String(HotUpdateManager.read(context.getAssets().open("site-default.json"), 131072), StandardCharsets.UTF_8)), jSONObject == null ? new JSONObject() : jSONObject);
        this.rules = jSONObjectMerge;
        String strReplaceAll = jSONObjectMerge.getString("origin").replaceAll("/+$", "");
        this.origin = strReplaceAll;
        if (!strReplaceAll.isEmpty()) {
            URI uri = new URI(strReplaceAll);
            if (!ProxyConfig.MATCH_HTTPS.equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || !uri.getPath().isEmpty()) {
                throw new IllegalArgumentException("站点必须是 HTTPS 域名");
            }
        }
        JSONArray jSONArray = object("cover").getJSONArray("allowedHosts");
        if (jSONArray.length() == 0) {
            throw new IllegalArgumentException("缺少封面域名");
        }
        for (int i = 0; i < jSONArray.length(); i++) {
            if (!jSONArray.getString(i).matches("[a-zA-Z0-9.-]+")) {
                throw new IllegalArgumentException("无效封面域名");
            }
        }
        String string = object("cover").getString("mode");
        if (!Arrays.asList("plain", "aes-cbc", Text.TEXT_EMPHASIS_AUTO).contains(string)) {
            throw new IllegalArgumentException("不支持的封面模式");
        }
        if (!Arrays.asList("AES/CBC/NoPadding", "AES/CBC/PKCS5Padding").contains(object("cover").getString("transformation"))) {
            throw new IllegalArgumentException("不支持的解密方式");
        }
        if (!"plain".equals(string) && ((keyBytes("key").length != 16 && keyBytes("key").length != 24 && keyBytes("key").length != 32) || keyBytes("iv").length != 16)) {
            throw new IllegalArgumentException("无效封面密钥或 IV");
        }
        // 让适配器给旧配置补缺省块（纯本地）。必须在 NetRoute.configure 之前 ——
        // 它补的正是 net 块，顺序反了就等于没补。
        String adapterId = jSONObjectMerge.optString("adapter", "");
        if (adapterId.length() > 0) {
            SiteAdapter impl = Adapters.byId(adapterId);
            if (impl != null) {
                try { impl.patch(jSONObjectMerge); } catch (Throwable e) {
                    UserSource.log("适配器补配置失败（" + adapterId + "）：" + e);
                }
            }
        }

        // 网络路由跟着源走。这是整个 App 里唯一一处「当前源长什么样」的组装点，
        // 不在这儿装就得在每个适配器里各装一遍 —— 那份名单注定漂。
        NetRoute.configure(jSONObjectMerge.optJSONObject("net"), strReplaceAll);
        // 播放层出口的主机白名单同理：原来是两行硬编码在 PlaybackHttpBridge 里，
        // 换个源或换个播放线路就得改 Java。现在由源自己声明（http.hosts），
        // 位置就在路由旁边 —— 两个都是「这个源能碰哪些域名」，读一处就够。
        PlaybackHttpBridge.configure(jSONObjectMerge.optJSONObject("http"));
        this.fingerprint = hex(MessageDigest.getInstance("SHA-256").digest(this.rules.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static JSONObject merge(JSONObject jSONObject, JSONObject jSONObject2) throws Exception {
        JSONObject jSONObject3 = new JSONObject(jSONObject.toString());
        Iterator<String> itKeys = jSONObject2.keys();
        while (itKeys.hasNext()) {
            String next = itKeys.next();
            Object obj = jSONObject2.get(next);
            if ((obj instanceof JSONObject) && (jSONObject3.opt(next) instanceof JSONObject)) {
                jSONObject3.put(next, merge(jSONObject3.getJSONObject(next), (JSONObject) obj));
            } else {
                jSONObject3.put(next, obj);
            }
        }
        return jSONObject3;
    }

    JSONObject object(String str) {
        return this.rules.optJSONObject(str);
    }

    byte[] keyBytes(String str) {
        String strOptString = object("cover").optString(str);
        String strOptString2 = object("cover").optString("keyEncoding", "text");
        if ("base64".equals(strOptString2)) {
            return Base64.decode(strOptString, 0);
        }
        if ("hex".equals(strOptString2)) {
            if (!strOptString.matches("(?:[0-9a-fA-F]{2})+")) {
                throw new IllegalArgumentException("无效十六进制密钥");
            }
            int length = strOptString.length() / 2;
            byte[] bArr = new byte[length];
            for (int i = 0; i < length; i++) {
                int i2 = i * 2;
                bArr[i] = (byte) Integer.parseInt(strOptString.substring(i2, i2 + 2), 16);
            }
            return bArr;
        }
        if (!"text".equals(strOptString2)) {
            throw new IllegalArgumentException("不支持的密钥编码");
        }
        return strOptString.getBytes(StandardCharsets.UTF_8);
    }

    String coverUrl(String str) throws Exception {
        URI uri = new URI(str);
        String host = uri.getHost();
        if (!ProxyConfig.MATCH_HTTPS.equals(uri.getScheme()) || host == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("无效封面地址");
        }
        JSONObject jSONObjectOptJSONObject = object("cover").optJSONObject("hostRewrites");
        if (jSONObjectOptJSONObject != null && jSONObjectOptJSONObject.has(host)) {
            String string = jSONObjectOptJSONObject.getString(host);
            uri = new URI(ProxyConfig.MATCH_HTTPS, null, string, uri.getPort(), uri.getPath(), uri.getQuery(), null);
            host = string;
        }
        JSONArray jSONArray = object("cover").getJSONArray("allowedHosts");
        for (int i = 0; i < jSONArray.length(); i++) {
            if (host.equalsIgnoreCase(jSONArray.getString(i))) {
                return uri.toString();
            }
        }
        throw new IllegalArgumentException("无效封面域名");
    }

    boolean patchHeader(byte[] bArr) throws Exception {
        JSONArray jSONArrayOptJSONArray = object("cover").optJSONArray("bytePatches");
        boolean z = false;
        for (int i = 0; jSONArrayOptJSONArray != null && i < jSONArrayOptJSONArray.length(); i++) {
            JSONObject jSONObject = jSONArrayOptJSONArray.getJSONObject(i);
            int i2 = jSONObject.getInt("offset");
            if (i2 < 0 || i2 >= bArr.length) {
                throw new IllegalArgumentException("封面补丁越界");
            }
            if ((bArr[i2] & 255) == jSONObject.getInt("from")) {
                z |= (bArr[i2] & 255) != jSONObject.getInt("to");
                bArr[i2] = (byte) jSONObject.getInt("to");
            }
        }
        return z;
    }

    static String hex(byte[] bArr) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bArr) {
            sb.append(String.format(Locale.ROOT, "%02x", Integer.valueOf(b & 255)));
        }
        return sb.toString();
    }
}
