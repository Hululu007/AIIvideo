.class final Lcom/huangguo/mobile/SiteRepository;
.super Ljava/lang/Object;
.source "SiteRepository.java"


# instance fields
.field private final appContext:Landroid/content/Context;

.field private final coverDir:Ljava/io/File;

.field private final profile:Lcom/huangguo/mobile/SiteProfile;


# direct methods
.method home()Lorg/json/JSONObject;
    .locals 2
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    # 抓 HTML 的实现已整块移除（2026-10-16）。数据层现在全走 /api/ REST，
    # 这条路没有入口 —— 前端 apiPathFor() 会给这些动作算出 /api/ 路径，
    # 走的是 detail/search/category 那几个 REST 分支。
    #
    # 方法签名保留是因为 DualContentRepository 还在调它们，删了会在类加载期
    # NoSuchMethodError。留着壳子并抛异常，比返回 null 让人在别处碰 NPE 好定位。
    new-instance v0, Ljava/lang/UnsupportedOperationException;
    const-string v1, "home 已改为纯 REST，抓 HTML 的分支已移除"
    invoke-direct {v0, v1}, Ljava/lang/UnsupportedOperationException;-><init>(Ljava/lang/String;)V

    throw v0
.end method

.method category(Ljava/lang/String;I)Lorg/json/JSONObject;
    .locals 2
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    # 抓 HTML 的实现已整块移除（2026-10-16）。数据层现在全走 /api/ REST，
    # 这条路没有入口 —— 前端 apiPathFor() 会给这些动作算出 /api/ 路径，
    # 走的是 detail/search/category 那几个 REST 分支。
    #
    # 方法签名保留是因为 DualContentRepository 还在调它们，删了会在类加载期
    # NoSuchMethodError。留着壳子并抛异常，比返回 null 让人在别处碰 NPE 好定位。
    new-instance v0, Ljava/lang/UnsupportedOperationException;
    const-string v1, "category 已改为纯 REST，抓 HTML 的分支已移除"
    invoke-direct {v0, v1}, Ljava/lang/UnsupportedOperationException;-><init>(Ljava/lang/String;)V

    throw v0
.end method

.method episode(Ljava/lang/String;I)Lorg/json/JSONObject;
    .locals 2
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    # 抓 HTML 的实现已整块移除（2026-10-16）。数据层现在全走 /api/ REST，
    # 这条路没有入口 —— 前端 apiPathFor() 会给这些动作算出 /api/ 路径，
    # 走的是 detail/search/category 那几个 REST 分支。
    #
    # 方法签名保留是因为 DualContentRepository 还在调它们，删了会在类加载期
    # NoSuchMethodError。留着壳子并抛异常，比返回 null 让人在别处碰 NPE 好定位。
    new-instance v0, Ljava/lang/UnsupportedOperationException;
    const-string v1, "episode 已改为纯 REST，抓 HTML 的分支已移除"
    invoke-direct {v0, v1}, Ljava/lang/UnsupportedOperationException;-><init>(Ljava/lang/String;)V

    throw v0
.end method

.method recommend(IZ)Lorg/json/JSONObject;
    .locals 2
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    # 抓 HTML 的实现已整块移除（2026-10-16）。数据层现在全走 /api/ REST，
    # 这条路没有入口 —— 前端 apiPathFor() 会给这些动作算出 /api/ 路径，
    # 走的是 detail/search/category 那几个 REST 分支。
    #
    # 方法签名保留是因为 DualContentRepository 还在调它们，删了会在类加载期
    # NoSuchMethodError。留着壳子并抛异常，比返回 null 让人在别处碰 NPE 好定位。
    new-instance v0, Ljava/lang/UnsupportedOperationException;
    const-string v1, "recommend 已改为纯 REST，抓 HTML 的分支已移除"
    invoke-direct {v0, v1}, Ljava/lang/UnsupportedOperationException;-><init>(Ljava/lang/String;)V

    throw v0
.end method

.method search(Ljava/lang/String;)Lorg/json/JSONObject;
    .locals 2
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    # 抓 HTML 的实现已整块移除（2026-10-16）。数据层现在全走 /api/ REST，
    # 这条路没有入口 —— 前端 apiPathFor() 会给这些动作算出 /api/ 路径，
    # 走的是 detail/search/category 那几个 REST 分支。
    #
    # 方法签名保留是因为 DualContentRepository 还在调它们，删了会在类加载期
    # NoSuchMethodError。留着壳子并抛异常，比返回 null 让人在别处碰 NPE 好定位。
    new-instance v0, Ljava/lang/UnsupportedOperationException;
    const-string v1, "search 已改为纯 REST，抓 HTML 的分支已移除"
    invoke-direct {v0, v1}, Ljava/lang/UnsupportedOperationException;-><init>(Ljava/lang/String;)V

    throw v0
.end method

