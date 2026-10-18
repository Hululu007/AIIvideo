package com.huangguo.mobile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 适配器注册表 —— 全工程唯一一份名单。
 *
 * 加站点动这里一行；识别、分流、校验、白名单全部从这里读，
 * 不再有第二份、第三份拷贝跟着漂。
 */
final class Adapters {

    /** 顺序即优先级：先按地址认，认不出来才按响应形状认。 */
    static final List<SiteAdapter> ALL = Collections.unmodifiableList(Arrays.<SiteAdapter>asList(
            new AgedmSite(),
            new Hanime1Site(),
            new Mrds66Site(),
            new Porn91Site(),
            new OpenListSite(),
            new CmsSite()));

    private Adapters() {}

    static SiteAdapter byId(String id) {
        if (id == null) return null;
        for (int i = 0; i < ALL.size(); i++) {
            if (ALL.get(i).id().equals(id)) return ALL.get(i);
        }
        return null;
    }

    static List<String> ids() {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < ALL.size(); i++) out.add(ALL.get(i).id());
        return out;
    }

    /** 当前源用的是哪个适配器。没有 adapter 字段（REST 源）返回 null。 */
    static SiteAdapter of(SiteProfile profile) {
        return profile == null ? null : byId(profile.rule("adapter"));
    }

    /**
     * 把 URL 归一化成 `/api/...` 这条内部动作路径。不是动作路径返回 null。
     *
     * 归一化的原因见 AiiHttp 里那段「详情页 404」：详情走 SiteRepository.detail()，
     * 那里会把相对路径拼上 origin，进来时已经是绝对地址 —— 不剥掉 origin，
     * 所有 startsWith("/api/") 都落空，请求真的发到 `<origin>/api/videos/xxx`。
     */
    static String actionPath(String url, SiteProfile profile) {
        if (url == null) return null;
        if (url.startsWith("/api/")) return url;
        String relative = stripOrigin(url, profile);
        return relative != null && relative.startsWith("/api/") ? relative : null;
    }

    /** 去掉 url 前面的 origin。不是这个 origin 的返回 null。 */
    static String stripOrigin(String url, SiteProfile profile) {
        if (url == null || profile == null) return null;
        String origin = profile.origin;
        if (origin == null || origin.length() == 0) return null;
        if (!url.startsWith(origin)) return null;
        String rest = url.substring(origin.length());
        if (rest.length() == 0 || rest.charAt(0) != '/') return null;
        return rest;
    }
}
