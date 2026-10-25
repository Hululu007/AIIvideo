# 待办

> **2026-10-18 分发链路变了，本文里凡是提到分发方式的段落都可能过时。**
> 热更清单、热更包、APK 清单、免费节点池这四样全部搬出自己的服务器，改走
> GitHub + gh 镜像；自己那台现在只剩账号 API。当前状态以
> [`docs/开发说明.md`](docs/开发说明.md) 的「发版清单」和
> [`docs/热更新说明.md`](docs/热更新说明.md) 为准。
>
> 本文是按时间累积的开发记录，**早期段落保留当时的写法，不回改** —— 那是
> 历史。看的时候注意每段的日期。哪份文档是现行口径、哪份只剩历史价值，
> 看 [`docs/README.md`](docs/README.md)。

## 2026-10-24 傍晚：2.0.21 —— 把四条报错从「看得出坏了」变成「看得出为什么坏」

对着 2.0.20 的数据查了一遍用户报错（72 台设备、3,335 条事件、真失败 2,505 条 / 49 台，
零真实崩溃），顺着查出四件该改的事，这一版全做了。

### 一、封面报错以前把三种原因混成一句

`封面解密结果无效` 1,340 条、6 台设备，看着像密钥坏了。实际去图床抽了 20 张：全部解得出
JPEG / GIF / PNG；但抽到过一张 URL 回的是 160 字节 HTML —— 图床 404 或防盗链时回错误页，
App 拿它去 AES 解密，解出来当然不是图片。

现在报错带上原始字节数、解出来的前四字节、主机：

```
封面不是图片：mode=aes-cbc 原始160字节 解出3c6874（HTML，图床回了错误页） @pic.sufgew.cn
```

同时加了**五分钟负缓存**：同一条地址第一次照常抛、照常报；五分钟内重试直接抛缓存里
那条，异常实现 `Report.Once`，`dispatch` 见到就不再上报。那 1,340 条里绝大多数是
`cover.js` 每 30 秒重试同一批坏图刷出来的（一台设备 10 分钟 659 条 ≈ 40 张坏图 × 16 次）。

### 二、取消和真失败混在一起，失败率被顶到 75%

用户快速划过时上一个请求被取代（503 条）、spider 宿主退出时掐掉在途调用（39 条）、
排队超时（14 条）、`InterruptedException`（49 条）—— 这些不是故障，但带着 `code` 上报，
服务端按「code 非空」判失败，于是一半以上的"失败"是假的。

现在 `Report.bridgeFailure` 先分类，取消类发成 `bridge/command_cancelled` 且**不带 code**
（服务端就不计进失败），真失败还是 `command_error`。后台那层按文案正则的临时过滤可以
慢慢退休了。

### 三、每条事件补上源身份

2.0.20 的库里 `source_id` 全是空的：只有前端播放事件带 source，桥指令（封面、spider、
详情）一律不带，所以「哪个源在出事」只能靠报错文案倒推。

现在 `SiteProfile` 组装时把 `SourceConfig.label()` 挂进 `Report`（spider 取地址最后一段：
`51cg` / `mrds66`；REST 取主机名），`build()` 时补进每条事件。挂载点选在 SiteProfile
构造里，是因为注释里早就写着「这是全 App 唯一一处『当前源长什么样』的组装点」。

### 四、spider 绑定失败以前看不出是哪种

118 条 / 6 台，集中在几分钟内，没有对应的崩溃事件 —— 说明不是 Java 异常退出。
原来的消息只有一句「spider 服务绑定失败。」，分不清三种情况：bindService 被拒、
服务连上又断、回调到了但 channel 为空。现在分开写，并且 `SpiderService` 补了
`spider/host_start`、`spider/host_stop` 两个端点事件（带 pid，进程内只写队列，
由主进程统一发）—— 下次再出，时间线上能直接看出是「没起来」还是「起来又死」。

### 验证方式

先在服务端做**契约验证**：拿服务端密钥签一批新形状的事件直接 POST 进去，再看 `/stats/*`
和后台页面 —— `command_cancelled` 计入事件流但失败数为 0、`source_id` 落成 51cg / mrds66、
`host_start` / `host_stop` 都进了「事件类型」表。验完把那 6 条 selftest 行删了。

然后装到真机（23113RKC6C / Android 16）上跑，四条里有三条拿到了实机证据：

```
18:12:26  app/app_open              2.0.21
18:12:26  spider/host_start   pid=22421      ← :spider 进程的起点事件，带 pid
18:16:52  bridge/command_error  src=yhdm     ← 每条事件带源身份了
18:17:26  bridge/command_cancelled src=yhdm code=-   ← 取消不再是失败，且不带 code
```

`command_cancelled` 是这么造出来的：并发发三个 spider home 调用，第三个进来之后，
第二个拿到锁时 generation 已经不是自己那一代，`SpiderClient` 抛「被更新的请求取代」——
正是线上那 503 条的成因。

封面那条（`封面不是图片：mode=… 原始N字节 解出xxxx…`）没有现成的坏图可造，改成单测盯文案：
`coverFailureDetail` 放开成包可见，用一段 HTML 和一个 JPEG 头各断言一次（字节数、前四字节、
HTML 提示、主机名）。

`host_stop` 只验了代码路径：它走 `SpiderService.onDestroy`，而服务被 `bindService` 绑着，
`am stopservice` 停不掉（`Error stopping service`），只能等 App 正常退出时自然触发。

单测 260 个全过（新增 4 条：源回填、取消分类、Once 标记、封面文案）。
APK 构建成 2.0.21（20021，40.97 MB）并已装到真机，冷启动、首页、分类列表都正常，
`logcat -b crash` 里没有 App 的崩溃（那几条是 adb 注入器 `dsh.Injector` 自己的）。
还没发版 —— 发版要动 android-update.json 和 GitHub release。

## 2026-10-24：后台「数据」页 —— 埋点在哪儿看

用户问「我在哪里看见用户的操作」。答案在那之前是：没有地方，只有我自己 ssh 上去敲 psql。
现在有了 `https://149.104.21.177.sslip.io/admin/data`（左侧「数据」）。

**为什么不是后台直接连库**：track-db 一步都没出 track 那套 compose 网络，后台是
`network_mode: host`，够不着；而且不该把埋点库再分一份写权限给第二个服务 —— 那张表
每天 00:10 重算、90 天清理，多一个写入方就多一种「谁删的」的可能。

所以读也走 HTTP：track 加两个只读接口 `/stats/overview`、`/stats/events`，
口令是 `X-Stats-Token`（新的 `STATS_TOKEN`，和 App 签名用的 `TRACK_SECRET` 分开：
那把验请求体完整性，这把验「你是值班台」）。没配口令时整组回 503 而不是放行。
`deploy.sh` 现在会给老的 `.env` 补一条 `STATS_TOKEN`。

页面按「值班台」的语言排：规模 / 每天 / 源 / 失败排行 / 版本机型 / 事件类型 / 事件流，
窗口可切 1/7/30/90 天，事件流可按 kind 过滤。

**口径比数字重要，两个坑当场踩到了：**

1. **失败率 93% 是假的。** 库里的 `bridge/command_error` 里有一大批不是故障：用户快速
   划过时上一个请求被取代（「被更新的请求取代」503 条）、spider 宿主生命周期结束时掐掉
   在途调用（「宿主已关闭」39 条）、排队超时（14 条）、`InterruptedException` 49 条。
   现在统计里单列 `noise`，页面上写「真失败 2505 · 取消 619」，失败率按真失败算。
   治本要去 App 侧把这些加进 `bridgeFailure` 的安静名单，排在下一版。
2. **源那一列全是「没报源」。** App 侧只有前端播放事件带 source，桥指令（封面、spider、
   详情）一律不带，服务端只能落空。要在这一页回答「哪个源在出事」，得让 Java 侧
   `Report` 在每条事件上补当前源身份（`UserSource.identity()`），也是下一版。

**页面一上线就照出三件之前不知道的事：**

- `cover / 封面解密结果无效` 1340 次、6 台设备，最后一次 10-24 14:07 —— 51cg 那种整份
  AES 的图床，解不开的比例比想象的高。
- `spider / spider 服务绑定失败。` 118 次、6 台设备 —— 独立进程起不来，这些用户的源
  等于全废。
- `detail / Too many follow-up requests: 21` 9 次、2 台 —— 重定向环。

## 2026-10-23 深夜：内容源页 —— 一个 CSS 语法错误吃掉 98 条规则

