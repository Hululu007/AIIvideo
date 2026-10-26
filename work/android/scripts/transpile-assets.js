#!/usr/bin/env node
/**
 * 构建期把前端降级到 ES2017，并当场自检。
 *
 *   node scripts/transpile-assets.js --src <assets> --out <dir>   复制 + 降级 + 自检
 *   node scripts/transpile-assets.js --check <dir>                只验 ES2017 可解析
 *
 * ## 为什么要降级
 *
 * 2.0.23 线上有两台设备的 `js_error` 是「14 个文件全部倒在第一处 `?.`」，
 * 除了 `app_open` 一个业务事件都没有 —— 白屏。`?.` 要 Chrome 80，`||=` 要
 * Chrome 85，而 `minSdk 26` 的出厂 WebView 是 Chrome 58。详见
 * `scripts/js-target-check.js` 的文件头和 `tools/VENDOR.md`。
 *
 * ## 为什么是「整棵拷到生成目录」而不是给 sourceSets 加第二个 srcDir
 *
 * AGP 合并两个 assets 目录里同名文件时，谁赢取决于目录顺序 —— 那正是
 * 「某个分片没打进包」这类静默故障的温床（check-apk.py 的头注释里记着同一类
 * 事故）。一个目录、一次拷贝，没有优先级问题。整棵 assets 只有 4.9MB，
 * 拷一次几毫秒。
 *
 * ## 为什么跳过 vendor/ 和 hxm/
 *
 * 那是第三方代码和 410KB 的 wasm glue，本来就没有 `?.`，过一遍 Babel 没有
 * 任何收益，只有「改坏了第三方」的风险。但它们仍然要过 ES2017 解析断言 ——
 * 哪天 vendor 里进了 ES2020 的依赖，构建得当场失败，而不是等某台旧机器白屏。
 *
 * ## 六条自检
 *
 * 降级这件事坏了从来不报错，只是某天某台机器白屏。所以每一条能想到的
 * 「静默失效」都在这里钉住：
 *
 *   ① 行号对齐        Babel 在 `retainLines` 下吃掉文件末尾换行，漂移一律 -1。
 *                     漂移超过 ±2 说明 retainLines 失效了 —— 那之后所有
 *                     `js_error` 的 `where` 和堆栈都不可信，必须当场停下。
 *   ② 顶层声明不许丢  20 个 script 共享全局作用域，`app.js` 里的 `function hgNativeResolve`
 *                     被包进 IIFE 或者改了名，界面就是一片 ReferenceError。
 *   ③ 不许注入 "use strict"  源码是 sloppy mode，一加严格模式，某些赋值和
 *                     `arguments` 的行为就变了。
 *   ④ ES2017 可解析    这是本体的目的，交给 js-target-check.js。
 *   ⑤ 残留语法扫描    `?.` / `??` / `||=` 的文本扫描。会命中字符串里的同类
 *                     字符，所以只记为注记 —— 解析通过才是有权威的那一条。
 *   ⑥ 体积            报告总字节变化，超过 20% 就要问一句为什么。
 *
 * 退出码：0 通过 / 1 自检失败 / 2 环境问题
 */
'use strict';

const fs = require('fs');
const path = require('path');

const HERE = __dirname;
const BABEL = path.resolve(HERE, '../tools/babel.standalone.min.js');
const check = require('./js-target-check.js');

/** 不降级的目录（相对 assets 根）。仍然参与 ES2017 解析断言。 */
const SKIP_DIRS = ['vendor', 'hxm'];

/** 行号漂移的容忍上限。见自检①。 */
const DRIFT_LIMIT = 2;

/** 体积增长上限。见自检⑥。 */
const SIZE_GROWTH_LIMIT = 0.20;

const BABEL_OPTS = {
  presets: [['env', {
    // Chrome 58 = Android 8.0 出厂 WebView（minSdk 26）。
    // 再往下压 async/await 会触发 regenerator 注入，凭空多一层运行期依赖。
    targets: { chrome: '58' },
    modules: false,
  }]],
  // 这些文件是 classic script，不是 module。sourceType 写错会让 Babel 按 ESM
  // 处理（加 "use strict"、按模块作用域提升），20 个 script 共享的全局就断了。
  sourceType: 'script',
  // 行号钉死在源码的行号上 —— 这是整个方案能落地的前提。
  retainLines: true,
  comments: true,
  compact: false,
  babelrc: false,
  configFile: false,
};

function fail(message) {
  console.error('✗ ' + message);
  process.exit(1);
}

function note(message) {
  console.log('  ·  ' + message);
}

function walk(dir, root, out) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    const rel = path.relative(root, full);
    if (entry.isDirectory()) walk(full, root, out);
    else if (entry.isFile()) out.push({ full, rel });
  }
  return out;
}

function shouldTranspile(rel) {
  if (!rel.endsWith('.js')) return false;
  const parts = rel.split(path.sep);
  return !SKIP_DIRS.includes(parts[0]);
}

/** 顶层声明名（函数 + 变量）。见自检②。 */
function topLevelNames(source) {
  const acorn = require(path.resolve(HERE, '../tools/acorn.js'));
  const ast = acorn.parse(source, { ecmaVersion: 2022, sourceType: 'script', allowHashBang: true });
  const names = [];
  for (const node of ast.body) {
    if (node.type === 'FunctionDeclaration' && node.id) names.push(node.id.name);
    if (node.type === 'VariableDeclaration') {
      for (const decl of node.declarations) {
        if (decl.id && decl.id.type === 'Identifier') names.push(decl.id.name);
      }
    }
  }
  return names;
}

