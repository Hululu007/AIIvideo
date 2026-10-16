.class final Lcom/huangguo/mobile/MainActivity$Bridge;
.super Ljava/lang/Object;
.source "MainActivity.java"


# annotations
.annotation system Ldalvik/annotation/EnclosingClass;
    value = Lcom/huangguo/mobile/MainActivity;
.end annotation

.annotation system Ldalvik/annotation/InnerClass;
    accessFlags = 0x12
    name = "Bridge"
.end annotation


# instance fields
.field final synthetic this$0:Lcom/huangguo/mobile/MainActivity;


# direct methods
.method public static synthetic $r8$lambda$9LQKvl8-d0ElvIYgkXsulkelQso(Lcom/huangguo/mobile/MainActivity$Bridge;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lcom/huangguo/mobile/AccountService;)V
    .locals 0

    invoke-direct {p0, p1, p2, p3, p4}, Lcom/huangguo/mobile/MainActivity$Bridge;->lambda$request$1(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lcom/huangguo/mobile/AccountService;)V

    return-void
.end method

.method public static synthetic $r8$lambda$Qsa-xY5V-IruOIMCp-RwuzjDqbg(Lcom/huangguo/mobile/MainActivity$Bridge;Ljava/lang/String;)V
    .locals 0

    invoke-direct {p0, p1}, Lcom/huangguo/mobile/MainActivity$Bridge;->lambda$request$2(Ljava/lang/String;)V

    return-void
.end method

