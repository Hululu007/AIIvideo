# spider ABI 的三个缺口：这一轮补完了（2026-10-19 晚）

起因是一句反问：**「你的设计说壳不动源动，刚刚违反了这个设计吗？」**

违反了三处。盘下去发现不是源写错了，是壳的 ABI 缺三样东西，源再怎么动都绕不过去。
这一轮把三样都补上，顺带把上一轮为了绕开缺口而加的外部依赖拆掉。

---

## 一、缺口一：能力集写在前端

旧代码是两张表：

```js
const SPIDER_CAP = { home, category, search, detail, tags: 'tags', 'tag-list': 'tagList' };
const SPIDER_MISSING = new Set(['tags', 'tag-list', 'recommend', 'newest']);
```

第一张回答「这个动作调哪个函数」，第二张回答「这个源支持什么」。第二张是错的 ——
前端凭什么替所有源做这个判决。91porn 补了 `tags` 之后前端照样抛
「这个 spider 源暂不支持」，用户看到的是源坏了。

**现在：问源。** `spider` 桥加一个伪能力 `__meta`，注入源码后不求值任何能力，只读一遍
`__spiderSite`：

```js
var caps = [];
for (var k in e) { if (typeof e[k] === 'function') caps.push(k); }
```

**按函数存在性枚举，不读源里写的字面量声明** —— 源加一个函数壳立刻知道，忘了同步一份
`caps` 数组也不会出现「实现了但前端说没有」。实测一次 312ms：

```
cap=__meta ok ms=312 caps=["home","category","tagList","tags","reconcile","search","detail","play"]
```

前端按 id 缓存（`spiderMetaCache`），一次会话问一次；装源后 `HG_SPIDER_META_RESET()`
清掉。`SPIDER_MISSING` 整张删了，`SPIDER_CAP` 只留「动作 → 函数名」这一层含义。

探测失败时**不拦**，直接交给源自己报错 —— 拦错的代价（源是好的、前端说它不支持）
比漏判大。

---

## 二、缺口二：`net` 是同步桥，源里写不出并发

上一轮修 91porn 的卡片错位时，最需要的就是「一次拉 24 个详情页」。写在源里是这样：

```js
var WORKERS = 8;
await Promise.all(workers.map(worker));
```

看着像并发，实际全串行 —— `ctx.call('net')` 在渲染线程上阻塞等 Java 回包，`await`
只是把等待点挪了个位置。真机实测 45 秒被看门狗杀掉，零结果。当时只能把校正搬到
服务器的 Python 服务上，等于给一个「自包含的 js 文件」配了个后端。

**现在：`netAll`。** 一次 bridge 往返带 N 个请求，Java 侧线程池并发：

```json
入参 {"requests":[{"url":…,"headers":…}, …], "concurrency":8}
出参 {"results":[{"ok":true,"status":200,"body":…} | {"ok":false,"error":…}, …]}
```

三个约束：

- 上限 30 条、并发上限 8（一页 24 条刚好用满）。
- **单项失败不抛**，带 `ok:false` 占位，顺序和入参一一对应 —— 源按索引映射回自己的 id。
  批量请求里坏一条是常态（下架、id 打错），整批失败会让调用方一条都拿不到。
- 每个子请求走的是同一个 `net`：白名单、路由、UA 分流、空响应检查一条不少。

真机数据：

```
cap=reconcile netAll n=24 conc=8
cap=reconcile ok ms=7372
```

24 条详情 7.4 秒（走代理，单条 1.3-2.7 秒）。服务器版本是 1.8 秒，但那是直连，
而且多一个需要运维的进程。7.4 秒是**后台**跑，首屏不等它。

---

## 三、缺口三：源没有元信息通道

上一轮为了让前端知道「这个源要不要做后处理」，往源配置里塞了一个 `web.reconcile: true`。
那是把实现细节漏进了配置结构 —— 配置是给人填参数的，不是给壳做能力协商的。

