.class public final Lcom/huangguo/mobile/MainActivity;
.super Landroid/app/Activity;
.source "MainActivity.java"


# annotations
.annotation system Ldalvik/annotation/MemberClasses;
    value = {
        Lcom/huangguo/mobile/MainActivity$Bridge;
    }
.end annotation


# static fields
# 当前活着的 MainActivity 实例。换源之后 SiteProfile 必须重建（它只在 onCreate 里
# 构造一次），而 WebView 的 location.reload() 不会重建 Activity —— 所以
# UserSource 拿这个引用调 restart()。onDestroy 里置空，别留已销毁的实例。
.field public static volatile host:Lcom/huangguo/mobile/MainActivity;

.field private static final AVATAR_PICKER:I = 0x1bbd

.field private static final TAG:Ljava/lang/String; = "AIIvideo"


# instance fields
.field private account:Lcom/huangguo/mobile/AccountService;

.field private backCallback:Landroid/window/OnBackInvokedCallback;

.field private backLeadPx:F

.field private backProgressGeneration:I

.field private backProgressInFlight:Z

.field private backRegistered:Z

.field private backViewLeft:I

.field private backViewWidth:I

.field private final coverRequests:Ljava/util/concurrent/ExecutorService;

.field private detailSystemBars:Z

.field private final hotHandler:Landroid/os/Handler;

.field private hotSession:Lcom/huangguo/mobile/HotUpdateManager$Session;

.field private hotUpdates:Lcom/huangguo/mobile/HotUpdateManager;

.field private final localRequests:Ljava/util/concurrent/ExecutorService;

.field private migratingOrigin:Z

.field private pendingBackProgress:F

.field private pendingUpdateApk:Ljava/io/File;

.field private repository:Lcom/huangguo/mobile/DualContentRepository;

.field private final requests:Ljava/util/concurrent/ExecutorService;

.field private resourceReady:Z

.field private final resourceTimeout:Ljava/lang/Runnable;

.field private root:Landroid/widget/FrameLayout;

.field private volatile statusBarInsetDp:F

.field private store:Lcom/huangguo/mobile/AppStore;

.field private updates:Lcom/huangguo/mobile/UpdateManager;

.field private webView:Landroid/webkit/WebView;


# direct methods
.method public static synthetic $r8$lambda$A5KcuPJf-vVR96HtZxpmldEcBFc(Lcom/huangguo/mobile/MainActivity;I)V
    .locals 0

    invoke-direct {p0, p1}, Lcom/huangguo/mobile/MainActivity;->lambda$dispatch$2(I)V

    return-void
.end method

.method public static synthetic $r8$lambda$BV32DXs7agocMibFGehPL2IzxZ4(Lcom/huangguo/mobile/MainActivity;Ljava/lang/String;)V
    .locals 0

    invoke-direct {p0, p1}, Lcom/huangguo/mobile/MainActivity;->lambda$handleBack$0(Ljava/lang/String;)V

    return-void
.end method

.method public static synthetic $r8$lambda$Cq9W-g-G1CAEvvfy0WnOQ1ECHgA(Lcom/huangguo/mobile/MainActivity;Landroid/net/Uri;)V
    .locals 0

    invoke-direct {p0, p1}, Lcom/huangguo/mobile/MainActivity;->lambda$onActivityResult$0(Landroid/net/Uri;)V

    return-void
.end method

.method public static synthetic $r8$lambda$EOu3RyGz3_I0B460wCs9snwWRYE(Lcom/huangguo/mobile/MainActivity;Ljava/lang/String;)V
    .locals 0

    invoke-direct {p0, p1}, Lcom/huangguo/mobile/MainActivity;->lambda$refreshAccountAfterAuth$2(Ljava/lang/String;)V

    return-void
.end method

.method public static synthetic $r8$lambda$H00p9fCYGfZfIa4ZldwBXhUpDtA(Lcom/huangguo/mobile/MainActivity;Landroid/content/Intent;)V
    .locals 0

    invoke-direct {p0, p1}, Lcom/huangguo/mobile/MainActivity;->lambda$dispatch$8(Landroid/content/Intent;)V

    return-void
.end method

.method public static synthetic $r8$lambda$HrlZHkDOdpWTsCTpzDh_alSAVGs(Lcom/huangguo/mobile/MainActivity;Lorg/json/JSONObject;)V
    .locals 0

    invoke-direct {p0, p1}, Lcom/huangguo/mobile/MainActivity;->lambda$onActivityResult$1(Lorg/json/JSONObject;)V

    return-void
.end method

.method public static synthetic $r8$lambda$LarwegKwx-b6qWcdlhA5Wad1b1k(Lcom/huangguo/mobile/MainActivity;)V
    .locals 0

    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->lambda$checkHotUpdates$0()V

    return-void
.end method

.method public static synthetic $r8$lambda$Le2ogUmE8KsDajt0HOkVthAkAZ0(Lcom/huangguo/mobile/MainActivity;)V
    .locals 0

    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->applyPendingResources()V

    return-void
.end method

.method public static synthetic $r8$lambda$MXMaK43hQKyTU6JyI-PMLNnjgQo(Lcom/huangguo/mobile/MainActivity;Lcom/huangguo/mobile/AccountService;Ljava/lang/String;)V
    .locals 0

    invoke-direct {p0, p1, p2}, Lcom/huangguo/mobile/MainActivity;->lambda$refreshAccountSilently$0(Lcom/huangguo/mobile/AccountService;Ljava/lang/String;)V

    return-void
.end method

.method public static synthetic $r8$lambda$QuUMybkPIMx-28ISYui6RJ0CnYI(Lcom/huangguo/mobile/MainActivity;)V
    .locals 0

    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->lambda$refreshAccountAfterAuth$1()V

    return-void
.end method

.method public static synthetic $r8$lambda$SJn5AaLIJgVKGjt5YOankmL1_yk(Lcom/huangguo/mobile/MainActivity;Ljava/io/File;)V
    .locals 0

    invoke-direct {p0, p1}, Lcom/huangguo/mobile/MainActivity;->lambda$dispatch$4(Ljava/io/File;)V

    return-void
.end method

.method public static synthetic $r8$lambda$U9PDRTCVQdDwzzWJXtgo0UbKkxo(Lcom/huangguo/mobile/MainActivity;Lorg/json/JSONObject;)V
    .locals 0

    invoke-direct {p0, p1}, Lcom/huangguo/mobile/MainActivity;->lambda$refreshAccountSilently$1(Lorg/json/JSONObject;)V

    return-void
.end method

.method public static synthetic $r8$lambda$Yb5pkrdijuPHqjWB-AAlwPXxWKE(Lcom/huangguo/mobile/MainActivity;ILjava/lang/String;)V
    .locals 0

    invoke-direct {p0, p1, p2}, Lcom/huangguo/mobile/MainActivity;->lambda$sendLatestBackProgress$0(ILjava/lang/String;)V

    return-void
.end method

.method public static synthetic $r8$lambda$cWh_DZeKtwoN1xXmfluRcmDEigE(Lcom/huangguo/mobile/MainActivity;Ljava/lang/String;)V
    .locals 0

    invoke-direct {p0, p1}, Lcom/huangguo/mobile/MainActivity;->lambda$onActivityResult$2(Ljava/lang/String;)V

    return-void
.end method

.method public static synthetic $r8$lambda$fLWD078hMmEojmtqOrLXJNuMaCw(Lcom/huangguo/mobile/MainActivity;)V
    .locals 0

    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->lambda$onResume$1()V

    return-void
.end method

.method public static synthetic $r8$lambda$flXyCV7Vu_ylpr7CtsBgf5EkLHA(Lcom/huangguo/mobile/MainActivity;)V
    .locals 0

    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->lambda$onResume$0()V

    return-void
.end method

.method public static synthetic $r8$lambda$gnRaDzDYqELgDmH-mVYV3_X_k6c(Lcom/huangguo/mobile/MainActivity;)V
    .locals 0

    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->lambda$dispatch$0()V

    return-void
.end method

.method public static synthetic $r8$lambda$lVvTWO04J1R9g7VxJQkW5YC3J9w(Lcom/huangguo/mobile/MainActivity;Ljava/lang/String;)V
    .locals 0

    invoke-direct {p0, p1}, Lcom/huangguo/mobile/MainActivity;->lambda$applyPendingResources$0(Ljava/lang/String;)V

    return-void
.end method

.method public static synthetic $r8$lambda$mcBin20tMtHwhY1qeEfXXlnTSuI(Lcom/huangguo/mobile/MainActivity;Lorg/json/JSONObject;)V
    .locals 0

    invoke-direct {p0, p1}, Lcom/huangguo/mobile/MainActivity;->lambda$checkHotUpdates$1(Lorg/json/JSONObject;)V

    return-void
.end method

.method public static synthetic $r8$lambda$qb7UqpuzXQz1_GCm7kecgEDBIWA(Lcom/huangguo/mobile/MainActivity;I)V
    .locals 0

    invoke-direct {p0, p1}, Lcom/huangguo/mobile/MainActivity;->lambda$dispatch$3(I)V

    return-void
.end method

.method public static synthetic $r8$lambda$t0N5HHoiBMw8Pd6YItSE8k93rqs(Lcom/huangguo/mobile/MainActivity;Ljava/lang/String;)Landroid/webkit/WebResourceResponse;
    .locals 0

    invoke-direct {p0, p1}, Lcom/huangguo/mobile/MainActivity;->lambda$onCreate$1(Ljava/lang/String;)Landroid/webkit/WebResourceResponse;

    move-result-object p0

    return-object p0
.end method

.method public static synthetic $r8$lambda$tZv82cbpr80x9j8s-cnJwyPkBX0(Lcom/huangguo/mobile/MainActivity;)V
    .locals 0

    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->lambda$updateBackRegistration$0()V

    return-void
.end method

.method public static synthetic $r8$lambda$uD1f7azcpKWEv519edj8PniK7YE(Lcom/huangguo/mobile/MainActivity;)V
    .locals 0

    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->lambda$dispatch$1()V

    return-void
.end method

.method public static synthetic $r8$lambda$w-rhaFiiyTWC3Ziisgz7p-dF558(Lcom/huangguo/mobile/MainActivity;)V
    .locals 0

    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->lambda$new$0()V

    return-void
.end method

.method public static synthetic $r8$lambda$w2YIaYvVFB_TDXifrXu4XH2tmks(Lcom/huangguo/mobile/MainActivity;Ljava/lang/String;)V
    .locals 0

    invoke-direct {p0, p1}, Lcom/huangguo/mobile/MainActivity;->lambda$refreshAccountAfterAuth$0(Ljava/lang/String;)V

    return-void
.end method

.method public static synthetic $r8$lambda$yCNNlwLwu8mTbMAmz_jevbhQ6w8(Lcom/huangguo/mobile/MainActivity;Landroid/view/View;Landroid/view/WindowInsets;)Landroid/view/WindowInsets;
    .locals 0

    invoke-direct {p0, p1, p2}, Lcom/huangguo/mobile/MainActivity;->lambda$onCreate$0(Landroid/view/View;Landroid/view/WindowInsets;)Landroid/view/WindowInsets;

    move-result-object p0

    return-object p0
.end method

.method static bridge synthetic -$$Nest$fgetaccount(Lcom/huangguo/mobile/MainActivity;)Lcom/huangguo/mobile/AccountService;
    .locals 0

    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity;->account:Lcom/huangguo/mobile/AccountService;

    return-object p0
.end method

.method static bridge synthetic -$$Nest$fgetcoverRequests(Lcom/huangguo/mobile/MainActivity;)Ljava/util/concurrent/ExecutorService;
    .locals 0

    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity;->coverRequests:Ljava/util/concurrent/ExecutorService;

    return-object p0
.end method

.method static bridge synthetic -$$Nest$fgetdetailSystemBars(Lcom/huangguo/mobile/MainActivity;)Z
    .locals 0

    iget-boolean p0, p0, Lcom/huangguo/mobile/MainActivity;->detailSystemBars:Z

    return p0
.end method

.method static bridge synthetic -$$Nest$fgethotHandler(Lcom/huangguo/mobile/MainActivity;)Landroid/os/Handler;
    .locals 0

    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity;->hotHandler:Landroid/os/Handler;

    return-object p0
.end method

.method static bridge synthetic -$$Nest$fgethotSession(Lcom/huangguo/mobile/MainActivity;)Lcom/huangguo/mobile/HotUpdateManager$Session;
    .locals 0

    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity;->hotSession:Lcom/huangguo/mobile/HotUpdateManager$Session;

    return-object p0
.end method

.method static bridge synthetic -$$Nest$fgethotUpdates(Lcom/huangguo/mobile/MainActivity;)Lcom/huangguo/mobile/HotUpdateManager;
    .locals 0

    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity;->hotUpdates:Lcom/huangguo/mobile/HotUpdateManager;

    return-object p0
.end method

.method static bridge synthetic -$$Nest$fgetlocalRequests(Lcom/huangguo/mobile/MainActivity;)Ljava/util/concurrent/ExecutorService;
    .locals 0

    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity;->localRequests:Ljava/util/concurrent/ExecutorService;

    return-object p0
.end method

.method static bridge synthetic -$$Nest$fgetmigratingOrigin(Lcom/huangguo/mobile/MainActivity;)Z
    .locals 0

    iget-boolean p0, p0, Lcom/huangguo/mobile/MainActivity;->migratingOrigin:Z

    return p0
.end method

.method static bridge synthetic -$$Nest$fgetrequests(Lcom/huangguo/mobile/MainActivity;)Ljava/util/concurrent/ExecutorService;
    .locals 0

    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity;->requests:Ljava/util/concurrent/ExecutorService;

    return-object p0
.end method

.method static bridge synthetic -$$Nest$fgetresourceTimeout(Lcom/huangguo/mobile/MainActivity;)Ljava/lang/Runnable;
    .locals 0

    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity;->resourceTimeout:Ljava/lang/Runnable;

    return-object p0
.end method

.method static bridge synthetic -$$Nest$fgetroot(Lcom/huangguo/mobile/MainActivity;)Landroid/widget/FrameLayout;
    .locals 0

    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity;->root:Landroid/widget/FrameLayout;

    return-object p0
.end method

.method static bridge synthetic -$$Nest$fgetwebView(Lcom/huangguo/mobile/MainActivity;)Landroid/webkit/WebView;
    .locals 0

    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    return-object p0
.end method

.method static bridge synthetic -$$Nest$fputdetailSystemBars(Lcom/huangguo/mobile/MainActivity;Z)V
    .locals 0

    iput-boolean p1, p0, Lcom/huangguo/mobile/MainActivity;->detailSystemBars:Z

    return-void
.end method

.method static bridge synthetic -$$Nest$fputmigratingOrigin(Lcom/huangguo/mobile/MainActivity;Z)V
    .locals 0

    iput-boolean p1, p0, Lcom/huangguo/mobile/MainActivity;->migratingOrigin:Z

    return-void
.end method

.method static bridge synthetic -$$Nest$fputpendingBackProgress(Lcom/huangguo/mobile/MainActivity;F)V
    .locals 0

    iput p1, p0, Lcom/huangguo/mobile/MainActivity;->pendingBackProgress:F

    return-void
.end method

.method static bridge synthetic -$$Nest$fputresourceReady(Lcom/huangguo/mobile/MainActivity;Z)V
    .locals 0

    iput-boolean p1, p0, Lcom/huangguo/mobile/MainActivity;->resourceReady:Z

    return-void
.end method

.method static bridge synthetic -$$Nest$fputwebView(Lcom/huangguo/mobile/MainActivity;Landroid/webkit/WebView;)V
    .locals 0

    iput-object p1, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    return-void
.end method

.method static bridge synthetic -$$Nest$mapplySystemBars(Lcom/huangguo/mobile/MainActivity;)V
    .locals 0

    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->applySystemBars()V

    return-void
.end method

.method static bridge synthetic -$$Nest$mbackTouchFraction(Lcom/huangguo/mobile/MainActivity;Landroid/window/BackEvent;)F
    .locals 0

    invoke-direct {p0, p1}, Lcom/huangguo/mobile/MainActivity;->backTouchFraction(Landroid/window/BackEvent;)F

    move-result p0

    return p0
.end method

.method static bridge synthetic -$$Nest$mcaptureBackGeometry(Lcom/huangguo/mobile/MainActivity;)V
    .locals 0

    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->captureBackGeometry()V

    return-void
.end method

.method static bridge synthetic -$$Nest$mdispatch(Lcom/huangguo/mobile/MainActivity;Ljava/lang/String;Lorg/json/JSONObject;)Ljava/lang/Object;
    .locals 0

    invoke-direct {p0, p1, p2}, Lcom/huangguo/mobile/MainActivity;->dispatch(Ljava/lang/String;Lorg/json/JSONObject;)Ljava/lang/Object;

    move-result-object p0

    return-object p0
.end method

.method static bridge synthetic -$$Nest$mhandleBack(Lcom/huangguo/mobile/MainActivity;Z)V
    .locals 0

    invoke-direct {p0, p1}, Lcom/huangguo/mobile/MainActivity;->handleBack(Z)V

    return-void
.end method

