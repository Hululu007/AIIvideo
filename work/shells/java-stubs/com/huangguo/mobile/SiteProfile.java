package com.huangguo.mobile;

import org.json.JSONObject;

/**
 * 编译期桩，不打包。
 *
 * SiteProfile 是 APK 里的既有类，javac 看不到它。这里只声明 AiiHttp 真正调用的
 * 那几个签名，让 javac 能过；d8 只编译 java-src/，这个文件不会进 dex。
 *
 * 签名必须和 smali 里的一致，否则运行期会 NoSuchMethodError：
 *   SiteProfile.smali: .method object(Ljava/lang/String;)Lorg/json/JSONObject;
 * 真实方法包级可见，AiiHttp 同包，调得到。
 */
public class SiteProfile {
    /** smali 里是 `.field final origin:Ljava/lang/String;` —— 包级可见，同包直接读。 */
    public String origin;
    public SiteProfile(android.content.Context context, JSONObject rules) throws Exception {}

    public JSONObject object(String key) { return null; }

    public String rule(String key) { return null; }
}