用户发来两条反馈：备注那个按钮丑得不像这一页的东西，以及「点了一下莫名其妙变大」。
第一条查下去，根因比想象的严重得多。

### 断在一行 data URI 上

`app.css` 第 5169 行：

```css
background-image:url("data:image/svg+xml;
    charset=utf-8,%3Csvg …");
```

引号里的字符串跨了行 —— CSS 里这是语法错误。Chrome 的恢复策略是把出错位置之后的
规则一起丢掉。实测 `document.styleSheets` 只解析出 **881 条**，而 5169 行之后还有
**98 条**一条都没进去，包括：

- 整个「设置页 v2 · 内容源列表」块（`.scripts-card .source-row/.source-pick/.source-note/.source-drop`）
- `.press-host` / `.press-ripple`（按压涟漪）
- `.settings-danger-button`、`.settings-row .settings-mini` 的收口
- 外观那组「选中打勾」的 `:after`

所以「备注」按钮压根没吃到任何 App 样式，用的是浏览器默认 `<button>`：
`background: rgb(107,107,107)`、`border: 2px outset`、`border-radius: 0px`、`font: 16px`。
旁边那个 × 用的是断点之前的旧规则，圆角 15px —— 一对方角一对方角，谁看都觉得别扭。

改法就一个字：把 data URI 收回一行。规则数 881 → 994，`.settings-card .source-list`
回到第 932 条，外观页的蓝色勾也回来了。

```
修复前  note: bg=rgb(107,107,107) radius=0px border=1.9px outset   ← 浏览器默认
修复后  note: bg=rgba(118,118,128,.08) radius=12px border=1px        ← 设计里那个
```

### 备注改成一张「正在编辑的卡片」

`.source-note-form` 之前**一条 CSS 都没有**（不只是被断点吃掉，全文就没有）。点开
备注看到的是默认样式的 label + input + 三个系统按钮。

补上：青蓝描边的卡片占住原来那一行的位置，标签在上、输入框铺满、保存（蓝色胶囊）/
清空 / 取消在右下角，清空在没有备注时自动置灰。行高从 56 长到 114，量的就是输入框
34 + 标签 15 + 间距。

顺手把「备注」按钮本体也调成和 × 同一种安静按钮（44/46 × 56，12px 圆角，
8% 灰底），一行的读法变成「一张卡片 + 两个次要动作」。

端到端真机验证：填「测试备注」→ 保存 → `state.sources` 里第 2 项的 alias 变成它 →
点开再清空 → 全部回到 `(无)`，没留测试数据。

### 涟漪：改成内联定位

`spawnRipple` 只把圆心和直径写成内联，定位靠 `.press-ripple{position:absolute}`。
样式表一断，那个 span 就退回静态行内元素 —— 尺寸是 π×半径² 的直径，参与布局。
现在 `position:absolute;pointer-events:none` 也写进内联，样式表再怎么断，它也只是
浮在上面的一个圆。

### 另外三处（同一轮里查出来的）

1. **进页面就跳到底部**：`goSourceSettings()` 结尾 focus 了页面最底下那个输入框，
   滚动位置从 0 被拽到 783，同时弹键盘，紧接着异步刷新又整页重画一次。删掉 focus，
   二级页进来一律 `scrollTop = 0`（网络代理页同样有这毛病，325）。
2. **键盘开着按返回，整页跟着退**：`handleBack()` 不看输入法状态。加 `hideImeIfShown()`，
   两个判据都要试 —— `insets.isVisible(ime())` 在这台机器上恒为 false，而
   `imm.isAcceptingText()` 收完键盘还是 true（WebView 里那个 input 还攥着焦点），
   所以还得同时 `document.activeElement.blur()`。真机：back#1 收键盘停在原页，
   back#2 才返回。
3. **隐私说明被省略号截断**：「关掉之后不再上报…崩溃还会上报」用的是
   `.settings-body small`（nowrap + ellipsis），真机上只剩「…崩溃…」。换成
   `<small class="settings-note">`，两行铺满。

### 两条教训

- **样式表整体失效这种故障，症状是散装的。** 一个按钮丑、一页间距不对、打勾没了，
  看着像三个 bug，其实是同一行。以后遇到「这一块完全不像设计稿」，先去控制台数
  `document.styleSheets[0].cssRules.length`，比逐个比对像素快得多。
- **用预览图上的坐标点真机一定点错。** 838 宽的预览和 1080 的屏幕差 1.29 倍，我按
  0.596 的 y 去点「备注」，落到了下一行的「切换到这个源」上，把内容源从 mrds66 切成
  了 hanime1（首页 tab 都变了）。之后要么用 `android_tap_element` 按元素点，要么先
  换算成设备像素。

## 2026-10-23：可观测性 —— 崩溃、源健康、埋点（商业化地基）

**这是下一轮的主线。** 现在对线上发生的事一无所知：没有崩溃上报、没有播放失败记录、
没有页面行为。用户报「播不了」，能查的只有 adb logcat；商业化要回答的问题（谁在用、
看什么、在哪一步走）一个都答不上。

三件事共用一条管道：**App → 批量上报 → 服务端入表 → 后台一页看板**。先建管道，再往上加。
埋点的口径按商业化问题倒推，不是「有啥记啥」—— 见下面三个假设。

### 落地进度（2026-10-23 晚）

| 批次 | 状态 | 落点 |
|---|---|---|
| B 管道 | **已实现并部署** | `server/track/`（FastAPI + 自带 pg，`:8092`，Caddy `handle_path /t/*`）；App 侧 `Report.java` / `ReportQueue.java` |
| C 崩溃 / 播放失败 | **已接入** | `HuangGuoApp.installCrashReporter()`；`dispatch()` 统一出口报 `bridge/command_error`；前端 `trackPlayError()` 三个 stage |
| D 行为埋点 | 只落了 `app_open` | 其余 11 个事件按需接 |
| E 源 smoke | 未动 | |
| F 后台数据页 | 未动 | |
| A 包体 | 卡在「本机没装 NDK」 | 见下 |

**A 已解决（2026-10-23）**：根因是本机没有 NDK —— AGP 的 `stripDebugSymbols` 找不到
`llvm-strip` 就静默跳过，把带 `.debug_info` 的 so 原样打了进去，构建日志一句不吭。

做法是装 NDK + 在 `android {}` 里声明 `ndkVersion = "27.0.12077973"`。声明这一行是关键：
没有它，即使 NDK 装了 AGP 也不会把 strip 任务挂上去。

```bash
yes | sdkmanager --sdk_root=$HOME/Library/Android/sdk "ndk;27.0.12077973"
```

结果：`libc++_shared.so` 8.9 MB → **1.3 MB**，APK 42.89 MB → **39.07 MB**（-3.82 MB）。
`llvm-readelf` 复核：`.dynsym` 2502 个符号、`.dynstr`、`.text` 都在，`.debug_*` 没了，
所以只是删调试信息，动态链接不受影响；模拟器上装完启动、上报都正常。

剩下两项没动：assets 里 `geosite.dat` + `ip-country.bin` 那 3.6MB 要改成首次进代理设置时
下载（缺了没有 routing 规则，得分支处理，不是删文件那么简单）；R8 等有 instrumentation
测试之后再开。

**密钥怎么放**：`TRACK_SECRET` 不进源码。服务器在 `/srv/track/.env`，本机在
`keystore.properties` 的 `trackSecret`（已 gitignore），经 `buildConfigField` 注入
`BuildConfig.TRACK_SECRET`。留空等于关掉上报，构建不报错。

**两端对齐的验收**（已跑过）：

| 场景 | 结果 |
|---|---|
| 正确签名 + 2 条事件（gzip，签未压缩体） | 200 `{"ok":true,"accepted":2}` |
| 错签名 | 401 `签名不对：X-Sig 不是…` |
| 同一 `event_id` 发两次 | 第二次 `accepted:0`，库里 1 行 |
| 模拟器冷启动 | `app_open` 入库，device_id 是 UUID、os 33、model `sdk_gphone64_arm64` |
| 飞行模式冷启动 | 本地 `files/reports/*.jsonl` 有 1 行，库里 0 行 |
| 恢复网络 | 约 1 分钟后补发成功，库里出现，本地文件删除 |

### 开关撤了：埋点常开（2026-10-23 23:20）

上面第 1、2 条（「设置页有了开关」和「关掉时清队列」）已经被推翻 —— 那个开关的 UI 撤掉了，
埋点一直开着。