.method public static synthetic $r8$lambda$Rr82kQiyCCG-UqEyN8UBvQfJMGg(Lcom/huangguo/mobile/MainActivity$Bridge;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
    .locals 0

    invoke-direct {p0, p1, p2, p3}, Lcom/huangguo/mobile/MainActivity$Bridge;->lambda$request$0(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V

    return-void
.end method

.method public static synthetic $r8$lambda$TNzVaRq6Ce5NdOrgwkf4urxigyM(Lcom/huangguo/mobile/MainActivity$Bridge;Z)V
    .locals 0

    invoke-direct {p0, p1}, Lcom/huangguo/mobile/MainActivity$Bridge;->lambda$setBackEnabled$0(Z)V

    return-void
.end method

.method public static synthetic $r8$lambda$m1Uaieituczwt1rm7se0kmmlsBc(Lcom/huangguo/mobile/MainActivity$Bridge;Z)V
    .locals 0

    invoke-direct {p0, p1}, Lcom/huangguo/mobile/MainActivity$Bridge;->lambda$setDetailMode$0(Z)V

    return-void
.end method

.method private constructor <init>(Lcom/huangguo/mobile/MainActivity;)V
    .locals 0
    .annotation system Ldalvik/annotation/MethodParameters;
        accessFlags = {
            0x1010
        }
        names = {
            null
        }
    .end annotation

    .line 593
    iput-object p1, p0, Lcom/huangguo/mobile/MainActivity$Bridge;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method synthetic constructor <init>(Lcom/huangguo/mobile/MainActivity;Lcom/huangguo/mobile/MainActivity-IA;)V
    .locals 0

    invoke-direct {p0, p1}, Lcom/huangguo/mobile/MainActivity$Bridge;-><init>(Lcom/huangguo/mobile/MainActivity;)V

    return-void
.end method

.method private synthetic lambda$request$0(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
    .locals 11

    .line 610
    const-string v1, ")"

    const-string v2, ","

    .line 0
    const-string v0, "window.NativeCallbacks.resolve("

    .line 610
    :try_start_0
    new-instance v3, Lorg/json/JSONObject;

    invoke-direct {v3, p1}, Lorg/json/JSONObject;-><init>(Ljava/lang/String;)V

    .line 611
    iget-object p1, p0, Lcom/huangguo/mobile/MainActivity$Bridge;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-static {p1, p2, v3}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$mdispatch(Lcom/huangguo/mobile/MainActivity;Ljava/lang/String;Lorg/json/JSONObject;)Ljava/lang/Object;

    move-result-object p1
    :try_end_0
    .catch Ljava/lang/Exception; {:try_start_0 .. :try_end_0} :catch_2

    if-nez p1, :cond_0

    .line 613
    :try_start_1
    const-string v3, "null"
    :try_end_1
    .catch Ljava/lang/Exception; {:try_start_1 .. :try_end_1} :catch_0

    goto :goto_1

    :catch_0
    move-exception v0

    move-object p1, v0

    move-object v6, p0

    goto/16 :goto_5

    .line 614
    :cond_0
    :try_start_2
    instance-of v3, p1, Lorg/json/JSONObject;
    :try_end_2
    .catch Ljava/lang/Exception; {:try_start_2 .. :try_end_2} :catch_2

    if-eqz v3, :cond_1

    :try_start_3
    invoke-virtual {p1}, Ljava/lang/Object;->toString()Ljava/lang/String;

    move-result-object v3
    :try_end_3
    .catch Ljava/lang/Exception; {:try_start_3 .. :try_end_3} :catch_0

    goto :goto_1

    .line 615
    :cond_1
    :try_start_4
    instance-of v3, p1, Ljava/lang/Boolean;
    :try_end_4
    .catch Ljava/lang/Exception; {:try_start_4 .. :try_end_4} :catch_2

    if-nez v3, :cond_3

    :try_start_5
    instance-of v3, p1, Ljava/lang/Number;

    if-eqz v3, :cond_2

    goto :goto_0

    .line 616
    :cond_2
    invoke-static {p1}, Ljava/lang/String;->valueOf(Ljava/lang/Object;)Ljava/lang/String;

    move-result-object v3

    invoke-static {v3}, Lorg/json/JSONObject;->quote(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v3
    :try_end_5
    .catch Ljava/lang/Exception; {:try_start_5 .. :try_end_5} :catch_0

    goto :goto_1

    .line 615
    :cond_3
    :goto_0
    :try_start_6
    invoke-virtual {p1}, Ljava/lang/Object;->toString()Ljava/lang/String;

    move-result-object v3

    .line 617
    :goto_1
    new-instance v4, Ljava/lang/StringBuilder;

    invoke-direct {v4, v0}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-static {p3}, Lorg/json/JSONObject;->quote(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v0

    invoke-virtual {v4, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v4, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v4, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v4, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v4}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v7

    .line 618
    const-string v0, "login"

    invoke-virtual {v0, p2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v0
    :try_end_6
    .catch Ljava/lang/Exception; {:try_start_6 .. :try_end_6} :catch_2

    const-string v3, "userId"

    const-string v4, ""

    if-nez v0, :cond_4

    :try_start_7
    const-string v0, "register"

    invoke-virtual {v0, p2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v0
    :try_end_7
    .catch Ljava/lang/Exception; {:try_start_7 .. :try_end_7} :catch_0

    if-eqz v0, :cond_5

    :cond_4
    :try_start_8
    instance-of v0, p1, Lorg/json/JSONObject;
    :try_end_8
    .catch Ljava/lang/Exception; {:try_start_8 .. :try_end_8} :catch_2

    if-eqz v0, :cond_5

    .line 619
    :try_start_9
    move-object v0, p1

    check-cast v0, Lorg/json/JSONObject;

    invoke-virtual {v0, v3}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v0
    :try_end_9
    .catch Ljava/lang/Exception; {:try_start_9 .. :try_end_9} :catch_0

    move-object v8, v0

    goto :goto_2

    :cond_5
    move-object v8, v4

    .line 620
    :goto_2
    :try_start_a
    const-string v0, "account"

    invoke-virtual {v0, p2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v0
    :try_end_a
    .catch Ljava/lang/Exception; {:try_start_a .. :try_end_a} :catch_2

    if-eqz v0, :cond_6

    :try_start_b
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity$Bridge;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-static {v0}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$fgetaccount(Lcom/huangguo/mobile/MainActivity;)Lcom/huangguo/mobile/AccountService;

    move-result-object v0

    goto :goto_3

    :cond_6
    const/4 v0, 0x0

    :goto_3
    move-object v10, v0

    if-eqz v10, :cond_7

    .line 621
    instance-of v0, p1, Lorg/json/JSONObject;

    if-eqz v0, :cond_7

    check-cast p1, Lorg/json/JSONObject;

    invoke-virtual {p1, v3}, Lorg/json/JSONObject;->optString(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v4
    :try_end_b
    .catch Ljava/lang/Exception; {:try_start_b .. :try_end_b} :catch_0

    :cond_7
    move-object v9, v4

    .line 622
    :try_start_c
    iget-object p1, p0, Lcom/huangguo/mobile/MainActivity$Bridge;->this$0:Lcom/huangguo/mobile/MainActivity;

    new-instance v5, Lcom/huangguo/mobile/MainActivity$Bridge$$ExternalSyntheticLambda0;
    :try_end_c
    .catch Ljava/lang/Exception; {:try_start_c .. :try_end_c} :catch_2

    move-object v6, p0

    :try_start_d
    invoke-direct/range {v5 .. v10}, Lcom/huangguo/mobile/MainActivity$Bridge$$ExternalSyntheticLambda0;-><init>(Lcom/huangguo/mobile/MainActivity$Bridge;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lcom/huangguo/mobile/AccountService;)V

    invoke-virtual {p1, v5}, Lcom/huangguo/mobile/MainActivity;->runOnUiThread(Ljava/lang/Runnable;)V
    :try_end_d
    .catch Ljava/lang/Exception; {:try_start_d .. :try_end_d} :catch_1

    return-void

    :catch_1
    move-exception v0

    goto :goto_4

    :catch_2
    move-exception v0

    move-object v6, p0

    :goto_4
    move-object p1, v0

    .line 629
    :goto_5
    new-instance p0, Ljava/lang/StringBuilder;

    invoke-direct {p0}, Ljava/lang/StringBuilder;-><init>()V

    invoke-virtual {p0, p2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    const-string p2, " failed"

    invoke-virtual {p0, p2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {p0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p0

    const-string p2, "AIIvideo"

    invoke-static {p2, p0, p1}, Landroid/util/Log;->e(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I

    .line 630
    new-instance p0, Ljava/lang/StringBuilder;

    const-string p2, "window.NativeCallbacks.reject("

    invoke-direct {p0, p2}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-static {p3}, Lorg/json/JSONObject;->quote(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p2

    invoke-virtual {p0, p2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {p0, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {p1}, Ljava/lang/Exception;->getMessage()Ljava/lang/String;

    move-result-object p2

    if-nez p2, :cond_8

    const-string p1, "\u8bf7\u6c42\u5931\u8d25"

    goto :goto_6

    :cond_8
    invoke-virtual {p1}, Ljava/lang/Exception;->getMessage()Ljava/lang/String;

    move-result-object p1

    :goto_6
    invoke-static {p1}, Lorg/json/JSONObject;->quote(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p1

    invoke-virtual {p0, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {p0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {p0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p0

    .line 631
    iget-object p1, v6, Lcom/huangguo/mobile/MainActivity$Bridge;->this$0:Lcom/huangguo/mobile/MainActivity;

    new-instance p2, Lcom/huangguo/mobile/MainActivity$Bridge$$ExternalSyntheticLambda1;

    invoke-direct {p2, v6, p0}, Lcom/huangguo/mobile/MainActivity$Bridge$$ExternalSyntheticLambda1;-><init>(Lcom/huangguo/mobile/MainActivity$Bridge;Ljava/lang/String;)V

    invoke-virtual {p1, p2}, Lcom/huangguo/mobile/MainActivity;->runOnUiThread(Ljava/lang/Runnable;)V

    return-void
.end method

.method private synthetic lambda$request$1(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lcom/huangguo/mobile/AccountService;)V
    .locals 2

    .line 623
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity$Bridge;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-virtual {v0}, Lcom/huangguo/mobile/MainActivity;->isDestroyed()Z

    move-result v0

    if-nez v0, :cond_2

    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity$Bridge;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-static {v0}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$fgetwebView(Lcom/huangguo/mobile/MainActivity;)Landroid/webkit/WebView;

    move-result-object v0

    if-nez v0, :cond_0

    goto :goto_0

    .line 624
    :cond_0
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity$Bridge;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-static {v0}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$fgetwebView(Lcom/huangguo/mobile/MainActivity;)Landroid/webkit/WebView;

    move-result-object v0

    const/4 v1, 0x0

    invoke-virtual {v0, p1, v1}, Landroid/webkit/WebView;->evaluateJavascript(Ljava/lang/String;Landroid/webkit/ValueCallback;)V

    .line 625
    invoke-virtual {p2}, Ljava/lang/String;->isEmpty()Z

    move-result p1

    if-nez p1, :cond_1

    iget-object p1, p0, Lcom/huangguo/mobile/MainActivity$Bridge;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-static {p1, p2}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$mrefreshAccountAfterAuth(Lcom/huangguo/mobile/MainActivity;Ljava/lang/String;)V

    .line 626
    :cond_1
    invoke-virtual {p3}, Ljava/lang/String;->isEmpty()Z

    move-result p1

    if-nez p1, :cond_2

    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity$Bridge;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-static {p0, p4, p3}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$mrefreshAccountSilently(Lcom/huangguo/mobile/MainActivity;Lcom/huangguo/mobile/AccountService;Ljava/lang/String;)V

    :cond_2
    :goto_0
    return-void
.end method

.method private synthetic lambda$request$2(Ljava/lang/String;)V
    .locals 1

    .line 631
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity$Bridge;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-virtual {v0}, Lcom/huangguo/mobile/MainActivity;->isDestroyed()Z

    move-result v0

    if-nez v0, :cond_0

    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity$Bridge;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-static {v0}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$fgetwebView(Lcom/huangguo/mobile/MainActivity;)Landroid/webkit/WebView;

    move-result-object v0

    if-eqz v0, :cond_0

    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity$Bridge;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-static {p0}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$fgetwebView(Lcom/huangguo/mobile/MainActivity;)Landroid/webkit/WebView;

    move-result-object p0

    const/4 v0, 0x0

    invoke-virtual {p0, p1, v0}, Landroid/webkit/WebView;->evaluateJavascript(Ljava/lang/String;Landroid/webkit/ValueCallback;)V

    :cond_0
    return-void
.end method

.method private synthetic lambda$setBackEnabled$0(Z)V
    .locals 0

    .line 602
    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity$Bridge;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-static {p0, p1}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$mupdateBackRegistration(Lcom/huangguo/mobile/MainActivity;Z)V

    return-void
.end method

.method private synthetic lambda$setDetailMode$0(Z)V
    .locals 1

    .line 596
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity$Bridge;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-static {v0}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$fgetdetailSystemBars(Lcom/huangguo/mobile/MainActivity;)Z

    move-result v0

    if-ne v0, p1, :cond_0

    return-void

    .line 597
    :cond_0
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity$Bridge;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-static {v0, p1}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$fputdetailSystemBars(Lcom/huangguo/mobile/MainActivity;Z)V

    .line 598
    iget-object p0, p0, Lcom/huangguo/mobile/MainActivity$Bridge;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-static {p0}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$mapplySystemBars(Lcom/huangguo/mobile/MainActivity;)V

    return-void
.end method


# virtual methods
.method public request(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
    .locals 2
    .annotation runtime Landroid/webkit/JavascriptInterface;
    .end annotation

    .line 606
    const-string v0, "cached"

    invoke-virtual {v0, p2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v0

    if-eqz v0, :cond_0

    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity$Bridge;->this$0:Lcom/huangguo/mobile/MainActivity;

    invoke-static {v0}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$fgetlocalRequests(Lcom/huangguo/mobile/MainActivity;)Ljava/util/concurrent/ExecutorService;

    move-result-object v0

    goto :goto_0

    .line 607
    :cond_0
    const-string v0, "cover"

    invoke-virtual {v0, p2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v0

    iget-object v1, p0, Lcom/huangguo/mobile/MainActivity$Bridge;->this$0:Lcom/huangguo/mobile/MainActivity;

    if-eqz v0, :cond_1

    invoke-static {v1}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$fgetcoverRequests(Lcom/huangguo/mobile/MainActivity;)Ljava/util/concurrent/ExecutorService;

    move-result-object v0

    goto :goto_0

    :cond_1
    invoke-static {v1}, Lcom/huangguo/mobile/MainActivity;->-$$Nest$fgetrequests(Lcom/huangguo/mobile/MainActivity;)Ljava/util/concurrent/ExecutorService;

    move-result-object v0

    .line 608
    :goto_0
    new-instance v1, Lcom/huangguo/mobile/MainActivity$Bridge$$ExternalSyntheticLambda4;

    invoke-direct {v1, p0, p3, p2, p1}, Lcom/huangguo/mobile/MainActivity$Bridge$$ExternalSyntheticLambda4;-><init>(Lcom/huangguo/mobile/MainActivity$Bridge;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V

    invoke-interface {v0, v1}, Ljava/util/concurrent/ExecutorService;->execute(Ljava/lang/Runnable;)V

    return-void
.end method

.method public setBackEnabled(Z)V
    .locals 2
    .annotation runtime Landroid/webkit/JavascriptInterface;
    .end annotation

    .line 602
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity$Bridge;->this$0:Lcom/huangguo/mobile/MainActivity;

    new-instance v1, Lcom/huangguo/mobile/MainActivity$Bridge$$ExternalSyntheticLambda2;

    invoke-direct {v1, p0, p1}, Lcom/huangguo/mobile/MainActivity$Bridge$$ExternalSyntheticLambda2;-><init>(Lcom/huangguo/mobile/MainActivity$Bridge;Z)V

    invoke-virtual {v0, v1}, Lcom/huangguo/mobile/MainActivity;->runOnUiThread(Ljava/lang/Runnable;)V

    return-void
.end method

.method public setDetailMode(Z)V
    .locals 2
    .annotation runtime Landroid/webkit/JavascriptInterface;
    .end annotation

    .line 595
    iget-object v0, p0, Lcom/huangguo/mobile/MainActivity$Bridge;->this$0:Lcom/huangguo/mobile/MainActivity;

    new-instance v1, Lcom/huangguo/mobile/MainActivity$Bridge$$ExternalSyntheticLambda3;

    invoke-direct {v1, p0, p1}, Lcom/huangguo/mobile/MainActivity$Bridge$$ExternalSyntheticLambda3;-><init>(Lcom/huangguo/mobile/MainActivity$Bridge;Z)V

    invoke-virtual {v0, v1}, Lcom/huangguo/mobile/MainActivity;->runOnUiThread(Ljava/lang/Runnable;)V

    return-void
.end method
