.class final Lcom/huangguo/mobile/UpdateManager;
.super Ljava/lang/Object;
.source "UpdateManager.java"


# annotations
.annotation system Ldalvik/annotation/MemberClasses;
    value = {
        Lcom/huangguo/mobile/UpdateManager$Candidate;,
        Lcom/huangguo/mobile/UpdateManager$Progress;
    }
.end annotation


# static fields
.field private static final AUTO_INTERVAL_MS:J = 0x2932e00L

.field private static final MANIFEST_URL:Ljava/lang/String; = "[\"https://149.104.21.177.sslip.io/updates/android-update.json\",\"https://ghgo.de5.net/https://raw.githubusercontent.com/zhaoruizhe81/AIIvideo/main/android-update.json\",\"https://raw.githubusercontent.com/zhaoruizhe81/AIIvideo/main/android-update.json\"]"

.field private static final MANUAL_LIMIT:I = 0x5

.field private static final MANUAL_WINDOW_MS:J = 0x36ee80L

.field private static final RELEASE_BASE:Ljava/lang/String; = "https://149.104.21.177.sslip.io/updates/"

.field private static final VERSION:Ljava/util/regex/Pattern;


# instance fields
.field private candidate:Lcom/huangguo/mobile/UpdateManager$Candidate;

.field private final context:Landroid/content/Context;

.field private downloading:Z

.field private final preferences:Landroid/content/SharedPreferences;


# direct methods
.method static constructor <clinit>()V
    .locals 1

    .line 24
    const-string v0, "\\d+\\.\\d+\\.\\d+"

    invoke-static {v0}, Ljava/util/regex/Pattern;->compile(Ljava/lang/String;)Ljava/util/regex/Pattern;

    move-result-object v0

    sput-object v0, Lcom/huangguo/mobile/UpdateManager;->VERSION:Ljava/util/regex/Pattern;

    return-void
.end method

.method constructor <init>(Landroid/content/Context;)V
    .locals 2

    .line 44
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    .line 45
    invoke-virtual {p1}, Landroid/content/Context;->getApplicationContext()Landroid/content/Context;

    move-result-object p1

    iput-object p1, p0, Lcom/huangguo/mobile/UpdateManager;->context:Landroid/content/Context;

    .line 46
    const-string v0, "updates"

    const/4 v1, 0x0

    invoke-virtual {p1, v0, v1}, Landroid/content/Context;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;

    move-result-object p1

    iput-object p1, p0, Lcom/huangguo/mobile/UpdateManager;->preferences:Landroid/content/SharedPreferences;

    return-void
.end method