改动：`renderSettings()` 里那张「隐私」卡片整块删除，跟着删的还有 `data-track` 的点击处理、
`state.track` 的懒加载、Java 侧 `Report.setOptOut` / `isOptOut` / `ReportQueue.clearBehaviorEvents`
和桥命令 `report-config`。`Report.event()` 不再看 optOut，来了就入队。

老用户那边要单独处理：装过带开关那版的机器，`SharedPreferences("report")` 里可能留着
`opt_out=true`，而界面上已经没有地方能打开它 —— 那样这些设备会永远静默。
所以 `install()` 里读到这个键就直接删掉，日志打一行「清掉老版本的「关掉使用统计」标记」。

顺手确认的事：`diag` 的自检口子留着，返回的 `state()` 现在只有 `pending`。

### 埋点上线两小时就抓到一条真 bug：pornhub 封面全挂

删完开关顺手查了一眼库，`bridge/command_error` 在 2 秒里刷了 8 条：

```
stage=cover  code=IllegalArgumentException  msg=不支持的封面格式
```

`SiteRepository.coverData()` 拿路径最后一段当扩展名，不在 jpg/jpeg/png/webp/gif 里就抛。
问题出在 `lastIndexOf('.')` 返回 -1 的情况：pornhub 那批缩略图是裸路径，压根没有扩展名
（`/videos/202111/29/398816751/180P_225K_...`），于是每一张都判成「格式不支持」，整页灰块。

改成「点在最后一段路径里才算有扩展名」，没有扩展名就放行 —— 真正的边界是 `allowedHosts`
白名单加上拿回来之后必须解得开图片，有扩展名但不是图片类型才拒。报错文案也带上了
扩展名和主机，下次再出这种问题不用猜。

改完整页封面正常，同一时段的库里 `cover` 错误归零，只有一条 `app/app_open`。

这条值得记：埋点不是为了报表，第一天就用来定位了一个没人报的 bug。

### 三条补完（2026-10-23 深夜，真机验证）

1. ~~**设置页有了开关**~~：已撤销，见上面「开关撤了」。
2. ~~**关掉时清队列**~~：`clearBehaviorEvents()` 已删除。
3. **崩溃可触发**：桥命令 `diag`，`mode=crash` 时在主线程 post 一个必抛的 Runnable，
   逃出 Looper 走真正的 `UncaughtExceptionHandler`。

真机验证（7f8f5254 / 23113RKC6C / Android 16）：

```
22:27:07 FATAL EXCEPTION  java.lang.IllegalStateException: diag: 手动触发的崩溃（验证上报链路，不是 bug）
22:27:44 库里查到 kind=crash name=uncaught code=IllegalStateException
         msg=「diag: 手动触发的崩溃（验证上报链路，不是 bug）」
         trace=com.huangguo.mobile.HuangGuoApp.lambda$onCreate$0(HuangGuoApp.java:35)…
```

中文不再是乱码，说明 `readLines` 的编码修复在真机上同样成立。

**两个踩了很久的坑，记下来：**

- **改了 assets 必须 `--rerun-tasks`。** `./gradlew :app:assembleRelease` 在增量状态下会把
  `mergeReleaseAssets` 判成 UP-TO-DATE，打进包里的还是上一次的 app.css / app.js。我改了四遍
  CSS、装了四遍包、每次都看到旧的渲染，就是这个原因。判断方法：`unzip -p app.apk assets/app.css
  | grep 你刚写的类名`。
- **自绘开关在这个 WebView 里渲染不出来。** `<button>` + `::after` 圆点 / `span` 圆点 /
  `aria-checked` 属性选择器 / `.is-on` 类 / 内联宽高，五种写法都试过，胶囊在真机上始终是
  「灰色关态」且圆点看不见，而写死 `is-on` 类也不生效 —— 说明问题在渲染而不是 JS 状态
  （Java 日志打出 `optOut=false`）。最后换成同页其它按钮在用的 `.settings-mini` 纯文字按钮，
  一次就对了。那个胶囊的 CSS 已经删掉，别再加回来。

真机验证抓到两个只有真机才暴露的 bug：

1. **事件永远发不出去。** `enqueue()` 只写文件，没有任何东西踢它；`install()` 里那次
   `maybeFlush(true)` 跑在队列还空的时候。表现是「日志看着一切正常，库里永远空」。
   修法：入队后调一次 `maybeFlush(false)`，再把发送窗口从 60 秒压到 3 秒（这个量级
   不需要攒批），另起一个 15 秒的兜底节拍器。
2. **节拍器把发送线程占死了。** 第一版把 `while(true) + sleep` 丢进单线程 executor，
   后面的 flush 任务永远排不上。改成独立 daemon 线程。

**还有一个更隐蔽的：中文在落盘那一刻就烂了。** `readLines()` 用的是
`RandomAccessFile.readLine()` —— 它按字节逐 char 读（等于 ISO-8859-1），「中」的
E4B8AD 读回来变成 `ä¸­` 三个字符，再按 UTF-8 写出去就是 C3A4 C2B8 C2AD。ASCII 部分完全
正常，所以现象是「只有中文乱码」。自己加的探针打了一遍字符级 hex 才定位到：String 对
（char0=4e2d、utf8=e4b8ad）、JSON 对、字节对 —— 坏在**读回队列文件**那一步。改成
`BufferedReader(new InputStreamReader(..., UTF_8))` 之后往返正确。

这个 bug 顺带说明一件事：App 里所有 Java 侧的中文异常消息以前都是这种形态，只是没有
任何地方把它们发出来看过。

还有两处问题。`device_id` 一开始全落成 `unknown`：App 把 `device` 写在每条事件里，
服务端只读 `X-Device` 头和请求体顶层。两边各补一半 —— App 在 payload 顶层也带一份，
服务端优先级改成「事件的 device → X-Device → 顶层」，谁先谁后都不再落空。

`:spider` 是独立进程，Application 会在两个进程各跑一次 `onCreate`，`app_open` 上报了两条，
两个队列还会抢同一批文件。现在按 `/proc/self/cmdline` 判主进程：主进程完整跑（队列 +
节拍器 + 发送），`:spider` 只写不发（`ReportQueue.canFlush`），它的崩溃由主进程顺路带走。

### B 上报管道（先做，其余都挂在它上面）

**服务端** `server/track/`：FastAPI + Docker，绑 `127.0.0.1:8092`，Caddy 挂 `handle_path /t/*`。

- `POST /t/events`：批量，gzip 请求体，单次 ≤ 200 条、单条 ≤ 4KB
- 鉴权：`X-Sig: hmac_sha256(app_secret, body)` + 单 IP 60 次/分钟。公网无账号，少这两道
  就一定被刷满
- 表 `events(id, device_id, session_id, at, kind, name, props jsonb, app_version,
  patch, os, model)`；`kind` 里常用字段（source_id / stage / error_code / ms）落库前
  抽成列，不然查不动
- 明细留 90 天，每天凌晨聚合进 `events_daily` 永久保留
- 量级：189 个用户 × 每天 200 条 ≈ 4 万/天，Postgres 毫无压力，不需要采样

**App 端** `Report.java` + `ReportQueue.java`

- 事件先进内存队列，写 `files/reports/<date>.jsonl`；满 30 条 / 60 秒 / 切后台时发一次，
  发成功删文件。杀进程不丢
- **独立通道**：不走 `AiiHttp`、不走代理、不复用 `Outbound.applyHeaders`。源和代理挂了，
  上报必须还活着 —— 这条是整条链的立足点，别图省事合并
- 失败退避 3 次（1 / 5 / 30 分钟），仍失败留到下个会话
- `device_id` 安装时生成 UUID 存 SharedPreferences；不采账号、不采手机号
- 设置页给一个「不发送使用统计」，关掉之后只上报崩溃

### C 崩溃与播放失败（复用管道，一天内能落地）

- `Thread.setDefaultUncaughtExceptionHandler` → 写 `crash` 事件（堆栈前 40 行、机型、
  版本、当前源 id、当前阶段），再交回系统默认处理。该崩还是崩，但留下证据
- WebView 侧 `window.onerror` / `unhandledrejection` 经桥转成 `js_error`
- `play()` 的每个失败出口收敛成一个 `play_error`，带 `stage`：装源 / 取详情 / 取播放地址 /
  解密 / 起播 / 首帧。现在各写各的文案，用户只看到「播放失败」，你也一样

**验收**：真机上人为断网、把某个源 js 改成抛错、播一集再退出，后台能看到对应的三条记录。

