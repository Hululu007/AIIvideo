package com.huangguo.mobile;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 双内容源仓库。
 *
 * 名字里的「双」指的是对外一个门面、对内可以有两套数据来源：这个类同时握着
 * {@link ContentMode}（当前是公开还是私密）和 {@link SiteRepository}（黄果源的
 * REST 拉取 + 封面缓存），构造时把 mode.active() 读到自己的 source 字段里锁住。
 * 上层 MainActivity 只在模式切换时重建一份（rebindContent），所以一次实例生命周期
 * 内的 source 不会变，请求不会串源。
 *
 * 公开源那半边（HongguoRepository）连同 privateMode() 的分派已经整类去掉了：
 * 现在前端只跑黄果源这一条链路，所有方法直接落到 SiteRepository。source 和 mode
 * 两个字段暂时没被方法体用到，保留是因为门禁语义还在 ContentMode 里，桥指令那边
 * 仍然按 source 校验，随时可以把公开源接回来。
 */
final class DualContentRepository {
    private final SiteRepository huangguo;
    private final ContentMode mode;
    private final String source;

    DualContentRepository(Context context) {
        this(context, ContentMode.get(context).active(), HotUpdateManager.get(context).currentSite());
    }

    DualContentRepository(Context context, String source, SiteProfile siteProfile) {
        this.source = source;
        this.mode = ContentMode.get(context);
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