.method private static availableStatus(Lcom/huangguo/mobile/UpdateManager$Candidate;Ljava/lang/String;)Lorg/json/JSONObject;
    .locals 3
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    .line 93
    const-string v0, "available"

    invoke-static {v0}, Lcom/huangguo/mobile/UpdateManager;->status(Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object v0

    const-string v1, "version"

    iget-object v2, p0, Lcom/huangguo/mobile/UpdateManager$Candidate;->version:Ljava/lang/String;

    invoke-virtual {v0, v1, v2}, Lorg/json/JSONObject;->put(Ljava/lang/String;Ljava/lang/Object;)Lorg/json/JSONObject;

    move-result-object v0

    const-string v1, "currentVersion"

    invoke-virtual {v0, v1, p1}, Lorg/json/JSONObject;->put(Ljava/lang/String;Ljava/lang/Object;)Lorg/json/JSONObject;

    move-result-object p1

    const-string v0, "notes"

    iget-object v1, p0, Lcom/huangguo/mobile/UpdateManager$Candidate;->notes:Ljava/lang/String;

    .line 94
    invoke-virtual {p1, v0, v1}, Lorg/json/JSONObject;->put(Ljava/lang/String;Ljava/lang/Object;)Lorg/json/JSONObject;

    move-result-object p1

    const-string v0, "size"

    iget-wide v1, p0, Lcom/huangguo/mobile/UpdateManager$Candidate;->size:J

    invoke-virtual {p1, v0, v1, v2}, Lorg/json/JSONObject;->put(Ljava/lang/String;J)Lorg/json/JSONObject;

    move-result-object p0

    return-object p0
.end method

.method private static candidateFrom(Lorg/json/JSONObject;)Lcom/huangguo/mobile/UpdateManager$Candidate;
    .locals 9

    .line 74
    const-string v0, "versionName"

    invoke-virtual {p0, v0}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v0

    .line 75
    const-string v1, "tag"

    invoke-virtual {p0, v1}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v1

    .line 76
    const-string v2, "apk"

    invoke-virtual {p0, v2}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v2

    .line 77
    const-string v3, "sha256"

    invoke-virtual {p0, v3}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v3

    .line 78
    sget-object v4, Lcom/huangguo/mobile/UpdateManager;->VERSION:Ljava/util/regex/Pattern;

    invoke-virtual {v4, v0}, Ljava/util/regex/Pattern;->matcher(Ljava/lang/CharSequence;)Ljava/util/regex/Matcher;

    move-result-object v4

    invoke-virtual {v4}, Ljava/util/regex/Matcher;->matches()Z

    move-result v4

    if-eqz v4, :cond_0

    new-instance v4, Ljava/lang/StringBuilder;

    const-string v5, "v"

    invoke-direct {v4, v5}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {v4, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v4}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v4

    invoke-virtual {v1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-eqz v4, :cond_0

    new-instance v4, Ljava/lang/StringBuilder;

    const-string v5, "AIIvideo-v"

    invoke-direct {v4, v5}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {v4, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const-string v5, "-release.apk"

    invoke-virtual {v4, v5}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v4}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v4

    .line 79
    invoke-virtual {v2, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-eqz v4, :cond_0

    const-string v4, "[0-9a-fA-F]{64}"

    .line 80
    invoke-virtual {v3, v4}, Ljava/lang/String;->matches(Ljava/lang/String;)Z

    move-result v4

    if-eqz v4, :cond_0

    const-string v4, "size"

    invoke-virtual {p0, v4}, Lorg/json/JSONObject;->optLong(Ljava/lang/String;)J

    move-result-wide v5

    const-wide/16 v7, 0x0

    cmp-long v5, v5, v7

    if-lez v5, :cond_0

    .line 83
    new-instance v5, Lcom/huangguo/mobile/UpdateManager$Candidate;

    const/4 v6, 0x0

    invoke-direct {v5, v6}, Lcom/huangguo/mobile/UpdateManager$Candidate;-><init>(Lcom/huangguo/mobile/UpdateManager-IA;)V

    .line 84
    iput-object v0, v5, Lcom/huangguo/mobile/UpdateManager$Candidate;->version:Ljava/lang/String;

    .line 85
    # 清单里带一组 url（GitHub Releases + 几个镜像 + 自家 VPS 兜底）。
    # 有这个字段就交给 UpdateMirror.pick 并发探一遍挑最快的；老清单没有就
    # 退回「RELEASE_BASE + 文件名」的拼法。
    const-string v6, "urls"

    invoke-virtual {p0, v6}, Lorg/json/JSONObject;->optJSONArray(Ljava/lang/String;)Lorg/json/JSONArray;

    move-result-object v6

    if-nez v6, :cond_have_urls

    new-instance v0, Ljava/lang/StringBuilder;

    const-string v6, "https://149.104.21.177.sslip.io/updates/"

    invoke-direct {v0, v6}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {v0, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v0

    goto :cond_url_ready

    :cond_have_urls
    invoke-virtual {v6}, Lorg/json/JSONArray;->toString()Ljava/lang/String;

    move-result-object v6

    invoke-static {v6}, Lcom/huangguo/mobile/UpdateMirror;->pick(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v0

    :cond_url_ready
    iput-object v0, v5, Lcom/huangguo/mobile/UpdateManager$Candidate;->url:Ljava/lang/String;

    .line 86
    new-instance v0, Ljava/lang/StringBuilder;

    const-string v1, "sha256:"

    invoke-direct {v0, v1}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {v0, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v0

    iput-object v0, v5, Lcom/huangguo/mobile/UpdateManager$Candidate;->digest:Ljava/lang/String;

    .line 87
    invoke-virtual {p0, v4}, Lorg/json/JSONObject;->optLong(Ljava/lang/String;)J

    move-result-wide v0

    iput-wide v0, v5, Lcom/huangguo/mobile/UpdateManager$Candidate;->size:J

    .line 88
    const-string v0, "notes"

    invoke-virtual {p0, v0}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    iput-object p0, v5, Lcom/huangguo/mobile/UpdateManager$Candidate;->notes:Ljava/lang/String;

    return-object v5

    .line 81
    :cond_0
    new-instance p0, Ljava/lang/IllegalStateException;

    const-string v0, "\u53d1\u5e03\u4fe1\u606f\u683c\u5f0f\u6709\u8bef"

    invoke-direct {p0, v0}, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V

    throw p0
.end method

.method private clearCandidate()V
    .locals 1

    const/4 v0, 0x0

    .line 69
    iput-object v0, p0, Lcom/huangguo/mobile/UpdateManager;->candidate:Lcom/huangguo/mobile/UpdateManager$Candidate;

    .line 70
    iget-object p0, p0, Lcom/huangguo/mobile/UpdateManager;->preferences:Landroid/content/SharedPreferences;

    invoke-interface {p0}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences$Editor;

    move-result-object p0

    const-string v0, "available_manifest"

    invoke-interface {p0, v0}, Landroid/content/SharedPreferences$Editor;->remove(Ljava/lang/String;)Landroid/content/SharedPreferences$Editor;

    move-result-object p0

    invoke-interface {p0}, Landroid/content/SharedPreferences$Editor;->apply()V

    return-void
.end method

.method private static fetchManifest()Lorg/json/JSONObject;
    .locals 3
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    # 2026-10-17：清单改从 GitHub Releases 拿，并带一组镜像。
    # 原来这里是一段手写的 HttpURLConnection —— 只认一个地址，那台机器一挂
    # 所有人就都更新不了。现在整段逻辑收进 UpdateMirror.fetch：依次试每个
    # 地址，第一个拉下来且看着像 JSON 的就用（VPS 留作最后一个兜底）。
    sget-object v0, Lcom/huangguo/mobile/UpdateManager;->MANIFEST_URL:Ljava/lang/String;

    const/16 v1, 0xf

    invoke-static {v0, v1}, Lcom/huangguo/mobile/UpdateMirror;->fetch(Ljava/lang/String;I)Ljava/lang/String;

    move-result-object v0

    if-eqz v0, :cond_none

    new-instance v1, Lorg/json/JSONObject;

    invoke-direct {v1, v0}, Lorg/json/JSONObject;-><init>(Ljava/lang/String;)V

    return-object v1

    # 全都不通时返回 null —— 调用方把它当「服务器上没有发布信息」处理，
    # 这条语义和改之前一致（原来 404 也走这里）。
    :cond_none
    const/4 v0, 0x0

    return-object v0
.end method

.method private static matchesDigest(Ljava/io/File;Ljava/lang/String;)Z
    .locals 6
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    .line 229
    const-string v0, "SHA-256"

    invoke-static {v0}, Ljava/security/MessageDigest;->getInstance(Ljava/lang/String;)Ljava/security/MessageDigest;

    move-result-object v0

    .line 230
    new-instance v1, Ljava/io/FileInputStream;

    invoke-direct {v1, p0}, Ljava/io/FileInputStream;-><init>(Ljava/io/File;)V

    const/high16 p0, 0x10000

    .line 231
    :try_start_0
    new-array p0, p0, [B

    .line 233
    :goto_0
    invoke-virtual {v1, p0}, Ljava/io/InputStream;->read([B)I

    move-result v2

    const/4 v3, -0x1

    const/4 v4, 0x0

    if-eq v2, v3, :cond_0

    invoke-virtual {v0, p0, v4, v2}, Ljava/security/MessageDigest;->update([BII)V
    :try_end_0
    .catchall {:try_start_0 .. :try_end_0} :catchall_0

    goto :goto_0

    .line 234
    :cond_0
    invoke-virtual {v1}, Ljava/io/InputStream;->close()V

    .line 235
    new-instance p0, Ljava/lang/StringBuilder;

    const-string v1, "sha256:"

    invoke-direct {p0, v1}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    .line 236
    invoke-virtual {v0}, Ljava/security/MessageDigest;->digest()[B

    move-result-object v0

    array-length v1, v0

    :goto_1
    if-ge v4, v1, :cond_1

    aget-byte v2, v0, v4

    sget-object v3, Ljava/util/Locale;->ROOT:Ljava/util/Locale;

    and-int/lit16 v2, v2, 0xff

    invoke-static {v2}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

    move-result-object v2

    filled-new-array {v2}, [Ljava/lang/Object;

    move-result-object v2

    const-string v5, "%02x"

    invoke-static {v3, v5, v2}, Ljava/lang/String;->format(Ljava/util/Locale;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;

    move-result-object v2

    invoke-virtual {p0, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    add-int/lit8 v4, v4, 0x1

    goto :goto_1

    .line 237
    :cond_1
    invoke-virtual {p0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p0

    invoke-virtual {p0, p1}, Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z

    move-result p0

    return p0

    :catchall_0
    move-exception p0

    .line 230
    :try_start_1
    invoke-virtual {v1}, Ljava/io/InputStream;->close()V
    :try_end_1
    .catchall {:try_start_1 .. :try_end_1} :catchall_1

    goto :goto_2

    :catchall_1
    move-exception p1

    invoke-virtual {p0, p1}, Ljava/lang/Throwable;->addSuppressed(Ljava/lang/Throwable;)V

    :goto_2
    throw p0
.end method

.method private static open(Ljava/lang/String;)Ljava/net/HttpURLConnection;
    .locals 3

    # 壳：真正的实现搬到 UpdateMirror.open，因为那边手动跟 Location。
    # HttpURLConnection 的 setInstanceFollowRedirects 只跟**同主机**跳转，
    # 而 GitHub 的 release 附件 302 到 objects.githubusercontent.com、
    # gh 镜像 302 到自己的 CDN —— 拿到的是个空 302，下载卡在 0%，检查更新
    # 还会误报「服务器上没有发布信息」。
    const/16 v0, 0x1e

    const/4 v1, 0x0

    invoke-static {p0, v0, v1}, Lcom/huangguo/mobile/UpdateMirror;->open(Ljava/lang/String;ILjava/lang/String;)Ljava/net/HttpURLConnection;

    move-result-object v0

    return-object v0
.end method

.method private static status(Ljava/lang/String;)Lorg/json/JSONObject;
    .locals 2
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    .line 186
    new-instance v0, Lorg/json/JSONObject;

    invoke-direct {v0}, Lorg/json/JSONObject;-><init>()V

    const-string v1, "status"

    invoke-virtual {v0, v1, p0}, Lorg/json/JSONObject;->put(Ljava/lang/String;Ljava/lang/Object;)Lorg/json/JSONObject;

    move-result-object p0

    return-object p0
.end method


# virtual methods
.method declared-synchronized cached()Lorg/json/JSONObject;
    .locals 6
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    monitor-enter p0

    .line 50
    :try_start_0
    iget-object v0, p0, Lcom/huangguo/mobile/UpdateManager;->preferences:Landroid/content/SharedPreferences;

    const-string v1, "available_manifest"

    const/4 v2, 0x0

    invoke-interface {v0, v1, v2}, Landroid/content/SharedPreferences;->getString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;

    move-result-object v0

    if-nez v0, :cond_0

    .line 51
    const-string v0, "none"

    invoke-static {v0}, Lcom/huangguo/mobile/UpdateManager;->status(Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object v0
    :try_end_0
    .catchall {:try_start_0 .. :try_end_0} :catchall_0

    monitor-exit p0

    return-object v0

    .line 53
    :cond_0
    :try_start_1
    new-instance v1, Lorg/json/JSONObject;

    invoke-direct {v1, v0}, Lorg/json/JSONObject;-><init>(Ljava/lang/String;)V

    .line 54
    iget-object v0, p0, Lcom/huangguo/mobile/UpdateManager;->context:Landroid/content/Context;

    invoke-virtual {v0}, Landroid/content/Context;->getPackageManager()Landroid/content/pm/PackageManager;

    move-result-object v0

    iget-object v2, p0, Lcom/huangguo/mobile/UpdateManager;->context:Landroid/content/Context;

    invoke-virtual {v2}, Landroid/content/Context;->getPackageName()Ljava/lang/String;

    move-result-object v2

    const/4 v3, 0x0

    invoke-virtual {v0, v2, v3}, Landroid/content/pm/PackageManager;->getPackageInfo(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;

    move-result-object v0

    .line 55
    sget v2, Landroid/os/Build$VERSION;->SDK_INT:I

    const/16 v3, 0x1c

    if-lt v2, v3, :cond_1

    invoke-static {v0}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticApiModelOutline0;->m(Landroid/content/pm/PackageInfo;)J

    move-result-wide v2

    goto :goto_0

    :cond_1
    iget v2, v0, Landroid/content/pm/PackageInfo;->versionCode:I

    int-to-long v2, v2

    .line 56
    :goto_0
    const-string v4, "versionCode"

    invoke-virtual {v1, v4}, Lorg/json/JSONObject;->optLong(Ljava/lang/String;)J

    move-result-wide v4

    cmp-long v2, v4, v2

    if-gtz v2, :cond_2

    .line 57
    invoke-direct {p0}, Lcom/huangguo/mobile/UpdateManager;->clearCandidate()V

    .line 58
    const-string v0, "none"

    invoke-static {v0}, Lcom/huangguo/mobile/UpdateManager;->status(Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object v0
    :try_end_1
    .catch Ljava/lang/Exception; {:try_start_1 .. :try_end_1} :catch_0
    .catchall {:try_start_1 .. :try_end_1} :catchall_0

    monitor-exit p0

    return-object v0

    .line 60
    :cond_2
    :try_start_2
    invoke-static {v1}, Lcom/huangguo/mobile/UpdateManager;->candidateFrom(Lorg/json/JSONObject;)Lcom/huangguo/mobile/UpdateManager$Candidate;

    move-result-object v1

    iput-object v1, p0, Lcom/huangguo/mobile/UpdateManager;->candidate:Lcom/huangguo/mobile/UpdateManager$Candidate;

    .line 61
    iget-object v0, v0, Landroid/content/pm/PackageInfo;->versionName:Ljava/lang/String;

    invoke-static {v1, v0}, Lcom/huangguo/mobile/UpdateManager;->availableStatus(Lcom/huangguo/mobile/UpdateManager$Candidate;Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object v0
    :try_end_2
    .catch Ljava/lang/Exception; {:try_start_2 .. :try_end_2} :catch_0
    .catchall {:try_start_2 .. :try_end_2} :catchall_0

    monitor-exit p0

    return-object v0

    .line 63
    :catch_0
    :try_start_3
    invoke-direct {p0}, Lcom/huangguo/mobile/UpdateManager;->clearCandidate()V

    .line 64
    const-string v0, "none"

    invoke-static {v0}, Lcom/huangguo/mobile/UpdateManager;->status(Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object v0
    :try_end_3
    .catchall {:try_start_3 .. :try_end_3} :catchall_0

    monitor-exit p0

    return-object v0

    :catchall_0
    move-exception v0

    :try_start_4
    monitor-exit p0
    :try_end_4
    .catchall {:try_start_4 .. :try_end_4} :catchall_0

    throw v0
.end method

.method declared-synchronized check(Z)Lorg/json/JSONObject;
    .locals 13
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    # 2026-10-17：自更新通道恢复（URL 已指到自建 VPS 的 /updates/，前端 UI 重建）。
    monitor-enter p0

    .line 98
    :try_start_0
    invoke-static {}, Ljava/lang/System;->currentTimeMillis()J

    move-result-wide v0

    const/4 v2, 0x0

    if-eqz p1, :cond_5

    .line 100
    new-instance v3, Ljava/util/ArrayList;

    invoke-direct {v3}, Ljava/util/ArrayList;-><init>()V

    .line 101
    iget-object v4, p0, Lcom/huangguo/mobile/UpdateManager;->preferences:Landroid/content/SharedPreferences;

    const-string v5, "manual_checks"

    const-string v6, ""

    invoke-interface {v4, v5, v6}, Landroid/content/SharedPreferences;->getString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;

    move-result-object v4

    const-string v5, ","

    invoke-virtual {v4, v5}, Ljava/lang/String;->split(Ljava/lang/String;)[Ljava/lang/String;

    move-result-object v4

    array-length v5, v4

    move v6, v2

    :goto_0
    if-ge v6, v5, :cond_1

    aget-object v7, v4, v6
    :try_end_0
    .catchall {:try_start_0 .. :try_end_0} :catchall_0

    .line 103
    :try_start_1
    invoke-static {v7}, Ljava/lang/Long;->parseLong(Ljava/lang/String;)J

    move-result-wide v7

    cmp-long v9, v7, v0

    if-gtz v9, :cond_0

    sub-long v9, v0, v7

    const-wide/32 v11, 0x36ee80

    cmp-long v9, v9, v11

    if-gez v9, :cond_0

    .line 104
    invoke-static {v7, v8}, Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;

    move-result-object v7

    invoke-interface {v3, v7}, Ljava/util/List;->add(Ljava/lang/Object;)Z
    :try_end_1
    .catch Ljava/lang/NumberFormatException; {:try_start_1 .. :try_end_1} :catch_0
    .catchall {:try_start_1 .. :try_end_1} :catchall_0

    :catch_0
    :cond_0
    add-int/lit8 v6, v6, 0x1

    goto :goto_0

    .line 107
    :cond_1
    :try_start_2
    invoke-interface {v3}, Ljava/util/List;->size()I

    move-result v4

    const/4 v5, 0x5

    if-lt v4, v5, :cond_2

    const-string p1, "throttled"

    invoke-static {p1}, Lcom/huangguo/mobile/UpdateManager;->status(Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object p1
    :try_end_2
    .catchall {:try_start_2 .. :try_end_2} :catchall_0

    monitor-exit p0

    return-object p1

    .line 108
    :cond_2
    :try_start_3
    invoke-static {v0, v1}, Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;

    move-result-object v4

    invoke-interface {v3, v4}, Ljava/util/List;->add(Ljava/lang/Object;)Z

    .line 109
    new-instance v4, Ljava/lang/StringBuilder;

    invoke-direct {v4}, Ljava/lang/StringBuilder;-><init>()V

    .line 110
    invoke-interface {v3}, Ljava/util/List;->iterator()Ljava/util/Iterator;

    move-result-object v3

    :goto_1
    invoke-interface {v3}, Ljava/util/Iterator;->hasNext()Z

    move-result v5

    if-eqz v5, :cond_4

    invoke-interface {v3}, Ljava/util/Iterator;->next()Ljava/lang/Object;

    move-result-object v5

    check-cast v5, Ljava/lang/Long;

    invoke-virtual {v5}, Ljava/lang/Long;->longValue()J

    move-result-wide v5

    .line 111
    invoke-virtual {v4}, Ljava/lang/StringBuilder;->length()I

    move-result v7

    if-lez v7, :cond_3

    const/16 v7, 0x2c

    invoke-virtual {v4, v7}, Ljava/lang/StringBuilder;->append(C)Ljava/lang/StringBuilder;

    .line 112
    :cond_3
    invoke-virtual {v4, v5, v6}, Ljava/lang/StringBuilder;->append(J)Ljava/lang/StringBuilder;

    goto :goto_1

    .line 114
    :cond_4
    iget-object v3, p0, Lcom/huangguo/mobile/UpdateManager;->preferences:Landroid/content/SharedPreferences;

    invoke-interface {v3}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences$Editor;

    move-result-object v3

    const-string v5, "manual_checks"

    invoke-virtual {v4}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v4

    invoke-interface {v3, v5, v4}, Landroid/content/SharedPreferences$Editor;->putString(Ljava/lang/String;Ljava/lang/String;)Landroid/content/SharedPreferences$Editor;

    move-result-object v3

    invoke-interface {v3}, Landroid/content/SharedPreferences$Editor;->apply()V

    goto :goto_2

    .line 116
    :cond_5
    iget-object v3, p0, Lcom/huangguo/mobile/UpdateManager;->preferences:Landroid/content/SharedPreferences;

    const-string v4, "last_auto_check"

    const-wide/16 v5, 0x0

    invoke-interface {v3, v4, v5, v6}, Landroid/content/SharedPreferences;->getLong(Ljava/lang/String;J)J

    move-result-wide v3

    cmp-long v5, v3, v5

    if-lez v5, :cond_6

    cmp-long v5, v0, v3

    if-ltz v5, :cond_6

    sub-long v3, v0, v3

    const-wide/32 v5, 0x2932e00

    cmp-long v3, v3, v5

    if-gez v3, :cond_6

    .line 117
    invoke-virtual {p0}, Lcom/huangguo/mobile/UpdateManager;->cached()Lorg/json/JSONObject;

    move-result-object p1
    :try_end_3
    .catchall {:try_start_3 .. :try_end_3} :catchall_0

    monitor-exit p0

    return-object p1

    .line 118
    :cond_6
    :try_start_4
    iget-object v3, p0, Lcom/huangguo/mobile/UpdateManager;->preferences:Landroid/content/SharedPreferences;

    invoke-interface {v3}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences$Editor;

    move-result-object v3

    const-string v4, "last_auto_check"

    invoke-interface {v3, v4, v0, v1}, Landroid/content/SharedPreferences$Editor;->putLong(Ljava/lang/String;J)Landroid/content/SharedPreferences$Editor;

    move-result-object v3

    invoke-interface {v3}, Landroid/content/SharedPreferences$Editor;->apply()V

    .line 121
    :goto_2
    invoke-static {}, Lcom/huangguo/mobile/UpdateManager;->fetchManifest()Lorg/json/JSONObject;

    move-result-object v3

    if-eqz p1, :cond_7

    .line 122
    iget-object p1, p0, Lcom/huangguo/mobile/UpdateManager;->preferences:Landroid/content/SharedPreferences;

    invoke-interface {p1}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences$Editor;

    move-result-object p1

    const-string v4, "last_auto_check"

    invoke-interface {p1, v4, v0, v1}, Landroid/content/SharedPreferences$Editor;->putLong(Ljava/lang/String;J)Landroid/content/SharedPreferences$Editor;

    move-result-object p1

    invoke-interface {p1}, Landroid/content/SharedPreferences$Editor;->apply()V

    .line 123
    :cond_7
    iget-object p1, p0, Lcom/huangguo/mobile/UpdateManager;->context:Landroid/content/Context;

    invoke-virtual {p1}, Landroid/content/Context;->getPackageManager()Landroid/content/pm/PackageManager;

    move-result-object p1

    iget-object v0, p0, Lcom/huangguo/mobile/UpdateManager;->context:Landroid/content/Context;

    invoke-virtual {v0}, Landroid/content/Context;->getPackageName()Ljava/lang/String;

    move-result-object v0

    invoke-virtual {p1, v0, v2}, Landroid/content/pm/PackageManager;->getPackageInfo(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;

    move-result-object p1

    .line 124
    iget-object v0, p1, Landroid/content/pm/PackageInfo;->versionName:Ljava/lang/String;

    if-nez v3, :cond_8

    .line 126
    invoke-direct {p0}, Lcom/huangguo/mobile/UpdateManager;->clearCandidate()V

    .line 127
    const-string p1, "unpublished"

    invoke-static {p1}, Lcom/huangguo/mobile/UpdateManager;->status(Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object p1
    :try_end_4
    .catchall {:try_start_4 .. :try_end_4} :catchall_0

    monitor-exit p0

    return-object p1

    .line 129
    :cond_8
    :try_start_5
    sget v1, Landroid/os/Build$VERSION;->SDK_INT:I

    const/16 v2, 0x1c

    if-lt v1, v2, :cond_9

    invoke-static {p1}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticApiModelOutline0;->m(Landroid/content/pm/PackageInfo;)J

    move-result-wide v1

    goto :goto_3

    :cond_9
    iget p1, p1, Landroid/content/pm/PackageInfo;->versionCode:I

    int-to-long v1, p1

    .line 130
    :goto_3
    const-string p1, "versionCode"

    invoke-virtual {v3, p1}, Lorg/json/JSONObject;->optLong(Ljava/lang/String;)J

    move-result-wide v4

    cmp-long p1, v4, v1

    if-gtz p1, :cond_a

    .line 131
    invoke-direct {p0}, Lcom/huangguo/mobile/UpdateManager;->clearCandidate()V

    .line 132
    const-string p1, "current"

    invoke-static {p1}, Lcom/huangguo/mobile/UpdateManager;->status(Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object p1

    const-string v1, "version"

    invoke-virtual {p1, v1, v0}, Lorg/json/JSONObject;->put(Ljava/lang/String;Ljava/lang/Object;)Lorg/json/JSONObject;

    move-result-object p1
    :try_end_5
    .catchall {:try_start_5 .. :try_end_5} :catchall_0

    monitor-exit p0

    return-object p1

    .line 134
    :cond_a
    :try_start_6
    invoke-static {v3}, Lcom/huangguo/mobile/UpdateManager;->candidateFrom(Lorg/json/JSONObject;)Lcom/huangguo/mobile/UpdateManager$Candidate;

    move-result-object p1

    iput-object p1, p0, Lcom/huangguo/mobile/UpdateManager;->candidate:Lcom/huangguo/mobile/UpdateManager$Candidate;

    .line 135
    iget-object p1, p0, Lcom/huangguo/mobile/UpdateManager;->preferences:Landroid/content/SharedPreferences;

    invoke-interface {p1}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences$Editor;

    move-result-object p1

    const-string v1, "available_manifest"

    invoke-virtual {v3}, Lorg/json/JSONObject;->toString()Ljava/lang/String;

    move-result-object v2

    invoke-interface {p1, v1, v2}, Landroid/content/SharedPreferences$Editor;->putString(Ljava/lang/String;Ljava/lang/String;)Landroid/content/SharedPreferences$Editor;

    move-result-object p1

    invoke-interface {p1}, Landroid/content/SharedPreferences$Editor;->apply()V

    .line 136
    iget-object p1, p0, Lcom/huangguo/mobile/UpdateManager;->candidate:Lcom/huangguo/mobile/UpdateManager$Candidate;

    invoke-static {p1, v0}, Lcom/huangguo/mobile/UpdateManager;->availableStatus(Lcom/huangguo/mobile/UpdateManager$Candidate;Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object p1
    :try_end_6
    .catchall {:try_start_6 .. :try_end_6} :catchall_0

    monitor-exit p0

    return-object p1

    :catchall_0
    move-exception p1

    :try_start_7
    monitor-exit p0
    :try_end_7
    .catchall {:try_start_7 .. :try_end_7} :catchall_0

    throw p1
.end method

.method declared-synchronized download(Lcom/huangguo/mobile/UpdateManager$Progress;)Ljava/io/File;
    .locals 6

    # 真正的下载搬进 UpdateMirror：那边手动跟 Location（GitHub 和 gh 镜像都
    # 会 302 到别的域名，HttpURLConnection 的自动重定向只认同主机）、64KB buffer、
    # 边下边算 sha256。原来这一大段在自己的实现里，304/302 一出现就卡在 0%。
    # 这里只剩三件事：判重、准备目标文件、管 downloading 标志。
    iget-boolean v0, p0, Lcom/huangguo/mobile/UpdateManager;->downloading:Z

    if-eqz v0, :cond_free

    const/4 v0, 0x0

    return-object v0

    :cond_free
    iget-object v0, p0, Lcom/huangguo/mobile/UpdateManager;->candidate:Lcom/huangguo/mobile/UpdateManager$Candidate;

    if-eqz v0, :cond_none

    new-instance v1, Ljava/io/File;

    iget-object v2, p0, Lcom/huangguo/mobile/UpdateManager;->context:Landroid/content/Context;

    invoke-virtual {v2}, Landroid/content/Context;->getCacheDir()Ljava/io/File;

    move-result-object v2

    const-string v3, "updates"

    invoke-direct {v1, v2, v3}, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V

    invoke-virtual {v1}, Ljava/io/File;->mkdirs()Z

    new-instance v2, Ljava/lang/StringBuilder;

    const-string v3, "AIIvideo-v"

    invoke-direct {v2, v3}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    iget-object v3, v0, Lcom/huangguo/mobile/UpdateManager$Candidate;->version:Ljava/lang/String;

    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const-string v3, "-release.apk"

    invoke-virtual {v2, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v2

    new-instance v3, Ljava/io/File;

    invoke-direct {v3, v1, v2}, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V

    const/4 v4, 0x1

    iput-boolean v4, p0, Lcom/huangguo/mobile/UpdateManager;->downloading:Z

    :try_start_0
    iget-object v4, v0, Lcom/huangguo/mobile/UpdateManager$Candidate;->url:Ljava/lang/String;

    iget-object v5, v0, Lcom/huangguo/mobile/UpdateManager$Candidate;->digest:Ljava/lang/String;

    invoke-static {v4, v3, p1, v5}, Lcom/huangguo/mobile/UpdateMirror;->download(Ljava/lang/String;Ljava/io/File;Ljava/lang/Object;Ljava/lang/String;)Ljava/io/File;

    move-result-object v0
    :try_end_0
    .catchall {:try_start_0 .. :try_end_0} :catchall_0

    const/4 v4, 0x0

    iput-boolean v4, p0, Lcom/huangguo/mobile/UpdateManager;->downloading:Z

    return-object v0

    :catchall_0
    move-exception v0

    const/4 v4, 0x0

    iput-boolean v4, p0, Lcom/huangguo/mobile/UpdateManager;->downloading:Z

    throw v0

    :cond_none
    const/4 v0, 0x0

    return-object v0
.end method