.method tagListing(Ljava/lang/String;I)Lorg/json/JSONObject;
    .locals 2
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    # 抓 HTML 的实现已整块移除（2026-10-16）。数据层现在全走 /api/ REST，
    # 这条路没有入口 —— 前端 apiPathFor() 会给这些动作算出 /api/ 路径，
    # 走的是 detail/search/category 那几个 REST 分支。
    #
    # 方法签名保留是因为 DualContentRepository 还在调它们，删了会在类加载期
    # NoSuchMethodError。留着壳子并抛异常，比返回 null 让人在别处碰 NPE 好定位。
    new-instance v0, Ljava/lang/UnsupportedOperationException;
    const-string v1, "tagListing 已改为纯 REST，抓 HTML 的分支已移除"
    invoke-direct {v0, v1}, Ljava/lang/UnsupportedOperationException;-><init>(Ljava/lang/String;)V

    throw v0
.end method

.method tags()Lorg/json/JSONObject;
    .locals 2
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    # 抓 HTML 的实现已整块移除（2026-10-16）。数据层现在全走 /api/ REST，
    # 这条路没有入口 —— 前端 apiPathFor() 会给这些动作算出 /api/ 路径，
    # 走的是 detail/search/category 那几个 REST 分支。
    #
    # 方法签名保留是因为 DualContentRepository 还在调它们，删了会在类加载期
    # NoSuchMethodError。留着壳子并抛异常，比返回 null 让人在别处碰 NPE 好定位。
    new-instance v0, Ljava/lang/UnsupportedOperationException;
    const-string v1, "tags 已改为纯 REST，抓 HTML 的分支已移除"
    invoke-direct {v0, v1}, Ljava/lang/UnsupportedOperationException;-><init>(Ljava/lang/String;)V

    throw v0
.end method

.method constructor <init>(Landroid/content/Context;)V
    .locals 1

    .line 38
    invoke-static {p1}, Lcom/huangguo/mobile/HotUpdateManager;->get(Landroid/content/Context;)Lcom/huangguo/mobile/HotUpdateManager;

    move-result-object v0

    invoke-virtual {v0}, Lcom/huangguo/mobile/HotUpdateManager;->currentSite()Lcom/huangguo/mobile/SiteProfile;

    move-result-object v0

    invoke-direct {p0, p1, v0}, Lcom/huangguo/mobile/SiteRepository;-><init>(Landroid/content/Context;Lcom/huangguo/mobile/SiteProfile;)V

    return-void
.end method

.method constructor <init>(Landroid/content/Context;Lcom/huangguo/mobile/SiteProfile;)V
    .locals 1

    .line 40
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    .line 41
    iput-object p2, p0, Lcom/huangguo/mobile/SiteRepository;->profile:Lcom/huangguo/mobile/SiteProfile;

    .line 42
    invoke-virtual {p1}, Landroid/content/Context;->getApplicationContext()Landroid/content/Context;

    move-result-object p2

    iput-object p2, p0, Lcom/huangguo/mobile/SiteRepository;->appContext:Landroid/content/Context;

    .line 43
    new-instance p2, Ljava/io/File;

    invoke-virtual {p1}, Landroid/content/Context;->getCacheDir()Ljava/io/File;

    move-result-object p1

    const-string v0, "covers"

    invoke-direct {p2, p1, v0}, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V

    iput-object p2, p0, Lcom/huangguo/mobile/SiteRepository;->coverDir:Ljava/io/File;

    .line 44
    invoke-virtual {p2}, Ljava/io/File;->exists()Z

    move-result p0

    if-nez p0, :cond_0

    invoke-virtual {p2}, Ljava/io/File;->mkdirs()Z

    :cond_0
    return-void
.end method




