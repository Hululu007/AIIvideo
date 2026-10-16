.class final Lcom/huangguo/mobile/DualContentRepository;
.super Ljava/lang/Object;
.source "DualContentRepository.java"


# 红果源已整块移除（2026-10-16）。
#
# 这个类原本是「双源分发」：privateMode() 为真走 SiteRepository（黄果），
# 为假走 HongguoRepository（红果）。现在只剩黄果，每个方法直接转发。
#
# 为什么不干脆删掉这个类、让 MainActivity 直接用 SiteRepository：
# MainActivity 的 dispatch 里有 13 处 invoke 指向这里，而那是个按编号跳的
# packed-switch（索引和上面的 sparse-switch 哈希表耦合）。改那两张表是本项目
# 最容易出事的地方，留一层薄壳就一行都不用动。
#
# 顺带说明红果为什么删得掉：它没坏 —— 站点 HTTP 200、字段形状实测还对得上、
# hongguotv 那 47 个类也都在。但它是**没接线**：前端改造后所有动作都算成
# /api/ 路径，而红果要的是「动作名 + series id」，两套调用约定对不上。
# 而它那套实现（SSR 抓 HTML + Signer 签名 + AesV3 解密）恰好是我们前几轮
# 从 SiteRepository 里整块删掉的架构，留着跟现在的方向是矛盾的。

# instance fields
.field private final huangguo:Lcom/huangguo/mobile/SiteRepository;

.field private final mode:Lcom/huangguo/mobile/ContentMode;

.field private final source:Ljava/lang/String;


# direct methods
.method constructor <init>(Landroid/content/Context;)V
    .locals 2

    invoke-static {p1}, Lcom/huangguo/mobile/ContentMode;->get(Landroid/content/Context;)Lcom/huangguo/mobile/ContentMode;

    move-result-object v0

    invoke-virtual {v0}, Lcom/huangguo/mobile/ContentMode;->active()Ljava/lang/String;

    move-result-object v0

    invoke-static {p1}, Lcom/huangguo/mobile/HotUpdateManager;->get(Landroid/content/Context;)Lcom/huangguo/mobile/HotUpdateManager;

    move-result-object v1

    invoke-virtual {v1}, Lcom/huangguo/mobile/HotUpdateManager;->currentSite()Lcom/huangguo/mobile/SiteProfile;

    move-result-object v1

    invoke-direct {p0, p1, v0, v1}, Lcom/huangguo/mobile/DualContentRepository;-><init>(Landroid/content/Context;Ljava/lang/String;Lcom/huangguo/mobile/SiteProfile;)V

    return-void
.end method

.method constructor <init>(Landroid/content/Context;Ljava/lang/String;Lcom/huangguo/mobile/SiteProfile;)V
    .locals 0

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    iput-object p2, p0, Lcom/huangguo/mobile/DualContentRepository;->source:Ljava/lang/String;

    invoke-static {p1}, Lcom/huangguo/mobile/ContentMode;->get(Landroid/content/Context;)Lcom/huangguo/mobile/ContentMode;

    move-result-object p2

    iput-object p2, p0, Lcom/huangguo/mobile/DualContentRepository;->mode:Lcom/huangguo/mobile/ContentMode;

    new-instance p2, Lcom/huangguo/mobile/SiteRepository;

    invoke-direct {p2, p1, p3}, Lcom/huangguo/mobile/SiteRepository;-><init>(Landroid/content/Context;Lcom/huangguo/mobile/SiteProfile;)V

    iput-object p2, p0, Lcom/huangguo/mobile/DualContentRepository;->huangguo:Lcom/huangguo/mobile/SiteRepository;

    return-void
.end method

.method category(Ljava/lang/String;I)Lorg/json/JSONObject;
    .locals 1

    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    iget-object p0, p0, Lcom/huangguo/mobile/DualContentRepository;->huangguo:Lcom/huangguo/mobile/SiteRepository;

    invoke-virtual {p0, p1, p2}, Lcom/huangguo/mobile/SiteRepository;->category(Ljava/lang/String;I)Lorg/json/JSONObject;

    move-result-object p0

    return-object p0
.end method