.method static bridge synthetic -$$Nest$mpublishWindowInsets(Lcom/huangguo/mobile/MainActivity;)V
    .locals 0

    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->publishWindowInsets()V

    return-void
.end method

.method static bridge synthetic -$$Nest$mrecoverResources(Lcom/huangguo/mobile/MainActivity;)V
    .locals 0

    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->recoverResources()V

    return-void
.end method

.method static bridge synthetic -$$Nest$mrefreshAccountAfterAuth(Lcom/huangguo/mobile/MainActivity;Ljava/lang/String;)V
    .locals 0

    invoke-direct {p0, p1}, Lcom/huangguo/mobile/MainActivity;->refreshAccountAfterAuth(Ljava/lang/String;)V

    return-void
.end method

.method static bridge synthetic -$$Nest$mrefreshAccountSilently(Lcom/huangguo/mobile/MainActivity;Lcom/huangguo/mobile/AccountService;Ljava/lang/String;)V
    .locals 0

    invoke-direct {p0, p1, p2}, Lcom/huangguo/mobile/MainActivity;->refreshAccountSilently(Lcom/huangguo/mobile/AccountService;Ljava/lang/String;)V

    return-void
.end method

.method static bridge synthetic -$$Nest$mresetBackProgress(Lcom/huangguo/mobile/MainActivity;)V
    .locals 0

    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->resetBackProgress()V

    return-void
.end method

.method static bridge synthetic -$$Nest$msendLatestBackProgress(Lcom/huangguo/mobile/MainActivity;)V
    .locals 0

    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->sendLatestBackProgress()V

    return-void
.end method

.method static bridge synthetic -$$Nest$mupdateBackRegistration(Lcom/huangguo/mobile/MainActivity;Z)V
    .locals 0

    invoke-direct {p0, p1}, Lcom/huangguo/mobile/MainActivity;->updateBackRegistration(Z)V

    return-void
.end method

.method public constructor <init>()V
    .locals 2

    .line 45
    invoke-direct {p0}, Landroid/app/Activity;-><init>()V

    const/4 v0, 0x4

    .line 48
    invoke-static {v0}, Ljava/util/concurrent/Executors;->newFixedThreadPool(I)Ljava/util/concurrent/ExecutorService;

    move-result-object v0

    iput-object v0, p0, Lcom/huangguo/mobile/MainActivity;->requests:Ljava/util/concurrent/ExecutorService;

    .line 49
    invoke-static {}, Ljava/util/concurrent/Executors;->newSingleThreadExecutor()Ljava/util/concurrent/ExecutorService;

    move-result-object v0

    iput-object v0, p0, Lcom/huangguo/mobile/MainActivity;->localRequests:Ljava/util/concurrent/ExecutorService;

    const/4 v0, 0x2

    .line 50
    invoke-static {v0}, Ljava/util/concurrent/Executors;->newFixedThreadPool(I)Ljava/util/concurrent/ExecutorService;

    move-result-object v0

    iput-object v0, p0, Lcom/huangguo/mobile/MainActivity;->coverRequests:Ljava/util/concurrent/ExecutorService;

    .line 61
    new-instance v0, Landroid/os/Handler;

    invoke-static {}, Landroid/os/Looper;->getMainLooper()Landroid/os/Looper;

    move-result-object v1

    invoke-direct {v0, v1}, Landroid/os/Handler;-><init>(Landroid/os/Looper;)V

    iput-object v0, p0, Lcom/huangguo/mobile/MainActivity;->hotHandler:Landroid/os/Handler;

    .line 62
    new-instance v0, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda47;

    invoke-direct {v0, p0}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda47;-><init>(Lcom/huangguo/mobile/MainActivity;)V

    iput-object v0, p0, Lcom/huangguo/mobile/MainActivity;->resourceTimeout:Ljava/lang/Runnable;

    const/high16 v0, -0x40800000    # -1.0f

    .line 67
    iput v0, p0, Lcom/huangguo/mobile/MainActivity;->pendingBackProgress:F

    return-void
.end method

.method private applyPendingResources()V
    .locals 2

    .line 222
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity;->hotUpdates:Lcom/huangguo/mobile/HotUpdateManager;

    invoke-virtual {v0}, Lcom/huangguo/mobile/HotUpdateManager;->hasPending()Z

    move-result v0

    if-eqz v0, :cond_1

    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    if-eqz v0, :cond_1

    invoke-virtual {p0}, Lcom/huangguo/mobile/MainActivity;->isDestroyed()Z

    move-result v0

    if-eqz v0, :cond_0

    goto :goto_0

    .line 223
    :cond_0
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    new-instance v1, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda30;

    invoke-direct {v1, p0}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda30;-><init>(Lcom/huangguo/mobile/MainActivity;)V

    const-string p0, "window.hgCanApplyHotUpdate ? window.hgCanApplyHotUpdate() : false"

    invoke-virtual {v0, p0, v1}, Landroid/webkit/WebView;->evaluateJavascript(Ljava/lang/String;Landroid/webkit/ValueCallback;)V

    :cond_1
    :goto_0
    return-void
.end method

.method private applySystemBars()V
    .locals 4

    .line 262
    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->dark()Z

    move-result v0

    .line 263
    invoke-virtual {p0}, Lcom/huangguo/mobile/MainActivity;->getWindow()Landroid/view/Window;

    move-result-object v1

    const/4 v2, 0x0

    invoke-virtual {v1, v2}, Landroid/view/Window;->setStatusBarColor(I)V

    .line 264
    invoke-virtual {p0}, Lcom/huangguo/mobile/MainActivity;->getWindow()Landroid/view/Window;

    move-result-object v1

    if-eqz v0, :cond_0

    const-string v3, "#171B1D"

    goto :goto_0

    :cond_0
    const-string v3, "#F5F2E9"

    :goto_0
    invoke-static {v3}, Landroid/graphics/Color;->parseColor(Ljava/lang/String;)I

    move-result v3

    invoke-virtual {v1, v3}, Landroid/view/Window;->setNavigationBarColor(I)V

    .line 265
    sget v1, Landroid/os/Build$VERSION;->SDK_INT:I

    const/16 v3, 0x1d

    if-lt v1, v3, :cond_1

    invoke-virtual {p0}, Lcom/huangguo/mobile/MainActivity;->getWindow()Landroid/view/Window;

    move-result-object v1

    invoke-static {v1, v2}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticApiModelOutline0;->m$1(Landroid/view/Window;Z)V

    :cond_1
    if-nez v0, :cond_2

    const/16 v1, 0x510

    goto :goto_1

    :cond_2
    const/16 v1, 0x500

    :goto_1
    if-nez v0, :cond_3

    .line 268
    iget-boolean v0, p0, Lcom/huangguo/mobile/MainActivity;->detailSystemBars:Z

    if-nez v0, :cond_3

    or-int/lit16 v1, v1, 0x2000

    .line 269
    :cond_3
    invoke-virtual {p0}, Lcom/huangguo/mobile/MainActivity;->getWindow()Landroid/view/Window;

    move-result-object p0

    invoke-virtual {p0}, Landroid/view/Window;->getDecorView()Landroid/view/View;

    move-result-object p0

    invoke-virtual {p0, v1}, Landroid/view/View;->setSystemUiVisibility(I)V

    return-void
.end method