.method private fetch(Ljava/lang/String;)[B
    .locals 2
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    # 原来这里有 289 行 HttpURLConnection 的直连逻辑（拼 URL、套超时、遍历 headers
    # 模板、读流）。整块搬到 AiiHttp（java-src/com/huangguo/mobile/AiiHttp.java），
    # 由 build.sh 编译成 classes5.dex。搬走的理由是那里要处理两件 HttpURLConnection
    # 做不到的事：
    #   1. 自定义 DNS —— Java 没有公开的 resolver 钩子，而入口域名的系统解析被污染
    #   2. SNI 保留 —— 直连 IP 会被 Cloudflare 以 handshake failure 拒掉
    # 顺带还做了多 IP 重试：入口域名对应五个地址，实测其中一个是死的。
    iget-object v0, p0, Lcom/huangguo/mobile/SiteRepository;->profile:Lcom/huangguo/mobile/SiteProfile;

    invoke-static {p1, v0}, Lcom/huangguo/mobile/AiiHttp;->get(Ljava/lang/String;Lcom/huangguo/mobile/SiteProfile;)[B

    move-result-object v1

    return-object v1
.end method



.method private repairConfiguredHeader([B)Z
    .locals 3
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    .line 353
    iget-object v0, p0, Lcom/huangguo/mobile/SiteRepository;->profile:Lcom/huangguo/mobile/SiteProfile;

    const-string v1, "cover"

    invoke-virtual {v0, v1}, Lcom/huangguo/mobile/SiteProfile;->object(Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object v0

    const-string v1, "headerRepair"

    invoke-virtual {v0, v1}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v0

    const-string v1, "legacy-xor"

    invoke-virtual {v1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v0

    const/4 v1, 0x1

    const/4 v2, 0x0

    if-eqz v0, :cond_0

    invoke-static {p1}, Lcom/huangguo/mobile/SiteRepository;->repairCoverHeader([B)Z

    move-result v0

    if-eqz v0, :cond_0

    move v0, v1

    goto :goto_0

    :cond_0
    move v0, v2

    .line 354
    :goto_0
    iget-object p0, p0, Lcom/huangguo/mobile/SiteRepository;->profile:Lcom/huangguo/mobile/SiteProfile;

    invoke-virtual {p0, p1}, Lcom/huangguo/mobile/SiteProfile;->patchHeader([B)Z

    move-result p0

    if-nez v0, :cond_2

    if-eqz p0, :cond_1

    goto :goto_1

    :cond_1
    return v2

    :cond_2
    :goto_1
    return v1
.end method

.method private static repairCoverHeader([B)Z
    .locals 15

    .line 361
    array-length v0, p0

    const/4 v1, 0x0

    const/16 v2, 0xc

    if-ge v0, v2, :cond_0

    return v1

    .line 362
    :cond_0
    aget-byte v0, p0, v1

    const/16 v3, -0x77

    const/16 v4, 0x47

    const/4 v5, 0x2

    const/16 v6, 0xa

    const/4 v7, 0x7

    const/4 v8, 0x3

    const/4 v9, 0x6

    const/4 v10, 0x1

    if-ne v0, v3, :cond_1

    aget-byte v3, p0, v10

    const/16 v11, 0x50

    if-ne v3, v11, :cond_1

    aget-byte v3, p0, v5

    const/16 v11, 0x4e

    if-ne v3, v11, :cond_1

    aget-byte v3, p0, v8

    if-ne v3, v4, :cond_1

    const/4 v3, 0x4

    aget-byte v3, p0, v3

    const/16 v11, 0xd

    if-ne v3, v11, :cond_1

    const/4 v3, 0x5

    aget-byte v3, p0, v3

    if-ne v3, v6, :cond_1

    aget-byte v3, p0, v9

    const/16 v11, 0x14

    if-ne v3, v11, :cond_1

    aget-byte v3, p0, v7

    if-ne v3, v6, :cond_1

    move v3, v10

    goto :goto_0

    :cond_1
    move v3, v1

    :goto_0
    const/4 v11, -0x1

    if-ne v0, v11, :cond_2

    .line 364
    aget-byte v0, p0, v10

    const/16 v12, -0x28

    if-ne v0, v12, :cond_2

    aget-byte v0, p0, v5

    if-ne v0, v11, :cond_2

    move v0, v10

    goto :goto_1

    :cond_2
    move v0, v1

    :goto_1
    const/16 v5, 0x9

    const/16 v11, 0x8

    if-eqz v0, :cond_3

    .line 365
    aget-byte v12, p0, v8

    const/16 v13, -0x20

    if-ne v12, v13, :cond_3

    aget-byte v12, p0, v9

    const/16 v13, 0x44

    if-ne v12, v13, :cond_3

    aget-byte v12, p0, v7

    const/16 v13, 0x46

    if-ne v12, v13, :cond_3

    aget-byte v12, p0, v11

    const/16 v14, 0x49

    if-ne v12, v14, :cond_3

    aget-byte v12, p0, v5

    if-ne v12, v13, :cond_3

    aget-byte v6, p0, v6

    if-nez v6, :cond_3

    move v6, v10

    goto :goto_2

    :cond_3
    move v6, v1

    :goto_2
    if-eqz v0, :cond_4

    .line 367
    aget-byte v12, p0, v8

    const/16 v13, -0x25

    if-ne v12, v13, :cond_4

    aget-byte v12, p0, v9

    and-int/lit16 v12, v12, 0xfc

    if-ne v12, v2, :cond_4

    move v2, v10

    goto :goto_3

    :cond_4
    move v2, v1

    :goto_3
    if-eqz v0, :cond_5

    .line 368
    aget-byte v12, p0, v8

    const/16 v13, -0x1f

    if-ne v12, v13, :cond_5

    aget-byte v12, p0, v9

    const/16 v13, 0x4b

    if-ne v12, v13, :cond_5

    aget-byte v12, p0, v7

    const/16 v13, 0x78

    if-ne v12, v13, :cond_5

    aget-byte v12, p0, v11

    const/16 v13, 0x69

    if-ne v12, v13, :cond_5

    aget-byte v12, p0, v5

    const/16 v13, 0x66

    if-ne v12, v13, :cond_5

    move v12, v10

    goto :goto_4

    :cond_5
    move v12, v1

    :goto_4
    if-eqz v0, :cond_6

    .line 370
    aget-byte v0, p0, v8

    const/16 v8, -0x1e

    if-ne v0, v8, :cond_6

    aget-byte v0, p0, v9

    if-ne v0, v4, :cond_6

    aget-byte v0, p0, v7

    const/16 v4, 0x43

    if-ne v0, v4, :cond_6

    aget-byte v0, p0, v11

    if-ne v0, v4, :cond_6

    aget-byte v0, p0, v5

    const/16 v4, 0x5f

    if-ne v0, v4, :cond_6

    move v0, v10

    goto :goto_5

    :cond_6
    move v0, v1

    :goto_5
    if-nez v3, :cond_7

    if-nez v6, :cond_7

    if-nez v2, :cond_7

    if-nez v12, :cond_7

    if-nez v0, :cond_7

    return v1

    .line 373
    :cond_7
    aget-byte v0, p0, v9

    xor-int/lit8 v0, v0, 0xe

    int-to-byte v0, v0

    aput-byte v0, p0, v9

    return v10
.end method


.method private trimCoverCache()V
    .locals 9

    .line 388
    iget-object p0, p0, Lcom/huangguo/mobile/SiteRepository;->coverDir:Ljava/io/File;

    invoke-virtual {p0}, Ljava/io/File;->listFiles()[Ljava/io/File;

    move-result-object p0

    if-nez p0, :cond_0

    goto :goto_2

    .line 391
    :cond_0
    array-length v0, p0

    const/4 v1, 0x0

    const-wide/16 v2, 0x0

    move v4, v1

    :goto_0
    if-ge v4, v0, :cond_1

    aget-object v5, p0, v4

    invoke-virtual {v5}, Ljava/io/File;->length()J

    move-result-wide v5

    add-long/2addr v2, v5

    add-int/lit8 v4, v4, 0x1

    goto :goto_0

    :cond_1
    const-wide/32 v4, 0x9600000

    cmp-long v0, v2, v4

    if-gtz v0, :cond_2

    goto :goto_2

    .line 393
    :cond_2
    new-instance v0, Lcom/huangguo/mobile/SiteRepository$$ExternalSyntheticLambda0;

    invoke-direct {v0}, Lcom/huangguo/mobile/SiteRepository$$ExternalSyntheticLambda0;-><init>()V

    invoke-static {v0}, Ljava/util/Comparator;->comparingLong(Ljava/util/function/ToLongFunction;)Ljava/util/Comparator;

    move-result-object v0

    invoke-static {p0, v0}, Ljava/util/Arrays;->sort([Ljava/lang/Object;Ljava/util/Comparator;)V

    .line 394
    array-length v0, p0

    :goto_1
    if-ge v1, v0, :cond_5

    aget-object v6, p0, v1

    cmp-long v7, v2, v4

    if-gtz v7, :cond_3

    goto :goto_2

    .line 396
    :cond_3
    invoke-virtual {v6}, Ljava/io/File;->length()J

    move-result-wide v7

    .line 397
    invoke-virtual {v6}, Ljava/io/File;->delete()Z

    move-result v6

    if-eqz v6, :cond_4

    sub-long/2addr v2, v7

    :cond_4
    add-int/lit8 v1, v1, 0x1

    goto :goto_1

    :cond_5
    :goto_2
    return-void
.end method

.method private static validImage([B)Z
    .locals 11

    .line 378
    array-length v0, p0

    const/16 v1, 0x10

    const/4 v2, 0x0

    if-ge v0, v1, :cond_0

    return v2

    .line 379
    :cond_0
    aget-byte v0, p0, v2

    const/4 v1, -0x1

    const/4 v3, 0x1

    if-ne v0, v1, :cond_1

    aget-byte v1, p0, v3

    const/16 v4, -0x28

    if-eq v1, v4, :cond_4

    :cond_1
    const/16 v1, -0x77

    const/4 v4, 0x3

    const/16 v5, 0x47

    const/16 v6, 0x50

    const/16 v7, 0xa

    const/4 v8, 0x2

    if-ne v0, v1, :cond_2

    aget-byte v1, p0, v3

    if-ne v1, v6, :cond_2

    aget-byte v1, p0, v8

    const/16 v9, 0x4e

    if-ne v1, v9, :cond_2

    aget-byte v1, p0, v4

    if-ne v1, v5, :cond_2

    const/4 v1, 0x4

    aget-byte v1, p0, v1

    const/16 v9, 0xd

    if-ne v1, v9, :cond_2

    const/4 v1, 0x5

    aget-byte v1, p0, v1

    if-ne v1, v7, :cond_2

    const/4 v1, 0x6

    aget-byte v1, p0, v1

    const/16 v9, 0x1a

    if-ne v1, v9, :cond_2

    const/4 v1, 0x7

    aget-byte v1, p0, v1

    if-eq v1, v7, :cond_4

    :cond_2
    const/16 v1, 0x52

    const/16 v9, 0x49

    const/16 v10, 0x46

    if-ne v0, v1, :cond_3

    aget-byte v1, p0, v3

    if-ne v1, v9, :cond_3

    aget-byte v1, p0, v8

    if-ne v1, v10, :cond_3

    aget-byte v1, p0, v4

    if-ne v1, v10, :cond_3

    const/16 v1, 0x8

    aget-byte v1, p0, v1

    const/16 v4, 0x57

    if-ne v1, v4, :cond_3

    const/16 v1, 0x9

    aget-byte v1, p0, v1

    const/16 v4, 0x45

    if-ne v1, v4, :cond_3

    aget-byte v1, p0, v7

    const/16 v4, 0x42

    if-ne v1, v4, :cond_3

    const/16 v1, 0xb

    aget-byte v1, p0, v1

    if-eq v1, v6, :cond_4

    :cond_3
    if-ne v0, v5, :cond_5

    aget-byte v0, p0, v3

    if-ne v0, v9, :cond_5

    aget-byte p0, p0, v8

    if-ne p0, v10, :cond_5

    :cond_4
    return v3

    :cond_5
    return v2
.end method


# virtual methods

.method clearCovers()V
    .locals 3

    .line 402
    iget-object p0, p0, Lcom/huangguo/mobile/SiteRepository;->coverDir:Ljava/io/File;

    invoke-virtual {p0}, Ljava/io/File;->listFiles()[Ljava/io/File;

    move-result-object p0

    if-eqz p0, :cond_0

    .line 403
    array-length v0, p0

    const/4 v1, 0x0

    :goto_0
    if-ge v1, v0, :cond_0

    aget-object v2, p0, v1

    invoke-virtual {v2}, Ljava/io/File;->delete()Z

    add-int/lit8 v1, v1, 0x1

    goto :goto_0

    :cond_0
    return-void
.end method

.method coverData(Ljava/lang/String;)Ljava/lang/String;
    .locals 9
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    .line 312
    iget-object v0, p0, Lcom/huangguo/mobile/SiteRepository;->profile:Lcom/huangguo/mobile/SiteProfile;

    invoke-virtual {v0, p1}, Lcom/huangguo/mobile/SiteProfile;->coverUrl(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p1

    .line 313
    new-instance v0, Ljava/net/URL;

    invoke-direct {v0, p1}, Ljava/net/URL;-><init>(Ljava/lang/String;)V

    .line 314
    invoke-virtual {v0}, Ljava/net/URL;->getPath()Ljava/lang/String;

    move-result-object v1

    const/16 v2, 0x2e

    .line 315
    invoke-virtual {v1, v2}, Ljava/lang/String;->lastIndexOf(I)I

    move-result v2

    const/4 v3, 0x1

    add-int/2addr v2, v3

    invoke-virtual {v1, v2}, Ljava/lang/String;->substring(I)Ljava/lang/String;

    move-result-object v2

    sget-object v4, Ljava/util/Locale;->ROOT:Ljava/util/Locale;

    invoke-virtual {v2, v4}, Ljava/lang/String;->toLowerCase(Ljava/util/Locale;)Ljava/lang/String;

    move-result-object v2

    .line 316
    iget-object v4, p0, Lcom/huangguo/mobile/SiteRepository;->profile:Lcom/huangguo/mobile/SiteProfile;

    const-string v5, "cover"

    invoke-virtual {v4, v5}, Lcom/huangguo/mobile/SiteProfile;->object(Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object v4

    const-string v6, "checkExtension"

    invoke-virtual {v4, v6, v3}, Lorg/json/JSONObject;->optBoolean(Ljava/lang/String;Z)Z

    move-result v3

    if-eqz v3, :cond_1

    const-string v3, "webp"

    const-string v4, "gif"

    const-string v6, "jpg"

    const-string v7, "jpeg"

    const-string v8, "png"

    filled-new-array {v6, v7, v8, v3, v4}, [Ljava/lang/String;

    move-result-object v3

    invoke-static {v3}, Ljava/util/Arrays;->asList([Ljava/lang/Object;)Ljava/util/List;

    move-result-object v3

    invoke-interface {v3, v2}, Ljava/util/List;->contains(Ljava/lang/Object;)Z

    move-result v2

    if-eqz v2, :cond_0

    goto :goto_0

    :cond_0
    new-instance p0, Ljava/lang/IllegalArgumentException;

    const-string p1, "\u4e0d\u652f\u6301\u7684\u5c01\u9762\u683c\u5f0f"

    invoke-direct {p0, p1}, Ljava/lang/IllegalArgumentException;-><init>(Ljava/lang/String;)V

    throw p0

    .line 317
    :cond_1
    :goto_0
    const-string v2, "SHA-256"

    invoke-static {v2}, Ljava/security/MessageDigest;->getInstance(Ljava/lang/String;)Ljava/security/MessageDigest;

    move-result-object v2

    .line 318
    new-instance v3, Ljava/lang/StringBuilder;

    invoke-direct {v3}, Ljava/lang/StringBuilder;-><init>()V

    iget-object v4, p0, Lcom/huangguo/mobile/SiteRepository;->profile:Lcom/huangguo/mobile/SiteProfile;

    iget-object v4, v4, Lcom/huangguo/mobile/SiteProfile;->fingerprint:Ljava/lang/String;

    invoke-virtual {v3, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const-string v4, ":"

    invoke-virtual {v3, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v0}, Ljava/net/URL;->getHost()Ljava/lang/String;

    move-result-object v0

    invoke-virtual {v3, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v3, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v3}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v0

    sget-object v1, Ljava/nio/charset/StandardCharsets;->UTF_8:Ljava/nio/charset/Charset;

    invoke-virtual {v0, v1}, Ljava/lang/String;->getBytes(Ljava/nio/charset/Charset;)[B

    move-result-object v0

    invoke-virtual {v2, v0}, Ljava/security/MessageDigest;->digest([B)[B

    move-result-object v0

    .line 319
    new-instance v1, Ljava/lang/StringBuilder;

    invoke-direct {v1}, Ljava/lang/StringBuilder;-><init>()V

    const/4 v2, 0x0

    move v3, v2

    :goto_1
    const/16 v4, 0x10

    if-ge v3, v4, :cond_2

    .line 320
    aget-byte v4, v0, v3

    and-int/lit16 v4, v4, 0xff

    invoke-static {v4}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

    move-result-object v4

    filled-new-array {v4}, [Ljava/lang/Object;

    move-result-object v4

    const-string v6, "%02x"

    invoke-static {v6, v4}, Ljava/lang/String;->format(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;

    move-result-object v4

    invoke-virtual {v1, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    add-int/lit8 v3, v3, 0x1

    goto :goto_1

    .line 321
    :cond_2
    new-instance v0, Ljava/io/File;

    iget-object v3, p0, Lcom/huangguo/mobile/SiteRepository;->coverDir:Ljava/io/File;

    new-instance v4, Ljava/lang/StringBuilder;

    invoke-direct {v4}, Ljava/lang/StringBuilder;-><init>()V

    invoke-virtual {v4, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;

    const-string v1, ".img"

    invoke-virtual {v4, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v4}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v1

    invoke-direct {v0, v3, v1}, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V

    .line 323
    invoke-virtual {v0}, Ljava/io/File;->isFile()Z

    move-result v1

    const/4 v3, 0x0

    if-eqz v1, :cond_5

    .line 324
    invoke-virtual {v0}, Ljava/io/File;->toPath()Ljava/nio/file/Path;

    move-result-object v1

    invoke-static {v1}, Ljava/nio/file/Files;->readAllBytes(Ljava/nio/file/Path;)[B

    move-result-object v1

    .line 325
    invoke-direct {p0, v1}, Lcom/huangguo/mobile/SiteRepository;->repairConfiguredHeader([B)Z

    move-result v4

    if-eqz v4, :cond_3

    .line 326
    new-instance v4, Ljava/io/FileOutputStream;

    invoke-direct {v4, v0}, Ljava/io/FileOutputStream;-><init>(Ljava/io/File;)V

    :try_start_0
    invoke-virtual {v4, v1}, Ljava/io/FileOutputStream;->write([B)V
    :try_end_0
    .catchall {:try_start_0 .. :try_end_0} :catchall_0

    invoke-virtual {v4}, Ljava/io/FileOutputStream;->close()V

    goto :goto_3

    :catchall_0
    move-exception p0

    :try_start_1
    invoke-virtual {v4}, Ljava/io/FileOutputStream;->close()V
    :try_end_1
    .catchall {:try_start_1 .. :try_end_1} :catchall_1

    goto :goto_2

    :catchall_1
    move-exception p1

    invoke-virtual {p0, p1}, Ljava/lang/Throwable;->addSuppressed(Ljava/lang/Throwable;)V

    :goto_2
    throw p0

    .line 328
    :cond_3
    :goto_3
    invoke-static {v1}, Lcom/huangguo/mobile/SiteRepository;->validImage([B)Z

    move-result v4

    if-nez v4, :cond_4

    .line 329
    invoke-virtual {v0}, Ljava/io/File;->delete()Z

    goto :goto_4

    .line 331
    :cond_4
    invoke-static {}, Ljava/lang/System;->currentTimeMillis()J

    move-result-wide v3

    invoke-virtual {v0, v3, v4}, Ljava/io/File;->setLastModified(J)Z

    move-object v3, v1

    :cond_5
    :goto_4
    const/4 v1, 0x2

    if-nez v3, :cond_9

    .line 334
    invoke-direct {p0, p1}, Lcom/huangguo/mobile/SiteRepository;->fetch(Ljava/lang/String;)[B

    move-result-object p1

    .line 335
    iget-object v3, p0, Lcom/huangguo/mobile/SiteRepository;->profile:Lcom/huangguo/mobile/SiteProfile;

    invoke-virtual {v3, v5}, Lcom/huangguo/mobile/SiteProfile;->object(Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object v3

    const-string v4, "mode"

    invoke-virtual {v3, v4}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v3

    .line 336
    const-string v4, "aes-cbc"

    invoke-virtual {v4, v3}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_6

    const-string v4, "auto"

    invoke-virtual {v4, v3}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v3

    if-eqz v3, :cond_7

    invoke-static {p1}, Lcom/huangguo/mobile/SiteRepository;->validImage([B)Z

    move-result v3

    if-nez v3, :cond_7

    .line 337
    :cond_6
    iget-object v3, p0, Lcom/huangguo/mobile/SiteRepository;->profile:Lcom/huangguo/mobile/SiteProfile;

    invoke-virtual {v3, v5}, Lcom/huangguo/mobile/SiteProfile;->object(Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object v3

    const-string v4, "transformation"

    invoke-virtual {v3, v4}, Lorg/json/JSONObject;->getString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v3

    invoke-static {v3}, Ljavax/crypto/Cipher;->getInstance(Ljava/lang/String;)Ljavax/crypto/Cipher;

    move-result-object v3

    .line 338
    new-instance v4, Ljavax/crypto/spec/SecretKeySpec;

    iget-object v5, p0, Lcom/huangguo/mobile/SiteRepository;->profile:Lcom/huangguo/mobile/SiteProfile;

    const-string v6, "key"

    invoke-virtual {v5, v6}, Lcom/huangguo/mobile/SiteProfile;->keyBytes(Ljava/lang/String;)[B

    move-result-object v5

    const-string v6, "AES"

    invoke-direct {v4, v5, v6}, Ljavax/crypto/spec/SecretKeySpec;-><init>([BLjava/lang/String;)V

    new-instance v5, Ljavax/crypto/spec/IvParameterSpec;

    iget-object v6, p0, Lcom/huangguo/mobile/SiteRepository;->profile:Lcom/huangguo/mobile/SiteProfile;

    const-string v7, "iv"

    .line 339
    invoke-virtual {v6, v7}, Lcom/huangguo/mobile/SiteProfile;->keyBytes(Ljava/lang/String;)[B

    move-result-object v6

    invoke-direct {v5, v6}, Ljavax/crypto/spec/IvParameterSpec;-><init>([B)V

    .line 338
    invoke-virtual {v3, v1, v4, v5}, Ljavax/crypto/Cipher;->init(ILjava/security/Key;Ljava/security/spec/AlgorithmParameterSpec;)V

    .line 340
    invoke-virtual {v3, p1}, Ljavax/crypto/Cipher;->doFinal([B)[B

    move-result-object p1

    :cond_7
    move-object v3, p1

    .line 342
    invoke-direct {p0, v3}, Lcom/huangguo/mobile/SiteRepository;->repairConfiguredHeader([B)Z

    .line 343
    invoke-static {v3}, Lcom/huangguo/mobile/SiteRepository;->validImage([B)Z

    move-result p1

    if-eqz p1, :cond_8

    .line 344
    new-instance p1, Ljava/io/FileOutputStream;

    invoke-direct {p1, v0}, Ljava/io/FileOutputStream;-><init>(Ljava/io/File;)V

    :try_start_2
    invoke-virtual {p1, v3}, Ljava/io/FileOutputStream;->write([B)V
    :try_end_2
    .catchall {:try_start_2 .. :try_end_2} :catchall_2

    invoke-virtual {p1}, Ljava/io/FileOutputStream;->close()V

    .line 345
    invoke-direct {p0}, Lcom/huangguo/mobile/SiteRepository;->trimCoverCache()V

    goto :goto_6

    :catchall_2
    move-exception p0

    .line 344
    :try_start_3
    invoke-virtual {p1}, Ljava/io/FileOutputStream;->close()V
    :try_end_3
    .catchall {:try_start_3 .. :try_end_3} :catchall_3

    goto :goto_5

    :catchall_3
    move-exception p1

    invoke-virtual {p0, p1}, Ljava/lang/Throwable;->addSuppressed(Ljava/lang/Throwable;)V

    :goto_5
    throw p0

    .line 343
    :cond_8
    new-instance p0, Ljava/lang/IllegalStateException;

    const-string p1, "\u5c01\u9762\u89e3\u5bc6\u7ed3\u679c\u65e0\u6548"

    invoke-direct {p0, p1}, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V

    throw p0

    .line 347
    :cond_9
    :goto_6
    aget-byte p0, v3, v2

    const/4 p1, -0x1

    if-ne p0, p1, :cond_a

    const-string p0, "image/jpeg"

    goto :goto_7

    :cond_a
    const/16 p1, -0x77

    if-ne p0, p1, :cond_b

    .line 348
    const-string p0, "image/png"

    goto :goto_7

    :cond_b
    const/16 p1, 0x47

    if-ne p0, p1, :cond_c

    const-string p0, "image/gif"

    goto :goto_7

    :cond_c
    const-string p0, "image/webp"

    .line 349
    :goto_7
    new-instance p1, Ljava/lang/StringBuilder;

    const-string v0, "data:"

    invoke-direct {p1, v0}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {p1, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const-string p0, ";base64,"

    invoke-virtual {p1, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-static {v3, v1}, Landroid/util/Base64;->encodeToString([BI)Ljava/lang/String;

    move-result-object p0

    invoke-virtual {p1, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {p1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p0

    return-object p0
.end method

.method detail(Ljava/lang/String;)Lorg/json/JSONObject;
    .locals 8
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    # 用户自带源的设置入口。走 detail 这条桥而不是新加一个 dispatch 动作 ——
    # MainActivity 的 dispatch 是 packed-switch 按编号跳的，索引和上面那张
    # sparse-switch 哈希表耦合，加一个动作要同时改两张表，这是本项目里最容易
    # 出事的地方。fetchApi 传 /api/ 路径早就用的同一招。
    #
    # appContext 是 private，但这里就在 SiteRepository 内部，读自己的字段合法，
    # 由这里取出来传给 Java（那边读不到）。
    # 前缀判断用 "__" 而不是 "__source:" —— 这条桥上的指令（UserSource.handle
    # 认的那些）都是 __ 开头的，认一次就够。多看几个字符没有任何好处，
    # 却意味着**每加一种指令都要回来改 smali**（__progress: 就是这么撞了一次：
    # 它掉到下面那道路径检查里，报的是「路径既不是 / 开头也不是 https」，
    # 一句和真实原因完全无关的话）。
    const-string v0, "__"

    invoke-virtual {p1, v0}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z

    move-result v0

    if-eqz v0, :detail_not_source

    iget-object v0, p0, Lcom/huangguo/mobile/SiteRepository;->appContext:Landroid/content/Context;

    # profile 也一起传：__source:web 要把合并后的 web 块（paths/categories/fields）
    # 交给前端 —— 用户换了源，前端读的不该还是打包时那份。
    iget-object v1, p0, Lcom/huangguo/mobile/SiteRepository;->profile:Lcom/huangguo/mobile/SiteProfile;

    invoke-static {v0, v1, p1}, Lcom/huangguo/mobile/UserSource;->handle(Landroid/content/Context;Lcom/huangguo/mobile/SiteProfile;Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object v0

    return-object v0

    :detail_not_source
    # REST 桥的入参：一条以 / 开头的动作路径，或者一个绝对 https 地址。
    #
    # 为什么允许绝对地址（2026-10-16 加）：origin 是**单一主机**，而有些站的接口
    # 开在别的域名上（AGE动漫 的接口在 api.agedm.io，页面在 m.agedm.io），或者同域
    # 但不在 /api/ 下面。只认 /api/ 的话，这类站就"只能写适配器" —— 可它们的差别
    # 本来只是**地址**而已，那是配置该管的事，不是代码该管的事。
    #
    # 上一条判断（__source: 前缀）已经拦掉了设置指令，所以这里放行 / 开头的东西
    # 是安全的：能走到这儿的只有 fetchApi 从配置里填出来的路径。
    # 先判绝对地址：它不以 / 开头，放在「/ 开头」那条后面会被误杀。
    const-string v0, "https://"

    invoke-virtual {p1, v0}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z

    move-result v0

    if-nez v0, :detail_absolute

    const-string v0, "/"

    invoke-virtual {p1, v0}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z

    move-result v0

    if-eqz v0, :detail_html

    # 相对路径才拼 origin（CMS 那条线的 origin 是空的，
    # 拼出来还是 /api/... 原样交给适配器）。
    iget-object v0, p0, Lcom/huangguo/mobile/SiteRepository;->profile:Lcom/huangguo/mobile/SiteProfile;
    iget-object v0, v0, Lcom/huangguo/mobile/SiteProfile;->origin:Ljava/lang/String;
    new-instance v1, Ljava/lang/StringBuilder;
    invoke-direct {v1, v0}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V
    invoke-virtual {v1, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    move-result-object v1
    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
    move-result-object v1

    goto :detail_fetch

    :detail_absolute
    move-object v1, p1

    :detail_fetch
    invoke-direct {p0, v1}, Lcom/huangguo/mobile/SiteRepository;->fetch(Ljava/lang/String;)[B
    move-result-object v1
    new-instance v2, Ljava/lang/String;
    sget-object v3, Ljava/nio/charset/StandardCharsets;->UTF_8:Ljava/nio/charset/Charset;
    invoke-direct {v2, v1, v3}, Ljava/lang/String;-><init>([BLjava/nio/charset/Charset;)V
    new-instance v0, Lorg/json/JSONObject;
    invoke-direct {v0, v2}, Lorg/json/JSONObject;-><init>(Ljava/lang/String;)V
    return-object v0

    :detail_html

    # 抓 HTML 的实现已整块移除（2026-10-16）。数据层现在全走 /api/ REST，
    # 这些分支没有入口。方法签名保留是因为 DualContentRepository 还在调它们 ——
    # 删了会在类加载期 NoSuchMethodError。留着壳子并抛异常，比返回 null
    # 让人在别处碰到 NPE 更容易定位。
    new-instance v0, Ljava/lang/UnsupportedOperationException;
    const-string v1, "路径既不是 / 开头的动作路径，也不是 https 绝对地址，无法处理"
    invoke-direct {v0, v1}, Ljava/lang/UnsupportedOperationException;-><init>(Ljava/lang/String;)V

    throw v0
.end method