/** 首条语句是不是 "use strict"。见自检③。 */
function hasUseStrict(source) {
  return /^\s*(?:\/\/[^\n]*\n|\/\*[\s\S]*?\*\/\s*)*(["'])use strict\1/.test(source);
}

function transpile(srcRoot, outRoot) {
  if (!fs.existsSync(BABEL)) {
    console.error('缺 tools/babel.standalone.min.js（见 tools/VENDOR.md）');
    process.exit(2);
  }
  const babel = require(BABEL);

  console.log('拷贝 ' + srcRoot + ' → ' + outRoot);
  fs.rmSync(outRoot, { recursive: true, force: true });
  fs.cpSync(srcRoot, outRoot, { recursive: true });

  console.log('降级（target chrome58）');
  const entries = walk(outRoot, outRoot, []).sort((a, b) => a.rel.localeCompare(b.rel));
  const skipped = [];
  const table = [];
  let bytesIn = 0;
  let bytesOut = 0;

  for (const entry of entries) {
    if (!entry.rel.endsWith('.js')) continue;
    const source = fs.readFileSync(entry.full, 'utf8');
    if (!shouldTranspile(entry.rel)) {
      skipped.push(entry.rel);
      // 跳过的文件也算进体积账里，否则百分比失真。
      bytesIn += Buffer.byteLength(source);
      bytesOut += Buffer.byteLength(source);
      continue;
    }
    let output;
    try {
      output = babel.transform(source, BABEL_OPTS).code;
    } catch (error) {
      fail(entry.rel + ' 降级失败：' + (error && error.message));
    }
    fs.writeFileSync(entry.full, output);

    const linesIn = source.split('\n').length;
    const linesOut = output.split('\n').length;
    const drift = linesOut - linesIn;
    bytesIn += Buffer.byteLength(source);
    bytesOut += Buffer.byteLength(output);

    // ① 行号对齐
    if (Math.abs(drift) > DRIFT_LIMIT) {
      fail(entry.rel + ' 行号漂移 ' + drift + ' 行（上限 ±' + DRIFT_LIMIT + '）。'
        + 'retainLines 失效了 —— 从那以后所有 js_error 的 where 和堆栈都不可信。');
    }

    // ② 顶层声明不许丢
    let lost = [];
    try {
      const before = topLevelNames(source);
      const after = new Set(topLevelNames(output));
      lost = before.filter(name => !after.has(name));
    } catch (error) {
      fail(entry.rel + ' 顶层声明对比失败：' + (error && error.message));
    }
    if (lost.length) {
      fail(entry.rel + ' 顶层声明丢了 ' + lost.length + ' 个：' + lost.slice(0, 5).join(', ')
        + '。这些是跨 script 共享的全局名，丢了就是 ReferenceError。');
    }

    // ③ 不许注入 "use strict"
    if (hasUseStrict(output) && !hasUseStrict(source)) {
      fail(entry.rel + ' 被注入了 "use strict"。源码是 sloppy mode，加上严格模式会改语义。');
    }

    // ⑤ 残留语法扫描（只注记，解析通过才是有权威的那一条）
    const residue = (output.match(/\?\.|\?\?|\|\|=|&&=|\?\?=/g) || []).length;
    if (residue) note(entry.rel + ' 文本里还有 ' + residue + ' 处 ?./??/逻辑赋值 —— 若解析通过，说明落在字符串里');

    table.push({ rel: entry.rel, linesIn, linesOut, drift });
  }

  // ④ ES2017 可解析（对生成目录里**每一个** .js，含 vendor/hxm）
  console.log('');
  const verdict = check.check(outRoot, true);
  if (verdict !== 0) process.exit(verdict);

  // ⑥ 体积
  const growth = bytesIn ? (bytesOut - bytesIn) / bytesIn : 0;
  if (growth > SIZE_GROWTH_LIMIT) {
    fail('体积增长 ' + (growth * 100).toFixed(1) + '%（上限 ' + (SIZE_GROWTH_LIMIT * 100) + '%）');
  }

  console.log('');
  const width = Math.max(...table.map(row => row.rel.length), 4);
  for (const row of table) {
    console.log('  ' + row.rel.padEnd(width) + '  ' + String(row.linesIn).padStart(5)
      + ' → ' + String(row.linesOut).padStart(5) + '  漂移 ' + String(row.drift).padStart(3));
  }
  if (skipped.length) console.log('  跳过（第三方，仍过解析断言）：' + skipped.join(' '));
  console.log('');
  console.log('  ' + table.length + ' 个文件降级，体积 '
    + (bytesIn / 1024).toFixed(0) + 'KB → ' + (bytesOut / 1024).toFixed(0) + 'KB（+'
    + (growth * 100).toFixed(1) + '%）');
  return 0;
}

if (require.main === module) {
  const args = process.argv.slice(2);
  const get = flag => {
    const index = args.indexOf(flag);
    return index >= 0 ? args[index + 1] : null;
  };
  const checkOnly = get('--check');
  if (checkOnly) {
    if (!fs.existsSync(checkOnly)) {
      console.error('路径不存在: ' + checkOnly);
      process.exit(2);
    }
    process.exit(check.check(checkOnly, false));
  }
  const src = get('--src');
  const out = get('--out');
  if (!src || !out) {
    console.error('用法: transpile-assets.js --src <assets> --out <dir> | --check <dir>');
    process.exit(2);
  }
  // 只校验源目录存在。生成目录是构建产物，第一次跑的时候本来就不在 ——
  // 要求它先存在等于让 clean build 失败。
  if (!fs.existsSync(src)) {
    console.error('--src 路径不存在: ' + src);
    process.exit(2);
  }
  process.exit(transpile(src, out));
}

module.exports = { transpile, BABEL_OPTS, DRIFT_LIMIT };
