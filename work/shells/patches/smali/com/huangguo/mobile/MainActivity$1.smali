.class Lcom/huangguo/mobile/MainActivity$1;
.super Landroid/webkit/WebViewClient;
.source "MainActivity.java"


# annotations
.annotation system Ldalvik/annotation/EnclosingMethod;
    value = Lcom/huangguo/mobile/MainActivity;->onCreate(Landroid/os/Bundle;)V
.end annotation

.annotation system Ldalvik/annotation/InnerClass;
    accessFlags = 0x0
    name = null
.end annotation


# instance fields
.field final synthetic this$0:Lcom/huangguo/mobile/MainActivity;

.field final synthetic val$loader:Landroidx/webkit/WebViewAssetLoader;


# direct methods
.method public static synthetic $r8$lambda$d1UFMh8YvZPCnj5ipH3uZWM9D5o(Lcom/huangguo/mobile/MainActivity$1;Landroid/webkit/WebView;Ljava/lang/String;)V
    .locals 0

    invoke-direct {p0, p1, p2}, Lcom/huangguo/mobile/MainActivity$1;->lambda$onPageFinished$0(Landroid/webkit/WebView;Ljava/lang/String;)V

    return-void
.end method

.method constructor <init>(Lcom/huangguo/mobile/MainActivity;Landroidx/webkit/WebViewAssetLoader;)V
    .locals 0
    .annotation system Ldalvik/annotation/MethodParameters;
        accessFlags = {
            0x8010,
            0x1010
        }
        names = {
            null,
            null
        }
    .end annotation

    .line 130
    iput-object p2, p0, Lcom/huangguo/mobile/MainActivity$1;->val$loader:Landroidx/webkit/WebViewAssetLoader;

    iput-object p1, p0, Lcom/huangguo/mobile/MainActivity$1;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-direct {p0}, Landroid/webkit/WebViewClient;-><init>()V

    return-void
.end method

