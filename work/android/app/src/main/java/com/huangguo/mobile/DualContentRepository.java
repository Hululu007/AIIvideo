package com.huangguo.mobile;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 双内容源仓库。
 *
 * 「双」这个名字是历史遗留：以前它握着一个 {@code ContentMode}（公开 / 私密两态）
 * 和两套仓库。2026-10-19 双态整块拆掉，现在只剩一条链路 —— {@link SiteRepository}
 * （当前源的 REST 拉取 + 封面缓存）。
 *
 * 类名没改：改它要动 MainActivity 的字段类型和几处构造，收益只是少两个字。
 * 上层仍在换源之后调用 {@code rebindContent()} 重建一份，因为 SiteProfile
 * （paths / cover / 白名单）是构造时读的。
 */
final class DualContentRepository {
    private final SiteRepository huangguo;

    DualContentRepository(Context context) {
        this(context, SiteProfile.current(context));
    }

    DualContentRepository(Context context, SiteProfile siteProfile) {
        this.huangguo = new SiteRepository(context, siteProfile);
    }

    /** 控制面（源/播放器/代理/进度/HTTP）要用的 profile。转发给底下的 SiteRepository。 */
    SiteProfile profileForControl() {
        return this.huangguo.profileForControl();
    }

    void clearCovers() throws Exception {
        this.huangguo.clearCovers();
    }

    String coverData(String url) throws Exception {
        return this.huangguo.coverData(url);
    }

    JSONObject detail(String id) throws Exception {
        return this.huangguo.detail(id);
    }

    /** 续集推荐：黄果源没有这个接口，恒返回空数组（调用方自己兜住）。 */
    JSONArray sequelCandidates(JSONObject detail) throws Exception {
        return new JSONArray();
    }
}
