# 待办

## 工作流约定（2026-10-17 定，别再搞反）

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
详见 [`docs/2026-10-16-用户自带源与适配层.md`](docs/2026-10-16-用户自带源与适配层.md)。

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
java-src/com/huangguo/mobile/UserSource.java   detect / wrapCms / 干跑校验
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
