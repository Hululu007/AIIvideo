#!/usr/bin/env node
/**
 * ES2017 解析断言：一串 `.js` 文件里，有没有哪一份是**旧 WebView 读不了**的。
 *
 * ## 为什么会有这个脚本
 *
 * 2.0.23 的线上数据里，两台设备的 `js_error` 是这么来的：
 *
 *   7b824777 / SM-G9860 / Android 9    14 个文件，每个都倒在第一处 `?.`
 *                                       app.js:14:42  Unexpected token .
 *   88ff2011 / V2164PA  / Android 11   app.js:2982:21  Unexpected token '='
 *
 * 两台设备除了 `app_open` 之外一个业务事件都没有 —— 那是白屏：Java 侧起来了、
 * 页面也加载了，然后每个脚本在解析阶段就死了，`app.js` 从未执行过一行。
 *
 * `?.` 要 Chrome 80（2020-04），`||=` 要 Chrome 85（2020-09）。而 `minSdk 26`
 * 是 Android 8.0，出厂 WebView 是 Chrome 58；国内没有 Play 商店，很多机器
 * 的 WebView 就停在出厂版本，永远不会更新。
 *
 * 所以「能不能在旧 WebView 上跑」这件事必须是一条**构建期断言**，不能靠
 * 代码评审的眼神 —— 247 处 `?.` 分布在 14 个文件里，写的时候根本不会想起它。
 *
 * ## 用 acorn 而不是正则
 *
 * `?.` 会出现在字符串和正则里（"…?.\n" 这种），文本匹配必然误报；反过来
 * `??=`、`||=`、class field、BigInt 字面量这些也都是语法级的，正则列不全。
 * acorn 的 `ecmaVersion` 就是「这份代码能不能被那个版本的引擎解析」的本体，
 * 用它判定没有第二种解释。
 *
 * 报出来的坐标是 `行:列`，列按 1 起算 —— 和 `js_error` 里 `where` 的口径一致，
 * 可以直接跟生产数据对账。
 *
 * ## 它对着源码跑时会比线上多报几个文件，这是对的
 *
 * 线上那台设备的 WebView 是 Chrome 66~79：`?.` 不支持，但 ES2018 的对象展开
 * `{ ...x }`（Chrome 60）和 ES2019 的 optional catch binding `catch {`（Chrome 66）
 * 都支持。所以浏览器倒在 `?.` 上，而 ES2017 的解析器更早倒在展开语法上 ——
 * 例如 `proxy-panel.js` 线上报 `364:72`，这里报 `43:23`，两个都是真话，只是
 * 「第一个不认识的语法」不同。
 *
 * 结论：**这个脚本的判定对象是降级后的产物**（Babel 把这三类一起压平）。
 * 对着 `src/main/assets` 跑只用来确认「源码确实需要降级」。
 *
 * ## 用法
 *
 *   node scripts/js-target-check.js <dir 或 文件>
 *   node scripts/js-target-check.js --quiet <dir>
 *
 * 退出码：0 全部通过 / 1 有解析失败 / 2 环境问题
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ACORN = path.resolve(__dirname, '../tools/acorn.js');
if (!fs.existsSync(ACORN)) {
  console.error('缺 tools/acorn.js（见 tools/VENDOR.md）');
  process.exit(2);
}
const acorn = require(ACORN);

/**
 * 目标语法等级。
 *
 * 2017 不是随手取的数字：
 *   · `async/await` 是 ES2017，Chrome 55 就有 —— 再往下压 Babel 会注入
 *     regenerator，凭空多一层运行期依赖，不值。
 *   · 而 `?.`（ES2020）、`||=`（ES2021）都在 2017 之上，正好被这条线挡住。
 *   · `minSdk 26` 的出厂 WebView 是 Chrome 58，够用。
 */
const TARGET = 2017;

function walk(dir, out) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, out);
    else if (entry.isFile() && entry.name.endsWith('.js')) out.push(full);
  }
  return out;
}

function files(target) {
  const stat = fs.statSync(target);
  if (stat.isDirectory()) return walk(target, []).sort();
  return [target];
}

/** 把 acorn 的错误压成 `文件:行:列  消息`。 */
function describe(file, root, error) {
  const where = path.relative(root, file) || path.basename(file);
  if (error && error.loc) {
    return where + ':' + error.loc.line + ':' + (error.loc.column + 1) + '  ' + error.message;
  }
  return where + '  ' + (error && error.message ? error.message : String(error));
}

/** 解析一份源码。返回 {ok:true} 或 {ok:false, error}。 */
function parse(source) {
  try {
    acorn.parse(source, { ecmaVersion: TARGET, sourceType: 'script', allowHashBang: true });
    return { ok: true };
  } catch (error) {
    return { ok: false, error: error };
  }
}

function check(target, quiet) {
  const root = fs.statSync(target).isDirectory() ? target : path.dirname(target);
  const list = files(target);
  const bad = [];
  for (const file of list) {
    const source = fs.readFileSync(file, 'utf8');
    const result = parse(source);
    if (!result.ok) bad.push({ file, error: result.error });
    else if (!quiet) console.log('  ok  ' + (path.relative(root, file) || file));
  }
  if (bad.length) {
    console.error('');
    console.error('以下文件超过了 ES' + TARGET + '，旧 WebView 会整份解析失败（白屏）：');
    for (const item of bad) console.error('  x ' + describe(item.file, root, item.error));
    console.error('');
    console.error('降级入口：scripts/transpile-assets.js（构建期自动跑，见 build.gradle.kts 的 transpileAssets）');
    return 1;
  }
  if (!quiet) console.log('');
  console.log('  ' + list.length + ' 个文件全部在 ES' + TARGET + ' 可解析');
  return 0;
}

if (require.main === module) {
  const args = process.argv.slice(2);
  const quiet = args.includes('--quiet');
  const target = args.filter(a => !a.startsWith('--'))[0];
  if (!target) {
    console.error('用法: js-target-check.js <dir 或 文件> [--quiet]');
    process.exit(2);
  }
  if (!fs.existsSync(target)) {
    console.error('路径不存在: ' + target);
    process.exit(2);
  }
  process.exit(check(target, quiet));
}

module.exports = { parse, describe, files, check, TARGET };
