# Gradle 正面工程（路线 C 第一步）

`work/android/` 是 AIIvideo 的**源码工程**，取代 `work/shells/` 那套 apktool 重打包链路。

原来的形态是「别人的 APK + 补丁集」：`cz_repack/` 里 13933 个 smali 文件，只有 12 个是我们的补丁。现在这些补丁和手写源码都进了正式的 Gradle 工程，`apktool` 和 `patches/` 不再是构建的一部分。

## 构建

```bash
cd work/android
./gradlew :app:assembleRelease      # 出 app-arm64-v8a-release.apk（约 56 MB）
./gradlew :app:assembleDebug        # debug 包
./gradlew :app:compileDebugJavaWithJavac   # 只验编译，秒级
./gradlew :app:testDebugUnitTest           # 单元测试，42 个用例
```

或者用脚本（推荐，日志更清楚）：

```bash
./scripts/build.sh check      # 编译 + 资源 + assets
./scripts/build.sh test       # 42 个单元测试
./scripts/build.sh build      # 出 release APK
./scripts/build.sh install    # 构建 → 装真机 → 冷启动 → 截图
./scripts/build.sh verify     # 清空重建 + 测试 + 断言 APK 内容
```

首次需要 `local.properties` 里的 `sdk.dir`，以及 `keystore.properties`（不进 git）。
没有 keystore 也能出 debug 包，release 会退回 debug 签名。

真机：

```bash
adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk
adb shell am start -n com.huangguo.mobile/.MainActivity
```

## 目录

```
work/android/
├── app/src/main/
│   ├── java/com/huangguo/mobile/     26 个类
│   ├── assets/                        前端（7000+ 行 JS，全部自有）
│   ├── res/                           只有自己的资源（图标、主题、CSP 配置）
│   └── AndroidManifest.xml            40 行
├── scripts/gen-web-config.py          site-default.json → site-web.js
├── keystore/                          本地签名（git 忽略）
└── FIX_RULES.md                       反编译产物的修复规则（历史参考）
```

## 和旧链路的对照

| | apktool 链路 | Gradle 工程 |
|---|---|---|
| 构建时间 | 几分钟 | **10 秒** |
| dex 体积 | 15.1 MB | **8.8 MB** |
| 补丁管理 | `patches/` + `.deleted` 清单 | 直接改源码 |
| 第三方库 | 一百多 MB smali 源 | 6 个 maven 坐标 |
| 调试 | 只能装 APK 看日志 | 断点、单步 |

## 被删掉的东西（以及为什么）

`androidx/media3`（31 MB）、`com.google.common`（20 MB）、`org.bouncycastle`（47 MB）
原本只服务于 ExoPlayer 播放链 —— `PlayerActivity` / `HongguoPlayback` / `VideoPlaybackCache`
那几个类早在补丁集里就删了。**我们的代码对它们的引用数是 0**，只剩几个字符串常量。

常量值从 smali 里提取出来放进本地类了：

| 类 | 原来在 | 内容 |
|---|---|---|
| `Text` | `media3...TtmlNode` / `IcyHeaders` | `"id"`、`"all"`、`"body"` 等 |
| `Http` | guava `HttpHeaders` | `"Accept-Encoding"`、`"Range"` 等 |
| `Ctl` | guava `Ascii` | 控制字符字节值 |
| `Mime` | `media3...MimeTypes` | `"image/png"` 等 |
| `Json` | bouncycastle | `"title"`、`"label"` |
| `Constants` | `media3...C` / `Renderer` | `3000`、`10000` |

`org.jsoup` 同理 —— 清理后的 `SiteProfile` / `SiteRepository` 引用数是 0。

## 一个真实的 bug（迁移中引入，已修）

`HotUpdateManager.resource()` 是热更通道的资源拦截器，负责给 WebView 返回
`app.css` / `app.js` / 图片的 `WebResourceResponse`。

jadx 把字节码里的 **v7（MIME 类型）拆成了两个 Java 变量**：

- `str3` —— 初始 `application/javascript`，switch 各分支改写它
- `str2` —— 只有 `html` 分支赋 `"text/html"`，其余分支从不赋值

但最终的 `WebResourceResponse` 构造用的是 v10（= v7），也就是 `str3` 那条线。
jadx 版的 return 写成了 `new WebResourceResponse(str2, ...)` —— **所有 css/js/png 都返回了
错误的 MIME**。

配合响应头里的 `X-Content-Type-Options: nosniff`，浏览器直接拒绝加载样式表。
表现就是：页面 HTML 出来了，但完全没有 CSS，图标变成原始尺寸、布局塌陷。

冷启动不会崩、logcat 里没有异常，只有肉眼看截图才发现。按 smali 的
`:goto_4` → `move-object v10, v7` 对照修好，MIME 统一走 `str3`。

**这类错误的特征**：jadx 还原 `switch` over string 时会打
`/* JADX WARN: Failed to restore switch over string */` 警告，看到这句就要
逐分支对照 smali，不能信任生成的 Java 控制流。

## 桥指令注册表

`Bridge.dispatch()` 原来是**两段嵌套 switch**：`switch (str.hashCode())` 里 40 个
`str.equals("xxx") { b = N; }` 映射到一个 int，再 `switch (b)` 里 40 个 case 装业务逻辑。
加一条指令要改两处、编号还要手工对齐。

现在是一个 `Map<String, Command>`：