.method clearCovers()V
    .locals 1

    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    iget-object p0, p0, Lcom/huangguo/mobile/DualContentRepository;->huangguo:Lcom/huangguo/mobile/SiteRepository;

    invoke-virtual {p0}, Lcom/huangguo/mobile/SiteRepository;->clearCovers()V

    return-void
.end method

.method coverData(Ljava/lang/String;)Ljava/lang/String;
    .locals 1

    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    iget-object p0, p0, Lcom/huangguo/mobile/DualContentRepository;->huangguo:Lcom/huangguo/mobile/SiteRepository;

    invoke-virtual {p0, p1}, Lcom/huangguo/mobile/SiteRepository;->coverData(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    return-object p0
.end method

.method detail(Ljava/lang/String;)Lorg/json/JSONObject;
    .locals 1

    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    iget-object p0, p0, Lcom/huangguo/mobile/DualContentRepository;->huangguo:Lcom/huangguo/mobile/SiteRepository;

    invoke-virtual {p0, p1}, Lcom/huangguo/mobile/SiteRepository;->detail(Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object p0

    return-object p0
.end method

.method episode(Ljava/lang/String;I)Lorg/json/JSONObject;
    .locals 1

    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    iget-object p0, p0, Lcom/huangguo/mobile/DualContentRepository;->huangguo:Lcom/huangguo/mobile/SiteRepository;

    invoke-virtual {p0, p1, p2}, Lcom/huangguo/mobile/SiteRepository;->episode(Ljava/lang/String;I)Lorg/json/JSONObject;

    move-result-object p0

    return-object p0
.end method

.method home()Lorg/json/JSONObject;
    .locals 1

    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    iget-object p0, p0, Lcom/huangguo/mobile/DualContentRepository;->huangguo:Lcom/huangguo/mobile/SiteRepository;

    invoke-virtual {p0}, Lcom/huangguo/mobile/SiteRepository;->home()Lorg/json/JSONObject;

    move-result-object p0

    return-object p0
.end method

.method recommend(IZ)Lorg/json/JSONObject;
    .locals 1

    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    iget-object p0, p0, Lcom/huangguo/mobile/DualContentRepository;->huangguo:Lcom/huangguo/mobile/SiteRepository;

    invoke-virtual {p0, p1, p2}, Lcom/huangguo/mobile/SiteRepository;->recommend(IZ)Lorg/json/JSONObject;

    move-result-object p0

    return-object p0
.end method

.method search(Ljava/lang/String;)Lorg/json/JSONObject;
    .locals 1

    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    iget-object p0, p0, Lcom/huangguo/mobile/DualContentRepository;->huangguo:Lcom/huangguo/mobile/SiteRepository;

    invoke-virtual {p0, p1}, Lcom/huangguo/mobile/SiteRepository;->search(Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object p0

    return-object p0
.end method

.method sequelCandidates(Lorg/json/JSONObject;)Lorg/json/JSONArray;
    .locals 1

    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    # 原本是红果专属：用 SequelMatcher 按标题找续集，而私密模式（黄果）
    # 直接返回空数组。红果删掉后没有实现方，保持返回空 —— 那本来就是
    # 黄果模式下的行为，前端拿到的结果不变。
    new-instance p0, Lorg/json/JSONArray;

    invoke-direct {p0}, Lorg/json/JSONArray;-><init>()V

    return-object p0
.end method

.method tagListing(Ljava/lang/String;I)Lorg/json/JSONObject;
    .locals 1

    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    iget-object p0, p0, Lcom/huangguo/mobile/DualContentRepository;->huangguo:Lcom/huangguo/mobile/SiteRepository;

    invoke-virtual {p0, p1, p2}, Lcom/huangguo/mobile/SiteRepository;->tagListing(Ljava/lang/String;I)Lorg/json/JSONObject;

    move-result-object p0

    return-object p0
.end method

.method tags()Lorg/json/JSONObject;
    .locals 1

    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    iget-object p0, p0, Lcom/huangguo/mobile/DualContentRepository;->huangguo:Lcom/huangguo/mobile/SiteRepository;

    invoke-virtual {p0}, Lcom/huangguo/mobile/SiteRepository;->tags()Lorg/json/JSONObject;

    move-result-object p0

    return-object p0
.end method