### D 行为埋点

| 事件 | 关键字段 | 回答什么 |
|---|---|---|
| `app_open` | cold/warm、首屏 ms | 启动是不是变慢了 |
| `source_install` / `remove` / `switch` | source_id、来源（市场/手贴） | 装源这道坎有多高 |
| `home_view` | source_id、条目数、耗时 | 首屏出得来吗 |
| `card_click` | source_id、content_id、位置 | 用户看不看得到内容 |
| `search` | 关键词哈希、结果数、是否点开 | 站内搜有没有用 |
| `detail_view` | source_id、content_id、集数 | 内容偏好 |
| `play_start` / `play_first_frame` | source_id、content_id、ep、首帧 ms | 播放成功率、哪个源慢 |
| `play_error` | stage、error_code | 坏在哪一环 |
| `play_exit` | 时长 s、进度 %、是否播完 | 内容留不留人 |
| `episode_switch` | from / to | 追剧行为 |
| `favorite_add` / `history_view` | content_id | 账号体系值不值 |
| `proxy_enable` / `proxy_result` | 协议、成功与否 | 代理是不是最大的坎 |

切片维度只留三个：`app_version`、`source_id`、日期。多一个都要解释为什么。

### E 源健康 smoke

后台「源」页点「验证」，服务端按序打三枪：配置 JSON 200 → 首页/分类有条目 → 详情能取到
播放地址。结果写 `checks` 表（source_id / at / stage / ok / ms / 错误），灯带从「静态
可解析」升级成「最近一次验证的结果」。

之后可选：每天定点跑一遍，坏了发邮件。**这是把「用户先发现」改成「你先发现」的唯一办法。**

### A 包体（顺手，独立于上面）

- `libc++_shared.so` 换 strip 过的：8.9MB → 1.1MB（里面 7.1MB 是 `.debug_info`）
- assets 的 `geosite.dat` + `ip-country.bin`（3.6MB）改成首次进代理设置时下载
- R8 等 E 落地、有 instrumentation 测试之后再动，现在开会让 JS 桥全线白屏

### 这批数据要回答的三个商业假设

写死在这里，到时候拿数据判，别拍脑袋：

1. **多数人卡在「配代理」** —— 若 `source_install` → `play_start` 的转化低于 30%，
   且 `proxy_enable` 失败率高，付费点就是「内置中转 + 开箱即用」。判定阈值：漏斗第 3 步
   流失 > 50% 即成立。
2. **内容集中在少数源** —— 若 Top 2 源吃掉 60% 以上播放，谈白标/接口比撒网式接源值钱；
   若长尾源合计不到 10%，直接砍掉，省维护。
3. **留存靠片单** —— 若收藏用户的次留是非收藏用户的 1.5 倍以上，账号和跨端同步值得投入；
   否则账号只是登录负担，别为它做付费墙。

### 不做的

- 不接第三方统计 SDK（体积、隐私政策、国内合规都划不来）
- 不自建 BI，后台一页表格够用
- 不上报内容标题全文，只存 `content_id` + 源 + 分类。要标题时按 id 回源查，避免把站点
  内容抄进自己的库

### 工时

| 批次 | 内容 | 估 |
|---|---|---|
| B | 上报管道（服务端 + App 端） | 2 天 |
| C | 崩溃 / 播放失败接入 | 0.5 天 |
| D | 行为埋点（12 个事件） | 1 天 |
| E | 源 smoke + 灯带升级 | 1.5 天 |
| F | 后台「数据」页（漏斗 / 源榜 / 错误榜） | 1 天 |
| A | 包体三项 | 0.5 天 |

顺序：B → C → D → F。E 独立可并行。A 随时插队。

### 2.0.22：埋点管道里那个「数据在看不见的地方消失」的洞

上线两天（10-23 14:05 ~ 10-25 03:10 UTC）攒了 10065 条事件、218 台设备。按报错分桶
之后发现真正该看的不是那一堆 `command_error`，是**两个为 0 的计数**：

```
select count(*) from events where name='play_error';   -- 0
select count(*) from events where kind='crash';        -- 2（都是手搓的 diag）
```

TODO 里 C 那批写着「已接入」，实际一条都没进来过。根因不是接入漏了，是**一条坏事件
会炸掉自己的批次**，而客户端对 4xx 的处理是「丢掉」：

1. `feed-player.js:517` 的 decode 分支把 `video.error.code`（MediaError 枚举，数字）
   塞进 `props.code`；服务端 `ingest.py` 对 `code` 走 `_text()`，数字过不去 → 400。
2. `parse_batch` 遇到第 N 条不合法就 `raise`，前面 N-1 条合法的也一起不要。
3. `ReportQueue` 收到 400 走 `acknowledge(sending)`，把队列文件删掉，日志只一行 `Log.w`。

线上实测（`/t/events`，验证完已删掉探针行）：

```
对照(code=字符串)        200 {"ok":true,"accepted":1}
play decode(code=3)     400 {"ok":false,"error":"第 1 条的 code 必须是字符串"}
混合批次                 400 {"ok":false,"error":"第 2 条的 code 必须是字符串"}
```

**修法**：服务端逐条拒收（返回 `rejected`/`errors`，其余照收），`code` 列收数字；
客户端 4xx 改成二分隔离到 `files/reports/dead/`，401 单独退避。

顺带查出三个同源问题：

- **崩溃一直上报不上去。** `Report.crash` 把堆栈按**字符**截到 4000，而服务端算的是
  UTF-8 **字节**。真实 Android 堆栈（40 帧 × 70 字符 + cause 10 行）序列化后 4434 字节，
  被 413 掉；只有手搓的十帧 diag 崩溃（958 字节）刚好过得去 —— 线上 crash 表里就那两条。
  现在堆栈按字节裁（2400）、整条事件压到 `EVENT_BUDGET = 3800`，服务端留 296 字节余量。
- **单日超 200 条就丢。** `claimFiles()` 认领全部文件、只发前 200 条、然后
  `acknowledge()` 删掉**整个**文件。线上已发生 4 台设备日（708 / 539 / 449 / 322 条），
  丢了多少无从得知 —— 因为丢的时候日志写的是「发送成功」。现在按已发送条数把剩下的
  写回（`.tmp` + rename）。
- **封面负缓存漏了配置错误那一关。** `coverData()` 第一行就是 `profile.coverUrl(str)`，
  它在 `FAILED.get()` **之前**抛「无效封面域名」，于是这类失败永远进不了
  `rememberFailure`。证据：一台设备 34 条「无效封面域名」，横跨 351 分钟。缓存键也用
  原始 URL 而不是归一化后的（`coverUrl()` 抛的时候拿不到）。

**一个必须记住的判据坑**：`SocketTimeoutException extends InterruptedIOException`，
okhttp 的 callTimeout 也抛 `InterruptedIOException("timeout")`。按类名把
`InterruptedIOException` 加进「取消」名单，会把线上 26 条真超时（均值 30 秒、全在
detail 阶段）洗成取消 —— 那等于把「站点慢到读不出来」这个信号藏起来。只能按 message 判。

单测 260 → 271（`ReportTest` 新增 5 条、新建 `ReportQueueTest` 6 条），服务端从 0 个
测试变成 19 个（`server/track/tests/`，标准库 unittest，不需要库）。

**二分隔离里两个容易写错的地方**，都写进单测钉住了：

- 返回值不能是「成功条数」。坏事件在中间时，「前 N 条成功」这个假设不成立，按条数切
  前缀会把没发出去的删掉。改成按**对象身份**返回（`IdentityHashMap`）—— 用 equals 更糟，
  重试刷出来的事件内容可能完全相同。
- 从 tail 递归下来的单条**从没被单独发过**（父层只 post 了 head），直接判它有问题就是
  冤杀。进 `isolate` 之前必须自己试一次。

## 2026-10-21：中继取流与环境装配 —— 已完成（真机验证）

一天三个「看不了」，两个是同一个结构缺陷、一个是配置漂移。全文见
[出站请求的两条通道](docs/出站请求的两条通道.md)。

- **2.0.16 · 搜索历史**：点搜索框浮出历史、点词重搜、清空要确认；上限 20 条、
  去重不分大小写；面板宽度现量搜索框。存 `hgpref:search-history`，不进 `hgcache:`。
- **2.0.16 · 剔 m3u8 插播广告**：四条判据（路径特征 / 序号断裂 / 换域 / 两长夹一短），
  片头另有一条更保守的。整段连分片一起删，段内 `EXT-X-KEY` / `EXT-X-MAP` 保留。
