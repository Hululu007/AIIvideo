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

.field private static final MANIFEST_URL:Ljava/lang/String; = "https://github.com/MrSsg/HuangguoAPP-for-Andriod/releases/latest/download/android-update.json"

.field private static final MANUAL_LIMIT:I = 0x5

.field private static final MANUAL_WINDOW_MS:J = 0x36ee80L

.field private static final RELEASE_BASE:Ljava/lang/String; = "https://github.com/MrSsg/HuangguoAPP-for-Andriod/releases/"

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
    new-instance v0, Ljava/lang/StringBuilder;

    const-string v6, "https://github.com/MrSsg/HuangguoAPP-for-Andriod/releases/download/"

    invoke-direct {v0, v6}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const-string v1, "/"

    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v0, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v0

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
    .locals 8
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    .line 203
    new-instance v0, Ljava/net/URL;

    const-string v1, "https://github.com/MrSsg/HuangguoAPP-for-Andriod/releases/latest/download/android-update.json"

    invoke-direct {v0, v1}, Ljava/net/URL;-><init>(Ljava/lang/String;)V

    invoke-virtual {v0}, Ljava/net/URL;->openConnection()Ljava/net/URLConnection;

    move-result-object v0

    check-cast v0, Ljava/net/HttpURLConnection;

    const/16 v1, 0x3a98

    .line 204
    invoke-virtual {v0, v1}, Ljava/net/HttpURLConnection;->setConnectTimeout(I)V

    const/16 v1, 0x7530

    .line 205
    invoke-virtual {v0, v1}, Ljava/net/HttpURLConnection;->setReadTimeout(I)V

    .line 206
    const-string v1, "User-Agent"

    const-string v2, "AIIvideo-Updater"

    invoke-virtual {v0, v1, v2}, Ljava/net/HttpURLConnection;->setRequestProperty(Ljava/lang/String;Ljava/lang/String;)V

    .line 207
    invoke-virtual {v0}, Ljava/net/HttpURLConnection;->getResponseCode()I

    move-result v1

    const/16 v2, 0x194

    if-ne v1, v2, :cond_0

    .line 208
    invoke-virtual {v0}, Ljava/net/HttpURLConnection;->disconnect()V

    const/4 v0, 0x0

    return-object v0

    :cond_0
    const/16 v2, 0xc8

    if-lt v1, v2, :cond_5

    const/16 v2, 0x12c

    if-ge v1, v2, :cond_5

    .line 213
    :try_start_0
    invoke-virtual {v0}, Ljava/net/HttpURLConnection;->getInputStream()Ljava/io/InputStream;

    move-result-object v1
    :try_end_0
    .catchall {:try_start_0 .. :try_end_0} :catchall_2

    const/high16 v2, 0x10000

    .line 214
    :try_start_1
    new-array v3, v2, [B

    const/4 v4, 0x0

    move v5, v4

    :goto_0
    sub-int v6, v2, v5

    .line 217
    invoke-virtual {v1, v3, v5, v6}, Ljava/io/InputStream;->read([BII)I

    move-result v6

    if-gez v6, :cond_2

    .line 222
    new-instance v2, Lorg/json/JSONObject;

    new-instance v6, Ljava/lang/String;

    sget-object v7, Ljava/nio/charset/StandardCharsets;->UTF_8:Ljava/nio/charset/Charset;

    invoke-direct {v6, v3, v4, v5, v7}, Ljava/lang/String;-><init>([BIILjava/nio/charset/Charset;)V

    invoke-direct {v2, v6}, Lorg/json/JSONObject;-><init>(Ljava/lang/String;)V
    :try_end_1
    .catchall {:try_start_1 .. :try_end_1} :catchall_0

    if-eqz v1, :cond_1

    .line 223
    :try_start_2
    invoke-virtual {v1}, Ljava/io/InputStream;->close()V
    :try_end_2
    .catchall {:try_start_2 .. :try_end_2} :catchall_2

    .line 224
    :cond_1
    invoke-virtual {v0}, Ljava/net/HttpURLConnection;->disconnect()V

    return-object v2

    :cond_2
    add-int/2addr v5, v6

    if-eq v5, v2, :cond_3

    goto :goto_0

    .line 220
    :cond_3
    :try_start_3
    new-instance v2, Ljava/lang/IllegalStateException;

    const-string v3, "\u53d1\u5e03\u5217\u8868\u8fc7\u5927"

    invoke-direct {v2, v3}, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V

    throw v2
    :try_end_3
    .catchall {:try_start_3 .. :try_end_3} :catchall_0

    :catchall_0
    move-exception v2

    if-eqz v1, :cond_4

    .line 213
    :try_start_4
    invoke-virtual {v1}, Ljava/io/InputStream;->close()V
    :try_end_4
    .catchall {:try_start_4 .. :try_end_4} :catchall_1

    goto :goto_1

    :catchall_1
    move-exception v1

    :try_start_5
    invoke-virtual {v2, v1}, Ljava/lang/Throwable;->addSuppressed(Ljava/lang/Throwable;)V

    :cond_4
    :goto_1
    throw v2
    :try_end_5
    .catchall {:try_start_5 .. :try_end_5} :catchall_2

    :catchall_2
    move-exception v1

    .line 224
    invoke-virtual {v0}, Ljava/net/HttpURLConnection;->disconnect()V

    .line 225
    throw v1

    .line 210
    :cond_5
    invoke-virtual {v0}, Ljava/net/HttpURLConnection;->disconnect()V

    .line 211
    new-instance v0, Ljava/lang/IllegalStateException;

    new-instance v2, Ljava/lang/StringBuilder;

    const-string v3, "GitHub \u8bf7\u6c42\u5931\u8d25\uff08"

    invoke-direct {v2, v3}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {v2, v1}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    const-string v1, "\uff09"

    invoke-virtual {v2, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v1

    invoke-direct {v0, v1}, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V

    throw v0
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
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    .line 189
    new-instance v0, Ljava/net/URL;

    invoke-direct {v0, p0}, Ljava/net/URL;-><init>(Ljava/lang/String;)V

    invoke-virtual {v0}, Ljava/net/URL;->openConnection()Ljava/net/URLConnection;

    move-result-object p0

    check-cast p0, Ljava/net/HttpURLConnection;

    const/16 v0, 0x3a98

    .line 190
    invoke-virtual {p0, v0}, Ljava/net/HttpURLConnection;->setConnectTimeout(I)V

    const/16 v0, 0x7530

    .line 191
    invoke-virtual {p0, v0}, Ljava/net/HttpURLConnection;->setReadTimeout(I)V

    .line 192
    const-string v0, "User-Agent"

    const-string v1, "AIIvideo-Updater"

    invoke-virtual {p0, v0, v1}, Ljava/net/HttpURLConnection;->setRequestProperty(Ljava/lang/String;Ljava/lang/String;)V

    .line 193
    const-string v0, "Accept"

    const-string v1, "application/json"

    invoke-virtual {p0, v0, v1}, Ljava/net/HttpURLConnection;->setRequestProperty(Ljava/lang/String;Ljava/lang/String;)V

    .line 194
    invoke-virtual {p0}, Ljava/net/HttpURLConnection;->getResponseCode()I

    move-result v0

    const/16 v1, 0xc8

    if-lt v0, v1, :cond_0

    const/16 v1, 0x12c

    if-ge v0, v1, :cond_0

    return-object p0

    .line 196
    :cond_0
    invoke-virtual {p0}, Ljava/net/HttpURLConnection;->disconnect()V

    .line 197
    new-instance p0, Ljava/lang/IllegalStateException;

    new-instance v1, Ljava/lang/StringBuilder;

    const-string v2, "GitHub \u8bf7\u6c42\u5931\u8d25\uff08"

    invoke-direct {v1, v2}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {v1, v0}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    const-string v0, "\uff09"

    invoke-virtual {v1, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v0

    invoke-direct {p0, v0}, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V

    throw p0
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

    # ── 改造版：APK 自更新通道已切断（2026-10-16）────────────────────────
    # MANIFEST_URL 指向
    #   https://github.com/MrSsg/HuangguoAPP-for-Andriod/releases/latest/download/android-update.json
    # 而上游主仓库已经 404（2026-10-16 核实）。继续请求只会每次冷启动白打一次
    # 外网，拿不回任何东西。这里直接返回本地缓存状态，一个网络请求都不发。
    #
    # 插在 monitor-enter 之前：本方法是 synchronized，提前 return 时不能持锁。
    invoke-virtual {p0}, Lcom/huangguo/mobile/UpdateManager;->cached()Lorg/json/JSONObject;

    move-result-object p0

    return-object p0

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
    .locals 22
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    # ── 改造版：下载入口已封（2026-10-16）────────────────────────────────
    # check() 早就返回本地缓存不发网络，前端那套「发现新版本 → 下载并安装」
    # 的面板也已经整块删掉，所以 download() 现在没有任何调用点，
    # 只有 download-update 这个桥动作还留着口子。这里直接返回 null 封死。
    #
    # 插在 monitor-enter 之前：本方法是 synchronized，提前 return 时不能持锁。
    const/4 v0, 0x0

    return-object v0

    move-object/from16 v1, p0

    move-object/from16 v0, p1

    const-string v2, "AIIvideo-v"

    monitor-enter p0

    .line 140
    :try_start_0
    iget-boolean v3, v1, Lcom/huangguo/mobile/UpdateManager;->downloading:Z

    if-nez v3, :cond_14

    .line 141
    iget-object v3, v1, Lcom/huangguo/mobile/UpdateManager;->candidate:Lcom/huangguo/mobile/UpdateManager$Candidate;

    if-eqz v3, :cond_13

    .line 142
    iget-object v3, v3, Lcom/huangguo/mobile/UpdateManager$Candidate;->digest:Ljava/lang/String;

    const-string v4, "sha256:[0-9a-fA-F]{64}"

    invoke-virtual {v3, v4}, Ljava/lang/String;->matches(Ljava/lang/String;)Z

    move-result v3

    if-eqz v3, :cond_12

    .line 143
    iget-object v3, v1, Lcom/huangguo/mobile/UpdateManager;->candidate:Lcom/huangguo/mobile/UpdateManager$Candidate;

    iget-object v3, v3, Lcom/huangguo/mobile/UpdateManager$Candidate;->url:Ljava/lang/String;

    const-string v4, "https://github.com/MrSsg/HuangguoAPP-for-Andriod/releases/download/"

    invoke-virtual {v3, v4}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z

    move-result v3

    if-eqz v3, :cond_11

    const/4 v3, 0x1

    .line 146
    iput-boolean v3, v1, Lcom/huangguo/mobile/UpdateManager;->downloading:Z
    :try_end_0
    .catchall {:try_start_0 .. :try_end_0} :catchall_b

    const/4 v3, 0x0

    .line 148
    :try_start_1
    new-instance v4, Ljava/io/File;

    iget-object v5, v1, Lcom/huangguo/mobile/UpdateManager;->context:Landroid/content/Context;

    invoke-virtual {v5}, Landroid/content/Context;->getCacheDir()Ljava/io/File;

    move-result-object v5

    const-string v6, "updates"

    invoke-direct {v4, v5, v6}, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V

    .line 149
    invoke-virtual {v4}, Ljava/io/File;->exists()Z

    move-result v5
    :try_end_1
    .catchall {:try_start_1 .. :try_end_1} :catchall_a

    if-nez v5, :cond_1

    :try_start_2
    invoke-virtual {v4}, Ljava/io/File;->mkdirs()Z

    move-result v5

    if-eqz v5, :cond_0

    goto :goto_0

    :cond_0
    new-instance v0, Ljava/lang/IllegalStateException;

    const-string v2, "\u65e0\u6cd5\u521b\u5efa\u4e0b\u8f7d\u76ee\u5f55"

    invoke-direct {v0, v2}, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V

    throw v0
    :try_end_2
    .catchall {:try_start_2 .. :try_end_2} :catchall_0

    :catchall_0
    move-exception v0

    move v10, v3

    goto/16 :goto_c

    .line 150
    :cond_1
    :goto_0
    :try_start_3
    new-instance v5, Ljava/io/File;

    new-instance v6, Ljava/lang/StringBuilder;

    invoke-direct {v6, v2}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    iget-object v2, v1, Lcom/huangguo/mobile/UpdateManager;->candidate:Lcom/huangguo/mobile/UpdateManager$Candidate;

    iget-object v2, v2, Lcom/huangguo/mobile/UpdateManager$Candidate;->version:Ljava/lang/String;

    invoke-virtual {v6, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const-string v2, "-release.apk"

    invoke-virtual {v6, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v6}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v2

    invoke-direct {v5, v4, v2}, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V

    .line 151
    invoke-virtual {v5}, Ljava/io/File;->isFile()Z

    move-result v2
    :try_end_3
    .catchall {:try_start_3 .. :try_end_3} :catchall_a

    const/16 v6, 0x64

    if-eqz v2, :cond_2

    :try_start_4
    iget-object v2, v1, Lcom/huangguo/mobile/UpdateManager;->candidate:Lcom/huangguo/mobile/UpdateManager$Candidate;

    iget-object v2, v2, Lcom/huangguo/mobile/UpdateManager$Candidate;->digest:Ljava/lang/String;

    invoke-static {v5, v2}, Lcom/huangguo/mobile/UpdateManager;->matchesDigest(Ljava/io/File;Ljava/lang/String;)Z

    move-result v2

    if-eqz v2, :cond_2

    .line 152
    invoke-interface {v0, v6}, Lcom/huangguo/mobile/UpdateManager$Progress;->onPercent(I)V
    :try_end_4
    .catchall {:try_start_4 .. :try_end_4} :catchall_0

    .line 182
    :try_start_5
    iput-boolean v3, v1, Lcom/huangguo/mobile/UpdateManager;->downloading:Z
    :try_end_5
    .catchall {:try_start_5 .. :try_end_5} :catchall_b

    .line 153
    monitor-exit p0

    return-object v5

    .line 155
    :cond_2
    :try_start_6
    new-instance v2, Ljava/io/File;

    new-instance v7, Ljava/lang/StringBuilder;

    invoke-direct {v7}, Ljava/lang/StringBuilder;-><init>()V

    invoke-virtual {v5}, Ljava/io/File;->getName()Ljava/lang/String;

    move-result-object v8

    invoke-virtual {v7, v8}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const-string v8, ".part"

    invoke-virtual {v7, v8}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v7}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v7

    invoke-direct {v2, v4, v7}, Ljava/io/File;-><init>(Ljava/io/File;Ljava/lang/String;)V

    .line 156
    iget-object v4, v1, Lcom/huangguo/mobile/UpdateManager;->candidate:Lcom/huangguo/mobile/UpdateManager$Candidate;

    iget-object v4, v4, Lcom/huangguo/mobile/UpdateManager$Candidate;->url:Ljava/lang/String;

    invoke-static {v4}, Lcom/huangguo/mobile/UpdateManager;->open(Ljava/lang/String;)Ljava/net/HttpURLConnection;

    move-result-object v4
    :try_end_6
    .catchall {:try_start_6 .. :try_end_6} :catchall_a

    .line 159
    :try_start_7
    iget-object v7, v1, Lcom/huangguo/mobile/UpdateManager;->candidate:Lcom/huangguo/mobile/UpdateManager$Candidate;

    iget-wide v7, v7, Lcom/huangguo/mobile/UpdateManager$Candidate;->size:J

    const-wide/16 v9, 0x0

    cmp-long v7, v7, v9

    if-lez v7, :cond_3

    iget-object v7, v1, Lcom/huangguo/mobile/UpdateManager;->candidate:Lcom/huangguo/mobile/UpdateManager$Candidate;

    iget-wide v7, v7, Lcom/huangguo/mobile/UpdateManager$Candidate;->size:J

    goto :goto_1

    :cond_3
    invoke-virtual {v4}, Ljava/net/HttpURLConnection;->getContentLengthLong()J

    move-result-wide v7

    .line 160
    :goto_1
    invoke-virtual {v4}, Ljava/net/HttpURLConnection;->getInputStream()Ljava/io/InputStream;

    move-result-object v11
    :try_end_7
    .catchall {:try_start_7 .. :try_end_7} :catchall_9

    :try_start_8
    new-instance v12, Ljava/io/FileOutputStream;

    invoke-direct {v12, v2}, Ljava/io/FileOutputStream;-><init>(Ljava/io/File;)V
    :try_end_8
    .catchall {:try_start_8 .. :try_end_8} :catchall_6

    const/high16 v13, 0x10000

    .line 161
    :try_start_9
    new-array v13, v13, [B

    const/4 v14, -0x1

    move-wide v15, v9

    move-wide/from16 v17, v15

    move v9, v14

    .line 164
    :goto_2
    invoke-virtual {v11, v13}, Ljava/io/InputStream;->read([B)I

    move-result v10

    if-eq v10, v14, :cond_6

    .line 165
    invoke-virtual {v12, v13, v3, v10}, Ljava/io/FileOutputStream;->write([BII)V
    :try_end_9
    .catchall {:try_start_9 .. :try_end_9} :catchall_4

    move-wide/from16 v19, v15

    int-to-long v14, v10

    add-long v15, v19, v14

    cmp-long v10, v7, v17

    if-lez v10, :cond_4

    const-wide/16 v19, 0x64

    mul-long v19, v19, v15

    move-object v14, v4

    .line 167
    :try_start_a
    div-long v3, v19, v7
    :try_end_a
    .catchall {:try_start_a .. :try_end_a} :catchall_2

    move-object/from16 v21, v11

    const-wide/16 v10, 0x63

    :try_start_b
    invoke-static {v10, v11, v3, v4}, Ljava/lang/Math;->min(JJ)J

    move-result-wide v3

    long-to-int v10, v3

    goto :goto_3

    :catchall_1
    move-exception v0

    goto/16 :goto_7

    :catchall_2
    move-exception v0

    goto/16 :goto_6

    :cond_4
    move-object v14, v4

    move-object/from16 v21, v11

    const/4 v10, 0x0

    :goto_3
    if-eq v10, v9, :cond_5

    .line 168
    invoke-interface {v0, v10}, Lcom/huangguo/mobile/UpdateManager$Progress;->onPercent(I)V
    :try_end_b
    .catchall {:try_start_b .. :try_end_b} :catchall_1

    move v9, v10

    :cond_5
    move-object v4, v14

    move-object/from16 v11, v21

    const/4 v3, 0x0

    const/4 v14, -0x1

    goto :goto_2

    :cond_6
    move-object v14, v4

    move-object/from16 v21, v11

    move-wide/from16 v19, v15

    .line 170
    :try_start_c
    invoke-virtual {v12}, Ljava/io/FileOutputStream;->close()V
    :try_end_c
    .catchall {:try_start_c .. :try_end_c} :catchall_3

    if-eqz v21, :cond_7

    :try_start_d
    invoke-virtual/range {v21 .. v21}, Ljava/io/InputStream;->close()V

    .line 171
    :cond_7
    iget-object v3, v1, Lcom/huangguo/mobile/UpdateManager;->candidate:Lcom/huangguo/mobile/UpdateManager$Candidate;

    iget-wide v3, v3, Lcom/huangguo/mobile/UpdateManager$Candidate;->size:J

    cmp-long v3, v3, v17

    if-lez v3, :cond_9

    iget-object v3, v1, Lcom/huangguo/mobile/UpdateManager;->candidate:Lcom/huangguo/mobile/UpdateManager$Candidate;

    iget-wide v3, v3, Lcom/huangguo/mobile/UpdateManager$Candidate;->size:J

    cmp-long v3, v19, v3

    if-nez v3, :cond_8

    goto :goto_4

    :cond_8
    new-instance v0, Ljava/lang/IllegalStateException;

    const-string v3, "\u66f4\u65b0\u5305\u4e0b\u8f7d\u4e0d\u5b8c\u6574\uff0c\u8bf7\u91cd\u8bd5"

    invoke-direct {v0, v3}, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V

    throw v0

    .line 172
    :cond_9
    :goto_4
    iget-object v3, v1, Lcom/huangguo/mobile/UpdateManager;->candidate:Lcom/huangguo/mobile/UpdateManager$Candidate;

    iget-object v3, v3, Lcom/huangguo/mobile/UpdateManager$Candidate;->digest:Ljava/lang/String;

    invoke-static {v2, v3}, Lcom/huangguo/mobile/UpdateManager;->matchesDigest(Ljava/io/File;Ljava/lang/String;)Z

    move-result v3

    if-eqz v3, :cond_e

    .line 173
    invoke-virtual {v5}, Ljava/io/File;->exists()Z

    move-result v3

    if-eqz v3, :cond_b

    invoke-virtual {v5}, Ljava/io/File;->delete()Z

    move-result v3

    if-eqz v3, :cond_a

    goto :goto_5

    :cond_a
    new-instance v0, Ljava/lang/IllegalStateException;

    const-string v3, "\u65e0\u6cd5\u66ff\u6362\u65e7\u4e0b\u8f7d\u5305"

    invoke-direct {v0, v3}, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V

    throw v0

    .line 174
    :cond_b
    :goto_5
    invoke-virtual {v2, v5}, Ljava/io/File;->renameTo(Ljava/io/File;)Z

    move-result v3

    if-eqz v3, :cond_d

    .line 175
    invoke-interface {v0, v6}, Lcom/huangguo/mobile/UpdateManager$Progress;->onPercent(I)V
    :try_end_d
    .catchall {:try_start_d .. :try_end_d} :catchall_8

    .line 178
    :try_start_e
    invoke-virtual {v14}, Ljava/net/HttpURLConnection;->disconnect()V

    .line 179
    invoke-virtual {v2}, Ljava/io/File;->exists()Z

    move-result v0

    if-eqz v0, :cond_c

    invoke-virtual {v2}, Ljava/io/File;->delete()Z
    :try_end_e
    .catchall {:try_start_e .. :try_end_e} :catchall_a

    :cond_c
    const/4 v10, 0x0

    .line 182
    :try_start_f
    iput-boolean v10, v1, Lcom/huangguo/mobile/UpdateManager;->downloading:Z
    :try_end_f
    .catchall {:try_start_f .. :try_end_f} :catchall_b

    .line 176
    monitor-exit p0

    return-object v5

    .line 174
    :cond_d
    :try_start_10
    new-instance v0, Ljava/lang/IllegalStateException;

    const-string v3, "\u65e0\u6cd5\u4fdd\u5b58\u66f4\u65b0\u5305"

    invoke-direct {v0, v3}, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V

    throw v0

    .line 172
    :cond_e
    new-instance v0, Ljava/lang/IllegalStateException;

    const-string v3, "\u66f4\u65b0\u5305\u6821\u9a8c\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5"

    invoke-direct {v0, v3}, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V

    throw v0
    :try_end_10
    .catchall {:try_start_10 .. :try_end_10} :catchall_8

    :catchall_3
    move-exception v0

    goto :goto_9

    :catchall_4
    move-exception v0

    move-object v14, v4

    :goto_6
    move-object/from16 v21, v11

    :goto_7
    move-object v3, v0

    .line 160
    :try_start_11
    invoke-virtual {v12}, Ljava/io/FileOutputStream;->close()V
    :try_end_11
    .catchall {:try_start_11 .. :try_end_11} :catchall_5

    goto :goto_8

    :catchall_5
    move-exception v0

    :try_start_12
    invoke-virtual {v3, v0}, Ljava/lang/Throwable;->addSuppressed(Ljava/lang/Throwable;)V

    :goto_8
    throw v3
    :try_end_12
    .catchall {:try_start_12 .. :try_end_12} :catchall_3

    :catchall_6
    move-exception v0

    move-object v14, v4

    move-object/from16 v21, v11

    :goto_9
    move-object v3, v0

    if-eqz v21, :cond_f

    :try_start_13
    invoke-virtual/range {v21 .. v21}, Ljava/io/InputStream;->close()V
    :try_end_13
    .catchall {:try_start_13 .. :try_end_13} :catchall_7

    goto :goto_a

    :catchall_7
    move-exception v0

    :try_start_14
    invoke-virtual {v3, v0}, Ljava/lang/Throwable;->addSuppressed(Ljava/lang/Throwable;)V

    :cond_f
    :goto_a
    throw v3
    :try_end_14
    .catchall {:try_start_14 .. :try_end_14} :catchall_8

    :catchall_8
    move-exception v0

    goto :goto_b

    :catchall_9
    move-exception v0

    move-object v14, v4

    .line 178
    :goto_b
    :try_start_15
    invoke-virtual {v14}, Ljava/net/HttpURLConnection;->disconnect()V

    .line 179
    invoke-virtual {v2}, Ljava/io/File;->exists()Z

    move-result v3

    if-eqz v3, :cond_10

    invoke-virtual {v2}, Ljava/io/File;->delete()Z

    .line 180
    :cond_10
    throw v0
    :try_end_15
    .catchall {:try_start_15 .. :try_end_15} :catchall_a

    :catchall_a
    move-exception v0

    const/4 v10, 0x0

    .line 182
    :goto_c
    :try_start_16
    iput-boolean v10, v1, Lcom/huangguo/mobile/UpdateManager;->downloading:Z

    .line 183
    throw v0

    .line 144
    :cond_11
    new-instance v0, Ljava/lang/IllegalStateException;

    const-string v2, "\u66f4\u65b0\u5305\u5730\u5740\u65e0\u6548"

    invoke-direct {v0, v2}, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V

    throw v0

    .line 142
    :cond_12
    new-instance v0, Ljava/lang/IllegalStateException;

    const-string v2, "\u53d1\u5e03\u5305\u7f3a\u5c11 SHA-256 \u6821\u9a8c\u503c"

    invoke-direct {v0, v2}, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V

    throw v0

    .line 141
    :cond_13
    new-instance v0, Ljava/lang/IllegalStateException;

    const-string v2, "\u8bf7\u5148\u68c0\u67e5\u66f4\u65b0"

    invoke-direct {v0, v2}, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V

    throw v0

    .line 140
    :cond_14
    new-instance v0, Ljava/lang/IllegalStateException;

    const-string v2, "\u66f4\u65b0\u5305\u6b63\u5728\u4e0b\u8f7d"

    invoke-direct {v0, v2}, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V

    throw v0

    :catchall_b
    move-exception v0

    monitor-exit p0
    :try_end_16
    .catchall {:try_start_16 .. :try_end_16} :catchall_b

    throw v0
.end method
