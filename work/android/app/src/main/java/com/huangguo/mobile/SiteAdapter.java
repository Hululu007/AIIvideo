package com.huangguo.mobile;

import org.json.JSONObject;

/**
 * 一个站点类型的适配器。
 *
 * 这一层的存在只有一个目的：**加一个站点不再改 UserSource**。
 * 以前 load() 里是一串 `if (isXxx(url)) return wrapXxx(url);`，AiiHttp 里
 * 是同一串的第二份、第三份拷贝，validate 里还有第四份名单 —— 四份名单各自漂移，
 * 已经漂过一次（porn91 能跑但过不了校验）。
 *
 * 现在：新增站点 = 写一个实现类 + 在 {@link Adapters#ALL} 里加一行。
 * 识别、生成配置、/api/ 分流、必填项校验，四件事都在同一个类里。
 */
interface SiteAdapter {

    /** 配置里的 adapter 字段值。也是校验时的白名单来源。 */
    String id();

    /** 按地址认。认不出来返回 false，交给下一个。 */
    default boolean matchesUrl(String url) { return false; }

    /** 拉回来之后按内容形状认（苹果CMS 这种没固定域名的）。 */
    default boolean matchesBody(JSONObject body) { return false; }

    /** 按地址生成配置。 */
    default JSONObject probe(String url) throws Exception {
        throw new java.io.IOException("这个适配器不支持按地址识别：" + id());
    }

    /** 拿到响应之后再生成配置（可以复用已经拉下来的那份，少一次往返）。 */
    default JSONObject probe(String url, JSONObject body) throws Exception { return probe(url); }

    /** 把 `/api/...` 这个内部动作路径翻译成真实请求。没接管的适配器不许被调用。 */
    default byte[] fetch(SiteProfile profile, String path) throws Exception {
        throw new UnsupportedOperationException("适配器没有实现 /api/ 分流：" + id());
    }

    /** 这个适配器对配置的必填项要求。返回 null 表示通过。 */
    default String check(JSONObject config) { return null; }

    /**
     * 这个源的「id 空间」—— 观看历史靠它判断两个源能不能互通。
     *
     * id 只在源内部唯一，所以真正决定互通的是「id 会被翻到哪儿去」。
     * 每站的翻法不一样，只有适配器自己知道。返回 null 表示交给通用的 REST 推导。
     */
    default String identity(JSONObject config) { return null; }
}
