#!/usr/bin/env node
/**
 * spider 离线回归：把 assets/spiders 里的源放进一个**没有 DOM** 的沙箱，喂本地
 * fixture，看它算出来的东西对不对。
 *
 * ## 为什么是这个形状
 *
 * 阶段 1 的验收是「spider 的输出和现有 Java 适配器逐字段一致」。要让这句话可执行，
 * 只有一条路：同一个 HTML，两边各跑一次，比数字。Java 那边是 JUnit（`YhdmAdapterTest`），
 * 这边就是本脚本 —— 喂的是同一批 fixture（`app/src/test/resources/yhdm/`）。
 *
 * 沙箱里**故意不给** `document` / `window` / `DOMParser`：spider 一旦用了 DOM API，
 * 这脚本就跑不了，而那意味着这个源从此没有离线回归，只能靠真机点。约束用沙箱
 * 强制，不靠约定。
 *
 * ## 用法
 *
 *   node scripts/spider-check.js app/src/test/resources/spiders/yhdm.js
 *   node scripts/spider-check.js app/src/test/resources/spiders/yhdm.js --dump home   # 看原始输出
 *
 * 退出码：0 全过 / 1 有断言失败 / 2 环境问题（文件不存在、spider 抛错）
 */
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const HERE = __dirname;
const FIXTURES = path.resolve(HERE, '../app/src/test/resources/yhdm');

/** URL 片段 → fixture 文件。命中的第一条生效；都没命中会打印出来，方便补。 */
const ROUTES = [
  { match: /\/_get_plays\//, file: 'plays.json' },
  { match: /\/vod\/\d+/, file: 'detail-2026476948.html' },
  { match: /\/list\/|\/show\//, file: 'list.html' },
  { match: /yhdm\.one\/?(?:$|\?|#)/, file: 'home.html' },
];

/** 每个能力跑完之后要满足的断言。数字来自 YhdmAdapterTest 的既有断言。 */
const CHECKS = {
  home: [
    ['卡片数 ≥ 40', r => r.items.length >= 40],
    ['id 是数字', r => /^\d+$/.test(String(r.items[0].id))],
    ['标题非空', r => String(r.items[0].title || '').length > 0],
    ['封面是绝对地址', r => /^https?:\/\//.test(String(r.items[0].cover || ''))],
  ],
  detail: [
    ['标题对得上', r => String(r.title || '').indexOf('仙逆剧场版') >= 0],
    ['简介非空', r => String(r.description || '').length > 0],
    ['剧集 3 条', r => (r.episodes || []).length === 3],
    ['第一集是 ep1', r => String((r.episodes || [])[0] && r.episodes[0].ref) === 'ep1'],
    ['第一集标题「第01集」', r => String((r.episodes || [])[0] && r.episodes[0].title) === '第01集'],
    ['封面落在 /cover/2026476948', r => /\/cover\/2026476948/.test(String(r.cover || ''))],
  ],
  play: [
    ['地址是 m3u8', r => /\.m3u8/.test(String(r.url || ''))],
    ['至少有 1 条备选', r => (r.alts || []).length >= 1],
  ],
};

function fail(message) {
  console.error('✗ ' + message);
  process.exit(2);
}

const spiderPath = process.argv[2];
if (!spiderPath) fail('用法: node scripts/spider-check.js <spider.js> [--dump cap]');
const dumpIdx = process.argv.indexOf('--dump');
const dumpCap = dumpIdx > 0 ? process.argv[dumpIdx + 1] : null;

const source = fs.readFileSync(path.resolve(process.cwd(), spiderPath), 'utf8');

/** 记录所有请求过的 URL：没命中 fixture 时，这些就是你要补的规则。 */
const requested = [];

function bodyFor(url) {
  requested.push(url);
  for (const route of ROUTES) {
    if (!route.match.test(url)) continue;
    const file = path.join(FIXTURES, route.file);
    if (!fs.existsSync(file)) fail('fixture 不存在: ' + file);
    return fs.readFileSync(file, 'utf8');
  }
  return null;
}

/**
 * spider 能看到的全部能力：`call(op, args)` 是**规范形式**，`net` / `log` 是糖。
 *
 * 两套都给是有原因的：`call` 是宿主页里那个真对象（`window.__spider`），spider 里
 * 写的 `ctx.call('net', …)` 在真机和这里必须走同一条路；而 `ctx.net(…)` 读起来
 * 像普通函数，写源的人更愿意用它。宿主页也提供这两个糖，所以两边一致 —— 糖是
 * 宿主页的一行箭头函数，不是 spider 自己拼的。
 *
 * 刻意没有 window / document / DOMParser：spider 一用 DOM API，这个脚本就跑不了，
 * 等于这个源从此没有离线回归。约束用沙箱强制，不靠约定。
 */
const ctx = {
  async call(op, args) {
    const a = args || {};
    if (op === 'net') {
      const url = String(a.url || '');
      const body = bodyFor(url);
      if (body == null) return { ok: false, error: '离线回归里没有这个 URL 的 fixture：' + url };
      return { ok: true, status: 200, body };
    }
    if (op === 'log') {
      if (a.msg) console.error('  [spider] ' + a.msg);
      return { ok: true };
    }
    return { ok: false, error: '离线回归不支持 op=' + op };
  },
  net(args) { return this.call('net', args); },
  log(args) { return this.call('log', args); },
};

const sandbox = { __spiderSite: null, console: { log() {}, error() {} } };
vm.createContext(sandbox);
try {
  vm.runInContext(source, sandbox, { filename: spiderPath });
} catch (error) {
  fail('spider 执行失败：' + error.message);
}
const site = sandbox.__spiderSite;
if (!site) fail('spider 没有导出 __spiderSite');
if (!Array.isArray(site.hosts)) fail('spider 必须声明 hosts 数组');

(async () => {
  console.error('spider: ' + site.id + ' v' + site.version + ' requires=' + site.requires
    + ' hosts=' + JSON.stringify(site.hosts));

  let failed = 0;
  for (const cap of Object.keys(CHECKS)) {
    if (typeof site[cap] !== 'function') {
      console.error('✗ ' + cap + ' 没有实现');
      failed++;
      continue;
    }
    let result;
    try {
      result = await site[cap](ctx, cap === 'home' ? {} : (cap === 'detail'
        ? { ref: '2026476948' }
        : { ref: '2026476948', line: (site.__lineName || 'lz') }));
    } catch (error) {
      console.error('✗ ' + cap + ' 抛错：' + (error && error.message));
      failed++;
      continue;
    }
    if (dumpCap === cap) {
      console.log(JSON.stringify(result, null, 2));
      continue;
    }
    for (const [label, predicate] of CHECKS[cap]) {
      let ok = false;
      try { ok = !!predicate(result || {}); } catch (error) { ok = false; }
      if (!ok) {
        failed++;
        console.error('✗ ' + cap + ' · ' + label);
        console.error('   实际: ' + JSON.stringify(result).slice(0, 400));
      }
    }
    if (failed === 0) console.error('✓ ' + cap + ' ' + CHECKS[cap].length + ' 条');
  }

  if (requested.length && failed) {
    console.error(' 蜘蛛请求过的 URL：');
    for (const url of requested) console.error('   ' + url);
  }
  if (failed) {
    console.error('✗ 共 ' + failed + ' 条失败');
    process.exit(1);
  }
  console.error('全部通过');
})();
