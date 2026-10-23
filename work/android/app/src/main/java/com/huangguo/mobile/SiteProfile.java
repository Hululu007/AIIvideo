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

    /**
     * 生产路径：内置模板（assets/site-default.json）+ 用户配置，合并后交给下面那个构造器。
     *
     * 只有这一处需要 Context —— 读 asset 和读 files/ 下的配置。把它和「解释规则」
     * 拆成两个构造器，是为了那份解释逻辑（origin/封面/net 那一串校验）能在 JVM 上
     * 直接被验：单测里没有 AssetManager，构造不出一个能 open("site-default.json")
     * 的 Context，而这份逻辑最该被守住。
     */
    SiteProfile(Context context, JSONObject jSONObject) throws Exception {
        this(template(context), SourceConfig.has(context) ? SourceConfig.read(context) : jSONObject);
    }

    /** 内置模板。校验收到的配置时要用它做底，不能拿盘上那份当底。 */
    static JSONObject template(Context context) throws Exception {
        return new JSONObject(new String(
                Assets.read(context.getAssets().open("site-default.json"), 131072), StandardCharsets.UTF_8));
    }

    /**
     * 拿一份**还没落盘**的配置建 profile，用来校验「装上去会不会起不来」。
     *
     * <p>2026-10-23 修：原来 {@code verify()} 走的是上面那个 (Context, JSONObject) 构造，
     * 而它在盘上有配置时会**忽略传进来的那份**、改读盘上的 —— 于是校验永远是「旧的
     * 那份还能不能用」，新配置怎么写都过。实测后果：把 cover.transformation 写成
     * 一个不支持的值照样装进去，下次启动 SiteProfile 抛异常、App 退回内置模板，
     * 用户看到的是「源还在，但封面域名不对」。
     */
    static SiteProfile candidate(Context context, JSONObject config) throws Exception {
        return new SiteProfile(template(context), config);
    }

    /**
     * 当前生效配置，**不抛**。
     *
     * <p>用户配置是一个可以直接编辑、也会被装源流程写坏的文件。它坏了的时候，
     * 正确行为是退回内置模板让应用能启动，而不是在 {@code onCreate} 里抛 ——
     * 那表现就是「点开图标闪一下就没了」，用户连设置页都进不去，也就没法自己修。
     *
     * <p>热更层拆掉之后这里是唯一的取配置入口（原来绕一圈走
     * {@code HotUpdateManager.currentSite()}）。
     */
    static SiteProfile current(Context context) {
        try {
            return new SiteProfile(context, null);
        } catch (Throwable broken) {
            UserSource.log("站点配置读不出来，退回内置模板：" + broken);
            try {
                return new SiteProfile(
                        new JSONObject(new String(Assets.read(
                                context.getAssets().open("site-default.json"), 131072), StandardCharsets.UTF_8)),
                        new JSONObject());
            } catch (Throwable fatal) {
                throw new IllegalStateException("内置站点模板读不出来", fatal);
            }
        }
    }

    /**
     * 规则层：模板 + 用户配置合并，然后解释。
     *
     * 合并这一步是 spider 源能成立的地方：merge 只对「两边都是对象」的键递归，
     * 其余一律直接覆盖/塞进去 —— 所以用户配置里一个 {@code web.spider} 会原样
     * 出现在合并结果里，SpiderStore 那条桥拿到的就是这个块。
     */
    SiteProfile(JSONObject siteDefault, JSONObject userConfig) throws Exception {
        JSONObject jSONObjectMerge = merge(siteDefault, userConfig == null ? new JSONObject() : userConfig);
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
        // 网络路由跟着源走。这是整个 App 里唯一一处「当前源长什么样」的组装点，
        // 不在这儿装就得在每个源里各装一遍 —— 那份名单注定漂。
        NetRoute.configure(jSONObjectMerge.optJSONObject("net"), strReplaceAll);
        // 播放层出口的主机白名单同理：原来是两行硬编码在 PlaybackHttpBridge 里，
        // 换个源或换个播放线路就得改 Java。现在由源自己声明（http.hosts），
        // 位置就在路由旁边 —— 两个都是「这个源能碰哪些域名」，读一处就够。
        PlaybackHttpBridge.configure(jSONObjectMerge.optJSONObject("http"));
        // 过盾这件事也跟源走（web.shield）。装在这里的理由和上面两条一样：
        // 这是全 App 唯一一处「当前源长什么样」的组装点，散到各调用方去装必然漂。
        ShieldProfile.install(ShieldProfile.parse(jSONObjectMerge));
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