```java
private interface Command {
    Object run(JSONObject args) throws Exception;
}
private final Map<String, Command> commands = new HashMap<>();
```

`dispatch()` 从 429 行缩到 33 行。加指令只需要在 `registerCommands()` 里加一行
`commands.put("名字", args -> {...})`。

**40 条指令**：app-version / hot-ready / open-player / episode / cached / detail /
check-update / account / apply-hot-update / logout / newest / recommend /
clear-bookmarks / search / download-update / tag-list / change-password / register /
home / quit / sync / tags / category / cover / login / theme / window-insets /
library / confirm-age / clear-content-cache / cached-update / check-hot-update /
clear-history / age-status / bookmark-state / hot-update-status / get-progress /
choose-avatar / screen-corners / bookmark

四个 `content-*` 指令（content-status / enter-private / confirm-private-age /
leave-private）**故意留在 `dispatch()` 方法里**，不进注册表——它们必须在
`contentMode.require()` 校验之前执行，否则切换内容源时会先被门禁拦住。

`services()` 每次取 `repository` / `store` / `account` 的当前快照。原代码是在 switch
之前用 `synchronized(this)` 一次性抓三个引用，因为 `rebindContent()` 会整体替换它们。
抽成注册表后这个快照语义必须跟着每条指令走，否则实现会在换源后用到旧实例。

### 验证方法（可复用）

真机上跑通 40 条指令，不靠肉眼看界面：

```bash
# 1. 开 WebView 调试（debug 包或 debuggable）
adb forward tcp:9222 localabstract:webview_devtools_remote_$(adb shell pidof com.huangguo.mobile | tr -d '\r')
curl -s http://127.0.0.1:9222/json   # 取 webSocketDebuggerUrl

# 2. 用 CDP 逐条打桥指令（注意参数顺序是 request(回调id, 指令名, json)）
#    websocket 客户端要 suppress_origin=True，否则 WebView 拒绝握手
```

判定标准：logcat 里 `未知请求` 出现 **0 次** = 40 条全部被注册表接住。
出现 `xxx failed` 是业务失败（没配源、没登录、参数不全），不是路由错误。

## 测试

42 个 JUnit 用例，全部在 JVM 上跑，不需要设备，秒级。

| 测试类 | 用例 | 测什么 |
|---|---|---|
| `UserSourceTest` | 15 | 用户填的源配置能否通过校验，以及拒绝理由是否可读 |
| `Hanime1AdapterTest` | 17 | HTML 解析（首页/分类/搜索/详情/标签面板/播放直链） |
| `ProxyParseTest` | 10 | 代理串解析：哪些能认、哪些返回 null |

`./scripts/build.sh verify` 会连测试一起跑，42 个不过就不出包。

### 两个必须知道的坑

**一、单测的 `org.json` 必须是真实现。** classpath 里 `android.jar` 排在前面，
它的 `org.json` 是空壳 —— `put()` 不存值、`size()` 恒 0。表现是所有断言都"通过"
（因为读出来永远是 null/0），看着全绿其实什么都没测。所以 `build.gradle.kts` 里
显式加了 `testImplementation("org.json:json:20231013")`。

**二、`Hanime1AdapterTest` 的 fixture 不在仓库里。** 那些 HTML 是成人内容站的
真实页面，`.gitignore` 挡掉了。缺 fixture 时 16 个用例会被 `Assume` 跳过并标成
skipped，不会红 —— 但也就等于没测。要跑完整版：

```bash
cd ../shells/java-test/fixtures && python3 refresh.py
cp *.html ../../../android/app/src/test/resources/
```

## 后端地址

全部集中在 `Endpoints.java`，业务类里不再出现字符串。换域名只改这一个文件。

```java
Endpoints.API_BASE        // https://149.104.21.177.sslip.io/api/
Endpoints.HOT_BASE        // .../hot
Endpoints.HOT_INDEX       // .../hot/hot-update.json
Endpoints.UPDATES_BASE    // .../updates
Endpoints.RELEASE_BASE    // .../updates/
Endpoints.UPDATE_MANIFEST // 三个镜像的 JSON 数组，按序试
```

原来散在 `HuangGuoApp`、`AccountService`（注释）、`HotUpdateManager`、
`UpdateManager`（两处）—— 换一次要改五处，漏一处就是线上半瘫：
可能热更拉得到、但账号登不上。

## 还没做的

- `UpdateManager` 还是 jadx 反编译产物（1219 行 → 现在 224 行）。它是 7 个类里
  唯一没清理的，反编译形态能跑，需要时再处理。
- `MainActivity` 里还有 51 条 `JADX INFO` 注释，以及 `lambda$dispatch$N` 这类
  机器方法名。功能没问题，只是读起来还有反编译味。
- `MainActivity$$ExternalSyntheticApiModelOutline0` 是 R8 的合成类残留。
- 包名 `com.huangguo.mobile` 和类名前缀 `HuangGuo` 还留着。改它等于换一个应用
  （覆盖安装失败、老用户要卸载），除非上架否则不值得。
- `res/` 还留着 `orange_*` 这套名字，内容早就是白底黑 A 了。改要同步
  `values/public.xml` 的 id 和 `R$drawable`，风险中等收益为零，别跟别的改动混。
- `DualContentRepository.sequelCandidates()` 恒返回空数组——原 `HongguoRepository`
  的续集匹配逻辑在整类重写时丢了。要不要补回来取决于「续集」在现在的产品里还有没有意义。