`__meta` 顺手把 `meta` 也带回来了（`__spiderSite.meta`，任意对象）。这一轮实际没用上：
函数枚举已经能回答「有没有 reconcile」。字段留着，因为下一件想做的事（源声明
`direct` 直连域、`binary` 支持 range）都需要一个不占函数名的地方。

`porn91.json` 里那行 `"reconcile": true` 删了，`reconcileCards` 改成看 caps：

```js
const info = await spiderMetaOf(spiderId);
if (info.caps.length && info.caps.indexOf('reconcile') < 0) return;
```

---

## 四、服务器下线

源不再需要 `/p91/reconcile`：

```
systemctl disable --now p91-reconcile      → inactive
Caddy 移除 handle /p91/*，reload           → https://…/p91/reconcile 400/404
```

`server/p91/reconcile.py` 与 systemd unit 留在仓库里，没删。理由：它是个能用的东西
（全局缓存、1.8 秒），哪天出现「一个源要拉几百条」的场景还能拎出来。但**源不依赖它**，
`porn91.js` 的 `hosts` 回到 `['901.26702.app', '91porn.com']`，一个外部地址都不碰。

---

## 五、验证

离线（全部退出码 0）：

```
spider-all-check   8 个源 / 8 配了回归 / 8 全过 / 0 失败
model-check        全部通过
spider-id-check    全部通过
market-check       全部通过 —— 这份市场装得上
```

`spider-check` 的沙箱补了 `netAll`：串行跑完再按顺序返回，形状必须和真机一致，
否则「按索引映射回 id」在真机错位而离线是绿的。

真机（23113RKC6C，1.0.32，走代理）：

```
__meta       312ms   caps 8 个，含 reconcile
reconcile    7372ms  netAll n=24 conc=8
卡片/详情/播放  1320337546 → 1252773.jpg → mp43/1252773.mp4，三者同号
冷启动        fixes=44 从 localStorage 恢复，cap=reconcile 请求 0 次
```

首页那张「跳过刷新也要套真值」的补丁是实测出来的：`HOME_FRESH_MS` 是两分钟，
缓存里存的是网格页原样（未校正），如果跳过刷新连 `reconcileCards` 一起跳过，
第二次进首页看到的还是旧标题 —— 而真值明明已经在 localStorage 里。现在跳过网络
也套一遍，只花内存查找。

---

## 六、这一轮壳改动的账

| 改动 | 位置 | 能不能只在源里做 |
|---|---|---|
| `__meta` 探针 + caps 枚举 | SpiderRuntime / SpiderBridge | 不能，壳的能力 |
| `netAll` 线程池 | SpiderRuntime / SpiderBridge | 不能，壳的能力 |
| `api()` 按 caps 分派 | app.js | 不能，壳的职责 |
| `reconcileCards` 看 caps | app.js | 不能，壳的职责 |
| `reconcile` 改 netAll | porn91.js | **只在源里** |
| 删除服务器依赖 | porn91.js / Caddy / systemd | **只在源与运维里** |

前四条是「补一次、以后所有源受益」的债。从下一轮起，源加一个新能力（比如
`bulk`、`resolve`）不再需要动壳：函数写进去，`__meta` 自动枚举出来，前端按 caps 分派。
这才是「壳不动源动」应该有的样子 —— 壳提供能力面，源决定用不用。

---

## 七、还欠着的

三条上一轮记下的缺口，这一轮只补了一条（并发），还剩两条：

- **`net` 传不了二进制**：1.3MB 的 ts 分片走 UTF-8 解码，`body.length` 是替换符个数
  不是字节数。补法是 `range: {from,to}` + 返回 `bytes`，`AiiHttp.getRawRange` 已经存在。
- **`net` 看不见非 2xx 的正文**：403 挑战页、404 改版、500 参数错，现在都只剩一句
  `HTTP 403`。补法是可选入参 `non2xx: true`。
- **`DirectHosts` 没有对应物**：yhdm 的线路探测因此被放弃，代价是「第一条线路是死线
  时用户点了播不了」。

这三条都不阻塞当前八家源，下一轮按需补。