- **2.0.17 · AGE 一集都看不了**：2.0.16 把播放列表改成「一律绕中继」后，中继仍无条件
  跟着代理走，直连表里的 `ffzy-plays.com` 被塞回境外节点，CDN 回
  `403 The region has been denied`。改成按 `NetRoute.mustProxy` 判。
- **2.0.18 · Pornhub 分片全 403**：`master` / `variant` 200、`seg-*.ts` 403 —— 中继没套
  源声明的 Referer/UA，phncdn 的 HLS 只认站点 Referer。播放器打开时把当前源交给中继。
- **2.0.19 · `Outbound`**：请求头模板与代理判定各一份实现，`AiiHttp` 和 `VlcRelay`
  都只调它；7 条用例盯边界。以后加通道必须先来这儿确认。
- **mrds66 封面全空**：站点图床 `pic.jtzatly.cn` → `pic.sugfew.cn`，服务器上那份
  `spiders/mrds66.json` 的 `cover.allowedHosts` 没跟（已改，两个域都留）。
  **这类问题靠配置更新解决，用户要重装一次源才生效** —— 白名单在配置里是要付代价的。

顺带确认的事实：AGE 站点自己在批量失效（抽样 10 部 7 部 `Code=9`，官网同集也是
「视频加载失败」）；香港那台 `149.104.21.177` 的配置目录在 `/srv/cfg/`，
`spiders/*.json` 公开读、`admin/sources/` 要鉴权。

## 工作流约定（2026-10-17 定，别再搞反）

## 2026-10-19 晚：动画根治 + 路由智能化 —— 已完成（真机验证）

一轮四件事，细节全在
[`docs/history/2026-10-19-卡片动画卡顿排查.md`](docs/history/2026-10-19-卡片动画卡顿排查.md) 和
[`docs/history/2026-10-19-代理路由智能化.md`](docs/history/2026-10-19-代理路由智能化.md)：

1. **卡片动画恢复 + 缓存修复 + 过场盖加载**：`CARD_TRANSITION_ENABLED` 开关
   重新打开，`capture` 跟着开关走（之前关着动画还照跑 cloneNode，还把缓存渲染
   挡死了）；动画中不重建 DOM，渲染排队到 `onOpened`；帧停顿看门狗兜底。
2. **spider 宿主迁 `:spider` 独立进程**：Android WebView 全 app 共享渲染主线程，
   spider 抓取解析会把主页面动画冻住 1.7~2.4s——进程隔离是根治。AIDL 通道 +
   WebView 数据目录后缀（多进程官方解法）+ 代理快照跨进程传。
3. **代理路由智能化**：Xray 核心接 `geosite:cn` 分流（dat 由 libv2ray.aar 自带，
   零额外工作）+ `RouteMemory` 失败自愈（连接层失败换路重试一次，学习 24h）。
   `DirectHosts` 手填白名单过渡保留，待退役。
4. **闪退一课**：geosite 规则让核心第一次读 geo 文件，`go.Seq.setContext` 没调
   → Go `os.Exit(1)` → 点图标闪退且无 Java 栈。新核心配置资源类型必须在
   「核心真实启动」的档位下验收。

## 2026-10-18 下午：代理分流 + 播放三修 —— 已完成（真机验证）

四个 bug 一轮修完，细节全在
[`docs/history/2026-10-18-代理分流与播放器修复.md`](docs/history/2026-10-18-代理分流与播放器修复.md)：

1. **代理全局拖垮直连源**（AGE动漫 SSLHandshake）：新 `DirectHosts` 显式域名表，
   `AiiHttp` / `VlcRelay.wrap` / `XrayConfig` 三个出口共用。以后新接直连源往
   `DirectHosts.HOSTS` 加一行即可。
2. **视频 → 选集 → 返回死循环**：`hgResume` 先把 route 归位再进 `goDetail`，
   boot 残页不再污染 `state.previous`。
3. **设置四个子页「返回设置」落首页**：`hgBack` 补 `source/proxy/player/market`
   四个返回分支（老坑，一直没暴露）。
4. **VLC 第 N 集有声无画 + 上下常驻暗带**：pager 没滚到 `curPos` 导致 vout
   永远不挂（`pager.post(scrollToPosition)` 修）；scrim 跟控件一起显隐。

**本地 git = 代码回滚用。** 想提交就提交，不纠结提交信息、不纠结某个文件该不该进
仓库。它就是个存档点，唯一用途是出问题时能退回去。

