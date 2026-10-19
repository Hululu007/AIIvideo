#!/usr/bin/env node
/**
 * web.spider 的两种写法 → id。
 *
 * ## 为什么这个要单独测
 *
 * `web.spider` 在 2026-10-19 之前存的是纯 id（"yhdm"），之后改成源码地址
 * （"https://…/yhdm.js"）—— 因为 TVBox 的 sites[] 就是这么做的：每条配置自己
 * 声明源码在哪，而不是靠「配置和源码同目录」这种约定去猜。
 *
 * 两种都得认：已经发出去的配置里写的是 id，用户手里那些不能废掉。
 *
 * 这段逻辑有两个消费者，**规则必须一字不差**：
 *
 *   app.js 的 spiderIdOf()      → 前端拿它去调 spider 桥（要 id，不是 url）
 *   setup.js 的 addOneSource()  → 装源时判断「这是地址还是 id」
 *
 * 两边写岔了的表现很难查：装源时说装好了、点进去报「找不到 spider: undefined」。
 *
 * ## 用法
 *
 *   node scripts/spider-id-check.js
 *
 * 退出码 0 全过 / 1 有断言失败
 */
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const HERE = __dirname;
const APP_JS = path.resolve(HERE, '../app/src/main/assets/app.js');

/** 从 app.js 里把 spiderIdOf 抽出来跑 —— 不抄一份，抄的那份永远绿灯。 */
function loadFromAppJs() {
  const src = fs.readFileSync(APP_JS, 'utf8');
  const start = src.indexOf('function spiderIdOf()');
  if (start < 0) throw new Error('app.js 里找不到 spiderIdOf()');
  // 找到函数体配平的右花括号
  let depth = 0;
  let i = src.indexOf('{', start);
  const open = i;
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++;
    else if (src[i] === '}') {
      depth--;
      if (depth === 0) { i++; break; }
    }
  }
  const body = src.substring(start, i);
  const sandbox = { window: { HG_SITE: {} } };
  vm.createContext(sandbox);
  vm.runInContext(body + '\n;this.__f = spiderIdOf;', sandbox);
  return (site) => {
    sandbox.window.HG_SITE = site;
    return sandbox.__f();
  };
}

const spiderIdOf = loadFromAppJs();

const CASES = [
  // [输入 web.spider, 期望 id, 说明]
  ['https://149.104.21.177.sslip.io/spiders/yhdm.js', 'yhdm', '源码地址 → id'],
  ['https://example.com/a/b/mrds66.js', 'mrds66', '深层目录'],
  ['https://example.com/a/b/missav.js?v=2', 'missav', '带查询串'],
  ['https://example.com/a/b/porn91.js#x', 'porn91', '带 fragment'],
  ['https://example.com/a/b/agedm.JS', 'agedm', '大写后缀'],
  ['https://example.com/a/b/cms.js/', 'cms', '地址结尾多一个斜杠'],
  ['yhdm', 'yhdm', '纯 id（旧写法）'],
  ['  yhdm  ', 'yhdm', '前后空格'],
  ['', '', '空串'],
];

let failed = 0;
for (const [input, want, label] of CASES) {
  let got;
  try {
    got = spiderIdOf({ spider: input });
  } catch (error) {
    got = 'THREW: ' + error.message;
  }
  const ok = got === want;
  if (!ok) failed++;
  console.log((ok ? '  ok   ' : '  FAIL ') + label
    + '  ' + JSON.stringify(input) + ' → ' + JSON.stringify(got)
    + (ok ? '' : '（期望 ' + JSON.stringify(want) + '）'));
}

// 「没有 web.spider」这种配置（REST 源）必须得到空串，前端据此走老路。
const noField = spiderIdOf({});
if (noField !== '') { failed++; console.log('  FAIL 没有 spider 字段 → ' + JSON.stringify(noField)); }
else console.log('  ok   没有 spider 字段 → ""');

if (failed) {
  console.error('\n✗ ' + failed + ' 条失败');
  process.exit(1);
}
console.log('\n全部通过');
