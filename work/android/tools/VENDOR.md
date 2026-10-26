# tools/ 里那两个进 git 的 JS 依赖

构建期把前端从 ES2021 降到 ES2017 用的。为什么要降，见
`../../TODO.md` 的「2.0.24：旧 WebView 白屏的根因」。

## 两个文件

| 文件 | 包 | 版本 | 大小 | sha256 |
| --- | --- | --- | --- | --- |
| `babel.standalone.min.js` | `@babel/standalone` | 7.26.4 | 2.98 MB | `a12872ea8da3d29b2a296c51bfac7c482e81419c755f2207a49ad9b77200f4ea` |
| `acorn.js` | `acorn` | 8.14.0 | 238 KB | `bec194b9abb10147d3bb77e544d95cf1c7b4f9f42dad00dfc83791909ebf49c7` |

来源：

```
https://registry.npmjs.org/@babel/standalone/-/standalone-7.26.4.tgz   → package/babel.min.js
https://registry.npmjs.org/acorn/-/acorn-8.14.0.tgz                    → package/dist/acorn.js
```

`babel.min.js` 原样拷，没改一个字节 —— 它自带的 sourcemap 注释指向同目录的
`babel.min.js.map`，那份没进来，所以 devtools 里会提示 sourcemap 缺失。构建期
不需要，忽略即可。

一样抄不动的还有 acorn：`dist/acorn.js` 是 UMD，`require('./tools/acorn.js')`
直接就能用，不依赖 Node 的模块解析规则。

## 为什么进 git 而不是 `npm install`

和 `app/libs/libv2ray.aar`（20MB，同样进 git）一个理由：**构建可复现，且不依赖
某个 registry 那天还在。** 这个仓库的构建链上已经有 python3、gradle、Android SDK
三个外部依赖，再加一个「必须联网、必须连上 npmjs」是第四种坏法，而且它坏起来
是构建到一半 404，不是一开始就报错。

代价是仓库多 3.2MB。相比 libv2ray.aar 的 20MB，这个代价可以忽略。

## 升级怎么办

两个文件都只看 `package.json` 里的 `engines`/`browserslist` 口径，不跟 minor
版本。升级时把上表的版本和 sha256 一起改，然后跑：

```
./scripts/build.sh test
```

`transpile-assets.js` 自带六条自检（行号对齐、无 `?.`、顶层函数未被包裹、
没注入 `"use strict"`、ES2017 可解析、漂移阈值）。这六条里任何一条变了，说明
新版 Babel 的输出形态变了，那就得重新评估下面这个已知限制。

## 已知限制：行号可靠，列号不保证

用 `retainLines: true` 让 Babel 尽量把输出压回源码的行号上。实测 20 个文件
漂移全是 `-1`（Babel 吃掉文件末尾那个换行），所以 `js_error` 上报的
`where: "app.js:14:42"` 里的**行**是可信的。

但当一个表达式降级后需要额外变量时，Babel 会把多条语句塞进同一行：

```js
// 源码第 14 行
resolve(id, value) { callbacks.get(id)?.resolve(value); callbacks.delete(id); },
// 输出第 14 行
resolve(id, value) {var _callbacks$get;(_callbacks$get = callbacks.get(id)) === null || …
```

语法完全合法，但那一段里**列号**会比源码大。所以堆栈里 `:42` 这种列号只能当
参考，定位靠行号 + 行内容。