.method private applyTheme()V
    .locals 3

    .line 250
    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->dark()Z

    move-result v0

    if-eqz v0, :cond_0

    .line 251
    const-string v1, "#171B1D"

    goto :goto_0

    :cond_0
    const-string v1, "#F5F2E9"

    :goto_0
    invoke-static {v1}, Landroid/graphics/Color;->parseColor(Ljava/lang/String;)I

    move-result v1

    .line 252
    iget-object v2, p0, Lcom/huangguo/mobile/MainActivity;->root:Landroid/widget/FrameLayout;

    invoke-virtual {v2, v1}, Landroid/widget/FrameLayout;->setBackgroundColor(I)V

    .line 253
    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->applySystemBars()V

    .line 254
    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    if-eqz p0, :cond_1

    new-instance v1, Ljava/lang/StringBuilder;

    const-string v2, "window.hgTheme && window.hgTheme("

    invoke-direct {v1, v2}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {v1, v0}, Ljava/lang/StringBuilder;->append(Z)Ljava/lang/StringBuilder;

    const-string v0, ")"

    invoke-virtual {v1, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v0

    const/4 v1, 0x0

    invoke-virtual {p0, v0, v1}, Landroid/webkit/WebView;->evaluateJavascript(Ljava/lang/String;Landroid/webkit/ValueCallback;)V

    :cond_1
    return-void
.end method

.method private backTouchFraction(Landroid/window/BackEvent;)F
    .locals 4

    .line 396
    invoke-static {p1}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticApiModelOutline0;->m(Landroid/window/BackEvent;)F

    move-result v0

    .line 397
    invoke-static {p1}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticApiModelOutline0;->m(Landroid/window/BackEvent;)I

    move-result v1

    .line 398
    invoke-static {v0}, Ljava/lang/Float;->isNaN(F)Z

    move-result v2

    if-nez v2, :cond_2

    const/4 v2, 0x2

    if-eq v1, v2, :cond_2

    iget v2, p0, Lcom/huangguo/mobile/MainActivity;->backViewWidth:I

    if-nez v2, :cond_0

    goto :goto_1

    :cond_0
    int-to-float p1, v2

    .line 402
    iget v2, p0, Lcom/huangguo/mobile/MainActivity;->backViewLeft:I

    const/4 v3, 0x1

    if-ne v1, v3, :cond_1

    int-to-float v1, v2

    add-float/2addr v1, p1

    sub-float/2addr v1, v0

    goto :goto_0

    :cond_1
    int-to-float v1, v2

    sub-float v1, v0, v1

    :goto_0
    const/4 v0, 0x0

    .line 403
    invoke-static {v0, v1}, Ljava/lang/Math;->max(FF)F

    move-result v0

    .line 404
    iget p0, p0, Lcom/huangguo/mobile/MainActivity;->backLeadPx:F

    const v1, 0x3eb33333    # 0.35f

    mul-float/2addr v1, v0

    invoke-static {p0, v1}, Ljava/lang/Math;->min(FF)F

    move-result p0

    add-float/2addr v0, p0

    div-float/2addr v0, p1

    const/high16 p0, 0x3f800000    # 1.0f

    .line 405
    invoke-static {p0, v0}, Ljava/lang/Math;->min(FF)F

    move-result p0

    return p0

    .line 399
    :cond_2
    :goto_1
    invoke-static {p1}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticApiModelOutline0;->m$1(Landroid/window/BackEvent;)F

    move-result p0

    return p0
.end method

.method private captureBackGeometry()V
    .locals 2

    const/4 v0, 0x2

    .line 388
    new-array v0, v0, [I

    .line 389
    iget-object v1, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    invoke-virtual {v1, v0}, Landroid/webkit/WebView;->getLocationOnScreen([I)V

    const/4 v1, 0x0

    .line 390
    aget v0, v0, v1

    iput v0, p0, Lcom/huangguo/mobile/MainActivity;->backViewLeft:I

    .line 391
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    invoke-virtual {v0}, Landroid/webkit/WebView;->getWidth()I

    move-result v0

    iput v0, p0, Lcom/huangguo/mobile/MainActivity;->backViewWidth:I

    .line 392
    invoke-virtual {p0}, Lcom/huangguo/mobile/MainActivity;->getResources()Landroid/content/res/Resources;

    move-result-object v0

    invoke-virtual {v0}, Landroid/content/res/Resources;->getDisplayMetrics()Landroid/util/DisplayMetrics;

    move-result-object v0

    iget v0, v0, Landroid/util/DisplayMetrics;->density:F

    const/high16 v1, 0x41900000    # 18.0f

    mul-float/2addr v0, v1

    iput v0, p0, Lcom/huangguo/mobile/MainActivity;->backLeadPx:F

    return-void
.end method

.method private checkHotUpdates()V
    .locals 2

    .line 195
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity;->requests:Ljava/util/concurrent/ExecutorService;

    new-instance v1, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda43;

    invoke-direct {v1, p0}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda43;-><init>(Lcom/huangguo/mobile/MainActivity;)V

    invoke-interface {v0, v1}, Ljava/util/concurrent/ExecutorService;->execute(Ljava/lang/Runnable;)V

    return-void
.end method

.method private dark()Z
    .locals 2

    # 用户在设置里选了浅色/深色时以选择为准；-1 才回去读系统 uiMode。
    invoke-static {}, Lcom/huangguo/mobile/ThemeBridge;->override()I

    move-result v0

    const/4 v1, -0x1

    if-ne v0, v1, :cond_system

    if-eqz v0, :cond_light

    const/4 p0, 0x1

    return p0

    :cond_light
    const/4 p0, 0x0

    return p0

    .line 229
    :cond_system
    invoke-virtual {p0}, Lcom/huangguo/mobile/MainActivity;->getResources()Landroid/content/res/Resources;

    move-result-object p0

    invoke-virtual {p0}, Landroid/content/res/Resources;->getConfiguration()Landroid/content/res/Configuration;

    move-result-object p0

    iget p0, p0, Landroid/content/res/Configuration;->uiMode:I

    and-int/lit8 p0, p0, 0x30

    const/16 v0, 0x20

    if-ne p0, v0, :cond_0

    const/4 p0, 0x1

    return p0

    :cond_0
    const/4 p0, 0x0

    return p0
.end method

.method private dispatch(Ljava/lang/String;Lorg/json/JSONObject;)Ljava/lang/Object;
    .locals 8
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    .line 461
    invoke-static {p0}, Lcom/huangguo/mobile/ContentMode;->get(Landroid/content/Context;)Lcom/huangguo/mobile/ContentMode;

    move-result-object v0

    .line 462
    const-string v1, "content-status"

    invoke-virtual {v1, p1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v1

    if-eqz v1, :cond_0

    invoke-virtual {v0}, Lcom/huangguo/mobile/ContentMode;->status()Lorg/json/JSONObject;

    move-result-object p0

    return-object p0

    .line 463
    :cond_0
    const-string v1, "enter-private"

    invoke-virtual {v1, p1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v1

    if-eqz v1, :cond_2

    .line 464
    const-string p1, "password"

    invoke-virtual {p2, p1}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p1

    invoke-virtual {v0, p1}, Lcom/huangguo/mobile/ContentMode;->enterPrivate(Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object p1

    .line 465
    const-string p2, "huangguo"

    invoke-virtual {v0}, Lcom/huangguo/mobile/ContentMode;->active()Ljava/lang/String;

    move-result-object v0

    invoke-virtual {p2, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result p2

    if-eqz p2, :cond_1

    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->rebindContent()V

    :cond_1
    return-object p1

    .line 468
    :cond_2
    const-string v1, "confirm-private-age"

    invoke-virtual {v1, p1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v1

    if-eqz v1, :cond_3

    .line 469
    invoke-virtual {v0}, Lcom/huangguo/mobile/ContentMode;->finishPrivateEntry()Lorg/json/JSONObject;

    move-result-object p1

    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->rebindContent()V

    return-object p1

    .line 471
    :cond_3
    const-string v1, "leave-private"

    invoke-virtual {v1, p1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v1

    if-eqz v1, :cond_4

    invoke-virtual {v0}, Lcom/huangguo/mobile/ContentMode;->startPublic()V

    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->rebindContent()V

    invoke-virtual {v0}, Lcom/huangguo/mobile/ContentMode;->status()Lorg/json/JSONObject;

    move-result-object p0

    return-object p0

    .line 476
    :cond_4
    monitor-enter p0

    .line 477
    :try_start_0
    const-string v1, "source"

    invoke-virtual {p2, v1}, Lorg/json/JSONObject;->has(Ljava/lang/String;)Z

    move-result v1

    if-eqz v1, :cond_5

    const-string v1, "source"

    invoke-virtual {p2, v1}, Lorg/json/JSONObject;->getString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v1

    invoke-virtual {v0, v1}, Lcom/huangguo/mobile/ContentMode;->require(Ljava/lang/String;)V

    .line 478
    :cond_5
    iget-object v1, p0, Lcom/huangguo/mobile/MainActivity;->repository:Lcom/huangguo/mobile/DualContentRepository;

    iget-object v2, p0, Lcom/huangguo/mobile/MainActivity;->store:Lcom/huangguo/mobile/AppStore;

    iget-object v3, p0, Lcom/huangguo/mobile/MainActivity;->account:Lcom/huangguo/mobile/AccountService;

    .line 479
    monitor-exit p0
    :try_end_0
    .catchall {:try_start_0 .. :try_end_0} :catchall_0

    .line 480
    invoke-virtual {p1}, Ljava/lang/String;->hashCode()I

    invoke-virtual {p1}, Ljava/lang/String;->hashCode()I

    move-result v4

    const/4 v5, 0x0

    const/4 v6, 0x1

    const/4 v7, -0x1

    sparse-switch v4, :sswitch_data_0

    goto/16 :goto_0

    :sswitch_0
    const-string v4, "bookmark"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_6

    goto/16 :goto_0

    :cond_6
    const/16 v7, 0x27

    goto/16 :goto_0

    :sswitch_1
    const-string v4, "screen-corners"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_7

    goto/16 :goto_0

    :cond_7
    const/16 v7, 0x26

    goto/16 :goto_0

    :sswitch_2
    const-string v4, "choose-avatar"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_8

    goto/16 :goto_0

    :cond_8
    const/16 v7, 0x25

    goto/16 :goto_0

    :sswitch_3
    const-string v4, "get-progress"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_9

    goto/16 :goto_0

    :cond_9
    const/16 v7, 0x24

    goto/16 :goto_0

    :sswitch_4
    const-string v4, "hot-update-status"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_a

    goto/16 :goto_0

    :cond_a
    const/16 v7, 0x23

    goto/16 :goto_0

    :sswitch_5
    const-string v4, "bookmark-state"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_b

    goto/16 :goto_0

    :cond_b
    const/16 v7, 0x22

    goto/16 :goto_0

    :sswitch_6
    const-string v4, "age-status"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_c

    goto/16 :goto_0

    :cond_c
    const/16 v7, 0x21

    goto/16 :goto_0

    :sswitch_7
    const-string v4, "clear-history"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_d

    goto/16 :goto_0

    :cond_d
    const/16 v7, 0x20

    goto/16 :goto_0

    :sswitch_8
    const-string v4, "recommend"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_e

    goto/16 :goto_0

    :cond_e
    const/16 v7, 0x1f

    goto/16 :goto_0

    :sswitch_9
    const-string v4, "check-hot-update"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_f

    goto/16 :goto_0

    :cond_f
    const/16 v7, 0x1e

    goto/16 :goto_0

    :sswitch_a
    const-string v4, "cached-update"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_10

    goto/16 :goto_0

    :cond_10
    const/16 v7, 0x1d

    goto/16 :goto_0

    :sswitch_b
    const-string v4, "clear-content-cache"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_11

    goto/16 :goto_0

    :cond_11
    const/16 v7, 0x1c

    goto/16 :goto_0

    :sswitch_c
    const-string v4, "confirm-age"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_12

    goto/16 :goto_0

    :cond_12
    const/16 v7, 0x1b

    goto/16 :goto_0

    :sswitch_d
    const-string v4, "library"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_13

    goto/16 :goto_0

    :cond_13
    const/16 v7, 0x1a

    goto/16 :goto_0

    :sswitch_e
    const-string v4, "window-insets"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_14

    goto/16 :goto_0

    :cond_14
    const/16 v7, 0x19

    goto/16 :goto_0

    :sswitch_f
    const-string v4, "theme"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_15

    goto/16 :goto_0

    :cond_15
    const/16 v7, 0x18

    goto/16 :goto_0

    :sswitch_10
    const-string v4, "login"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_16

    goto/16 :goto_0

    :cond_16
    const/16 v7, 0x17

    goto/16 :goto_0

    :sswitch_11
    const-string v4, "cover"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_17

    goto/16 :goto_0

    :cond_17
    const/16 v7, 0x16

    goto/16 :goto_0

    :sswitch_12
    const-string v4, "category"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_18

    goto/16 :goto_0

    :cond_18
    const/16 v7, 0x15

    goto/16 :goto_0

    :sswitch_13
    const-string v4, "tags"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_19

    goto/16 :goto_0

    :cond_19
    const/16 v7, 0x14

    goto/16 :goto_0

    :sswitch_14
    const-string v4, "sync"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_1a

    goto/16 :goto_0

    :cond_1a
    const/16 v7, 0x13

    goto/16 :goto_0

    :sswitch_15
    const-string v4, "quit"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_1b

    goto/16 :goto_0

    :cond_1b
    const/16 v7, 0x12

    goto/16 :goto_0

    :sswitch_16
    const-string v4, "home"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_1c

    goto/16 :goto_0

    :cond_1c
    const/16 v7, 0x11

    goto/16 :goto_0

    :sswitch_17
    const-string v4, "register"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_1d

    goto/16 :goto_0

    :cond_1d
    const/16 v7, 0x10

    goto/16 :goto_0

    :sswitch_18
    const-string v4, "change-password"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_1e

    goto/16 :goto_0

    :cond_1e
    const/16 v7, 0xf

    goto/16 :goto_0

    :sswitch_19
    const-string v4, "tag-list"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_1f

    goto/16 :goto_0

    :cond_1f
    const/16 v7, 0xe

    goto/16 :goto_0

    :sswitch_1a
    const-string v4, "download-update"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_20

    goto/16 :goto_0

    :cond_20
    const/16 v7, 0xd

    goto/16 :goto_0

    :sswitch_1b
    const-string v4, "search"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_21

    goto/16 :goto_0

    :cond_21
    const/16 v7, 0xc

    goto/16 :goto_0

    :sswitch_1c
    const-string v4, "clear-bookmarks"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_22

    goto/16 :goto_0

    :cond_22
    const/16 v7, 0xb

    goto/16 :goto_0

    :sswitch_1d
    const-string v4, "newest"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_23

    goto/16 :goto_0

    :cond_23
    const/16 v7, 0xa

    goto/16 :goto_0

    :sswitch_1e
    const-string v4, "logout"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_24

    goto/16 :goto_0

    :cond_24
    const/16 v7, 0x9

    goto/16 :goto_0

    :sswitch_1f
    const-string v4, "apply-hot-update"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_25

    goto/16 :goto_0

    :cond_25
    const/16 v7, 0x8

    goto/16 :goto_0

    :sswitch_20
    const-string v4, "account"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_26

    goto :goto_0

    :cond_26
    const/4 v7, 0x7

    goto :goto_0

    :sswitch_21
    const-string v4, "check-update"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_27

    goto :goto_0

    :cond_27
    const/4 v7, 0x6

    goto :goto_0

    :sswitch_22
    const-string v4, "detail"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_28

    goto :goto_0

    :cond_28
    const/4 v7, 0x5

    goto :goto_0

    :sswitch_23
    const-string v4, "cached"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_29

    goto :goto_0

    :cond_29
    const/4 v7, 0x4

    goto :goto_0

    :sswitch_24
    const-string v4, "episode"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_2a

    goto :goto_0

    :cond_2a
    const/4 v7, 0x3

    goto :goto_0

    :sswitch_25
    const-string v4, "open-player"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_2b

    goto :goto_0

    :cond_2b
    const/4 v7, 0x2

    goto :goto_0

    :sswitch_26
    const-string v4, "hot-ready"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_2c

    goto :goto_0

    :cond_2c
    move v7, v6

    goto :goto_0

    :sswitch_27
    const-string v4, "app-version"

    invoke-virtual {p1, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v4

    if-nez v4, :cond_2d

    goto :goto_0

    :cond_2d
    move v7, v5

    :goto_0
    packed-switch v7, :pswitch_data_0

    .line 589
    new-instance p0, Ljava/lang/IllegalArgumentException;

    const-string p1, "\u672a\u77e5\u8bf7\u6c42"

    invoke-direct {p0, p1}, Ljava/lang/IllegalArgumentException;-><init>(Ljava/lang/String;)V

    throw p0

    .line 570
    :pswitch_0
    invoke-virtual {v2, p2}, Lcom/huangguo/mobile/AppStore;->toggleBookmark(Lorg/json/JSONObject;)Z

    move-result p1

    .line 571
    invoke-static {}, Lcom/parse/ParseUser;->getCurrentUser()Lcom/parse/ParseUser;

    move-result-object p2

    if-eqz p2, :cond_2e

    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity;->requests:Ljava/util/concurrent/ExecutorService;

    new-instance p2, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda37;

    invoke-direct {p2, v3}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda37;-><init>(Lcom/huangguo/mobile/AccountService;)V

    invoke-interface {p0, p2}, Ljava/util/concurrent/ExecutorService;->execute(Ljava/lang/Runnable;)V

    .line 574
    :cond_2e
    invoke-static {p1}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;

    move-result-object p0

    return-object p0

    .line 495
    :pswitch_1
    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->screenCorners()Lorg/json/JSONObject;

    move-result-object p0

    return-object p0

    .line 504
    :pswitch_2
    invoke-static {}, Lcom/parse/ParseUser;->getCurrentUser()Lcom/parse/ParseUser;

    move-result-object p1

    if-eqz p1, :cond_2f

    .line 505
    new-instance p1, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda34;

    invoke-direct {p1, p0}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda34;-><init>(Lcom/huangguo/mobile/MainActivity;)V

    invoke-virtual {p0, p1}, Lcom/huangguo/mobile/MainActivity;->runOnUiThread(Ljava/lang/Runnable;)V

    .line 511
    invoke-static {v6}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;

    move-result-object p0

    return-object p0

    .line 504
    :cond_2f
    new-instance p0, Ljava/lang/IllegalStateException;

    const-string p1, "\u8bf7\u5148\u767b\u5f55"

    invoke-direct {p0, p1}, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V

    throw p0

    .line 577
    :pswitch_3
    const-string p0, "id"

    invoke-virtual {p2, p0}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    const-string p1, "episode"

    invoke-virtual {p2, p1, v6}, Lorg/json/JSONObject;->optInt(Ljava/lang/String;I)I

    move-result p1

    invoke-virtual {v2, p0, p1}, Lcom/huangguo/mobile/AppStore;->getProgress(Ljava/lang/String;I)Lorg/json/JSONObject;

    move-result-object p0

    return-object p0

    .line 481
    :pswitch_4
    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity;->hotUpdates:Lcom/huangguo/mobile/HotUpdateManager;

    invoke-virtual {p0}, Lcom/huangguo/mobile/HotUpdateManager;->status()Lorg/json/JSONObject;

    move-result-object p0

    const-string p1, "enabled"

    invoke-virtual {p0, p1, v6}, Lorg/json/JSONObject;->put(Ljava/lang/String;Z)Lorg/json/JSONObject;

    move-result-object p0

    return-object p0

    .line 576
    :pswitch_5
    const-string p0, "id"

    invoke-virtual {p2, p0}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    invoke-virtual {v2, p0}, Lcom/huangguo/mobile/AppStore;->hasBookmark(Ljava/lang/String;)Z

    move-result p0

    invoke-static {p0}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;

    move-result-object p0

    return-object p0

    .line 484
    :pswitch_6
    const-string p0, "hongguo"

    invoke-virtual {v0}, Lcom/huangguo/mobile/ContentMode;->active()Ljava/lang/String;

    move-result-object p1

    invoke-virtual {p0, p1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result p0

    if-nez p0, :cond_30

    invoke-virtual {v0}, Lcom/huangguo/mobile/ContentMode;->adultConfirmed()Z

    move-result p0

    if-eqz p0, :cond_31

    :cond_30
    move v5, v6

    :cond_31
    invoke-static {v5}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;

    move-result-object p0

    return-object p0

    .line 578
    :pswitch_7
    invoke-virtual {v2}, Lcom/huangguo/mobile/AppStore;->clearHistory()V

    invoke-static {}, Lcom/parse/ParseUser;->getCurrentUser()Lcom/parse/ParseUser;

    move-result-object p1

    if-eqz p1, :cond_32

    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity;->requests:Ljava/util/concurrent/ExecutorService;

    new-instance p1, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda38;

    invoke-direct {p1, v3}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda38;-><init>(Lcom/huangguo/mobile/AccountService;)V

    invoke-interface {p0, p1}, Ljava/util/concurrent/ExecutorService;->execute(Ljava/lang/Runnable;)V

    :cond_32
    invoke-static {v6}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;

    move-result-object p0

    return-object p0

    .line 482
    :pswitch_8
    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity;->hotUpdates:Lcom/huangguo/mobile/HotUpdateManager;

    invoke-virtual {p0, v6}, Lcom/huangguo/mobile/HotUpdateManager;->check(Z)Lorg/json/JSONObject;

    move-result-object p0

    return-object p0

    .line 514
    :pswitch_9
    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity;->updates:Lcom/huangguo/mobile/UpdateManager;

    invoke-virtual {p0}, Lcom/huangguo/mobile/UpdateManager;->cached()Lorg/json/JSONObject;

    move-result-object p0

    return-object p0

    .line 580
    :pswitch_a
    invoke-virtual {v2}, Lcom/huangguo/mobile/AppStore;->clearCache()V

    invoke-virtual {v1}, Lcom/huangguo/mobile/DualContentRepository;->clearCovers()V

    invoke-static {v6}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;

    move-result-object p0

    return-object p0

    .line 485
    :pswitch_b
    invoke-virtual {v0}, Lcom/huangguo/mobile/ContentMode;->confirmAdult()V

    invoke-static {v6}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;

    move-result-object p0

    return-object p0

    .line 568
    :pswitch_c
    invoke-virtual {v2}, Lcom/huangguo/mobile/AppStore;->library()Lorg/json/JSONObject;

    move-result-object p0

    return-object p0

    .line 496
    :pswitch_d
    iget p0, p0, Lcom/huangguo/mobile/MainActivity;->statusBarInsetDp:F

    invoke-static {p0}, Ljava/lang/Float;->valueOf(F)Ljava/lang/Float;

    move-result-object p0

    return-object p0

    .line 494
    :pswitch_e
    # 带 mode 是「写」：system / light / dark，交给 ThemeBridge 改原生栏。
    # 不带 mode 是原有的「读」：返回当前生效的明暗，给前端首帧对齐用。
    const-string v0, "mode"

    invoke-virtual {p2, v0}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v0

    invoke-virtual {v0}, Ljava/lang/String;->isEmpty()Z

    move-result v1

    if-nez v1, :cond_theme_read

    invoke-static {p0, v0}, Lcom/huangguo/mobile/ThemeBridge;->applyMode(Landroid/app/Activity;Ljava/lang/String;)V

    const/4 p0, 0x1

    invoke-static {p0}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;

    move-result-object p0

    return-object p0

    :cond_theme_read
    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->dark()Z

    move-result p0

    invoke-static {p0}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;

    move-result-object p0

    return-object p0

    .line 499
    :pswitch_f
    const-string p0, "username"

    invoke-virtual {p2, p0}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    const-string p1, "password"

    invoke-virtual {p2, p1}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p1

    invoke-virtual {v3, p0, p1}, Lcom/huangguo/mobile/AccountService;->login(Ljava/lang/String;Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object p0

    return-object p0

    .line 567
    :pswitch_10
    const-string p0, "url"

    invoke-virtual {p2, p0}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    invoke-virtual {v1, p0}, Lcom/huangguo/mobile/DualContentRepository;->coverData(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    return-object p0

    .line 529
    :pswitch_11
    const-string p0, "id"

    invoke-virtual {p2, p0}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    .line 530
    const-string p1, "page"

    invoke-virtual {p2, p1, v6}, Lorg/json/JSONObject;->optInt(Ljava/lang/String;I)I

    move-result p1

    .line 531
    invoke-virtual {v1, p0, p1}, Lcom/huangguo/mobile/DualContentRepository;->category(Ljava/lang/String;I)Lorg/json/JSONObject;

    move-result-object p2

    .line 532
    new-instance v0, Ljava/lang/StringBuilder;

    const-string v1, "category:"

    invoke-direct {v0, v1}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const-string p0, ":"

    invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p0

    invoke-virtual {v2, p0, p2}, Lcom/huangguo/mobile/AppStore;->saveCache(Ljava/lang/String;Lorg/json/JSONObject;)V

    return-object p2

    .line 536
    :pswitch_12
    invoke-virtual {v1}, Lcom/huangguo/mobile/DualContentRepository;->tags()Lorg/json/JSONObject;

    move-result-object p0

    .line 537
    const-string p1, "tags"

    invoke-virtual {v2, p1, p0}, Lcom/huangguo/mobile/AppStore;->saveCache(Ljava/lang/String;Lorg/json/JSONObject;)V

    return-object p0

    .line 502
    :pswitch_13
    invoke-virtual {v3}, Lcom/huangguo/mobile/AccountService;->sync()Lorg/json/JSONObject;

    move-result-object p0

    return-object p0

    .line 581
    :pswitch_14
    new-instance p1, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda40;

    invoke-direct {p1, p0}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda40;-><init>(Lcom/huangguo/mobile/MainActivity;)V

    invoke-virtual {p0, p1}, Lcom/huangguo/mobile/MainActivity;->runOnUiThread(Ljava/lang/Runnable;)V

    invoke-static {v6}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;

    move-result-object p0

    return-object p0

    .line 524
    :pswitch_15
    invoke-virtual {v1}, Lcom/huangguo/mobile/DualContentRepository;->home()Lorg/json/JSONObject;

    move-result-object p0

    .line 525
    const-string p1, "home"

    invoke-virtual {v2, p1, p0}, Lcom/huangguo/mobile/AppStore;->saveCache(Ljava/lang/String;Lorg/json/JSONObject;)V

    return-object p0

    .line 498
    :pswitch_16
    const-string p0, "username"

    invoke-virtual {p2, p0}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    const-string p1, "password"

    invoke-virtual {p2, p1}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p1

    invoke-virtual {v3, p0, p1}, Lcom/huangguo/mobile/AccountService;->register(Ljava/lang/String;Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object p0

    return-object p0

    .line 500
    :pswitch_17
    const-string p0, "oldPassword"

    invoke-virtual {p2, p0}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    const-string p1, "newPassword"

    invoke-virtual {p2, p1}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p1

    invoke-virtual {v3, p0, p1}, Lcom/huangguo/mobile/AccountService;->changePassword(Ljava/lang/String;Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object p0

    return-object p0

    .line 541
    :pswitch_18
    const-string p0, "slug"

    invoke-virtual {p2, p0}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    .line 542
    const-string p1, "page"

    invoke-virtual {p2, p1, v6}, Lorg/json/JSONObject;->optInt(Ljava/lang/String;I)I

    move-result p1

    .line 543
    invoke-virtual {v1, p0, p1}, Lcom/huangguo/mobile/DualContentRepository;->tagListing(Ljava/lang/String;I)Lorg/json/JSONObject;

    move-result-object p2

    .line 544
    new-instance v0, Ljava/lang/StringBuilder;

    const-string v1, "tag:"

    invoke-direct {v0, v1}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const-string p0, ":"

    invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p0

    invoke-virtual {v2, p0, p2}, Lcom/huangguo/mobile/AppStore;->saveCache(Ljava/lang/String;Lorg/json/JSONObject;)V

    return-object p2

    .line 517
    :pswitch_19
    iget-object p1, p0, Lcom/huangguo/mobile/MainActivity;->updates:Lcom/huangguo/mobile/UpdateManager;

    new-instance p2, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda35;

    invoke-direct {p2, p0}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda35;-><init>(Lcom/huangguo/mobile/MainActivity;)V

    invoke-virtual {p1, p2}, Lcom/huangguo/mobile/UpdateManager;->download(Lcom/huangguo/mobile/UpdateManager$Progress;)Ljava/io/File;

    move-result-object p1

    .line 519
    new-instance p2, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda36;

    invoke-direct {p2, p0, p1}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda36;-><init>(Lcom/huangguo/mobile/MainActivity;Ljava/io/File;)V

    invoke-virtual {p0, p2}, Lcom/huangguo/mobile/MainActivity;->runOnUiThread(Ljava/lang/Runnable;)V

    .line 520
    invoke-static {v6}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;

    move-result-object p0

    return-object p0

    .line 555
    :pswitch_1a
    const-string p0, "query"

    invoke-virtual {p2, p0}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    invoke-virtual {p0}, Ljava/lang/String;->trim()Ljava/lang/String;

    move-result-object p0

    .line 556
    invoke-virtual {v1, p0}, Lcom/huangguo/mobile/DualContentRepository;->search(Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object p1

    .line 557
    new-instance p2, Ljava/lang/StringBuilder;

    const-string v0, "search:"

    invoke-direct {p2, v0}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {p2, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {p2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p0

    invoke-virtual {v2, p0, p1}, Lcom/huangguo/mobile/AppStore;->saveCache(Ljava/lang/String;Lorg/json/JSONObject;)V

    return-object p1

    .line 579
    :pswitch_1b
    invoke-virtual {v2}, Lcom/huangguo/mobile/AppStore;->clearBookmarks()V

    invoke-static {}, Lcom/parse/ParseUser;->getCurrentUser()Lcom/parse/ParseUser;

    move-result-object p1

    if-eqz p1, :cond_33

    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity;->requests:Ljava/util/concurrent/ExecutorService;

    new-instance p1, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda39;

    invoke-direct {p1, v3}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda39;-><init>(Lcom/huangguo/mobile/AccountService;)V

    invoke-interface {p0, p1}, Ljava/util/concurrent/ExecutorService;->execute(Ljava/lang/Runnable;)V

    :cond_33
    invoke-static {v6}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;

    move-result-object p0

    return-object p0

    .line 549
    :pswitch_1c
    const-string p0, "page"

    invoke-virtual {p2, p0, v6}, Lorg/json/JSONObject;->optInt(Ljava/lang/String;I)I

    move-result p0

    .line 550
    const-string p2, "newest"

    invoke-virtual {p2, p1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result p2

    invoke-virtual {v1, p0, p2}, Lcom/huangguo/mobile/DualContentRepository;->recommend(IZ)Lorg/json/JSONObject;

    move-result-object p2

    .line 551
    new-instance v0, Ljava/lang/StringBuilder;

    invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V

    invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const-string p1, ":"

    invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v0, p0}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p0

    invoke-virtual {v2, p0, p2}, Lcom/huangguo/mobile/AppStore;->saveCache(Ljava/lang/String;Lorg/json/JSONObject;)V

    return-object p2

    .line 501
    :pswitch_1d
    invoke-virtual {v3}, Lcom/huangguo/mobile/AccountService;->logout()V

    invoke-virtual {v3}, Lcom/huangguo/mobile/AccountService;->profile()Lorg/json/JSONObject;

    move-result-object p0

    return-object p0

    .line 483
    :pswitch_1e
    new-instance p1, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda32;

    invoke-direct {p1, p0}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda32;-><init>(Lcom/huangguo/mobile/MainActivity;)V

    invoke-virtual {p0, p1}, Lcom/huangguo/mobile/MainActivity;->runOnUiThread(Ljava/lang/Runnable;)V

    invoke-static {v6}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;

    move-result-object p0

    return-object p0

    .line 497
    :pswitch_1f
    invoke-virtual {v3}, Lcom/huangguo/mobile/AccountService;->profile()Lorg/json/JSONObject;

    move-result-object p0

    return-object p0

    .line 515
    :pswitch_20
    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity;->updates:Lcom/huangguo/mobile/UpdateManager;

    const-string p1, "manual"

    invoke-virtual {p2, p1}, Lorg/json/JSONObject;->optBoolean(Ljava/lang/String;)Z

    move-result p1

    invoke-virtual {p0, p1}, Lcom/huangguo/mobile/UpdateManager;->check(Z)Lorg/json/JSONObject;

    move-result-object p0

    return-object p0

    .line 561
    :pswitch_21
    const-string p0, "id"

    invoke-virtual {p2, p0}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    .line 562
    invoke-virtual {v1, p0}, Lcom/huangguo/mobile/DualContentRepository;->detail(Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object p1

    .line 563
    new-instance p2, Ljava/lang/StringBuilder;

    const-string v0, "detail:"

    invoke-direct {p2, v0}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {p2, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {p2}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p0

    invoke-virtual {v2, p0, p1}, Lcom/huangguo/mobile/AppStore;->saveCache(Ljava/lang/String;Lorg/json/JSONObject;)V

    return-object p1

    .line 522
    :pswitch_22
    const-string p0, "key"

    invoke-virtual {p2, p0}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    invoke-virtual {v2, p0}, Lcom/huangguo/mobile/AppStore;->cached(Ljava/lang/String;)Lorg/json/JSONObject;

    move-result-object p0

    return-object p0

    .line 566
    :pswitch_23
    const-string p0, "id"

    invoke-virtual {p2, p0}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    const-string p1, "episode"

    invoke-virtual {p2, p1, v6}, Lorg/json/JSONObject;->optInt(Ljava/lang/String;I)I

    move-result p1

    invoke-virtual {v1, p0, p1}, Lcom/huangguo/mobile/DualContentRepository;->episode(Ljava/lang/String;I)Lorg/json/JSONObject;

    move-result-object p0

    return-object p0

    .line 583
    :pswitch_24
    const-string p1, "source"

    invoke-virtual {v0}, Lcom/huangguo/mobile/ContentMode;->active()Ljava/lang/String;

    move-result-object v0

    invoke-virtual {p2, p1, v0}, Lorg/json/JSONObject;->put(Ljava/lang/String;Ljava/lang/Object;)Lorg/json/JSONObject;

    # ── 原生播放器已摘除（2026-10-16）──────────────────────────────────
    # 这一支原来是 open-player：构造 Intent 打 PlayerActivity，再包进
    # MainActivity$$ExternalSyntheticLambda41 里 runOnUiThread 启动。
    # PlayerActivity 连同 59 个附属类、VideoPlaybackCache、EpisodeLoadControl
    # 一起删掉了（共 63 个文件、660KB），播放现在走 WebView feed。
    #
    # 分支本身保留，因为 dispatch 用的是 packed-switch：索引和上面
    # sparse-switch 的哈希表是耦合的，删一个分支要连带改两张表，风险不抵收益。
    # 这里只把 body 收成「塞完 source 就原样返回」——返回类型和数量都没变，
    # switch 结构一个字节都不用动。
    # JS 侧 app.js 的 openPlayer() 也一并删了，这条路径现在没有任何调用点。
    invoke-static {v6}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;

    move-result-object p0

    return-object p0

    .line 487
    :pswitch_25
    iget-boolean p1, p0, Lcom/huangguo/mobile/MainActivity;->migratingOrigin:Z

    if-nez p1, :cond_34

    const-string p1, "revision"

    const-wide/16 v0, -0x1

    invoke-virtual {p2, p1, v0, v1}, Lorg/json/JSONObject;->optLong(Ljava/lang/String;J)J

    move-result-wide v0

    iget-object p1, p0, Lcom/huangguo/mobile/MainActivity;->hotSession:Lcom/huangguo/mobile/HotUpdateManager$Session;

    iget-wide v2, p1, Lcom/huangguo/mobile/HotUpdateManager$Session;->revision:J

    cmp-long p1, v0, v2

    if-nez p1, :cond_34

    iget-object p1, p0, Lcom/huangguo/mobile/MainActivity;->hotSession:Lcom/huangguo/mobile/HotUpdateManager$Session;

    iget-object p1, p1, Lcom/huangguo/mobile/HotUpdateManager$Session;->bootId:Ljava/lang/String;

    const-string v0, "bootId"

    .line 488
    invoke-virtual {p2, v0}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p2

    invoke-virtual {p1, p2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result p1

    if-eqz p1, :cond_34

    .line 489
    iget-object p1, p0, Lcom/huangguo/mobile/MainActivity;->hotUpdates:Lcom/huangguo/mobile/HotUpdateManager;

    iget-object p2, p0, Lcom/huangguo/mobile/MainActivity;->hotSession:Lcom/huangguo/mobile/HotUpdateManager$Session;

    invoke-virtual {p1, p2}, Lcom/huangguo/mobile/HotUpdateManager;->markHealthy(Lcom/huangguo/mobile/HotUpdateManager$Session;)V

    .line 490
    new-instance p1, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda33;

    invoke-direct {p1, p0}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda33;-><init>(Lcom/huangguo/mobile/MainActivity;)V

    invoke-virtual {p0, p1}, Lcom/huangguo/mobile/MainActivity;->runOnUiThread(Ljava/lang/Runnable;)V

    .line 492
    :cond_34
    invoke-static {v6}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;

    move-result-object p0

    return-object p0

    .line 513
    :pswitch_26
    invoke-virtual {p0}, Lcom/huangguo/mobile/MainActivity;->getPackageManager()Landroid/content/pm/PackageManager;

    move-result-object p1

    invoke-virtual {p0}, Lcom/huangguo/mobile/MainActivity;->getPackageName()Ljava/lang/String;

    move-result-object p0

    invoke-virtual {p1, p0, v5}, Landroid/content/pm/PackageManager;->getPackageInfo(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;

    move-result-object p0

    iget-object p0, p0, Landroid/content/pm/PackageInfo;->versionName:Ljava/lang/String;

    return-object p0

    :catchall_0
    move-exception p1

    .line 479
    :try_start_1
    monitor-exit p0
    :try_end_1
    .catchall {:try_start_1 .. :try_end_1} :catchall_0

    throw p1

    nop

    :sswitch_data_0
    .sparse-switch
        -0x7fbc36d4 -> :sswitch_27
        -0x6dfdabdd -> :sswitch_26
        -0x5d3ce83c -> :sswitch_25
        -0x5c0e4205 -> :sswitch_24
        -0x518abede -> :sswitch_23
        -0x4f95e7af -> :sswitch_22
        -0x4c5d7ad2 -> :sswitch_21
        -0x462c75d3 -> :sswitch_20
        -0x443fd9f8 -> :sswitch_1f
        -0x4167ea76 -> :sswitch_1e
        -0x3e84041a -> :sswitch_1d
        -0x3a662183 -> :sswitch_1c
        -0x36059a58 -> :sswitch_1b
        -0x31334e52 -> :sswitch_1a
        -0x304b3d0f -> :sswitch_19
        -0x2e82afe8 -> :sswitch_18
        -0x2923d15d -> :sswitch_17
        0x30f4df -> :sswitch_16
        0x35224f -> :sswitch_15
        0x361a9b -> :sswitch_14
        0x363419 -> :sswitch_13
        0x302bcfe -> :sswitch_12
        0x5a753b7 -> :sswitch_11
        0x625ef69 -> :sswitch_10
        0x69375c9 -> :sswitch_f
        0x6fb0373 -> :sswitch_e
        0x9e824bb -> :sswitch_d
        0x14757472 -> :sswitch_c
        0x242795ae -> :sswitch_b
        0x26b037d4 -> :sswitch_a
        0x2d2ea02e -> :sswitch_9
        0x3af610bc -> :sswitch_8
        0x49f9e114 -> :sswitch_7
        0x4a797480 -> :sswitch_6
        0x565d3d3a -> :sswitch_5
        0x598d37f6 -> :sswitch_4
        0x5c5796c4 -> :sswitch_3
        0x6717fdcf -> :sswitch_2
        0x7086505d -> :sswitch_1
        0x7787a536 -> :sswitch_0
    .end sparse-switch

    :pswitch_data_0
    .packed-switch 0x0
        :pswitch_26
        :pswitch_25
        :pswitch_24
        :pswitch_23
        :pswitch_22
        :pswitch_21
        :pswitch_20
        :pswitch_1f
        :pswitch_1e
        :pswitch_1d
        :pswitch_1c
        :pswitch_1b
        :pswitch_1a
        :pswitch_19
        :pswitch_18
        :pswitch_17
        :pswitch_16
        :pswitch_15
        :pswitch_14
        :pswitch_13
        :pswitch_12
        :pswitch_11
        :pswitch_10
        :pswitch_f
        :pswitch_e
        :pswitch_d
        :pswitch_c
        :pswitch_b
        :pswitch_a
        :pswitch_9
        :pswitch_8
        :pswitch_1c
        :pswitch_7
        :pswitch_6
        :pswitch_5
        :pswitch_4
        :pswitch_3
        :pswitch_2
        :pswitch_1
        :pswitch_0
    .end packed-switch
.end method

.method private handleBack(Z)V
    .locals 2

    .line 423
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    if-nez v0, :cond_0

    invoke-virtual {p0}, Lcom/huangguo/mobile/MainActivity;->finish()V

    return-void

    :cond_0
    if-eqz p1, :cond_1

    .line 425
    const-string p1, "window.hgPredictiveBackCommit ? window.hgPredictiveBackCommit() : (window.hgBack ? window.hgBack() : false)"

    goto :goto_0

    .line 426
    :cond_1
    const-string p1, "window.hgBack ? window.hgBack() : false"

    .line 427
    :goto_0
    new-instance v1, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda21;

    invoke-direct {v1, p0}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda21;-><init>(Lcom/huangguo/mobile/MainActivity;)V

    invoke-virtual {v0, p1, v1}, Landroid/webkit/WebView;->evaluateJavascript(Ljava/lang/String;Landroid/webkit/ValueCallback;)V

    return-void
.end method

.method private installUpdate(Ljava/io/File;)V
    .locals 2

    .line 321
    invoke-virtual {p0}, Lcom/huangguo/mobile/MainActivity;->getPackageManager()Landroid/content/pm/PackageManager;

    move-result-object v0

    invoke-virtual {v0}, Landroid/content/pm/PackageManager;->canRequestPackageInstalls()Z

    move-result v0

    if-nez v0, :cond_0

    .line 322
    iput-object p1, p0, Lcom/huangguo/mobile/MainActivity;->pendingUpdateApk:Ljava/io/File;

    .line 323
    new-instance p1, Landroid/content/Intent;

    new-instance v0, Ljava/lang/StringBuilder;

    const-string v1, "package:"

    invoke-direct {v0, v1}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    .line 324
    invoke-virtual {p0}, Lcom/huangguo/mobile/MainActivity;->getPackageName()Ljava/lang/String;

    move-result-object v1

    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v0

    invoke-static {v0}, Landroid/net/Uri;->parse(Ljava/lang/String;)Landroid/net/Uri;

    move-result-object v0

    const-string v1, "android.settings.MANAGE_UNKNOWN_APP_SOURCES"

    invoke-direct {p1, v1, v0}, Landroid/content/Intent;-><init>(Ljava/lang/String;Landroid/net/Uri;)V

    .line 325
    invoke-virtual {p0, p1}, Lcom/huangguo/mobile/MainActivity;->startActivity(Landroid/content/Intent;)V

    return-void

    .line 328
    :cond_0
    invoke-direct {p0, p1}, Lcom/huangguo/mobile/MainActivity;->openUpdateInstaller(Ljava/io/File;)V

    return-void
.end method

.method private synthetic lambda$applyPendingResources$0(Ljava/lang/String;)V
    .locals 1

    .line 224
    const-string v0, "true"

    invoke-virtual {v0, p1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result p1

    if-eqz p1, :cond_0

    invoke-virtual {p0}, Lcom/huangguo/mobile/MainActivity;->isDestroyed()Z

    move-result p1

    if-nez p1, :cond_0

    invoke-virtual {p0}, Lcom/huangguo/mobile/MainActivity;->recreate()V

    :cond_0
    return-void
.end method

.method private synthetic lambda$checkHotUpdates$0()V
    .locals 2

    .line 197
    :try_start_0
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity;->hotUpdates:Lcom/huangguo/mobile/HotUpdateManager;

    const/4 v1, 0x0

    invoke-virtual {v0, v1}, Lcom/huangguo/mobile/HotUpdateManager;->check(Z)Lorg/json/JSONObject;

    move-result-object v0

    .line 198
    new-instance v1, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda46;

    invoke-direct {v1, p0, v0}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda46;-><init>(Lcom/huangguo/mobile/MainActivity;Lorg/json/JSONObject;)V

    invoke-virtual {p0, v1}, Lcom/huangguo/mobile/MainActivity;->runOnUiThread(Ljava/lang/Runnable;)V
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0

    return-void

    :catch_0
    move-exception p0

    .line 202
    const-string v0, "AIIvideo"

    const-string v1, "\u8d44\u6e90\u66f4\u65b0\u6682\u4e0d\u53ef\u7528\uff0c\u7ee7\u7eed\u4f7f\u7528\u672c\u5730\u7248\u672c"

    invoke-static {v0, v1, p0}, Landroid/util/Log;->d(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I

    return-void
.end method

.method private synthetic lambda$checkHotUpdates$1(Lorg/json/JSONObject;)V
    .locals 2

    .line 199
    invoke-virtual {p0}, Lcom/huangguo/mobile/MainActivity;->isDestroyed()Z

    move-result v0

    if-nez v0, :cond_0

    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    if-eqz p0, :cond_0

    new-instance v0, Ljava/lang/StringBuilder;

    const-string v1, "window.hgHotUpdateStatus && window.hgHotUpdateStatus("

    invoke-direct {v0, v1}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;

    const-string p1, ")"

    invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p1

    const/4 v0, 0x0

    invoke-virtual {p0, p1, v0}, Landroid/webkit/WebView;->evaluateJavascript(Ljava/lang/String;Landroid/webkit/ValueCallback;)V

    :cond_0
    return-void
.end method

.method private synthetic lambda$dispatch$0()V
    .locals 1

    const/4 v0, 0x1

    .line 490
    iput-boolean v0, p0, Lcom/huangguo/mobile/MainActivity;->resourceReady:Z

    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity;->hotHandler:Landroid/os/Handler;

    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity;->resourceTimeout:Ljava/lang/Runnable;

    invoke-virtual {v0, p0}, Landroid/os/Handler;->removeCallbacks(Ljava/lang/Runnable;)V

    return-void
.end method

.method private synthetic lambda$dispatch$1()V
    .locals 2

    .line 506
    new-instance v0, Landroid/content/Intent;

    const-string v1, "android.intent.action.OPEN_DOCUMENT"

    invoke-direct {v0, v1}, Landroid/content/Intent;-><init>(Ljava/lang/String;)V

    .line 507
    const-string v1, "android.intent.category.OPENABLE"

    invoke-virtual {v0, v1}, Landroid/content/Intent;->addCategory(Ljava/lang/String;)Landroid/content/Intent;

    .line 508
    const-string v1, "image/*"

    invoke-virtual {v0, v1}, Landroid/content/Intent;->setType(Ljava/lang/String;)Landroid/content/Intent;

    const/16 v1, 0x1bbd

    .line 509
    invoke-virtual {p0, v0, v1}, Lcom/huangguo/mobile/MainActivity;->startActivityForResult(Landroid/content/Intent;I)V

    return-void
.end method

.method private synthetic lambda$dispatch$2(I)V
    .locals 1

    .line 517
    new-instance v0, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda20;

    invoke-direct {v0, p0, p1}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda20;-><init>(Lcom/huangguo/mobile/MainActivity;I)V

    invoke-virtual {p0, v0}, Lcom/huangguo/mobile/MainActivity;->runOnUiThread(Ljava/lang/Runnable;)V

    return-void
.end method

.method private synthetic lambda$dispatch$3(I)V
    .locals 2

    .line 518
    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    new-instance v0, Ljava/lang/StringBuilder;

    const-string v1, "window.hgUpdateProgress && window.hgUpdateProgress("

    invoke-direct {v0, v1}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    const-string p1, ")"

    invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p1

    const/4 v0, 0x0

    invoke-virtual {p0, p1, v0}, Landroid/webkit/WebView;->evaluateJavascript(Ljava/lang/String;Landroid/webkit/ValueCallback;)V

    return-void
.end method

.method private synthetic lambda$dispatch$4(Ljava/io/File;)V
    .locals 2

    # 下载失败时 download() 回 null。原来这里不看就直接 installUpdate(null)，
    # 一路走到 FileProvider.getUriForFile(context, authority, null) —— 那里
    # 拿 null 调 getCanonicalPath()，当场 NPE，用户看到的是「点下载就闪退」。
    if-nez p1, :cond_ok

    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    const-string v1, "window.hgUpdateFailed && window.hgUpdateFailed()"

    const/4 p1, 0x0

    invoke-virtual {v0, v1, p1}, Landroid/webkit/WebView;->evaluateJavascript(Ljava/lang/String;Landroid/webkit/ValueCallback;)V

    return-void

    :cond_ok
    .line 519
    invoke-direct {p0, p1}, Lcom/huangguo/mobile/MainActivity;->installUpdate(Ljava/io/File;)V

    return-void
.end method

.method static synthetic lambda$dispatch$5(Lcom/huangguo/mobile/AccountService;)V
    .locals 2

    .line 572
    :try_start_0
    invoke-virtual {p0}, Lcom/huangguo/mobile/AccountService;->sync()Lorg/json/JSONObject;
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0

    return-void

    :catch_0
    move-exception p0

    const-string v0, "AIIvideo"

    const-string v1, "\u6536\u85cf\u7a0d\u540e\u540c\u6b65"

    invoke-static {v0, v1, p0}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I

    return-void
.end method

.method static synthetic lambda$dispatch$6(Lcom/huangguo/mobile/AccountService;)V
    .locals 0

    .line 578
    :try_start_0
    invoke-virtual {p0}, Lcom/huangguo/mobile/AccountService;->sync()Lorg/json/JSONObject;
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0

    :catch_0
    return-void
.end method

.method static synthetic lambda$dispatch$7(Lcom/huangguo/mobile/AccountService;)V
    .locals 0

    .line 579
    :try_start_0
    invoke-virtual {p0}, Lcom/huangguo/mobile/AccountService;->sync()Lorg/json/JSONObject;
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0

    :catch_0
    return-void
.end method

.method private synthetic lambda$dispatch$8(Landroid/content/Intent;)V
    .locals 0

    .line 586
    invoke-virtual {p0, p1}, Lcom/huangguo/mobile/MainActivity;->startActivity(Landroid/content/Intent;)V

    return-void
.end method

.method private synthetic lambda$handleBack$0(Ljava/lang/String;)V
    .locals 1

    .line 428
    const-string v0, "true"

    invoke-virtual {v0, p1}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result p1

    if-nez p1, :cond_0

    const/4 p1, 0x1

    invoke-virtual {p0, p1}, Lcom/huangguo/mobile/MainActivity;->moveTaskToBack(Z)Z

    move-result p1

    if-nez p1, :cond_0

    invoke-virtual {p0}, Lcom/huangguo/mobile/MainActivity;->finish()V

    :cond_0
    return-void
.end method

.method private synthetic lambda$new$0()V
    .locals 0

    .line 62
    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->recoverResources()V

    return-void
.end method

.method private synthetic lambda$onActivityResult$0(Landroid/net/Uri;)V
    .locals 2

    .line 311
    :try_start_0
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity;->account:Lcom/huangguo/mobile/AccountService;

    invoke-virtual {v0, p1}, Lcom/huangguo/mobile/AccountService;->setAvatar(Landroid/net/Uri;)Lorg/json/JSONObject;

    move-result-object p1

    .line 312
    new-instance v0, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda23;

    invoke-direct {v0, p0, p1}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda23;-><init>(Lcom/huangguo/mobile/MainActivity;Lorg/json/JSONObject;)V

    invoke-virtual {p0, v0}, Lcom/huangguo/mobile/MainActivity;->runOnUiThread(Ljava/lang/Runnable;)V
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0

    return-void

    :catch_0
    move-exception p1

    .line 314
    new-instance v0, Ljava/lang/StringBuilder;

    const-string v1, "window.hgAvatarError && window.hgAvatarError("

    invoke-direct {v0, v1}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {p1}, Ljava/lang/Exception;->getMessage()Ljava/lang/String;

    move-result-object v1

    if-nez v1, :cond_0

    const-string p1, "\u5934\u50cf\u4fdd\u5b58\u5931\u8d25"

    goto :goto_0

    :cond_0
    invoke-virtual {p1}, Ljava/lang/Exception;->getMessage()Ljava/lang/String;

    move-result-object p1

    :goto_0
    invoke-static {p1}, Lorg/json/JSONObject;->quote(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p1

    invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const-string p1, ")"

    invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p1

    .line 315
    new-instance v0, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda24;

    invoke-direct {v0, p0, p1}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda24;-><init>(Lcom/huangguo/mobile/MainActivity;Ljava/lang/String;)V

    invoke-virtual {p0, v0}, Lcom/huangguo/mobile/MainActivity;->runOnUiThread(Ljava/lang/Runnable;)V

    return-void
.end method

.method private synthetic lambda$onActivityResult$1(Lorg/json/JSONObject;)V
    .locals 2

    .line 312
    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    new-instance v0, Ljava/lang/StringBuilder;

    const-string v1, "window.hgAvatarUpdated && window.hgAvatarUpdated("

    invoke-direct {v0, v1}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;

    const-string p1, ")"

    invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p1

    const/4 v0, 0x0

    invoke-virtual {p0, p1, v0}, Landroid/webkit/WebView;->evaluateJavascript(Ljava/lang/String;Landroid/webkit/ValueCallback;)V

    return-void
.end method

.method private synthetic lambda$onActivityResult$2(Ljava/lang/String;)V
    .locals 1

    .line 315
    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    const/4 v0, 0x0

    invoke-virtual {p0, p1, v0}, Landroid/webkit/WebView;->evaluateJavascript(Ljava/lang/String;Landroid/webkit/ValueCallback;)V

    return-void
.end method

.method private synthetic lambda$onCreate$0(Landroid/view/View;Landroid/view/WindowInsets;)Landroid/view/WindowInsets;
    .locals 4

    .line 109
    sget p1, Landroid/os/Build$VERSION;->SDK_INT:I

    const/16 v0, 0x1e

    const/4 v1, 0x0

    if-lt p1, v0, :cond_0

    .line 110
    invoke-static {}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticApiModelOutline0;->m()I

    move-result p1

    invoke-static {p2, p1}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticApiModelOutline0;->m(Landroid/view/WindowInsets;I)Landroid/graphics/Insets;

    move-result-object p1

    .line 111
    invoke-static {p1}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticApiModelOutline0;->m(Landroid/graphics/Insets;)I

    move-result v0

    int-to-float v0, v0

    invoke-virtual {p0}, Lcom/huangguo/mobile/MainActivity;->getResources()Landroid/content/res/Resources;

    move-result-object v2

    invoke-virtual {v2}, Landroid/content/res/Resources;->getDisplayMetrics()Landroid/util/DisplayMetrics;

    move-result-object v2

    iget v2, v2, Landroid/util/DisplayMetrics;->density:F

    div-float/2addr v0, v2

    iput v0, p0, Lcom/huangguo/mobile/MainActivity;->statusBarInsetDp:F

    .line 112
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity;->root:Landroid/widget/FrameLayout;

    invoke-static {p1}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticApiModelOutline0;->m$1(Landroid/graphics/Insets;)I

    move-result v2

    invoke-static {p1}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticApiModelOutline0;->m$2(Landroid/graphics/Insets;)I

    move-result v3

    invoke-static {p1}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticApiModelOutline0;->m$3(Landroid/graphics/Insets;)I

    move-result p1

    invoke-virtual {v0, v2, v1, v3, p1}, Landroid/widget/FrameLayout;->setPadding(IIII)V

    goto :goto_0

    .line 114
    :cond_0
    invoke-virtual {p2}, Landroid/view/WindowInsets;->getSystemWindowInsetTop()I

    move-result p1

    int-to-float p1, p1

    invoke-virtual {p0}, Lcom/huangguo/mobile/MainActivity;->getResources()Landroid/content/res/Resources;

    move-result-object v0

    invoke-virtual {v0}, Landroid/content/res/Resources;->getDisplayMetrics()Landroid/util/DisplayMetrics;

    move-result-object v0

    iget v0, v0, Landroid/util/DisplayMetrics;->density:F

    div-float/2addr p1, v0

    iput p1, p0, Lcom/huangguo/mobile/MainActivity;->statusBarInsetDp:F

    .line 115
    iget-object p1, p0, Lcom/huangguo/mobile/MainActivity;->root:Landroid/widget/FrameLayout;

    invoke-virtual {p2}, Landroid/view/WindowInsets;->getSystemWindowInsetLeft()I

    move-result v0

    .line 116
    invoke-virtual {p2}, Landroid/view/WindowInsets;->getSystemWindowInsetRight()I

    move-result v2

    invoke-virtual {p2}, Landroid/view/WindowInsets;->getSystemWindowInsetBottom()I

    move-result v3

    .line 115
    invoke-virtual {p1, v0, v1, v2, v3}, Landroid/widget/FrameLayout;->setPadding(IIII)V

    .line 118
    :goto_0
    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->publishWindowInsets()V

    return-object p2
.end method

.method private synthetic lambda$onCreate$1(Ljava/lang/String;)Landroid/webkit/WebResourceResponse;
    .locals 1

    .line 129
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity;->hotUpdates:Lcom/huangguo/mobile/HotUpdateManager;

    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity;->hotSession:Lcom/huangguo/mobile/HotUpdateManager$Session;

    invoke-virtual {v0, p0, p1}, Lcom/huangguo/mobile/HotUpdateManager;->resource(Lcom/huangguo/mobile/HotUpdateManager$Session;Ljava/lang/String;)Landroid/webkit/WebResourceResponse;

    move-result-object p0

    return-object p0
.end method

.method private synthetic lambda$onResume$0()V
    .locals 2

    .line 288
    :try_start_0
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity;->account:Lcom/huangguo/mobile/AccountService;

    invoke-virtual {v0}, Lcom/huangguo/mobile/AccountService;->sync()Lorg/json/JSONObject;

    .line 289
    new-instance v0, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda28;

    invoke-direct {v0, p0}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda28;-><init>(Lcom/huangguo/mobile/MainActivity;)V

    invoke-virtual {p0, v0}, Lcom/huangguo/mobile/MainActivity;->runOnUiThread(Ljava/lang/Runnable;)V
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0

    return-void

    :catch_0
    move-exception p0

    .line 290
    const-string v0, "AIIvideo"

    const-string v1, "\u4e91\u7aef\u540c\u6b65\u7a0d\u540e\u91cd\u8bd5"

    invoke-static {v0, v1, p0}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I

    return-void
.end method

.method private synthetic lambda$onResume$1()V
    .locals 2

    .line 289
    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    const-string v0, "window.hgCloudSynced && window.hgCloudSynced()"

    const/4 v1, 0x0

    invoke-virtual {p0, v0, v1}, Landroid/webkit/WebView;->evaluateJavascript(Ljava/lang/String;Landroid/webkit/ValueCallback;)V

    return-void
.end method

.method private synthetic lambda$refreshAccountAfterAuth$0(Ljava/lang/String;)V
    .locals 4

    .line 640
    const-string v0, "AIIvideo"

    .line 0
    const-string v1, "window.hgAccountRefreshed && window.hgAccountRefreshed("

    .line 640
    :try_start_0
    iget-object v2, p0, Lcom/huangguo/mobile/MainActivity;->account:Lcom/huangguo/mobile/AccountService;

    invoke-virtual {v2}, Lcom/huangguo/mobile/AccountService;->sync()Lorg/json/JSONObject;

    .line 641
    new-instance v2, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda18;

    invoke-direct {v2, p0}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda18;-><init>(Lcom/huangguo/mobile/MainActivity;)V

    invoke-virtual {p0, v2}, Lcom/huangguo/mobile/MainActivity;->runOnUiThread(Ljava/lang/Runnable;)V
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0

    goto :goto_0

    :catch_0
    move-exception v2

    .line 642
    const-string v3, "\u767b\u5f55\u540e\u7247\u5355\u7a0d\u540e\u540c\u6b65"

    invoke-static {v0, v3, v2}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I

    .line 644
    :goto_0
    :try_start_1
    iget-object v2, p0, Lcom/huangguo/mobile/MainActivity;->account:Lcom/huangguo/mobile/AccountService;

    invoke-virtual {v2}, Lcom/huangguo/mobile/AccountService;->refreshProfile()Lorg/json/JSONObject;

    move-result-object v2

    .line 645
    const-string v3, "userId"

    invoke-virtual {v2, v3}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v3

    invoke-virtual {p1, v3}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result p1

    if-nez p1, :cond_0

    goto :goto_1

    .line 646
    :cond_0
    new-instance p1, Ljava/lang/StringBuilder;

    invoke-direct {p1, v1}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {p1, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;

    const-string v1, ")"

    invoke-virtual {p1, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {p1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p1

    .line 647
    new-instance v1, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda19;

    invoke-direct {v1, p0, p1}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda19;-><init>(Lcom/huangguo/mobile/MainActivity;Ljava/lang/String;)V

    invoke-virtual {p0, v1}, Lcom/huangguo/mobile/MainActivity;->runOnUiThread(Ljava/lang/Runnable;)V
    :try_end_1
    .catch Ljava/lang/Exception; {:try_start_1 .. :try_end_1} :catch_1

    goto :goto_1

    :catch_1
    move-exception p0

    .line 648
    const-string p1, "\u767b\u5f55\u540e\u8d26\u53f7\u8d44\u6599\u7a0d\u540e\u5237\u65b0"

    invoke-static {v0, p1, p0}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I

    :goto_1
    return-void
.end method

.method private synthetic lambda$refreshAccountAfterAuth$1()V
    .locals 2

    .line 641
    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    const-string v0, "window.hgCloudSynced && window.hgCloudSynced()"

    const/4 v1, 0x0

    invoke-virtual {p0, v0, v1}, Landroid/webkit/WebView;->evaluateJavascript(Ljava/lang/String;Landroid/webkit/ValueCallback;)V

    return-void
.end method

.method private synthetic lambda$refreshAccountAfterAuth$2(Ljava/lang/String;)V
    .locals 1

    .line 647
    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    const/4 v0, 0x0

    invoke-virtual {p0, p1, v0}, Landroid/webkit/WebView;->evaluateJavascript(Ljava/lang/String;Landroid/webkit/ValueCallback;)V

    return-void
.end method

.method private synthetic lambda$refreshAccountSilently$0(Lcom/huangguo/mobile/AccountService;Ljava/lang/String;)V
    .locals 1

    .line 655
    :try_start_0
    invoke-virtual {p1}, Lcom/huangguo/mobile/AccountService;->refreshProfile()Lorg/json/JSONObject;

    move-result-object p1

    .line 656
    invoke-static {}, Lcom/parse/ParseUser;->getCurrentUser()Lcom/parse/ParseUser;

    move-result-object v0

    if-eqz v0, :cond_1

    .line 657
    invoke-virtual {v0}, Lcom/parse/ParseUser;->getObjectId()Ljava/lang/String;

    move-result-object v0

    invoke-virtual {p2, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v0

    if-eqz v0, :cond_1

    const-string v0, "userId"

    invoke-virtual {p1, v0}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v0

    invoke-virtual {p2, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result p2

    if-nez p2, :cond_0

    goto :goto_0

    .line 658
    :cond_0
    new-instance p2, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda44;

    invoke-direct {p2, p0, p1}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda44;-><init>(Lcom/huangguo/mobile/MainActivity;Lorg/json/JSONObject;)V

    invoke-virtual {p0, p2}, Lcom/huangguo/mobile/MainActivity;->runOnUiThread(Ljava/lang/Runnable;)V
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0

    :cond_1
    :goto_0
    return-void

    :catch_0
    move-exception p0

    .line 662
    const-string p1, "AIIvideo"

    const-string p2, "\u8d26\u53f7\u540e\u53f0\u5237\u65b0\u7a0d\u540e\u91cd\u8bd5"

    invoke-static {p1, p2, p0}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I

    return-void
.end method

.method private synthetic lambda$refreshAccountSilently$1(Lorg/json/JSONObject;)V
    .locals 2

    .line 659
    invoke-virtual {p0}, Lcom/huangguo/mobile/MainActivity;->isDestroyed()Z

    move-result v0

    if-nez v0, :cond_0

    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    if-eqz p0, :cond_0

    new-instance v0, Ljava/lang/StringBuilder;

    const-string v1, "window.hgAccountRefreshed && window.hgAccountRefreshed("

    invoke-direct {v0, v1}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;

    const-string p1, ")"

    invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p1

    const/4 v0, 0x0

    invoke-virtual {p0, p1, v0}, Landroid/webkit/WebView;->evaluateJavascript(Ljava/lang/String;Landroid/webkit/ValueCallback;)V

    :cond_0
    return-void
.end method

.method private synthetic lambda$sendLatestBackProgress$0(ILjava/lang/String;)V
    .locals 0

    .line 416
    iget p2, p0, Lcom/huangguo/mobile/MainActivity;->backProgressGeneration:I

    if-eq p1, p2, :cond_0

    return-void

    :cond_0
    const/4 p1, 0x0

    .line 417
    iput-boolean p1, p0, Lcom/huangguo/mobile/MainActivity;->backProgressInFlight:Z

    .line 418
    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->sendLatestBackProgress()V

    return-void
.end method

.method private synthetic lambda$updateBackRegistration$0()V
    .locals 1

    const/4 v0, 0x0

    .line 371
    invoke-direct {p0, v0}, Lcom/huangguo/mobile/MainActivity;->handleBack(Z)V

    return-void
.end method

.method private openUpdateInstaller(Ljava/io/File;)V
    .locals 2

    .line 333
    :try_start_0
    new-instance v0, Ljava/lang/StringBuilder;

    invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V

    invoke-virtual {p0}, Lcom/huangguo/mobile/MainActivity;->getPackageName()Ljava/lang/String;

    move-result-object v1

    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const-string v1, ".updates"

    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v0

    invoke-static {p0, v0, p1}, Landroidx/core/content/FileProvider;->getUriForFile(Landroid/content/Context;Ljava/lang/String;Ljava/io/File;)Landroid/net/Uri;

    move-result-object p1

    .line 334
    new-instance v0, Landroid/content/Intent;

    const-string v1, "android.intent.action.INSTALL_PACKAGE"

    invoke-direct {v0, v1}, Landroid/content/Intent;-><init>(Ljava/lang/String;)V

    .line 335
    invoke-virtual {v0, p1}, Landroid/content/Intent;->setData(Landroid/net/Uri;)Landroid/content/Intent;

    const/4 p1, 0x1

    .line 336
    invoke-virtual {v0, p1}, Landroid/content/Intent;->addFlags(I)Landroid/content/Intent;

    .line 337
    invoke-virtual {p0, v0}, Lcom/huangguo/mobile/MainActivity;->startActivity(Landroid/content/Intent;)V
    :try_end_0
    .catch Landroid/content/ActivityNotFoundException; {:try_start_0 .. :try_end_0} :catch_1
    .catch Ljava/lang/IllegalArgumentException; {:try_start_0 .. :try_end_0} :catch_0

    return-void

    :catch_0
    move-exception p1

    goto :goto_0

    :catch_1
    move-exception p1

    .line 339
    :goto_0
    const-string v0, "AIIvideo"

    const-string v1, "\u65e0\u6cd5\u6253\u5f00\u7cfb\u7edf\u5b89\u88c5\u7a0b\u5e8f"

    invoke-static {v0, v1, p1}, Landroid/util/Log;->e(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I

    .line 340
    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    const-string p1, "window.hgUpdateInstallError && window.hgUpdateInstallError()"

    const/4 v0, 0x0

    invoke-virtual {p0, p1, v0}, Landroid/webkit/WebView;->evaluateJavascript(Ljava/lang/String;Landroid/webkit/ValueCallback;)V

    return-void
.end method

.method private publishWindowInsets()V
    .locals 3

    .line 258
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    if-eqz v0, :cond_0

    new-instance v1, Ljava/lang/StringBuilder;

    const-string v2, "window.hgWindowInsets && window.hgWindowInsets("

    invoke-direct {v1, v2}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    iget p0, p0, Lcom/huangguo/mobile/MainActivity;->statusBarInsetDp:F

    invoke-virtual {v1, p0}, Ljava/lang/StringBuilder;->append(F)Ljava/lang/StringBuilder;

    const-string p0, ")"

    invoke-virtual {v1, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p0

    const/4 v1, 0x0

    invoke-virtual {v0, p0, v1}, Landroid/webkit/WebView;->evaluateJavascript(Ljava/lang/String;Landroid/webkit/ValueCallback;)V

    :cond_0
    return-void
.end method

.method private declared-synchronized rebindContent()V
    .locals 3
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    monitor-enter p0

    .line 452
    :try_start_0
    invoke-static {p0}, Lcom/huangguo/mobile/ContentMode;->get(Landroid/content/Context;)Lcom/huangguo/mobile/ContentMode;

    move-result-object v0

    invoke-virtual {v0}, Lcom/huangguo/mobile/ContentMode;->active()Ljava/lang/String;

    move-result-object v0

    .line 453
    new-instance v1, Lcom/huangguo/mobile/DualContentRepository;

    iget-object v2, p0, Lcom/huangguo/mobile/MainActivity;->hotSession:Lcom/huangguo/mobile/HotUpdateManager$Session;

    iget-object v2, v2, Lcom/huangguo/mobile/HotUpdateManager$Session;->site:Lcom/huangguo/mobile/SiteProfile;

    invoke-direct {v1, p0, v0, v2}, Lcom/huangguo/mobile/DualContentRepository;-><init>(Landroid/content/Context;Ljava/lang/String;Lcom/huangguo/mobile/SiteProfile;)V

    iput-object v1, p0, Lcom/huangguo/mobile/MainActivity;->repository:Lcom/huangguo/mobile/DualContentRepository;

    .line 454
    new-instance v1, Lcom/huangguo/mobile/AppStore;

    invoke-direct {v1, p0, v0}, Lcom/huangguo/mobile/AppStore;-><init>(Landroid/content/Context;Ljava/lang/String;)V

    iput-object v1, p0, Lcom/huangguo/mobile/MainActivity;->store:Lcom/huangguo/mobile/AppStore;

    .line 455
    new-instance v0, Lcom/huangguo/mobile/AccountService;

    iget-object v1, p0, Lcom/huangguo/mobile/MainActivity;->store:Lcom/huangguo/mobile/AppStore;

    invoke-direct {v0, p0, v1}, Lcom/huangguo/mobile/AccountService;-><init>(Landroid/content/Context;Lcom/huangguo/mobile/AppStore;)V

    iput-object v0, p0, Lcom/huangguo/mobile/MainActivity;->account:Lcom/huangguo/mobile/AccountService;

    .line 456
    invoke-static {}, Lcom/parse/ParseUser;->getCurrentUser()Lcom/parse/ParseUser;

    move-result-object v0

    .line 457
    iget-object v1, p0, Lcom/huangguo/mobile/MainActivity;->store:Lcom/huangguo/mobile/AppStore;

    if-nez v0, :cond_0

    const-string v0, ""

    goto :goto_0

    :cond_0
    invoke-virtual {v0}, Lcom/parse/ParseUser;->getObjectId()Ljava/lang/String;

    move-result-object v0

    :goto_0
    invoke-virtual {v1, v0}, Lcom/huangguo/mobile/AppStore;->activateUser(Ljava/lang/String;)V
    :try_end_0
    .catchall {:try_start_0 .. :try_end_0} :catchall_0

    .line 458
    monitor-exit p0

    return-void

    :catchall_0
    move-exception v0

    :try_start_1
    monitor-exit p0
    :try_end_1
    .catchall {:try_start_1 .. :try_end_1} :catchall_0

    throw v0
.end method

.method private recoverResources()V
    .locals 4

    .line 207
    const-string v0, "AIIvideo"

    iget-boolean v1, p0, Lcom/huangguo/mobile/MainActivity;->resourceReady:Z

    if-nez v1, :cond_1

    iget-object v1, p0, Lcom/huangguo/mobile/MainActivity;->hotSession:Lcom/huangguo/mobile/HotUpdateManager$Session;

    if-eqz v1, :cond_1

    iget-boolean v1, v1, Lcom/huangguo/mobile/HotUpdateManager$Session;->trial:Z

    if-eqz v1, :cond_1

    invoke-virtual {p0}, Lcom/huangguo/mobile/MainActivity;->isDestroyed()Z

    move-result v1

    if-eqz v1, :cond_0

    goto :goto_0

    .line 208
    :cond_0
    iget-object v1, p0, Lcom/huangguo/mobile/MainActivity;->hotHandler:Landroid/os/Handler;

    iget-object v2, p0, Lcom/huangguo/mobile/MainActivity;->resourceTimeout:Ljava/lang/Runnable;

    invoke-virtual {v1, v2}, Landroid/os/Handler;->removeCallbacks(Ljava/lang/Runnable;)V

    .line 210
    :try_start_0
    iget-object v1, p0, Lcom/huangguo/mobile/MainActivity;->hotUpdates:Lcom/huangguo/mobile/HotUpdateManager;

    iget-object v2, p0, Lcom/huangguo/mobile/MainActivity;->hotSession:Lcom/huangguo/mobile/HotUpdateManager$Session;

    invoke-virtual {v1, v2}, Lcom/huangguo/mobile/HotUpdateManager;->rollback(Lcom/huangguo/mobile/HotUpdateManager$Session;)V

    .line 211
    iget-object v1, p0, Lcom/huangguo/mobile/MainActivity;->hotUpdates:Lcom/huangguo/mobile/HotUpdateManager;

    invoke-virtual {v1}, Lcom/huangguo/mobile/HotUpdateManager;->beginSession()Lcom/huangguo/mobile/HotUpdateManager$Session;

    move-result-object v1

    iput-object v1, p0, Lcom/huangguo/mobile/MainActivity;->hotSession:Lcom/huangguo/mobile/HotUpdateManager$Session;

    .line 212
    new-instance v1, Lcom/huangguo/mobile/DualContentRepository;

    invoke-static {p0}, Lcom/huangguo/mobile/ContentMode;->get(Landroid/content/Context;)Lcom/huangguo/mobile/ContentMode;

    move-result-object v2

    invoke-virtual {v2}, Lcom/huangguo/mobile/ContentMode;->active()Ljava/lang/String;

    move-result-object v2

    iget-object v3, p0, Lcom/huangguo/mobile/MainActivity;->hotSession:Lcom/huangguo/mobile/HotUpdateManager$Session;

    iget-object v3, v3, Lcom/huangguo/mobile/HotUpdateManager$Session;->site:Lcom/huangguo/mobile/SiteProfile;

    invoke-direct {v1, p0, v2, v3}, Lcom/huangguo/mobile/DualContentRepository;-><init>(Landroid/content/Context;Ljava/lang/String;Lcom/huangguo/mobile/SiteProfile;)V

    iput-object v1, p0, Lcom/huangguo/mobile/MainActivity;->repository:Lcom/huangguo/mobile/DualContentRepository;

    .line 213
    iget-object v1, p0, Lcom/huangguo/mobile/MainActivity;->store:Lcom/huangguo/mobile/AppStore;

    invoke-virtual {v1}, Lcom/huangguo/mobile/AppStore;->clearCache()V

    .line 214
    const-string v1, "hot-updates"

    const/4 v2, 0x0

    invoke-virtual {p0, v1, v2}, Lcom/huangguo/mobile/MainActivity;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;

    move-result-object v1

    invoke-interface {v1}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences$Editor;

    move-result-object v1

    const-string v2, "siteFingerprint"

    iget-object v3, p0, Lcom/huangguo/mobile/MainActivity;->hotSession:Lcom/huangguo/mobile/HotUpdateManager$Session;

    iget-object v3, v3, Lcom/huangguo/mobile/HotUpdateManager$Session;->site:Lcom/huangguo/mobile/SiteProfile;

    iget-object v3, v3, Lcom/huangguo/mobile/SiteProfile;->fingerprint:Ljava/lang/String;

    .line 215
    invoke-interface {v1, v2, v3}, Landroid/content/SharedPreferences$Editor;->putString(Ljava/lang/String;Ljava/lang/String;)Landroid/content/SharedPreferences$Editor;

    move-result-object v1

    invoke-interface {v1}, Landroid/content/SharedPreferences$Editor;->apply()V

    .line 216
    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    const-string v1, "https://appassets.androidplatform.net/assets/app.html"

    invoke-virtual {p0, v1}, Landroid/webkit/WebView;->loadUrl(Ljava/lang/String;)V

    .line 217
    const-string p0, "\u8d44\u6e90\u5305\u542f\u52a8\u5f02\u5e38\uff0c\u5df2\u56de\u9000\u81f3\u53ef\u7528\u7248\u672c"

    invoke-static {v0, p0}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0

    return-void

    :catch_0
    move-exception p0

    .line 218
    const-string v1, "\u8d44\u6e90\u56de\u9000\u5931\u8d25"

    invoke-static {v0, v1, p0}, Landroid/util/Log;->e(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I

    :cond_1
    :goto_0
    return-void
.end method

.method private refreshAccountAfterAuth(Ljava/lang/String;)V
    .locals 2

    .line 638
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity;->requests:Ljava/util/concurrent/ExecutorService;

    new-instance v1, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda25;

    invoke-direct {v1, p0, p1}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda25;-><init>(Lcom/huangguo/mobile/MainActivity;Ljava/lang/String;)V

    invoke-interface {v0, v1}, Ljava/util/concurrent/ExecutorService;->execute(Ljava/lang/Runnable;)V

    return-void
.end method

.method private refreshAccountSilently(Lcom/huangguo/mobile/AccountService;Ljava/lang/String;)V
    .locals 2

    .line 653
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity;->requests:Ljava/util/concurrent/ExecutorService;

    new-instance v1, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda42;

    invoke-direct {v1, p0, p1, p2}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda42;-><init>(Lcom/huangguo/mobile/MainActivity;Lcom/huangguo/mobile/AccountService;Ljava/lang/String;)V

    invoke-interface {v0, v1}, Ljava/util/concurrent/ExecutorService;->execute(Ljava/lang/Runnable;)V

    return-void
.end method

.method private resetBackProgress()V
    .locals 1

    .line 382
    iget v0, p0, Lcom/huangguo/mobile/MainActivity;->backProgressGeneration:I

    add-int/lit8 v0, v0, 0x1

    iput v0, p0, Lcom/huangguo/mobile/MainActivity;->backProgressGeneration:I

    const/high16 v0, -0x40800000    # -1.0f

    .line 383
    iput v0, p0, Lcom/huangguo/mobile/MainActivity;->pendingBackProgress:F

    const/4 v0, 0x0

    .line 384
    iput-boolean v0, p0, Lcom/huangguo/mobile/MainActivity;->backProgressInFlight:Z

    return-void
.end method

.method private screenCornerRadius(I)F
    .locals 2

    .line 233
    sget v0, Landroid/os/Build$VERSION;->SDK_INT:I

    const/16 v1, 0x1f

    if-lt v0, v1, :cond_1

    .line 234
    invoke-static {p0}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticApiModelOutline0;->m(Lcom/huangguo/mobile/MainActivity;)Landroid/view/Display;

    move-result-object v0

    if-nez v0, :cond_0

    const/4 p1, 0x0

    goto :goto_0

    :cond_0
    invoke-static {p0}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticApiModelOutline0;->m(Lcom/huangguo/mobile/MainActivity;)Landroid/view/Display;

    move-result-object v0

    invoke-static {v0, p1}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticApiModelOutline0;->m(Landroid/view/Display;I)Landroid/view/RoundedCorner;

    move-result-object p1

    :goto_0
    if-eqz p1, :cond_1

    .line 235
    invoke-static {p1}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticApiModelOutline0;->m(Landroid/view/RoundedCorner;)I

    move-result v0

    if-lez v0, :cond_1

    .line 236
    invoke-static {p1}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticApiModelOutline0;->m(Landroid/view/RoundedCorner;)I

    move-result p1

    int-to-float p1, p1

    invoke-virtual {p0}, Lcom/huangguo/mobile/MainActivity;->getResources()Landroid/content/res/Resources;

    move-result-object p0

    invoke-virtual {p0}, Landroid/content/res/Resources;->getDisplayMetrics()Landroid/util/DisplayMetrics;

    move-result-object p0

    iget p0, p0, Landroid/util/DisplayMetrics;->density:F

    div-float/2addr p1, p0

    return p1

    :cond_1
    const/high16 p0, 0x41b00000    # 22.0f

    return p0
.end method

.method private screenCorners()Lorg/json/JSONObject;
    .locals 4
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    .line 242
    new-instance v0, Lorg/json/JSONObject;

    invoke-direct {v0}, Lorg/json/JSONObject;-><init>()V

    const/4 v1, 0x0

    .line 243
    invoke-direct {p0, v1}, Lcom/huangguo/mobile/MainActivity;->screenCornerRadius(I)F

    move-result v1

    float-to-double v1, v1

    const-string v3, "topLeft"

    invoke-virtual {v0, v3, v1, v2}, Lorg/json/JSONObject;->put(Ljava/lang/String;D)Lorg/json/JSONObject;

    move-result-object v0

    const/4 v1, 0x1

    .line 244
    invoke-direct {p0, v1}, Lcom/huangguo/mobile/MainActivity;->screenCornerRadius(I)F

    move-result v1

    float-to-double v1, v1

    const-string v3, "topRight"

    invoke-virtual {v0, v3, v1, v2}, Lorg/json/JSONObject;->put(Ljava/lang/String;D)Lorg/json/JSONObject;

    move-result-object v0

    const/4 v1, 0x2

    .line 245
    invoke-direct {p0, v1}, Lcom/huangguo/mobile/MainActivity;->screenCornerRadius(I)F

    move-result v1

    float-to-double v1, v1

    const-string v3, "bottomRight"

    invoke-virtual {v0, v3, v1, v2}, Lorg/json/JSONObject;->put(Ljava/lang/String;D)Lorg/json/JSONObject;

    move-result-object v0

    const/4 v1, 0x3

    .line 246
    invoke-direct {p0, v1}, Lcom/huangguo/mobile/MainActivity;->screenCornerRadius(I)F

    move-result p0

    float-to-double v1, p0

    const-string p0, "bottomLeft"

    invoke-virtual {v0, p0, v1, v2}, Lorg/json/JSONObject;->put(Ljava/lang/String;D)Lorg/json/JSONObject;

    move-result-object p0

    return-object p0
.end method

.method private sendLatestBackProgress()V
    .locals 5

    .line 409
    iget-boolean v0, p0, Lcom/huangguo/mobile/MainActivity;->backProgressInFlight:Z

    if-nez v0, :cond_1

    iget v0, p0, Lcom/huangguo/mobile/MainActivity;->pendingBackProgress:F

    const/4 v1, 0x0

    cmpg-float v1, v0, v1

    if-ltz v1, :cond_1

    iget-object v1, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    if-nez v1, :cond_0

    goto :goto_0

    :cond_0
    const/high16 v2, -0x40800000    # -1.0f

    .line 411
    iput v2, p0, Lcom/huangguo/mobile/MainActivity;->pendingBackProgress:F

    const/4 v2, 0x1

    .line 412
    iput-boolean v2, p0, Lcom/huangguo/mobile/MainActivity;->backProgressInFlight:Z

    .line 413
    iget v2, p0, Lcom/huangguo/mobile/MainActivity;->backProgressGeneration:I

    .line 414
    new-instance v3, Ljava/lang/StringBuilder;

    const-string v4, "window.hgPredictiveBackProgress && window.hgPredictiveBackProgress("

    invoke-direct {v3, v4}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {v3, v0}, Ljava/lang/StringBuilder;->append(F)Ljava/lang/StringBuilder;

    const-string v0, ")"

    invoke-virtual {v3, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v3}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v0

    new-instance v3, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda45;

    invoke-direct {v3, p0, v2}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda45;-><init>(Lcom/huangguo/mobile/MainActivity;I)V

    invoke-virtual {v1, v0, v3}, Landroid/webkit/WebView;->evaluateJavascript(Ljava/lang/String;Landroid/webkit/ValueCallback;)V

    :cond_1
    :goto_0
    return-void
.end method

.method private updateBackRegistration(Z)V
    .locals 3

    .line 345
    sget v0, Landroid/os/Build$VERSION;->SDK_INT:I

    const/16 v1, 0x21

    if-lt v0, v1, :cond_3

    iget-boolean v0, p0, Lcom/huangguo/mobile/MainActivity;->backRegistered:Z

    if-ne p1, v0, :cond_0

    goto :goto_2

    :cond_0
    if-eqz p1, :cond_2

    .line 347
    sget v0, Landroid/os/Build$VERSION;->SDK_INT:I

    const/16 v1, 0x22

    if-lt v0, v1, :cond_1

    .line 348
    new-instance v0, Lcom/huangguo/mobile/MainActivity$3;

    invoke-direct {v0, p0}, Lcom/huangguo/mobile/MainActivity$3;-><init>(Lcom/huangguo/mobile/MainActivity;)V

    iput-object v0, p0, Lcom/huangguo/mobile/MainActivity;->backCallback:Landroid/window/OnBackInvokedCallback;

    goto :goto_0

    .line 371
    :cond_1
    new-instance v0, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda31;

    invoke-direct {v0, p0}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda31;-><init>(Lcom/huangguo/mobile/MainActivity;)V

    iput-object v0, p0, Lcom/huangguo/mobile/MainActivity;->backCallback:Landroid/window/OnBackInvokedCallback;

    .line 372
    :goto_0
    invoke-static {p0}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticApiModelOutline0;->m(Lcom/huangguo/mobile/MainActivity;)Landroid/window/OnBackInvokedDispatcher;

    move-result-object v0

    const/4 v1, 0x0

    iget-object v2, p0, Lcom/huangguo/mobile/MainActivity;->backCallback:Landroid/window/OnBackInvokedCallback;

    invoke-static {v0, v1, v2}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticApiModelOutline0;->m(Landroid/window/OnBackInvokedDispatcher;ILandroid/window/OnBackInvokedCallback;)V

    goto :goto_1

    .line 375
    :cond_2
    invoke-static {p0}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticApiModelOutline0;->m(Lcom/huangguo/mobile/MainActivity;)Landroid/window/OnBackInvokedDispatcher;

    move-result-object v0

    iget-object v1, p0, Lcom/huangguo/mobile/MainActivity;->backCallback:Landroid/window/OnBackInvokedCallback;

    invoke-static {v0, v1}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticApiModelOutline0;->m(Landroid/window/OnBackInvokedDispatcher;Landroid/window/OnBackInvokedCallback;)V

    const/4 v0, 0x0

    .line 376
    iput-object v0, p0, Lcom/huangguo/mobile/MainActivity;->backCallback:Landroid/window/OnBackInvokedCallback;

    .line 378
    :goto_1
    iput-boolean p1, p0, Lcom/huangguo/mobile/MainActivity;->backRegistered:Z

    :cond_3
    :goto_2
    return-void
.end method


# virtual methods
.method protected onActivityResult(IILandroid/content/Intent;)V
    .locals 1

    .line 306
    invoke-super {p0, p1, p2, p3}, Landroid/app/Activity;->onActivityResult(IILandroid/content/Intent;)V

    const/16 v0, 0x1bbd

    if-ne p1, v0, :cond_1

    const/4 p1, -0x1

    if-ne p2, p1, :cond_1

    if-eqz p3, :cond_1

    .line 307
    invoke-virtual {p3}, Landroid/content/Intent;->getData()Landroid/net/Uri;

    move-result-object p1

    if-nez p1, :cond_0

    goto :goto_0

    .line 308
    :cond_0
    invoke-virtual {p3}, Landroid/content/Intent;->getData()Landroid/net/Uri;

    move-result-object p1

    .line 309
    iget-object p2, p0, Lcom/huangguo/mobile/MainActivity;->requests:Ljava/util/concurrent/ExecutorService;

    new-instance p3, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda29;

    invoke-direct {p3, p0, p1}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda29;-><init>(Lcom/huangguo/mobile/MainActivity;Landroid/net/Uri;)V

    invoke-interface {p2, p3}, Ljava/util/concurrent/ExecutorService;->execute(Ljava/lang/Runnable;)V

    :cond_1
    :goto_0
    return-void
.end method

.method public onBackPressed()V
    .locals 1

    const/4 v0, 0x0

    .line 433
    invoke-direct {p0, v0}, Lcom/huangguo/mobile/MainActivity;->handleBack(Z)V

    return-void
.end method

.method public onConfigurationChanged(Landroid/content/res/Configuration;)V
    .locals 0

    .line 273
    invoke-super {p0, p1}, Landroid/app/Activity;->onConfigurationChanged(Landroid/content/res/Configuration;)V

    .line 274
    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->applyTheme()V

    return-void
.end method

.method public onCreate(Landroid/os/Bundle;)V
    .locals 5

    .line 76
    invoke-super {p0, p1}, Landroid/app/Activity;->onCreate(Landroid/os/Bundle;)V

    # 记下当前实例。UserSource 换源之后要调 restart() —— 见那里的注释。
    sput-object p0, Lcom/huangguo/mobile/MainActivity;->host:Lcom/huangguo/mobile/MainActivity;

    .line 77
    invoke-static {p0}, Lcom/huangguo/mobile/ContentMode;->get(Landroid/content/Context;)Lcom/huangguo/mobile/ContentMode;

    move-result-object p1

    invoke-virtual {p1}, Lcom/huangguo/mobile/ContentMode;->startPublic()V

    .line 78
    invoke-static {p0}, Lcom/huangguo/mobile/HotUpdateManager;->get(Landroid/content/Context;)Lcom/huangguo/mobile/HotUpdateManager;

    move-result-object p1

    iput-object p1, p0, Lcom/huangguo/mobile/MainActivity;->hotUpdates:Lcom/huangguo/mobile/HotUpdateManager;

    .line 79
    invoke-virtual {p1}, Lcom/huangguo/mobile/HotUpdateManager;->beginSession()Lcom/huangguo/mobile/HotUpdateManager$Session;

    move-result-object p1

    iput-object p1, p0, Lcom/huangguo/mobile/MainActivity;->hotSession:Lcom/huangguo/mobile/HotUpdateManager$Session;

    .line 80
    new-instance p1, Lcom/huangguo/mobile/DualContentRepository;

    invoke-static {p0}, Lcom/huangguo/mobile/ContentMode;->get(Landroid/content/Context;)Lcom/huangguo/mobile/ContentMode;

    move-result-object v0

    invoke-virtual {v0}, Lcom/huangguo/mobile/ContentMode;->active()Ljava/lang/String;

    move-result-object v0

    iget-object v1, p0, Lcom/huangguo/mobile/MainActivity;->hotSession:Lcom/huangguo/mobile/HotUpdateManager$Session;

    iget-object v1, v1, Lcom/huangguo/mobile/HotUpdateManager$Session;->site:Lcom/huangguo/mobile/SiteProfile;

    invoke-direct {p1, p0, v0, v1}, Lcom/huangguo/mobile/DualContentRepository;-><init>(Landroid/content/Context;Ljava/lang/String;Lcom/huangguo/mobile/SiteProfile;)V

    iput-object p1, p0, Lcom/huangguo/mobile/MainActivity;->repository:Lcom/huangguo/mobile/DualContentRepository;

    .line 81
    new-instance p1, Lcom/huangguo/mobile/AppStore;

    invoke-direct {p1, p0}, Lcom/huangguo/mobile/AppStore;-><init>(Landroid/content/Context;)V

    iput-object p1, p0, Lcom/huangguo/mobile/MainActivity;->store:Lcom/huangguo/mobile/AppStore;

    .line 82
    const-string p1, "hot-updates"

    const/4 v0, 0x0

    invoke-virtual {p0, p1, v0}, Lcom/huangguo/mobile/MainActivity;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;

    move-result-object p1

    .line 83
    const-string v1, "siteFingerprint"

    const-string v2, ""

    invoke-interface {p1, v1, v2}, Landroid/content/SharedPreferences;->getString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;

    move-result-object v3

    .line 84
    invoke-virtual {v3}, Ljava/lang/String;->isEmpty()Z

    move-result v4

    if-nez v4, :cond_0

    iget-object v4, p0, Lcom/huangguo/mobile/MainActivity;->hotSession:Lcom/huangguo/mobile/HotUpdateManager$Session;

    iget-object v4, v4, Lcom/huangguo/mobile/HotUpdateManager$Session;->site:Lcom/huangguo/mobile/SiteProfile;

    iget-object v4, v4, Lcom/huangguo/mobile/SiteProfile;->fingerprint:Ljava/lang/String;

    invoke-virtual {v3, v4}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v3

    if-nez v3, :cond_0

    iget-object v3, p0, Lcom/huangguo/mobile/MainActivity;->store:Lcom/huangguo/mobile/AppStore;

    invoke-virtual {v3}, Lcom/huangguo/mobile/AppStore;->clearCache()V

    .line 85
    :cond_0
    invoke-interface {p1}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences$Editor;

    move-result-object v3

    iget-object v4, p0, Lcom/huangguo/mobile/MainActivity;->hotSession:Lcom/huangguo/mobile/HotUpdateManager$Session;

    iget-object v4, v4, Lcom/huangguo/mobile/HotUpdateManager$Session;->site:Lcom/huangguo/mobile/SiteProfile;

    iget-object v4, v4, Lcom/huangguo/mobile/SiteProfile;->fingerprint:Ljava/lang/String;

    invoke-interface {v3, v1, v4}, Landroid/content/SharedPreferences$Editor;->putString(Ljava/lang/String;Ljava/lang/String;)Landroid/content/SharedPreferences$Editor;

    move-result-object v1

    invoke-interface {v1}, Landroid/content/SharedPreferences$Editor;->apply()V

    .line 86
    const-string v1, "originMigrated"

    invoke-interface {p1, v1, v0}, Landroid/content/SharedPreferences;->getBoolean(Ljava/lang/String;Z)Z

    move-result p1

    const/4 v1, 0x1

    xor-int/2addr p1, v1

    iput-boolean p1, p0, Lcom/huangguo/mobile/MainActivity;->migratingOrigin:Z

    .line 87
    new-instance p1, Lcom/huangguo/mobile/AccountService;

    iget-object v3, p0, Lcom/huangguo/mobile/MainActivity;->store:Lcom/huangguo/mobile/AppStore;

    invoke-direct {p1, p0, v3}, Lcom/huangguo/mobile/AccountService;-><init>(Landroid/content/Context;Lcom/huangguo/mobile/AppStore;)V

    iput-object p1, p0, Lcom/huangguo/mobile/MainActivity;->account:Lcom/huangguo/mobile/AccountService;

    .line 89
    :try_start_0
    invoke-static {}, Lcom/parse/ParseUser;->getCurrentUser()Lcom/parse/ParseUser;

    move-result-object p1

    .line 90
    iget-object v3, p0, Lcom/huangguo/mobile/MainActivity;->store:Lcom/huangguo/mobile/AppStore;

    if-nez p1, :cond_1

    goto :goto_0

    :cond_1
    invoke-virtual {p1}, Lcom/parse/ParseUser;->getObjectId()Ljava/lang/String;

    move-result-object v2

    :goto_0
    invoke-virtual {v3, v2}, Lcom/huangguo/mobile/AppStore;->activateUser(Ljava/lang/String;)V
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_0

    goto :goto_1

    :catch_0
    move-exception p1

    .line 91
    const-string v2, "AIIvideo"

    const-string v3, "\u65e0\u6cd5\u6062\u590d\u8d26\u53f7\u6570\u636e"

    invoke-static {v2, v3, p1}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I

    .line 92
    :goto_1
    new-instance p1, Lcom/huangguo/mobile/UpdateManager;

    invoke-direct {p1, p0}, Lcom/huangguo/mobile/UpdateManager;-><init>(Landroid/content/Context;)V

    iput-object p1, p0, Lcom/huangguo/mobile/MainActivity;->updates:Lcom/huangguo/mobile/UpdateManager;

    .line 93
    invoke-virtual {p0}, Lcom/huangguo/mobile/MainActivity;->getApplicationInfo()Landroid/content/pm/ApplicationInfo;

    move-result-object p1

    iget p1, p1, Landroid/content/pm/ApplicationInfo;->flags:I

    and-int/lit8 p1, p1, 0x2

    # if-eqz p1, :cond_2  # 改造：跳过 debuggable 判断，总是开 WebView 调试

    .line 94
    invoke-static {v1}, Landroid/webkit/WebView;->setWebContentsDebuggingEnabled(Z)V

    .line 96
    :cond_2
    new-instance p1, Landroid/widget/FrameLayout;

    invoke-direct {p1, p0}, Landroid/widget/FrameLayout;-><init>(Landroid/content/Context;)V

    iput-object p1, p0, Lcom/huangguo/mobile/MainActivity;->root:Landroid/widget/FrameLayout;

    .line 97
    new-instance p1, Landroid/webkit/WebView;

    invoke-direct {p1, p0}, Landroid/webkit/WebView;-><init>(Landroid/content/Context;)V

    iput-object p1, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    .line 98
    invoke-virtual {p1, v0}, Landroid/webkit/WebView;->setBackgroundColor(I)V

    .line 99
    iget-object p1, p0, Lcom/huangguo/mobile/MainActivity;->root:Landroid/widget/FrameLayout;

    iget-object v2, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    new-instance v3, Landroid/widget/FrameLayout$LayoutParams;

    const/4 v4, -0x1

    invoke-direct {v3, v4, v4}, Landroid/widget/FrameLayout$LayoutParams;-><init>(II)V

    invoke-virtual {p1, v2, v3}, Landroid/widget/FrameLayout;->addView(Landroid/view/View;Landroid/view/ViewGroup$LayoutParams;)V

    .line 100
    iget-object p1, p0, Lcom/huangguo/mobile/MainActivity;->root:Landroid/widget/FrameLayout;

    invoke-virtual {p0, p1}, Lcom/huangguo/mobile/MainActivity;->setContentView(Landroid/view/View;)V

    .line 101
    iget-object p1, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    invoke-static {p0, p1}, Lcom/huangguo/mobile/UiRefreshRate;->apply(Landroid/app/Activity;Landroid/view/View;)V

    .line 102
    sget p1, Landroid/os/Build$VERSION;->SDK_INT:I

    const/16 v2, 0x1e

    if-lt p1, v2, :cond_3

    invoke-virtual {p0}, Lcom/huangguo/mobile/MainActivity;->getWindow()Landroid/view/Window;

    move-result-object p1

    invoke-static {p1, v0}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticApiModelOutline0;->m(Landroid/view/Window;Z)V

    .line 103
    :cond_3
    sget p1, Landroid/os/Build$VERSION;->SDK_INT:I

    const/16 v2, 0x1c

    if-lt p1, v2, :cond_4

    .line 104
    invoke-virtual {p0}, Lcom/huangguo/mobile/MainActivity;->getWindow()Landroid/view/Window;

    move-result-object p1

    invoke-virtual {p1}, Landroid/view/Window;->getAttributes()Landroid/view/WindowManager$LayoutParams;

    move-result-object p1

    .line 105
    invoke-static {p1, v1}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticApiModelOutline0;->m(Landroid/view/WindowManager$LayoutParams;I)V

    .line 106
    invoke-virtual {p0}, Lcom/huangguo/mobile/MainActivity;->getWindow()Landroid/view/Window;

    move-result-object v2

    invoke-virtual {v2, p1}, Landroid/view/Window;->setAttributes(Landroid/view/WindowManager$LayoutParams;)V

    .line 108
    :cond_4
    iget-object p1, p0, Lcom/huangguo/mobile/MainActivity;->root:Landroid/widget/FrameLayout;

    new-instance v2, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda26;

    invoke-direct {v2, p0}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda26;-><init>(Lcom/huangguo/mobile/MainActivity;)V

    invoke-virtual {p1, v2}, Landroid/widget/FrameLayout;->setOnApplyWindowInsetsListener(Landroid/view/View$OnApplyWindowInsetsListener;)V

    .line 121
    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->applyTheme()V

    .line 122
    iget-object p1, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    invoke-virtual {p1}, Landroid/webkit/WebView;->getSettings()Landroid/webkit/WebSettings;

    move-result-object p1

    invoke-virtual {p1, v1}, Landroid/webkit/WebSettings;->setJavaScriptEnabled(Z)V

    .line 123
    iget-object p1, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    invoke-virtual {p1}, Landroid/webkit/WebView;->getSettings()Landroid/webkit/WebSettings;

    move-result-object p1

    invoke-virtual {p1, v1}, Landroid/webkit/WebSettings;->setDomStorageEnabled(Z)V

    .line 124
    iget-object p1, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    invoke-virtual {p1}, Landroid/webkit/WebView;->getSettings()Landroid/webkit/WebSettings;

    move-result-object p1

    iget-boolean v2, p0, Lcom/huangguo/mobile/MainActivity;->migratingOrigin:Z

    invoke-virtual {p1, v2}, Landroid/webkit/WebSettings;->setAllowFileAccess(Z)V

    .line 125
    iget-object p1, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    invoke-virtual {p1}, Landroid/webkit/WebView;->getSettings()Landroid/webkit/WebSettings;

    move-result-object p1

    invoke-virtual {p1, v0}, Landroid/webkit/WebSettings;->setAllowFileAccessFromFileURLs(Z)V

    .line 126
    iget-object p1, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    invoke-virtual {p1}, Landroid/webkit/WebView;->getSettings()Landroid/webkit/WebSettings;

    move-result-object p1

    invoke-virtual {p1, v0}, Landroid/webkit/WebSettings;->setAllowUniversalAccessFromFileURLs(Z)V

    .line 127
    iget-object p1, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    invoke-virtual {p1}, Landroid/webkit/WebView;->getSettings()Landroid/webkit/WebSettings;

    move-result-object p1

    const/4 v1, 0x0

    invoke-virtual {p1, v1}, Landroid/webkit/WebSettings;->setMediaPlaybackRequiresUserGesture(Z)V

    .line 128
    new-instance p1, Landroidx/webkit/WebViewAssetLoader$Builder;

    invoke-direct {p1}, Landroidx/webkit/WebViewAssetLoader$Builder;-><init>()V

    new-instance v0, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda27;

    invoke-direct {v0, p0}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda27;-><init>(Lcom/huangguo/mobile/MainActivity;)V

    .line 129
    const-string v1, "/assets/"

    invoke-virtual {p1, v1, v0}, Landroidx/webkit/WebViewAssetLoader$Builder;->addPathHandler(Ljava/lang/String;Landroidx/webkit/WebViewAssetLoader$PathHandler;)Landroidx/webkit/WebViewAssetLoader$Builder;

    move-result-object p1

    invoke-virtual {p1}, Landroidx/webkit/WebViewAssetLoader$Builder;->build()Landroidx/webkit/WebViewAssetLoader;

    move-result-object p1

    .line 130
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    new-instance v1, Lcom/huangguo/mobile/MainActivity$1;

    invoke-direct {v1, p0, p1}, Lcom/huangguo/mobile/MainActivity$1;-><init>(Lcom/huangguo/mobile/MainActivity;Landroidx/webkit/WebViewAssetLoader;)V

    invoke-virtual {v0, v1}, Landroid/webkit/WebView;->setWebViewClient(Landroid/webkit/WebViewClient;)V

    .line 174
    iget-object p1, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    new-instance v0, Lcom/huangguo/mobile/MainActivity$2;

    invoke-direct {v0, p0}, Lcom/huangguo/mobile/MainActivity$2;-><init>(Lcom/huangguo/mobile/MainActivity;)V

    invoke-virtual {p1, v0}, Landroid/webkit/WebView;->setWebChromeClient(Landroid/webkit/WebChromeClient;)V

    .line 189
    iget-object p1, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    new-instance v0, Lcom/huangguo/mobile/MainActivity$Bridge;

    const/4 v1, 0x0

    invoke-direct {v0, p0, v1}, Lcom/huangguo/mobile/MainActivity$Bridge;-><init>(Lcom/huangguo/mobile/MainActivity;Lcom/huangguo/mobile/MainActivity-IA;)V

    const-string v1, "AndroidHost"

    invoke-virtual {p1, v0, v1}, Landroid/webkit/WebView;->addJavascriptInterface(Ljava/lang/Object;Ljava/lang/String;)V

    .line 190
    iget-object p1, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    iget-boolean v0, p0, Lcom/huangguo/mobile/MainActivity;->migratingOrigin:Z

    if-eqz v0, :cond_5

    const-string v0, "file:///android_asset/app.html"

    goto :goto_2

    :cond_5
    const-string v0, "https://appassets.androidplatform.net/assets/app.html"

    :goto_2
    invoke-virtual {p1, v0}, Landroid/webkit/WebView;->loadUrl(Ljava/lang/String;)V

    .line 191
    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->checkHotUpdates()V

    return-void
.end method

.method protected onDestroy()V
    .locals 2

    .line 437
    sget v0, Landroid/os/Build$VERSION;->SDK_INT:I

    const/16 v1, 0x21

    if-lt v0, v1, :cond_0

    iget-boolean v0, p0, Lcom/huangguo/mobile/MainActivity;->backRegistered:Z

    if-eqz v0, :cond_0

    .line 438
    invoke-static {p0}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticApiModelOutline0;->m(Lcom/huangguo/mobile/MainActivity;)Landroid/window/OnBackInvokedDispatcher;

    move-result-object v0

    iget-object v1, p0, Lcom/huangguo/mobile/MainActivity;->backCallback:Landroid/window/OnBackInvokedCallback;

    invoke-static {v0, v1}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticApiModelOutline0;->m(Landroid/window/OnBackInvokedDispatcher;Landroid/window/OnBackInvokedCallback;)V

    .line 440
    :cond_0
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity;->hotHandler:Landroid/os/Handler;

    const/4 v1, 0x0

    invoke-virtual {v0, v1}, Landroid/os/Handler;->removeCallbacksAndMessages(Ljava/lang/Object;)V

    .line 441
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity;->requests:Ljava/util/concurrent/ExecutorService;

    invoke-interface {v0}, Ljava/util/concurrent/ExecutorService;->shutdownNow()Ljava/util/List;

    .line 442
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity;->localRequests:Ljava/util/concurrent/ExecutorService;

    invoke-interface {v0}, Ljava/util/concurrent/ExecutorService;->shutdownNow()Ljava/util/List;

    .line 443
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity;->coverRequests:Ljava/util/concurrent/ExecutorService;

    invoke-interface {v0}, Ljava/util/concurrent/ExecutorService;->shutdownNow()Ljava/util/List;

    .line 444
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    if-eqz v0, :cond_1

    .line 445
    const-string v1, "AndroidHost"

    invoke-virtual {v0, v1}, Landroid/webkit/WebView;->removeJavascriptInterface(Ljava/lang/String;)V

    .line 446
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    invoke-virtual {v0}, Landroid/webkit/WebView;->destroy()V

    .line 448
    :cond_1
    # 只在这个实例**仍然是当前实例**时才置空。无条件置空的话，后建的实例会被
    # 先建实例的 onDestroy 抹掉 —— recreate()、双实例、切源重合都会撞上。
    sget-object v0, Lcom/huangguo/mobile/MainActivity;->host:Lcom/huangguo/mobile/MainActivity;

    if-ne v0, p0, :cond_2

    const/4 v0, 0x0

    sput-object v0, Lcom/huangguo/mobile/MainActivity;->host:Lcom/huangguo/mobile/MainActivity;

    :cond_2
    invoke-super {p0}, Landroid/app/Activity;->onDestroy()V

    return-void
.end method

.method protected onResume()V
    .locals 3

    .line 278
    invoke-super {p0}, Landroid/app/Activity;->onResume()V

    .line 279
    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->applySystemBars()V

    .line 280
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    if-eqz v0, :cond_0

    invoke-static {p0, v0}, Lcom/huangguo/mobile/UiRefreshRate;->apply(Landroid/app/Activity;Landroid/view/View;)V

    .line 281
    :cond_0
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    const/4 v1, 0x0

    if-eqz v0, :cond_1

    const-string v2, "window.hgResume && window.hgResume()"

    invoke-virtual {v0, v2, v1}, Landroid/webkit/WebView;->evaluateJavascript(Ljava/lang/String;Landroid/webkit/ValueCallback;)V

    .line 282
    :cond_1
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity;->hotUpdates:Lcom/huangguo/mobile/HotUpdateManager;

    if-eqz v0, :cond_2

    iget-boolean v0, p0, Lcom/huangguo/mobile/MainActivity;->migratingOrigin:Z

    if-nez v0, :cond_2

    .line 283
    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->applyPendingResources()V

    .line 284
    invoke-direct {p0}, Lcom/huangguo/mobile/MainActivity;->checkHotUpdates()V

    .line 286
    :cond_2
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity;->account:Lcom/huangguo/mobile/AccountService;

    if-eqz v0, :cond_3

    invoke-static {}, Lcom/parse/ParseUser;->getCurrentUser()Lcom/parse/ParseUser;

    move-result-object v0

    if-eqz v0, :cond_3

    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity;->requests:Ljava/util/concurrent/ExecutorService;

    new-instance v2, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda22;

    invoke-direct {v2, p0}, Lcom/huangguo/mobile/MainActivity$$ExternalSyntheticLambda22;-><init>(Lcom/huangguo/mobile/MainActivity;)V

    invoke-interface {v0, v2}, Ljava/util/concurrent/ExecutorService;->execute(Ljava/lang/Runnable;)V

    .line 292
    :cond_3
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity;->pendingUpdateApk:Ljava/io/File;

    if-eqz v0, :cond_5

    .line 294
    iput-object v1, p0, Lcom/huangguo/mobile/MainActivity;->pendingUpdateApk:Ljava/io/File;

    .line 295
    invoke-virtual {p0}, Lcom/huangguo/mobile/MainActivity;->getPackageManager()Landroid/content/pm/PackageManager;

    move-result-object v2

    invoke-virtual {v2}, Landroid/content/pm/PackageManager;->canRequestPackageInstalls()Z

    move-result v2

    if-eqz v2, :cond_4

    invoke-direct {p0, v0}, Lcom/huangguo/mobile/MainActivity;->openUpdateInstaller(Ljava/io/File;)V

    return-void

    .line 296
    :cond_4
    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    const-string v0, "window.hgUpdateInstallPermissionDenied && window.hgUpdateInstallPermissionDenied()"

    invoke-virtual {p0, v0, v1}, Landroid/webkit/WebView;->evaluateJavascript(Ljava/lang/String;Landroid/webkit/ValueCallback;)V

    :cond_5
    return-void
.end method

.method public onWindowFocusChanged(Z)V
    .locals 0

    .line 301
    invoke-super {p0, p1}, Landroid/app/Activity;->onWindowFocusChanged(Z)V

    if-eqz p1, :cond_0

    .line 302
    iget-object p1, p0, Lcom/huangguo/mobile/MainActivity;->webView:Landroid/webkit/WebView;

    if-eqz p1, :cond_0

    invoke-static {p0, p1}, Lcom/huangguo/mobile/UiRefreshRate;->apply(Landroid/app/Activity;Landroid/view/View;)V

    :cond_0
    return-void
.end method


.method public store()Lcom/huangguo/mobile/AppStore;
    .locals 1

    # 给 Java 侧（UserSource）用的访问器。store 本身是 private，
    # 而播放进度上报要落到 AppStore.recordProgress 上。
    # 只开这一个口子，不去动 dispatch 那张 packed-switch。
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity;->store:Lcom/huangguo/mobile/AppStore;

    return-object v0
.end method

.method public restart()V
    .locals 0

    # 重建 Activity。recreate() 走 onDestroy → onCreate，而 SiteProfile 就在
    # onCreate 里构造 —— 于是新配置生效。比杀进程干净：WebView、连接池、
    # 任务栈都还在，只是重新走一遍创建流程。
    invoke-virtual {p0}, Lcom/huangguo/mobile/MainActivity;->recreate()V

    return-void
.end method
