.class final Lcom/huangguo/mobile/SiteProfile;
.super Ljava/lang/Object;
.source "SiteProfile.java"


# instance fields
.field final fingerprint:Ljava/lang/String;

.field final origin:Ljava/lang/String;

.field private final rules:Lorg/json/JSONObject;


# direct methods
.method rule(Ljava/lang/String;)Ljava/lang/String;
    .locals 1

    # 读一条顶层规则（字符串）。原来只有 object() 能取 JSONObject，
    # 而 adapter / 以后其它标量字段取不出来。
    #
    # 新增而不是改现有的：SiteProfile 不在 MainActivity 那张 packed-switch 的
    # 索引链路上，加方法不影响任何调用点。
    #
    # rules 是 private，但这里就在 SiteProfile 内部，读自己的字段合法。
    iget-object v0, p0, Lcom/huangguo/mobile/SiteProfile;->rules:Lorg/json/JSONObject;

    invoke-virtual {v0, p1}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v0

    return-object v0
.end method


.method constructor <init>(Landroid/content/Context;Lorg/json/JSONObject;)V
    .locals 4
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    .line 19
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    # 用户自带的源优先（2026-10-16）。
    #
    # UserSource 读 files/user-source.json，那是用户通过 __source:set 桥写的。
    # 拿到就塞进 p2 —— 下面的 merge(内置, p2) 会把它当作覆盖层。这条合并路径
    # 本来就在，原来是给热更新的 site.json 用的；热更新切了之后正好拿来装用户配置。
    #
    # 位置有讲究：必须插在 getAssets() 之前。那一行会把 p1 从 Context 覆盖成
    # AssetManager，之后就再也拿不到 Context 了。
    invoke-static {p1}, Lcom/huangguo/mobile/UserSource;->has(Landroid/content/Context;)Z

    move-result v0

    if-eqz v0, :profile_no_user_source

    invoke-static {p1}, Lcom/huangguo/mobile/UserSource;->read(Landroid/content/Context;)Lorg/json/JSONObject;

    move-result-object p2

    :profile_no_user_source

    .line 20
    new-instance v0, Lorg/json/JSONObject;

    new-instance v1, Ljava/lang/String;

    .line 21
    invoke-virtual {p1}, Landroid/content/Context;->getAssets()Landroid/content/res/AssetManager;

    move-result-object p1

    const-string v2, "site-default.json"

    invoke-virtual {p1, v2}, Landroid/content/res/AssetManager;->open(Ljava/lang/String;)Ljava/io/InputStream;

    move-result-object p1

    const/high16 v2, 0x20000

    .line 20
    invoke-static {p1, v2}, Lcom/huangguo/mobile/HotUpdateManager;->read(Ljava/io/InputStream;I)[B

    move-result-object p1

    sget-object v2, Ljava/nio/charset/StandardCharsets;->UTF_8:Ljava/nio/charset/Charset;

    invoke-direct {v1, p1, v2}, Ljava/lang/String;-><init>([BLjava/nio/charset/Charset;)V

    invoke-direct {v0, v1}, Lorg/json/JSONObject;-><init>(Ljava/lang/String;)V

    if-nez p2, :cond_0

    .line 22
    new-instance p2, Lorg/json/JSONObject;

    invoke-direct {p2}, Lorg/json/JSONObject;-><init>()V

    :cond_0
    invoke-static {v0, p2}, Lcom/huangguo/mobile/SiteProfile;->merge(Lorg/json/JSONObject;Lorg/json/JSONObject;)Lorg/json/JSONObject;

    move-result-object p1

    iput-object p1, p0, Lcom/huangguo/mobile/SiteProfile;->rules:Lorg/json/JSONObject;
    .line 23
    const-string p2, "origin"

    invoke-virtual {p1, p2}, Lorg/json/JSONObject;->getString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p1

    const-string p2, "/+$"

    const-string v0, ""

    invoke-virtual {p1, p2, v0}, Ljava/lang/String;->replaceAll(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;

    move-result-object p1

    # ── 改造版：origin 钉死在这里，不吃远端 site.json 的值 ──────────────────
    # 上游的写法是「从 merge 后的规则里读 origin」，也就是热更新包说了算。
    # 上游 site.json 的 origin 是 https://huangguoai.com，这个域名在国内被 DNS
    # 污染 —— 实测同一台手机三次解析出三个互不相同的外网地址
    # （69.171.228.74 / 74.86.151.167，公网 DoH 又给 199.96.58.85 / 128.242.245.244），
    # 拿它当接口入口等于把可用性交给运气。
    #
    # 上面那 6 条指令（getString("origin") → replaceAll("/+$") → move-result）
    # 是上游原文，结果现在被下面这行直接覆盖，属于死代码。之所以保留不删：
    # 本方法 .locals 4，v0/v1/v2 在后面还有复用，删指令要连带核对整段寄存器，
    # 收益不抵风险。读到「算完又覆盖」不是笔误，是有意的。
    #
    # 其余规则（routes / selectors / headers / categories）照旧走 merge，
    # 所以站点改版时上游的结构性修复仍然吃得到。
    # origin 交给合并结果（2026-10-16 改）。这里原来是无条件覆盖成内置域名，
    # 会盖掉用户自带的源。现在把上面算出来的 p1（merge → getString → 去尾斜杠）
    # 落进字段 —— 这是 origin 唯一的赋值点，漏了就永远是 null。
    iput-object p1, p0, Lcom/huangguo/mobile/SiteProfile;->origin:Ljava/lang/String;

    # 没配源时 origin 是空串（首次启动、用户还没填）。这时必须跳过下面整段 URI 校验：
    # 空串会让 "https".equals(getScheme()) 为 false，落到 :cond_d 抛
    # 「站点必须是 HTTPS 域名」。而那是在 Activity.onCreate 里，表现为一装完就闪退，
    # 用户连设置页都看不到 —— 恰好把「不带默认源」变成了「装完打不开」。
    #
    # 空 origin 不等于配置错了，只代表还没配：前端会停在设置页，一个请求都不会发。
    invoke-virtual {p1}, Ljava/lang/String;->isEmpty()Z

    move-result v0

    if-nez v0, :profile_skip_origin_check

    .line 24
    new-instance p2, Ljava/net/URI;

    invoke-direct {p2, p1}, Ljava/net/URI;-><init>(Ljava/lang/String;)V

    .line 25
    const-string p1, "https"

    invoke-virtual {p2}, Ljava/net/URI;->getScheme()Ljava/lang/String;

    move-result-object v1

    invoke-virtual {p1, v1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result p1

    if-eqz p1, :cond_d

    invoke-virtual {p2}, Ljava/net/URI;->getHost()Ljava/lang/String;

    move-result-object p1

    if-eqz p1, :cond_d

    invoke-virtual {p2}, Ljava/net/URI;->getUserInfo()Ljava/lang/String;

    move-result-object p1

    if-nez p1, :cond_d

    .line 26
    invoke-virtual {p2}, Ljava/net/URI;->getQuery()Ljava/lang/String;

    move-result-object p1

    if-nez p1, :cond_d

    invoke-virtual {p2}, Ljava/net/URI;->getFragment()Ljava/lang/String;

    move-result-object p1

    if-nez p1, :cond_d

    invoke-virtual {p2}, Ljava/net/URI;->getPath()Ljava/lang/String;

    move-result-object p1

    invoke-virtual {p1}, Ljava/lang/String;->isEmpty()Z

    move-result p1

    if-eqz p1, :cond_d

    :profile_skip_origin_check
    .line 28
    const-string p1, "cover"

    invoke-virtual {p0, p1}, Lcom/huangguo/mobile/SiteProfile;->object(Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object p2

    const-string v0, "allowedHosts"

    invoke-virtual {p2, v0}, Lorg/json/JSONObject;->getJSONArray(Ljava/lang/String;)Lorg/json/JSONArray;

    move-result-object p2

    .line 38
    invoke-virtual {p2}, Lorg/json/JSONArray;->length()I

    move-result v0

    if-eqz v0, :cond_c

    const/4 v0, 0x0

    .line 39
    :goto_3
    invoke-virtual {p2}, Lorg/json/JSONArray;->length()I

    move-result v1

    if-ge v0, v1, :cond_6

    .line 40
    invoke-virtual {p2, v0}, Lorg/json/JSONArray;->getString(I)Ljava/lang/String;

    move-result-object v1

    const-string v2, "[a-zA-Z0-9.-]+"

    invoke-virtual {v1, v2}, Ljava/lang/String;->matches(Ljava/lang/String;)Z

    move-result v1

    if-eqz v1, :cond_5

    add-int/lit8 v0, v0, 0x1

    goto :goto_3

    :cond_5
    new-instance p0, Ljava/lang/IllegalArgumentException;

    const-string p1, "\u65e0\u6548\u5c01\u9762\u57df\u540d"

    invoke-direct {p0, p1}, Ljava/lang/IllegalArgumentException;-><init>(Ljava/lang/String;)V

    throw p0

    .line 42
    :cond_6
    invoke-virtual {p0, p1}, Lcom/huangguo/mobile/SiteProfile;->object(Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object p2

    const-string v0, "mode"

    invoke-virtual {p2, v0}, Lorg/json/JSONObject;->getString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p2

    .line 43
    const-string v0, "aes-cbc"

    const-string v1, "auto"

    const-string v2, "plain"

    filled-new-array {v2, v0, v1}, [Ljava/lang/String;

    move-result-object v0

    invoke-static {v0}, Ljava/util/Arrays;->asList([Ljava/lang/Object;)Ljava/util/List;

    move-result-object v0

    invoke-interface {v0, p2}, Ljava/util/List;->contains(Ljava/lang/Object;)Z

    move-result v0

    if-eqz v0, :cond_b

    .line 45
    invoke-virtual {p0, p1}, Lcom/huangguo/mobile/SiteProfile;->object(Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object p1

    const-string v0, "transformation"

    invoke-virtual {p1, v0}, Lorg/json/JSONObject;->getString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p1

    .line 46
    const-string v0, "AES/CBC/NoPadding"

    const-string v1, "AES/CBC/PKCS5Padding"

    filled-new-array {v0, v1}, [Ljava/lang/String;

    move-result-object v0

    invoke-static {v0}, Ljava/util/Arrays;->asList([Ljava/lang/Object;)Ljava/util/List;

    move-result-object v0

    invoke-interface {v0, p1}, Ljava/util/List;->contains(Ljava/lang/Object;)Z

    move-result p1

    if-eqz p1, :cond_a

    .line 48
    invoke-virtual {v2, p2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result p1

    if-nez p1, :cond_9

    const-string p1, "key"

    invoke-virtual {p0, p1}, Lcom/huangguo/mobile/SiteProfile;->keyBytes(Ljava/lang/String;)[B

    move-result-object p2

    array-length p2, p2

    const/16 v0, 0x10

    if-eq p2, v0, :cond_7

    invoke-virtual {p0, p1}, Lcom/huangguo/mobile/SiteProfile;->keyBytes(Ljava/lang/String;)[B

    move-result-object p2

    array-length p2, p2

    const/16 v1, 0x18

    if-eq p2, v1, :cond_7

    .line 49
    invoke-virtual {p0, p1}, Lcom/huangguo/mobile/SiteProfile;->keyBytes(Ljava/lang/String;)[B

    move-result-object p1

    array-length p1, p1

    const/16 p2, 0x20

    if-ne p1, p2, :cond_8

    :cond_7
    const-string p1, "iv"

    invoke-virtual {p0, p1}, Lcom/huangguo/mobile/SiteProfile;->keyBytes(Ljava/lang/String;)[B

    move-result-object p1

    array-length p1, p1

    if-ne p1, v0, :cond_8

    goto :goto_4

    .line 50
    :cond_8
    new-instance p0, Ljava/lang/IllegalArgumentException;

    const-string p1, "\u65e0\u6548\u5c01\u9762\u5bc6\u94a5\u6216 IV"

    invoke-direct {p0, p1}, Ljava/lang/IllegalArgumentException;-><init>(Ljava/lang/String;)V

    throw p0

    .line 51
    :cond_9
    :goto_4
    const-string p1, "SHA-256"

    invoke-static {p1}, Ljava/security/MessageDigest;->getInstance(Ljava/lang/String;)Ljava/security/MessageDigest;

    move-result-object p1

    iget-object p2, p0, Lcom/huangguo/mobile/SiteProfile;->rules:Lorg/json/JSONObject;

    invoke-virtual {p2}, Lorg/json/JSONObject;->toString()Ljava/lang/String;

    move-result-object p2

    sget-object v0, Ljava/nio/charset/StandardCharsets;->UTF_8:Ljava/nio/charset/Charset;

    invoke-virtual {p2, v0}, Ljava/lang/String;->getBytes(Ljava/nio/charset/Charset;)[B

    move-result-object p2

    invoke-virtual {p1, p2}, Ljava/security/MessageDigest;->digest([B)[B

    move-result-object p1

    invoke-static {p1}, Lcom/huangguo/mobile/SiteProfile;->hex([B)Ljava/lang/String;

    move-result-object p1

    iput-object p1, p0, Lcom/huangguo/mobile/SiteProfile;->fingerprint:Ljava/lang/String;

    return-void

    .line 47
    :cond_a
    new-instance p0, Ljava/lang/IllegalArgumentException;

    const-string p1, "\u4e0d\u652f\u6301\u7684\u89e3\u5bc6\u65b9\u5f0f"

    invoke-direct {p0, p1}, Ljava/lang/IllegalArgumentException;-><init>(Ljava/lang/String;)V

    throw p0

    .line 44
    :cond_b
    new-instance p0, Ljava/lang/IllegalArgumentException;

    const-string p1, "\u4e0d\u652f\u6301\u7684\u5c01\u9762\u6a21\u5f0f"

    invoke-direct {p0, p1}, Ljava/lang/IllegalArgumentException;-><init>(Ljava/lang/String;)V

    throw p0

    .line 38
    :cond_c
    new-instance p0, Ljava/lang/IllegalArgumentException;

    const-string p1, "\u7f3a\u5c11\u5c01\u9762\u57df\u540d"

    invoke-direct {p0, p1}, Ljava/lang/IllegalArgumentException;-><init>(Ljava/lang/String;)V

    throw p0

    .line 27
    :cond_d
    new-instance p0, Ljava/lang/IllegalArgumentException;

    const-string p1, "\u7ad9\u70b9\u5fc5\u987b\u662f HTTPS \u57df\u540d"

    invoke-direct {p0, p1}, Ljava/lang/IllegalArgumentException;-><init>(Ljava/lang/String;)V

    throw p0
.end method

.method static hex([B)Ljava/lang/String;
    .locals 6

    .line 147
    new-instance v0, Ljava/lang/StringBuilder;

    invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V

    .line 148
    array-length v1, p0

    const/4 v2, 0x0

    :goto_0
    if-ge v2, v1, :cond_0

    aget-byte v3, p0, v2

    sget-object v4, Ljava/util/Locale;->ROOT:Ljava/util/Locale;

    and-int/lit16 v3, v3, 0xff

    invoke-static {v3}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

    move-result-object v3

    filled-new-array {v3}, [Ljava/lang/Object;

    move-result-object v3

    const-string v5, "%02x"

    invoke-static {v4, v5, v3}, Ljava/lang/String;->format(Ljava/util/Locale;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;

    move-result-object v3

    invoke-virtual {v0, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    add-int/lit8 v2, v2, 0x1

    goto :goto_0

    .line 149
    :cond_0
    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p0

    return-object p0
.end method

.method private static merge(Lorg/json/JSONObject;Lorg/json/JSONObject;)Lorg/json/JSONObject;
    .locals 4
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    .line 55
    new-instance v0, Lorg/json/JSONObject;

    invoke-virtual {p0}, Lorg/json/JSONObject;->toString()Ljava/lang/String;

    move-result-object p0

    invoke-direct {v0, p0}, Lorg/json/JSONObject;-><init>(Ljava/lang/String;)V

    .line 56
    invoke-virtual {p1}, Lorg/json/JSONObject;->keys()Ljava/util/Iterator;

    move-result-object p0

    :goto_0
    invoke-interface {p0}, Ljava/util/Iterator;->hasNext()Z

    move-result v1

    if-eqz v1, :cond_1

    .line 57
    invoke-interface {p0}, Ljava/util/Iterator;->next()Ljava/lang/Object;

    move-result-object v1

    check-cast v1, Ljava/lang/String;

    .line 58
    invoke-virtual {p1, v1}, Lorg/json/JSONObject;->get(Ljava/lang/String;)Ljava/lang/Object;

    move-result-object v2

    .line 59
    instance-of v3, v2, Lorg/json/JSONObject;

    if-eqz v3, :cond_0

    invoke-virtual {v0, v1}, Lorg/json/JSONObject;->opt(Ljava/lang/String;)Ljava/lang/Object;

    move-result-object v3

    instance-of v3, v3, Lorg/json/JSONObject;

    if-eqz v3, :cond_0

    .line 60
    invoke-virtual {v0, v1}, Lorg/json/JSONObject;->getJSONObject(Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object v3

    check-cast v2, Lorg/json/JSONObject;

    invoke-static {v3, v2}, Lcom/huangguo/mobile/SiteProfile;->merge(Lorg/json/JSONObject;Lorg/json/JSONObject;)Lorg/json/JSONObject;

    move-result-object v2

    invoke-virtual {v0, v1, v2}, Lorg/json/JSONObject;->put(Ljava/lang/String;Ljava/lang/Object;)Lorg/json/JSONObject;

    goto :goto_0

    .line 61
    :cond_0
    invoke-virtual {v0, v1, v2}, Lorg/json/JSONObject;->put(Ljava/lang/String;Ljava/lang/Object;)Lorg/json/JSONObject;

    goto :goto_0

    :cond_1
    return-object v0
.end method


# virtual methods


.method coverUrl(Ljava/lang/String;)Ljava/lang/String;
    .locals 12
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    .line 119
    new-instance v0, Ljava/net/URI;

    invoke-direct {v0, p1}, Ljava/net/URI;-><init>(Ljava/lang/String;)V

    .line 120
    invoke-virtual {v0}, Ljava/net/URI;->getHost()Ljava/lang/String;

    move-result-object p1

    .line 121
    const-string v1, "https"

    invoke-virtual {v0}, Ljava/net/URI;->getScheme()Ljava/lang/String;

    move-result-object v2

    invoke-virtual {v1, v2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v1

    if-eqz v1, :cond_3

    if-eqz p1, :cond_3

    invoke-virtual {v0}, Ljava/net/URI;->getUserInfo()Ljava/lang/String;

    move-result-object v1

    if-nez v1, :cond_3

    .line 123
    const-string v1, "cover"

    invoke-virtual {p0, v1}, Lcom/huangguo/mobile/SiteProfile;->object(Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object v2

    const-string v3, "hostRewrites"

    invoke-virtual {v2, v3}, Lorg/json/JSONObject;->optJSONObject(Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object v2

    if-eqz v2, :cond_0

    .line 124
    invoke-virtual {v2, p1}, Lorg/json/JSONObject;->has(Ljava/lang/String;)Z

    move-result v3

    if-eqz v3, :cond_0

    .line 125
    invoke-virtual {v2, p1}, Lorg/json/JSONObject;->getString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v7

    .line 126
    new-instance v4, Ljava/net/URI;

    invoke-virtual {v0}, Ljava/net/URI;->getPort()I

    move-result v8

    invoke-virtual {v0}, Ljava/net/URI;->getPath()Ljava/lang/String;

    move-result-object v9

    invoke-virtual {v0}, Ljava/net/URI;->getQuery()Ljava/lang/String;

    move-result-object v10

    const/4 v11, 0x0

    const-string v5, "https"

    const/4 v6, 0x0

    invoke-direct/range {v4 .. v11}, Ljava/net/URI;-><init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V

    move-object v0, v4

    move-object p1, v7

    .line 128
    :cond_0
    invoke-virtual {p0, v1}, Lcom/huangguo/mobile/SiteProfile;->object(Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object p0

    const-string v1, "allowedHosts"

    invoke-virtual {p0, v1}, Lorg/json/JSONObject;->getJSONArray(Ljava/lang/String;)Lorg/json/JSONArray;

    move-result-object p0

    const/4 v1, 0x0

    .line 129
    :goto_0
    invoke-virtual {p0}, Lorg/json/JSONArray;->length()I

    move-result v2

    if-ge v1, v2, :cond_2

    invoke-virtual {p0, v1}, Lorg/json/JSONArray;->getString(I)Ljava/lang/String;

    move-result-object v2

    invoke-virtual {p1, v2}, Ljava/lang/String;->equalsIgnoreCase(Ljava/lang/String;)Z

    move-result v2

    if-eqz v2, :cond_1

    invoke-virtual {v0}, Ljava/net/URI;->toString()Ljava/lang/String;

    move-result-object p0

    return-object p0

    :cond_1
    add-int/lit8 v1, v1, 0x1

    goto :goto_0

    .line 130
    :cond_2
    new-instance p0, Ljava/lang/IllegalArgumentException;

    const-string p1, "\u65e0\u6548\u5c01\u9762\u57df\u540d"

    invoke-direct {p0, p1}, Ljava/lang/IllegalArgumentException;-><init>(Ljava/lang/String;)V

    throw p0

    .line 122
    :cond_3
    new-instance p0, Ljava/lang/IllegalArgumentException;

    const-string p1, "\u65e0\u6548\u5c01\u9762\u5730\u5740"

    invoke-direct {p0, p1}, Ljava/lang/IllegalArgumentException;-><init>(Ljava/lang/String;)V

    throw p0
.end method

.method keyBytes(Ljava/lang/String;)[B
    .locals 4

    .line 106
    const-string v0, "cover"

    invoke-virtual {p0, v0}, Lcom/huangguo/mobile/SiteProfile;->object(Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object v1

    invoke-virtual {v1, p1}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p1

    .line 107
    invoke-virtual {p0, v0}, Lcom/huangguo/mobile/SiteProfile;->object(Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object p0

    const-string v0, "keyEncoding"

    const-string v1, "text"

    invoke-virtual {p0, v0, v1}, Lorg/json/JSONObject;->optString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    .line 108
    const-string v0, "base64"

    invoke-virtual {v0, p0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v0

    const/4 v2, 0x0

    if-eqz v0, :cond_0

    invoke-static {p1, v2}, Landroid/util/Base64;->decode(Ljava/lang/String;I)[B

    move-result-object p0

    return-object p0

    .line 109
    :cond_0
    const-string v0, "hex"

    invoke-virtual {v0, p0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v0

    if-eqz v0, :cond_3

    .line 110
    const-string p0, "(?:[0-9a-fA-F]{2})+"

    invoke-virtual {p1, p0}, Ljava/lang/String;->matches(Ljava/lang/String;)Z

    move-result p0

    if-eqz p0, :cond_2

    .line 111
    invoke-virtual {p1}, Ljava/lang/String;->length()I

    move-result p0

    div-int/lit8 p0, p0, 0x2

    new-array v0, p0, [B

    :goto_0
    if-ge v2, p0, :cond_1

    mul-int/lit8 v1, v2, 0x2

    add-int/lit8 v3, v1, 0x2

    .line 112
    invoke-virtual {p1, v1, v3}, Ljava/lang/String;->substring(II)Ljava/lang/String;

    move-result-object v1

    const/16 v3, 0x10

    invoke-static {v1, v3}, Ljava/lang/Integer;->parseInt(Ljava/lang/String;I)I

    move-result v1

    int-to-byte v1, v1

    aput-byte v1, v0, v2

    add-int/lit8 v2, v2, 0x1

    goto :goto_0

    :cond_1
    return-object v0

    .line 110
    :cond_2
    new-instance p0, Ljava/lang/IllegalArgumentException;

    const-string p1, "\u65e0\u6548\u5341\u516d\u8fdb\u5236\u5bc6\u94a5"

    invoke-direct {p0, p1}, Ljava/lang/IllegalArgumentException;-><init>(Ljava/lang/String;)V

    throw p0

    .line 115
    :cond_3
    invoke-virtual {v1, p0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result p0

    if-eqz p0, :cond_4

    .line 116
    sget-object p0, Ljava/nio/charset/StandardCharsets;->UTF_8:Ljava/nio/charset/Charset;

    invoke-virtual {p1, p0}, Ljava/lang/String;->getBytes(Ljava/nio/charset/Charset;)[B

    move-result-object p0

    return-object p0

    .line 115
    :cond_4
    new-instance p0, Ljava/lang/IllegalArgumentException;

    const-string p1, "\u4e0d\u652f\u6301\u7684\u5bc6\u94a5\u7f16\u7801"

    invoke-direct {p0, p1}, Ljava/lang/IllegalArgumentException;-><init>(Ljava/lang/String;)V

    throw p0
.end method


.method object(Ljava/lang/String;)Lorg/json/JSONObject;
    .locals 0

    .line 66
    iget-object p0, p0, Lcom/huangguo/mobile/SiteProfile;->rules:Lorg/json/JSONObject;

    invoke-virtual {p0, p1}, Lorg/json/JSONObject;->optJSONObject(Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object p0

    return-object p0
.end method

.method patchHeader([B)Z
    .locals 8
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    .line 134
    const-string v0, "cover"

    invoke-virtual {p0, v0}, Lcom/huangguo/mobile/SiteProfile;->object(Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object p0

    const-string v0, "bytePatches"

    invoke-virtual {p0, v0}, Lorg/json/JSONObject;->optJSONArray(Ljava/lang/String;)Lorg/json/JSONArray;

    move-result-object p0

    const/4 v0, 0x0

    move v1, v0

    move v2, v1

    :goto_0
    if-eqz p0, :cond_3

    .line 135
    invoke-virtual {p0}, Lorg/json/JSONArray;->length()I

    move-result v3

    if-ge v1, v3, :cond_3

    .line 136
    invoke-virtual {p0, v1}, Lorg/json/JSONArray;->getJSONObject(I)Lorg/json/JSONObject;

    move-result-object v3

    .line 137
    const-string v4, "offset"

    invoke-virtual {v3, v4}, Lorg/json/JSONObject;->getInt(Ljava/lang/String;)I

    move-result v4

    if-ltz v4, :cond_2

    .line 138
    array-length v5, p1

    if-ge v4, v5, :cond_2

    .line 139
    aget-byte v5, p1, v4

    and-int/lit16 v5, v5, 0xff

    const-string v6, "from"

    invoke-virtual {v3, v6}, Lorg/json/JSONObject;->getInt(Ljava/lang/String;)I

    move-result v6

    if-ne v5, v6, :cond_1

    .line 140
    aget-byte v5, p1, v4

    and-int/lit16 v5, v5, 0xff

    const-string v6, "to"

    invoke-virtual {v3, v6}, Lorg/json/JSONObject;->getInt(Ljava/lang/String;)I

    move-result v7

    if-eq v5, v7, :cond_0

    const/4 v5, 0x1

    goto :goto_1

    :cond_0
    move v5, v0

    :goto_1
    or-int/2addr v2, v5

    .line 141
    invoke-virtual {v3, v6}, Lorg/json/JSONObject;->getInt(Ljava/lang/String;)I

    move-result v3

    int-to-byte v3, v3

    aput-byte v3, p1, v4

    :cond_1
    add-int/lit8 v1, v1, 0x1

    goto :goto_0

    .line 138
    :cond_2
    new-instance p0, Ljava/lang/IllegalArgumentException;

    const-string p1, "\u5c01\u9762\u8865\u4e01\u8d8a\u754c"

    invoke-direct {p0, p1}, Ljava/lang/IllegalArgumentException;-><init>(Ljava/lang/String;)V

    throw p0

    :cond_3
    return v2
.end method