**GitHub = 只保证 APK 是最新版 + README 好看。** 公开仓库面向使用者，不是开发记录。
有值得发布的改动就把最新 APK 挂 Releases；README 照
[piko](https://github.com/NihilDigit/piko) 的排版来（结构化风格基准见 README 本身）。

### 推论：不要为了「干净地推送」去删本地代码

这条是踩过的坑，写下来免得再犯。

2026-10-17 为了让仓库里不出现某个成人内容站的痕迹，我把 `UserSource.wrapHanime1()`
（约 230 行）、域名识别、探针全删了，还加了一堆 `.gitignore` 排除规则。**这是错的** ——
本地要**持续写这类适配器**，代码删掉等于自断一臂，而且当时并没有「干净推送」的要求。

真要避免某些内容进公开仓库，办法是**推的时候挑提交**，或者用 `.gitignore` 挡
**内容文件**（页面快照、密钥、配置），不是删代码。代码是可执行的资产，内容才是要挡的。

那次删除已全部复原，编译与两套回归（13 + 32 项）都过。

---

## 原生库集成 —— 已验证通过（2026-10-17）

> ⚠️ **2026-10-18 更新：本节描述的是旧链路（apktool 重打包），已经不存在了。**
> 项目已改成 Gradle 正面工程（`work/android/`），塞 .so 只需放 `jniLibs/` 或引 AAR，
> 下面那套「doNotCompress + zipalign -p 4」的手工配方作废。
>
> 结论层面**唯一还有用的**是那句「传递依赖要自己捆」——但它也只对 `extractNativeLibs="false"`
> 成立。现在配置是 `"true"`（安装时解压），系统库之外的依赖照样得捆，`libc++_shared.so`
> 仍要跟 `libvlc.so` 一起进包（libvlc 那个 9MB 就是这么来的）。
>
> 代理核心的体积实测见 `docs/开发说明.md` 的「so 打包方式」一节。

要决定的是「App 能不能内置一个代理核心（mihomo / Xray）」。前置问题是构建层：
这个项目是 apktool 重打包、没有 Gradle、从来没塞过 .so，而 manifest 里写着
`android:extractNativeLibs="false"` —— 那要求 .so **不压缩 + 4KB 页对齐**。
这条不通，后面所有讨论都白搭。

**结论：通了。** 五项检查全过：

| 检查 | 结果 |
|---|---|
| apktool 导入 `lib/` | ✅ 构建日志出现 `I: Importing lib...` |
| .so 落位 | ✅ `lib/arm64-v8a/libX.so` |
| 不压缩 | ✅ `unzip -v` 显示 **Stored, 0%** |
| 4KB 页对齐 | ✅ offset 6918144 = 1689×4096；`zipalign -c -p 4` 通过 |
| 真加载 | ✅ 真机 `System.loadLibrary()` 成功，日志「原生库探针 OK」 |

### 唯一一个坑：传递依赖要自己捆

第一次拿 `/system/lib64/liblog.so` 当载荷，`dlopen` **找到了文件但失败**：

```
dlopen failed: library "libc++.so" not found: needed by libtestprobe.so
```

**这条错误反过来证明打包是对的** —— 它已经成功打开 APK 内的库了，失败发生在之后
的依赖解析。系统的 `libc++.so` 不暴露给 App 命名空间。

把 `libc++.so` 一起放进 `lib/arm64-v8a/` 就过了。所以接真核心时，**先查它的
`DT_NEEDED`，把每个非系统库一起捆**。mihomo / Xray 的 gomobile 产物大概率依赖
`libc++_shared.so`（约 1 MB/ABI）。

### 配方（四步，已固化）

```text
① cz_repack/lib/<abi>/*.so                   把库放进去
② apktool.yml 的 doNotCompress 加一行 - so   （已加，留在配置里了）
③ build.sh 的 zipalign 已经是 -p 4           （本来就对，不用改）
④ System.loadLibrary("名字")                 从 java-src 调，不用碰 smali
```

第 ④ 步是这次验证出来的额外收获：**加载原生库不需要改 smali**，在 java-src 里
加个静态方法就行，`build.sh` 会把它编进 classes5.dex。

### 下一步的前置（还没装）

```text
Go        1.27.2   已装
NDK       未装
gomobile  未装
```

所以接真核心有两条路：装 NDK + gomobile 自己编（要下几个 G），或者直接用
别人打好的 AAR 只取里面的 `.so` 和 `classes.jar`。

---

## 单站 HTML 适配器（2026-10-17 **真机跑通**）

设备 `7f8f5254` 上首页 / 分类 / 详情 / 播放 / 标签**五层全通**，画面已渲染。
上一轮担心的「安卓 OkHttp 过不了 CF 的 TLS 指纹闸」**不存在** —— 推断成立，有真机证据了。

**这个适配器的代码和它的目标站都不在仓库里**（目标是成人内容站，内容不留、
教训留，见 .gitignore 末尾）。所以下面记的每条都是自足的、跟具体站点无关的结论。

- 回归：32 项离线断言（fixture 现场抓，不进仓库）
- 接线：`AiiHttp` 分流（含 `stripOrigin`）+ `MediaProxy.HOSTS` + 标签系统
- 当前包：`cz-final.apk` sha256 `7a86ed62719bc3d3…`

**真机抓出四个 bug，三个已修**：

```text
① 详情 404，URL 里带着 origin     SiteRepository.detail() 拼了 origin，适配器分流落空
                                 → AiiHttp.stripOrigin() 还原
                                 ★ 所有 origin 非空的适配器源都会踩，跟哪个站无关
② 分类 403，URL 里 id 是空的       分类列表掉进了 detail() 分支
                                 → fetch() 里按参数名分流，判据顺序不能换
③ 播放 MediaError 4              WebView 自己发的请求挂；服务端全对（裸 curl 206、
                                 编码 H.264、moov 在头）。接管过来走 OkHttp 强制 h1.1
                                 → MediaProxy.HOSTS 加该 CDN 域
```

第三条**成因和 bilibili 那族不同**（那族是服务端 h2 返 403，这族是服务端全对、
只有 WebView 发才挂），`MediaProxy` 注释里分开写了，别合并。

**标签系统那两个坑是跨源的**（任何想用标签的用户自带源都会撞上）：

```text
④ adapters.js 的 adaptTags 只认 code → TAG_GROUP_BY_CODE，标签自带的
   group 字段被完全忽略 → 走适配器的源自定义分组全折进 other → 整页空白
⑤ app.js 的 renderTags 在非 private 模式下**不看配置**，写死
   [['short','短剧类型'],['comic','漫剧类型']] —— 而 privateMode 只对内置
   黄果源为 true，于是所有用户自带源都被按死在那对分组上
```

两个都是**静默失败**：接口拿到了数据（右上角还显示着「240 个标签」），
页面却是空的，不报错。见 `adapters.js` / `app.js` 里对应位置的注释。

**还没修的一条**：详情页「正在加载… / 正在获取选集…」不消失。
`/api/videos/408297` 在 561ms 就 `ok` 了，是前端没更新状态。
**不影响功能**（画面照常出），但是个真眼可见的瑕疵。

**签名 TTL 的口径要重测**：原来写「约 12 小时」，但真机上每次抓到的 ts 都是
固定值、不随请求时间滑动 —— 更像按批次签发的固定窗口。别拿 12 小时当准数。
**「播放地址必须零缓存」这条结论不受影响**，仍然成立。

其余未验：带空格的分类、播放进度上报、收藏、横屏 / 倍速 / 拖进度。

**一个调试上的坑值得记**：改完适配器**光重装 APK 不够** —— 设备上的源配置是
上次「重新拉取」时生成的，不会因为换包而更新。要在源管理页点一次
「重新拉取这个源的配置」，否则你看到的是旧配置配新代码。

---

2026-10-16 当前状态：改造版功能完整、真机验证通过。红果源已摘除，用户自带源跑通，
出厂不带任何源（装完是空的，用户填地址才有内容）。

产物现状：`work/shells/cz-final.apk`（仓库里不放，挂 GitHub Releases），8045170 字节，补丁集 22 个文件 + 76 条删除记录 684K。
前端体量：`app.js` 1958 行，`setup.js` 186 行，`cover.js` 228 行，`adapters.js` 231 行，
`feed-player.js` 665 行，`app.css` 245 行。
Java 侧：`java-src/com/huangguo/mobile/` —— `AiiHttp.java` 322 行（HTTP + DoH），
`UserSource.java` 350 行（读写/校验/下载/桥指令），编译成 `classes5.dex`。

**已完成（2026-10-16 第二轮）**：苹果CMS 适配器（JSON + XML，`CmsAdapter` 340 行 / `CmsXml` 190 行，
真机验证三个源能播一个不能播）、网络代理（HTTP/SOCKS5，设置页可配）、源管理（清单 / 切换 / 删除 / 添加，
切换走完整校验 + `MainActivity.recreate()`，**点完即时生效**——主线程重建 + 前端不再 await 重启指令，
两个坑见 docs 坑 14/15，换源还要清掉进程里的预热与连接池，见坑 16）、缓存按源分作用域（换源不再画出
上一个源的内容）、首屏预热（`AiiHttp.warmup` + 在飞等待）、DoH 结果磁盘缓存（TTL 30 分钟 + 后台刷新）。
另有三个前端坑：局部 `cover` 遮蔽封面模块（坑 17）、`goHome` 的 try 把渲染异常说成接口失败（坑 18）。
**底部导航也归配置了**（`web.ui.tabs`）：以前那四格写死在 `app.html`，换成苹果CMS 之后「剧场」和
「AI」点进去是同一份数据、名字还跟内容没关系。现在 CMS 那边按源自己的 `type_pid` 分类树生成入口
（实测 `cj.lziapi.com` 出到 首页/电影/连续剧/动漫/综艺/其他/我的），一维分类退成一个「影视库」。
两个新坑：苹果CMS 查父类返回 0 条（坑 19）、XML 接口的 `<class>` 没有 `type_pid`（坑 20）。
**首页那排标签也修好了**：上一版把 `popularTags` / `tagGroups` 清成空数组，CMS 源上首页一个胶囊
都没有、「全部类型」页整页空白。现在标签走**分类**（`tag-list` = `/api/videos?cate_id={slug}`，
CMS 的搜是标题匹配，点标签等于白点）、每个频道轮流挑一个（从头截 8 个全是电影片的子类）。
第三个坑：`adaptTags` 把不认识的 group code 一律折进 `other`（坑 21）。
**抽象边界推后了一格**（10-16 下午）：① 字段改名从 Java 挪进配置 —— 适配器不再认字段名，只按 `web.fields` 把命中的键原样搬过去（同一个族里换字段名的站不用改代码了）；② `paths` 支持 https 绝对地址、`origin` 可以留空 —— 接口开在别的域名、或者路径不在 `/api/` 下面的站，现在纯配置能接；③ `CmsAdapter` 头上写清了它是**族适配器**不是站适配器。顺带发现并补上一条文档漏项：**REST 响应最外层必须有 `data` 信封**。

**又推远一格**（10-16 傍晚）：加了 `web.shapes` —— 响应形状映射（`featured: "recommend"`
这种，直译「拿站点的 D 当我的 A」）+ 带点的字段路径 + `{div:[a,b]}` 一个除法算页数 +
可配置的 `data` 信封。分界线画在「声明 vs 计算」：`shapes` 的键和值形态 `build.sh`
和 `UserSource.validate()` 两头都查，因为它拼错了不会自己报错、只会静默不生效。

**账号后端自建完成**（10-16 傍晚）：`server/account/` —— PostgreSQL 16 + FastAPI 写的
**Parse REST 子集**，跑在云服务器 149.104.21.177 上，Caddy 剥 `/api` 前缀转发。
客户端只改了**一行**（`HuangGuoApp.smali` 里的 server 地址）—— 因为这 APK 里打包了
完整的 Parse Android SDK，原版挂的是 Back4App，沿用协议最省事，拆掉它要动 2000 多行 smali。

真机全通：注册 / 登录 / 收藏 / 观看历史 / 换账号隔离 / 退出重登恢复 / 头像上传。
隔离那几条是拿另一个账号打同一批请求验的（直取 id、改、删全部 404）。

顺带补了一个**真缺口**：播放进度原来只写进 localStorage 的 `hgpref:progress`，
从没送到原生侧 —— 所以「我的 → 观看历史」永远是空的，也永远同步不上去。
`AppStore.recordProgress()` 其实**现成就有**，只是零调用者（原版大概是从抓 HTML
那条路调的，那条路整块删掉时把它一起带走了）。现在走 `__progress:` 指令接上了。

踩到的两个坑值得记：
1. **`__` 前缀**：桥上的指令判断原本写的是 `"__source:"`，于是 `__progress:` 掉进
   路径检查里，报「路径既不是 / 开头也不是 https」。改成按 `"__"` 判断 ——
   多看几个字符没有任何好处，却意味着每加一种指令都要回来改 smali。
2. **进度单位是毫秒**：`AppStore.library()` 里有一句
   `if (!completed && position <= 5000) 跳过`，判的是「看了不到 5 秒不算历史」。
   送秒进去的话，看了 456 秒变成 456「毫秒」，正好落在闸门下面 ——
   记录写进去了（`get-progress` 读得回来）但列表里一条不显示。

发版走 `./build.sh release`（打包→证补丁集→刷补丁→再打包→报 sha256），别手动 cp ——
踩过一次：验证自检时往 site-default.json 塞的临时 shapes 被发进 APK，而 verify 照样说「完全一致」
（`site-web.js` 是生成的、不参与比对）。见坑 22。

**AGE动漫 接进来了**（`AgedmAdapter.java` + 粘 `www.agedm.io` 自动生成配置）。
它的首页和列表本来纯配置就能接（实测过），卡在详情 ——
`playlists{源:[[集名,地址]]}` → 平的 `episodes[]` 是**结构重排**，属第 ⑥ 层，只能写代码。
播放绕过去了：拿标题去同名苹果CMS 采集站匹配（标题必须完全一致，不猜），
真机 `readyState=4` 在播。
详见 [`docs/history/2026-10-16-用户自带源与适配层.md`](docs/history/2026-10-16-用户自带源与适配层.md)。

**已完成（2026-10-16）**：红果源整块移除（723KB smali）、用户自带源（读写校验 + `__source`
桥 + 首次设置页 + 运行时时更新 web 配置）、原生播放器摘除（63 个文件 660KB）、
抓 HTML 那套整块移除（`SiteRepository` 3959→1445 行、`SiteProfile` 1518→977 行，共 3055 行）、
HTTP 层换 OkHttp + DoH、前端端点全配置化（`site-default.json` 单文件 scheme）、
`app.js` 拆出 `cover.js` 与 `adapters.js`、字段别名层（16 个角色）。

---

# 当前主线：兼容苹果CMS / TVBox

## 背景：拆开看是两件事，价值差很多

实测一份真实的 TVBox 单仓配置（`qist/tvbox` 的 `0821.json`，23535 字节，86 个源）：

| 类型 | 占比 | 是什么 | 可行性 |
|---|---|---|---|
| `type 0/1` 苹果CMS 采集 | **7 / 86** | 一条 URL，纯数据接口 | 直接做 |
| `type 3` spider JAR | **79 / 86** | 指向要下载执行的 JAR | 要 `DexClassLoader`，不做 |

**所以一期独立可交付**：不依赖 TVBox 配置，用户直接填一个 `api.php/provide/vod` 地址。

---

## 一期：苹果CMS 适配器 —— **已完成**（2026-10-16 真机验证）

```text
1.1 自动识别        ✅
1.2 CMS 客户端      ✅ JSON + XML 两种接口
1.3 字段映射        ✅
1.4 播放链路        ✅ 暴風源和量子 XML 源都播通了
1.5 前端文案        ⬜ 用户决定不改（没必要）
1.6 验收            ✅ 首页/分类/详情/搜索/播放全过
                    ⬜ build.sh 的配置自检还没加 CMS 分支
```

**实测结论（用手机测，我本机走代理美国 IP 全被 403 地域封锁）：**

```text
暴風  bfzyapi.com          分片 0000000.ts  video/mp2t     → 能播
索尼  suoniapi.com         标准 HLS 主播放列表            → 能播
量子  cj.lziapi.com/xml/   XML 接口，/share/ 与 m3u8 双线路 → 能播
百度  api.apibdzy.com      index0.jpeg  image/jpeg        → 播不了（图片混淆）
```

**3 个能播 / 4 个测过。** 图片混淆那种要在分片层解密，是 TVBox 用自研播放器解决的一层，
**不做**。

### 已落地的文件

```text
java-src/com/huangguo/mobile/CmsAdapter.java   约 340 行，三个动作 + 字段映射 + 线路选择
java-src/com/huangguo/mobile/CmsXml.java       约 190 行，XML → JSON 同形
java-src/com/huangguo/mobile/UserSource.java   detect 派发 / 干跑校验（适配器已拆到 *Site.java）
smali/com/huangguo/mobile/SiteProfile.smali    新增 rule(String)，读标量配置
```

### 一期踩过的坑（都记着，别再犯）

**① 探测要拉两次，结果互补。** 裸地址给 `class[]` 不给 `vod_pic`，`?ac=detail&pg=1`
反过来。少任何一边都不行——没有 `vod_pic` 就提取不出封面域名，而空的 `allowedHosts`
会在 `SiteProfile` 构造时抛「缺少封面域名」，那是 `Activity.onCreate` 里，
**表现是一装完就闪退，用户连设置页都进不去**。

**② 存配置前必须干跑一次 `SiteProfile` 构造。** 否则一份坏配置把 App 永久锁死。
现在 `__source:set` 会先 `new SiteProfile(context, config)` 试一遍，抛异常就拒绝落盘。

**③ 封面要显式覆盖两块。** `mode: "plain"`（CMS 是普通 JPEG，内置模板是 aes-cbc，
照内置那套解密会把图毁掉）+ `allowedHosts`（`coverUrl()` 逐个精确比对 host，
不匹配直接抛「无效封面域名」）。

**④ CMS 分流只接 `/api/` 开头的路径。** 封面是绝对 URL 也走 `AiiHttp.get`，
全路由给适配器的话会被以「不认识的路径」拒掉，表现是封面全挂。

**⑤ 界面常量的 key 必须是 `adult` / `ai`。** 那是前端 `state.tab` 的取值。
写成 `main` 前端读不到，静默退回黄果那套硬编码文案。

**⑥ `categoryTitles` 是字符串不是数组。** 塞数组进 innerHTML 会变成
「电影片,连续剧,综艺片,…」四十个名字连成一行的巨标题。前端加了类型检查兜底。

---

### 1.1 配置识别（自动识别，不给用户加选择）

用户贴地址 → Java 看响应形状决定用哪个适配器：

```text
{origin, web}      → REST（现在这种）
{code, class[]}    → 苹果CMS
{urls[]}           → TVBox 多仓（二期）
{spider, sites[]}  → TVBox 单仓（二期）
```

**落点**：`UserSource.fetch()` 之后、`validate()` 之前加一层 `detect()`，把结果写进
`adapter` 字段。`validate()` 按 adapter 分支校验（CMS 不需要 `web.paths`）。

**验收**：贴一个 CMS 地址，设置页不报错，存下的配置里 `adapter: "cms"`。

### 1.2 CMS 客户端（三个动作）

已实测确认的接口形状：

```text
取分类  GET ?ac=list
        → {code, class:[{type_id:1, type_name:"电影"}, …]}

取列表  GET ?ac=detail&t=<type_id>&pg=<page>
        → {code, page, pagecount, list:[{vod_id, vod_name, vod_pic, vod_remarks, …}]}

取详情  GET ?ac=detail&ids=<vod_id>
        → {code, list:[{…, vod_play_url, vod_play_from, vod_content}]}
```

**落点**：新建 `java-src/com/huangguo/mobile/CmsAdapter.java`。

### 1.3 字段映射（CMS → 我们内部 shape）

**这是整个计划最关键的一步。** 适配器负责让外部源的响应「看起来像我们的站」，于是
JS 的 `adapt*`（那 16 个字段别名角色）**一行不用改**。

| CMS | 我们 | 备注 |
|---|---|---|
| `vod_id` | `id` | |
| `vod_name` | `title` | |
| `vod_pic` | `cover` | |
| `vod_remarks` | `badge` | "更新至12集" 这类 |
| `vod_score` | `score` | |
| `vod_content` | `description` | **要去 HTML 标签**，实测是 `<p>…</p>` |
| `vod_play_url` | `episodes[].{number,title,source}` | 见 1.4 |
| `class[].type_id/type_name` | `categories` | 覆盖 `web.categories` |

对应的语义转换：`/api/home`、`/api/videos/{id}` 这些路径**从「黄果站的真实路径」升级成
「我们内部的动作词汇表」**，适配器负责实现它。

### 1.4 播放链路

```text
vod_play_from: "dbm3u8"                              线路名
vod_play_url:  "第1集$https://…/index.m3u8#第2集$…"
               └ # 分集    └ $ 分「集名 / 地址」
```

**里面是 m3u8 直链**，我们的 WebView HLS 播放器直接吃，不需要解析器或嗅探。

注意点：
- 线路可能有多条（`vod_play_from` 用 `$$$` 分隔），先取第一条或做成可选
- 集名可能是空的，要给个 `第 N 集` 兜底
- 地址可能是**分享页**而不是 m3u8（部分源），那种要 `parses[]` 才能解——**一期先不处理**，
  遇到就让播放报明确的错，别静默失败

### 1.5 前端

- 设置页文案：从「填一个配置地址」改成能说清支持哪几类
- 错误回显：识别失败时告诉用户「这不像一个已知的源」
- 不做「类型选择」——自动识别，用户不需要知道适配器是什么

### 1.6 验收（真机）—— 已通过

```text
首页 20 条带封面     ✅   分类翻页（pages=249）  ✅
详情 336 集          ✅   搜索 20 条            ✅
点播放出画面         ✅   无崩溃 / 无封面失败    ✅
build.sh 配置自检    ⬜   还没加 CMS 分支
```

---

## 二期：TVBox 配置

### 2.1 多仓 / 单仓识别

```text
多仓  { "urls": [ {"url": "./99188.json", "name": "…"} ] }   ← 94 字节，先拉子配置
单仓  { "spider", "sites": […] }                             ← 相对路径按父地址解析
```

**注意**：`./99188.json` 是相对路径，要按父配置的 base URL 解析。

### 2.2 站点选择

存选中结果：

```json
{ "adapter": "tvbox", "config": "<原配置URL>", "siteKey": "百度", "sites": [...] }
```

设置页列出 `sites[]`（`key` / `name` / `type`），选中 `type 0/1` 就转成一期的 CMS 适配器
（`sites[].api` 本身就是 CMS 接口地址）。

### 2.3 `type 3` 置灰

**必须在 UI 上说清楚**：86 个源里 79 个是 `type 3`，一期二期做完用户能挑的里**只有 7 个
能用**。别让人以为「兼容了 TVBox」。

---

## 三期：spider JAR —— 不做，记着理由

要做的话得：下载 JAR → `DexClassLoader` → 反射实例化 `csp_XXX` → 实现 CatVod 的 Spider
接口契约（`init` / `homeContent` / `categoryContent` / `detailContent` / `searchContent` /
`playerContent`）→ 还要处理不同 fork 的接口差异。

**代价是执行任意下载代码**，这正是 TVBox 生态「源里带毒」的根因，也是我们前几轮明确
拒绝的那条路。建议永久置灰。

---

# 其它待办

## 账号后端接到自己的服务器 —— **已完成**（核实于 2026-10-17）

**已上线的形态**：`HuangGuoApp.onCreate` 里 serverUrl 指向自己的 VPS：

```smali
const-string v0, "https://149.104.21.177.sslip.io/api/"
Parse.initialize(appId, clientKey, v0)
```

自建服务在 `server/account/`（Parse REST 子集 + PostgreSQL），VPS 上以 Docker 跑着
`account-api-1` + `account-db-1`（postgres:16-alpine），和 Caddy 同机。

**appId / clientKey**：还是 `res/values/strings.xml` 里那两个（`back4app_app_id` /
`back4app_client_key`，名字是历史遗留），服务端 `PARSE_APP_ID` 与之一致，不然每个
请求都是 403。要换 key 得两端同时改。

**核实记录**（2026-10-17）：`GET /api/classes/_User` 带 appId 请求返回
`{"code":206,"error":"没有 session token"}` —— Parse REST 语义正确，链路活着。
smali 里已搜不到任何 `parseapi.back4app.com` 引用。

**遗留**（不紧急）：字段名 `back4app_*` 只是名字难看，换名要动 `strings.xml` +
`R$string.smali` 两处，属于「纯洁癖项」，单独一轮做。

---

## 建仓库

一直悬着，因为「141MB smali 要不要入库」没定。现在补丁集形态已经跑通，随时可以提。

```text
chengzi-v0.3.15.apk     8.4MB   原始样本，clean 路径的唯一输入，要入库
patches/                684K    22 个文件 + 76 条删除记录，相对样本的全部改动
java-src/ java-stubs/           编译成 classes5.dex 的源码
build.sh / smoke.sh / gen-web-config.py
docs/ + README.md + LICENSE
```

`work/shells/cz_repack/`（146MB）不入库——`./build.sh clean` 能从样本 + 补丁精确重建。

**决策点**：`work/shots/` 那 20 张验证截图（7.6MB）入不入库。现在 `.gitignore` 忽略了，
但 README 和主文档引用了其中 9 张，不入库的话新克隆会看到断链。

---

## 双向滑动

主文档第八节那张表里**唯一还没做的功能项**：现在是单向 feed（上下滑切集），左右滑切剧没做。

2026-10-16 补一句：左右滑动这条轴已经给了「拖动进度」（见主文档第十四节），屏幕横向
被 seek 占着了。要做左右滑切剧，只能二选一：要么把 seek 挪走（比如只在暂停时横滑调
进度、播放中横滑切剧），要么切剧走别的入口（详情页选集、播完自动连播）。

---

## 资源名换掉 `orange_*`

`orange_icon_background` / `orange_icon_foreground` / `orange_logo`——名字是橙色主题时代
留下的，内容早就是白底黑 A 了。

改要同步三处：`res/values/public.xml` 的 id、`R$color.smali` / `R$drawable.smali` 的字段、
`res/mipmap-anydpi/ic_launcher.xml` 的引用。

**纯洁癖项**：用户永远看不到资源名。风险中等（动 `public.xml` 的 id 可能让 aapt2 重排资源
ID，把 smali 里的 R 常量对错位），收益是零。**要做单独一轮，别跟别的改动混。**

---

## 包名 `com.huangguo.mobile`

**改包名等于换一个应用**：覆盖安装会失败、老用户得先卸载、签名链路重走一遍，而且 smali 里
所有 `Lcom/huangguo/mobile/...` 的类型引用都要跟着改。

绑在一起的还有：`HuangGuoApp` 类名、它的 `.source` 调试属性、FileProvider 的 authority
（`com.huangguo.mobile.updates`）。

**建议**：除非要上应用商店，否则不动。

---

## `app.css` 的可读性

245 行却有 54KB——平均每行 224 字节，大段是压缩过的长行。不影响运行，但下次改样式很难定位。

展开成一行一规则，或者按区块拆成几个文件（`app.css` + `feed.css` + `settings.css`）。

---

## 已知的取舍（不是待办，是记着）

**恢复出厂设置不清 `content-access`，也不动账号。** 前者清了会让 `ContentMode` 的内存态
（`huangguo`）和盘上的解锁位对不上，`require()` 当场给每个桥指令抛「内容已切换，请重新打开」
（见「用户自带源与适配层」坑那一节的七之二）。而它本来就不是用户数据 —— 每次冷启动前端都会
自动 enter-private + confirm-private-age 写回来。后者是有意的：清的是**这台设备**，云端片单
留着，重新登录就回来了。

**出厂不带源。** 装完是空的，用户自己填。这是有意的——把「源提供者」和「工具提供者」
分开。代价是首次体验差（空界面 + 设置页），转化率会掉。恢复出厂设置之后也是这样一屏，
两个入口现在是同一个动作（`__source:restart`）。

**`headers` 不能热更新了。** 切断订阅前，站点改 UA/Referer 策略还能推一版 `site.json` 救；
现在只能重新发包。走 `/api/` REST + 钉死 origin 的必然代价。

**域名漂移不自愈。** `origin` 在配置里，站点换域名就得让用户改配置（比重新打包好，
但仍要用户动手）。

**`huangguo-data` / `huangguo-avatars` 这类 SharedPreferences 文件名没改。** 改了会丢
本地的观看历史、收藏、设置——而它们用户永远看不到。

**`/api/` 路径是内部契约，不是站点真实路径。** 一期做完之后这个性质会更明显：
适配器负责让外部源「看起来像我们的站」，JS 的 `adapt*` 才能一行不改地复用。
代价是语义上有点绕。