.method private synthetic lambda$onPageFinished$0(Landroid/webkit/WebView;Ljava/lang/String;)V
    .locals 4

    .line 144
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity$1;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-virtual {v0}, Lcom/huangguo/mobile/MainActivity;->isDestroyed()Z

    move-result v0

    if-eqz v0, :cond_0

    return-void

    :cond_0
    const/4 v0, 0x0

    .line 146
    :try_start_0
    new-instance v1, Lorg/json/JSONTokener;

    invoke-direct {v1, p2}, Lorg/json/JSONTokener;-><init>(Ljava/lang/String;)V

    invoke-virtual {v1}, Lorg/json/JSONTokener;->nextValue()Ljava/lang/Object;

    move-result-object p2

    .line 147
    iget-object v1, p0, Lcom/huangguo/mobile/MainActivity$1;->this$0:Lcom/huangguo/mobile/MainActivity;

    const-string v2, "hot-updates"

    invoke-virtual {v1, v2, v0}, Lcom/huangguo/mobile/MainActivity;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;

    move-result-object v1

    invoke-interface {v1}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences$Editor;

    move-result-object v1

    const-string v2, "originMigrated"

    const/4 v3, 0x1

    invoke-interface {v1, v2, v3}, Landroid/content/SharedPreferences$Editor;->putBoolean(Ljava/lang/String;Z)Landroid/content/SharedPreferences$Editor;

    move-result-object v1

    const-string v2, "adultConfirmed"

    const-string v3, "1"

    .line 148
    invoke-virtual {v3, p2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result p2

    invoke-interface {v1, v2, p2}, Landroid/content/SharedPreferences$Editor;->putBoolean(Ljava/lang/String;Z)Landroid/content/SharedPreferences$Editor;

    move-result-object p2

    invoke-interface {p2}, Landroid/content/SharedPreferences$Editor;->apply()V
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0

    .line 150
    :catch_0
    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity$1;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-static {p0, v0}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$fputmigratingOrigin(Lcom/huangguo/mobile/MainActivity;Z)V

    .line 151
    invoke-virtual {p1}, Landroid/webkit/WebView;->getSettings()Landroid/webkit/WebSettings;

    move-result-object p0

    invoke-virtual {p0, v0}, Landroid/webkit/WebSettings;->setAllowFileAccess(Z)V

    .line 152
    const-string p0, "https://appassets.androidplatform.net/assets/app.html"

    invoke-virtual {p1, p0}, Landroid/webkit/WebView;->loadUrl(Ljava/lang/String;)V

    return-void
.end method


# virtual methods
.method public onPageFinished(Landroid/webkit/WebView;Ljava/lang/String;)V
    .locals 1

    .line 142
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity$1;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-static {v0}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$fgetmigratingOrigin(Lcom/huangguo/mobile/MainActivity;)Z

    move-result v0

    if-eqz v0, :cond_0

    const-string v0, "file:///android_asset/app.html"

    invoke-virtual {v0, p2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result p2

    if-eqz p2, :cond_0

    .line 143
    new-instance p2, Lcom/huangguo/mobile/MainActivity$1$$ExternalSyntheticLambda0;

    invoke-direct {p2, p0, p1}, Lcom/huangguo/mobile/MainActivity$1$$ExternalSyntheticLambda0;-><init>(Lcom/huangguo/mobile/MainActivity$1;Landroid/webkit/WebView;)V

    const-string p0, "localStorage.getItem(\'adult-confirmed\')"

    invoke-virtual {p1, p0, p2}, Landroid/webkit/WebView;->evaluateJavascript(Ljava/lang/String;Landroid/webkit/ValueCallback;)V

    return-void

    .line 156
    :cond_0
    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity$1;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-static {p0}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$mpublishWindowInsets(Lcom/huangguo/mobile/MainActivity;)V

    return-void
.end method

.method public onPageStarted(Landroid/webkit/WebView;Ljava/lang/String;Landroid/graphics/Bitmap;)V
    .locals 0

    .line 135
    invoke-static {p2}, Landroid/net/Uri;->parse(Ljava/lang/String;)Landroid/net/Uri;

    move-result-object p1

    invoke-static {p1}, Lcom/huangguo/mobile/HotUpdateManager;->trusted(Landroid/net/Uri;)Z

    move-result p1

    if-eqz p1, :cond_0

    .line 136
    iget-object p1, p0, Lcom/huangguo/mobile/MainActivity$1;->this$0:Lcom/huangguo/mobile/MainActivity;

    const/4 p2, 0x0

    invoke-static {p1, p2}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$fputresourceReady(Lcom/huangguo/mobile/MainActivity;Z)V

    .line 137
    iget-object p1, p0, Lcom/huangguo/mobile/MainActivity$1;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-static {p1}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$fgethotHandler(Lcom/huangguo/mobile/MainActivity;)Landroid/os/Handler;

    move-result-object p1

    iget-object p2, p0, Lcom/huangguo/mobile/MainActivity$1;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-static {p2}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$fgetresourceTimeout(Lcom/huangguo/mobile/MainActivity;)Ljava/lang/Runnable;

    move-result-object p2

    invoke-virtual {p1, p2}, Landroid/os/Handler;->removeCallbacks(Ljava/lang/Runnable;)V

    .line 138
    iget-object p1, p0, Lcom/huangguo/mobile/MainActivity$1;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-static {p1}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$fgethotSession(Lcom/huangguo/mobile/MainActivity;)Lcom/huangguo/mobile/HotUpdateManager$Session;

    move-result-object p1

    iget-boolean p1, p1, Lcom/huangguo/mobile/HotUpdateManager$Session;->trial:Z

    if-eqz p1, :cond_0

    iget-object p1, p0, Lcom/huangguo/mobile/MainActivity$1;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-static {p1}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$fgethotHandler(Lcom/huangguo/mobile/MainActivity;)Landroid/os/Handler;

    move-result-object p1

    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity$1;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-static {p0}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$fgetresourceTimeout(Lcom/huangguo/mobile/MainActivity;)Ljava/lang/Runnable;

    move-result-object p0

    const-wide/16 p2, 0x3a98

    invoke-virtual {p1, p0, p2, p3}, Landroid/os/Handler;->postDelayed(Ljava/lang/Runnable;J)Z

    :cond_0
    return-void
.end method

.method public onReceivedError(Landroid/webkit/WebView;Landroid/webkit/WebResourceRequest;Landroid/webkit/WebResourceError;)V
    .locals 0

    .line 162
    invoke-interface {p2}, Landroid/webkit/WebResourceRequest;->isForMainFrame()Z

    move-result p1

    if-eqz p1, :cond_0

    iget-object p1, p0, Lcom/huangguo/mobile/MainActivity$1;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-static {p1}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$fgethotSession(Lcom/huangguo/mobile/MainActivity;)Lcom/huangguo/mobile/HotUpdateManager$Session;

    move-result-object p1

    iget-boolean p1, p1, Lcom/huangguo/mobile/HotUpdateManager$Session;->trial:Z

    if-eqz p1, :cond_0

    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity$1;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-static {p0}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$mrecoverResources(Lcom/huangguo/mobile/MainActivity;)V

    :cond_0
    return-void
.end method

.method public onReceivedHttpError(Landroid/webkit/WebView;Landroid/webkit/WebResourceRequest;Landroid/webkit/WebResourceResponse;)V
    .locals 0

    .line 165
    invoke-interface {p2}, Landroid/webkit/WebResourceRequest;->isForMainFrame()Z

    move-result p1

    if-eqz p1, :cond_0

    iget-object p1, p0, Lcom/huangguo/mobile/MainActivity$1;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-static {p1}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$fgethotSession(Lcom/huangguo/mobile/MainActivity;)Lcom/huangguo/mobile/HotUpdateManager$Session;

    move-result-object p1

    iget-boolean p1, p1, Lcom/huangguo/mobile/HotUpdateManager$Session;->trial:Z

    if-eqz p1, :cond_0

    invoke-virtual {p3}, Landroid/webkit/WebResourceResponse;->getStatusCode()I

    move-result p1

    const/16 p2, 0x190

    if-lt p1, p2, :cond_0

    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity$1;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-static {p0}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$mrecoverResources(Lcom/huangguo/mobile/MainActivity;)V

    :cond_0
    return-void
.end method

.method public onRenderProcessGone(Landroid/webkit/WebView;Landroid/webkit/RenderProcessGoneDetail;)Z
    .locals 2

    .line 168
    :try_start_0
    iget-object p2, p0, Lcom/huangguo/mobile/MainActivity$1;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-static {p2}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$fgethotUpdates(Lcom/huangguo/mobile/MainActivity;)Lcom/huangguo/mobile/HotUpdateManager;

    move-result-object p2

    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity$1;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-static {v0}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$fgethotSession(Lcom/huangguo/mobile/MainActivity;)Lcom/huangguo/mobile/HotUpdateManager$Session;

    move-result-object v0

    invoke-virtual {p2, v0}, Lcom/huangguo/mobile/HotUpdateManager;->rollback(Lcom/huangguo/mobile/HotUpdateManager$Session;)V
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0

    goto :goto_0

    :catch_0
    move-exception p2

    const-string v0, "AIIvideo"

    const-string v1, "\u8d44\u6e90\u56de\u9000\u5931\u8d25"

    invoke-static {v0, v1, p2}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I

    .line 169
    :goto_0
    iget-object p2, p0, Lcom/huangguo/mobile/MainActivity$1;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-static {p2}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$fgetroot(Lcom/huangguo/mobile/MainActivity;)Landroid/widget/FrameLayout;

    move-result-object p2

    invoke-virtual {p2, p1}, Landroid/widget/FrameLayout;->removeView(Landroid/view/View;)V

    invoke-virtual {p1}, Landroid/webkit/WebView;->destroy()V

    iget-object p1, p0, Lcom/huangguo/mobile/MainActivity$1;->this$0:Lcom/huangguo/mobile/MainActivity;

    const/4 p2, 0x0

    invoke-static {p1, p2}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$fputwebView(Lcom/huangguo/mobile/MainActivity;Landroid/webkit/WebView;)V

    .line 170
    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity$1;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-virtual {p0}, Lcom/huangguo/mobile/MainActivity;->recreate()V

    const/4 p0, 0x1

    return p0
.end method

.method public shouldInterceptRequest(Landroid/webkit/WebView;Landroid/webkit/WebResourceRequest;)Landroid/webkit/WebResourceResponse;
    .locals 2

    # ── 改造：媒体请求先问一句 MediaProxy ──────────────────────────────
    # bilibili 那族 CDN 对同一个签名地址，HTTP/2 后端回 403、HTTP/1.1 回 206，
    # 而 <video> 用什么协议是 WebView 自己谈的，前端无从干涉。所以命中那几个
    # 域名时自己用 OkHttp 发（强制 HTTP/1.1），再把响应原样还给 WebView。
    #
    # 先过 handles() 再 fetch()：不然每个 assets 请求都会被多发一次。
    # fetch() 拿不到东西会返回 null，那时照旧走下面 WebViewAssetLoader 那条路。
    invoke-interface {p2}, Landroid/webkit/WebResourceRequest;->getUrl()Landroid/net/Uri;

    move-result-object v0

    invoke-virtual {v0}, Landroid/net/Uri;->toString()Ljava/lang/String;

    move-result-object v0

    invoke-static {v0}, Lcom/huangguo/mobile/MediaProxy;->handles(Ljava/lang/String;)Z

    move-result v0

    if-eqz v0, :cond_proxy_end

    invoke-static {p2}, Lcom/huangguo/mobile/MediaProxy;->fetch(Landroid/webkit/WebResourceRequest;)Landroid/webkit/WebResourceResponse;

    move-result-object v0

    if-eqz v0, :cond_proxy_end

    return-object v0

    :cond_proxy_end
    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity$1;->val$loader:Landroidx/webkit/WebViewAssetLoader;

    invoke-interface {p2}, Landroid/webkit/WebResourceRequest;->getUrl()Landroid/net/Uri;

    move-result-object p1

    invoke-virtual {p0, p1}, Landroidx/webkit/WebViewAssetLoader;->shouldInterceptRequest(Landroid/net/Uri;)Landroid/webkit/WebResourceResponse;

    move-result-object p0

    return-object p0
.end method

.method public shouldOverrideUrlLoading(Landroid/webkit/WebView;Landroid/webkit/WebResourceRequest;)Z
    .locals 0

    .line 159
    invoke-interface {p2}, Landroid/webkit/WebResourceRequest;->getUrl()Landroid/net/Uri;

    move-result-object p0

    invoke-static {p0}, Lcom/huangguo/mobile/HotUpdateManager;->trusted(Landroid/net/Uri;)Z

    move-result p0

    xor-int/lit8 p0, p0, 0x1

    return p0
.end method
