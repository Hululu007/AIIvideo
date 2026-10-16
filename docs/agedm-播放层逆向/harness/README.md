# 播放层 harness

在 node 里把站点那套链路跑起来，不依赖浏览器、不依赖手机。每个脚本自己带一份
浏览器环境的替身（`shim.cjs`），跑之前需要：

```
/tmp/agedm/jx.wuzhoupai.com.wasm   站点的 Go/wasm 原件，307063 字节
                                    sha256 见 wasm.sha256
```

拿不到原件也可以从 APK 里还原：`assets/hxm/hxm.wasm.js` 是它的 base64。

## 脚本

| 文件 | 干什么 |
|---|---|
| `tinygo_exec.js` | TinyGo 的 wasm_exec 胶水，从站点 `global.min.js` 里抠出来的 |
| `shim.cjs` | 最小浏览器替身（window / document / localStorage …） |
| `final.cjs` | 用一条从浏览器抓回来的真 `Data` 验解密链是否自洽 |
| `poc4.cjs` | 换不同 `url` 字段发请求，看服务端回哪个 `Code` |
| `fallback-test.cjs` | **把 App 里那个 `assets/hxm/agedm-play.js` 原样加载**，喂它「一条坏线路 + 五条好线路」，验证 `resolveAny` 真的会换下一条 |
| `line-errors-test.cjs` | 采样多部片，看每种线路失败的报错文案 |

后两个是回归用的：改完 `agedm-play.js` 跑一遍，能看到每步的进度文字和最终是
成功还是抛错。`fallback-test.cjs` 里的 `REPO` 指到工作树，别写死成别的路径。

## 踩过的坑（都在这几个脚本里栽过）

- **token 里已经有 `%2F`，地址里不能再 `encodeURIComponent` 一次**，否则服务端
  解析不出来，只丢一个 32 位的占位 Vurl 回来，看着像「这部剧没源」
- **`url` 字段用 Vurl 原样**，再加密一次拿 `Code=1`
- **`Code` 是密钥的输入**（`md5(Code + ids + Appkey + Version)`），所以
  `Code=9` 那种错误载荷用谁的 key 都解不开 —— 八种材料全试过了
- node 默认会拒 `api.agedm.io` 的证书链，记得 `rejectUnauthorized: false`
