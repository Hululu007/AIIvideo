.class public final Lcom/huangguo/mobile/HuangGuoApp;
.super Landroid/app/Application;
.source "HuangGuoApp.java"


# direct methods
.method public constructor <init>()V
    .locals 0

    .line 7
    invoke-direct {p0}, Landroid/app/Application;-><init>()V

    return-void
.end method


# virtual methods
.method public onCreate()V
    .locals 2

    .line 9
    invoke-super {p0}, Landroid/app/Application;->onCreate()V

    .line 10
    new-instance v0, Lcom/parse/Parse$Configuration$Builder;

    invoke-direct {v0, p0}, Lcom/parse/Parse$Configuration$Builder;-><init>(Landroid/content/Context;)V

    sget v1, Lcom/huangguo/mobile/R$string;->back4app_app_id:I

    .line 11
    invoke-virtual {p0, v1}, Lcom/huangguo/mobile/HuangGuoApp;->getString(I)Ljava/lang/String;

    move-result-object v1

    invoke-virtual {v0, v1}, Lcom/parse/Parse$Configuration$Builder;->applicationId(Ljava/lang/String;)Lcom/parse/Parse$Configuration$Builder;

    move-result-object v0

    sget v1, Lcom/huangguo/mobile/R$string;->back4app_client_key:I

    .line 12
    invoke-virtual {p0, v1}, Lcom/huangguo/mobile/HuangGuoApp;->getString(I)Ljava/lang/String;

    move-result-object p0

    invoke-virtual {v0, p0}, Lcom/parse/Parse$Configuration$Builder;->clientKey(Ljava/lang/String;)Lcom/parse/Parse$Configuration$Builder;

    move-result-object p0

    # 账号后端换成自建的（server/account/，Parse REST 子集 + PostgreSQL）。
    # 上一版是原作者的 Back4App 托管服务 —— 我们既不该依赖它，也改不了它。
    # appId / clientKey 还是 res/values/strings.xml 里那两个，服务端的
    # PARSE_APP_ID 要和它一致，不然每个请求都是 403。
    const-string v0, "https://149.104.21.177.sslip.io/api/"

    .line 13
    invoke-virtual {p0, v0}, Lcom/parse/Parse$Configuration$Builder;->server(Ljava/lang/String;)Lcom/parse/Parse$Configuration$Builder;

    move-result-object p0

    .line 14
    invoke-virtual {p0}, Lcom/parse/Parse$Configuration$Builder;->build()Lcom/parse/Parse$Configuration;

    move-result-object p0

    .line 10
    invoke-static {p0}, Lcom/parse/Parse;->initialize(Lcom/parse/Parse$Configuration;)V

    return-void
.end method
