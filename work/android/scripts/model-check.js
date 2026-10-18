#!/usr/bin/env node
/**
 * 模型转换层离线回归：spider 的**原始输出**喂给 HGModel，看它变成的形状是不是
 * 前端今天真在读的那副。
 *
 * ## 和 scripts/spider-check.js 的分工
 *
 * 两个脚本用同一批 fixture（`app/src/test/resources/yhdm/`）、同一张 ROUTES 表，
 * 但断言的对象不是同一个东西：
 *
 *   spider-check.js   → 断言 **spider 的解析**对不对（卡片数、ref、封面落在哪个目录）。
 *                       它出问题时你要改的是 yhdm.js 里的正则。
 *   model-check.js    → 断言 **转换层的映射**对不对（no→episodeNumber、ref→id、
 *                       缺字段是不是空串）。它出问题时你要改的是 model.js。
 *
 * 所以 ROUTES 是**故意抄两份**的，不是忘了抽公共模块。抽出去之后，任何一次
 * 「补一条 fixture 路由」都会同时移动两个脚本的基线 —— 那时你分不清是解析变了
 * 还是映射变了，而这两件事的修法完全不同（正则 vs 字段名）。抄两份的代价是
 * 补 fixture 要改两处，收益是这个脚本单独跑还能定位到层。
 *
 * ## 用法
 *
 *   node scripts/model-check.js
 *   node scripts/model-check.js --dump detail    # 看转换后的完整形状
 *
 * 退出码：0 全过 / 1 有断言失败 / 2 环境问题（文件缺失、沙箱里拿不到 HGModel）
 */
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const HERE = __dirname;
const MODEL_PATH = path.resolve(HERE, '../app/src/main/assets/model.js');
const SPIDER_PATH = path.resolve(HERE, '../app/src/test/resources/spiders/yhdm.js');
const FIXTURES = path.resolve(HERE, '../app/src/test/resources/yhdm');

/**
 * URL 片段 → fixture 文件。和 spider-check.js 里那张表一致（为什么抄两份见文件头）。
 * 命中的第一条生效。
 */
const ROUTES = [
  { match: /\/_get_plays\//, file: 'plays.json' },
  { match: /\/vod\/\d+/, file: 'detail-2026476948.html' },
  { match: /\/list\/|\/show\//, file: 'list.html' },
  { match: /yhdm\.one\/?(?:$|\?|#)/, file: 'home.html' },
];

function fail(message) {
  console.error('✗ ' + message);
  process.exit(2);
}

const dumpIdx = process.argv.indexOf('--dump');
const dumpCap = dumpIdx > 0 ? process.argv[dumpIdx + 1] : null;

if (!fs.existsSync(MODEL_PATH)) fail('model.js 不存在: ' + MODEL_PATH);
if (!fs.existsSync(SPIDER_PATH)) fail('yhdm.js 不存在: ' + SPIDER_PATH);

// ── 沙箱一：model.js ─────────────────────────────────────────────────────
// 只给一个 `window` 空壳。不给 document / fetch / localStorage，也不给
// HG_SITE —— 转换层一旦偷偷读了配置，这里立刻 TypeError，而不是等真机上
// 换个源才悄悄走错分支。
const modelWindow = {};
const modelSandbox = { window: modelWindow };
vm.createContext(modelSandbox);
try {
  vm.runInContext(fs.readFileSync(MODEL_PATH, 'utf8'), modelSandbox, { filename: 'model.js' });
} catch (error) {
  fail('model.js 执行失败：' + error.message);
}
const HGModel = modelWindow.HGModel;
if (!HGModel) fail('model.js 没有导出 window.HGModel');
['card', 'paged', 'detail', 'play', 'fromBridge'].forEach(name => {
  if (typeof HGModel[name] !== 'function') fail('HGModel.' + name + ' 不是函数');
});

// ── 沙箱二：spider（没有 DOM，和 spider-check.js 同款）────────────────────
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

const ctx = {
  async call(op, args) {
    const a = args || {};
    if (op === 'net') {
      const url = String(a.url || '');
      const body = bodyFor(url);
      if (body == null) return { ok: false, error: '离线回归里没有这个 URL 的 fixture：' + url };
      return { ok: true, status: 200, body: body };
    }
    if (op === 'log') return { ok: true };
    return { ok: false, error: '离线回归不支持 op=' + op };
  },
  net(args) { return this.call('net', args); },
  log(args) { return this.call('log', args); },
};

const spiderSandbox = { __spiderSite: null, console: { log() {}, error() {} } };
vm.createContext(spiderSandbox);
try {
  vm.runInContext(fs.readFileSync(SPIDER_PATH, 'utf8'), spiderSandbox, { filename: 'yhdm.js' });
} catch (error) {
  fail('spider 执行失败：' + error.message);
}
const site = spiderSandbox.__spiderSite;
if (!site) fail('spider 没有导出 __spiderSite');

// ── 断言工具 ────────────────────────────────────────────────────────────
let failed = 0;

function check(label, predicate, actual) {
  let ok = false;
  try { ok = !!predicate(); } catch (error) { ok = false; }
  if (ok) return;
  failed++;
  console.error('✗ ' + label);
  console.error('   实际: ' + JSON.stringify(actual).slice(0, 500));
}

/**
 * undefined 扫描：整个形状序列化之后不许出现 undefined 值。
 *
 * 为什么单独做一条断言：`JSON.stringify` 会把 `{a: undefined}` 静默变成 `{}`，
 * 所以「少一个键」和「值是 undefined」在 dump 里长得一模一样，肉眼看不出来，
 * 而模板里 `undefined` 会渲染成五个字母（2026-10-18 的选集格子）。
 * 这里直接走一遍对象树，把 undefined / NaN 抓出来。
 */
function findUndefined(value, trail) {
  const where = trail || '$';
  if (value === undefined) return where;
  if (typeof value === 'number' && !isFinite(value)) return where + ' (非有限数)';
  if (Array.isArray(value)) {
    for (let i = 0; i < value.length; i++) {
      const hit = findUndefined(value[i], where + '[' + i + ']');
      if (hit) return hit;
    }
    return null;
  }
  if (value && typeof value === 'object') {
    for (const key of Object.keys(value)) {
      const hit = findUndefined(value[key], where + '.' + key);
      if (hit) return hit;
    }
  }
  return null;
}

function checkNoUndefined(label, shape) {
  check(label + ' · 没有 undefined', () => findUndefined(shape) === null, {
    where: findUndefined(shape)
  });
}

// ── 跑三条能力，喂转换层 ────────────────────────────────────────────────
(async () => {
  console.error('model: HGModel + ' + site.id + ' v' + site.version);

  // ---- home → paged ----
  const homeRaw = await site.home(ctx, {});
  const home = HGModel.fromBridge(homeRaw, 'home');
  if (dumpCap === 'home') { console.log(JSON.stringify(home, null, 2)); return; }

  // home 不是 Paged，是**首页信封**：renderHome 读 featured / recommend.items /
  // newest.items 这几处，少一个就是一块空白（2026-10-19 真机首页只有横幅）。
  check('home · 是信封不是 Paged', () => Array.isArray(home.recommend.items), Object.keys(home));
  check('home · featured 非空', () => home.featured.length > 0, { length: home.featured.length });
  check('home · 卡片数 ≥ 40', () => home.recommend.items.length >= 40, { length: home.recommend.items.length });
  check('home · newest 与 recommend 同源', () => home.newest.items.length === home.recommend.items.length, {});
  const home0 = home.recommend.items[0];
  check('home · 第一张 id 非空', () => String(home0.id).length > 0, home0);
  check('home · 第一张 title 非空', () => String(home0.title).length > 0, home0);
  check('home · 第一张 cover 是绝对地址',
    () => /^https?:\/\//.test(String(home0.cover)), home0.cover);
  check('home · 第一张 episodeLabel 是字符串（不是 undefined）',
    () => typeof home0.episodeLabel === 'string', home0.episodeLabel);
  checkNoUndefined('home', home);

  // ---- detail → Detail ----
  const detailRaw = await site.detail(ctx, { ref: '2026476948' });
  const detail = HGModel.fromBridge(detailRaw, 'detail');
  if (dumpCap === 'detail') { console.log(JSON.stringify(detail, null, 2)); return; }

  check('detail · 标题对得上',
    () => String(detail.title).indexOf('仙逆剧场版') >= 0, detail.title);
  check('detail · 简介非空', () => String(detail.description).length > 0, detail.description);
  check('detail · episodes[0].episodeNumber === 1',
    () => detail.episodes[0].episodeNumber === 1, detail.episodes[0]);
  check('detail · episodes[0].number === 1（renderDetail 读的是这个键）',
    () => detail.episodes[0].number === 1, detail.episodes[0]);
  check('detail · episodes[0].title === 「第01集」',
    () => detail.episodes[0].title === '第01集', detail.episodes[0]);
  check('detail · episodes[0].ref 原样保留（ep1）',
    () => detail.episodes[0].ref === 'ep1', detail.episodes[0]);
  check('detail · episodes[0].id 回落到 ref', () => detail.episodes[0].id === 'ep1', detail.episodes[0]);
  check('detail · episodeCount === 3', () => detail.episodeCount === 3, detail.episodeCount);
  check('detail · episodeLabel 是「共 3 集」',
    () => detail.episodeLabel === '共 3 集', detail.episodeLabel);
  check('detail · lines 非空', () => detail.lines.length > 0, detail.lines);
  check('detail · lines[0] 有 line 和 name',
    () => String(detail.lines[0].line).length > 0 && String(detail.lines[0].name).length > 0,
    detail.lines[0]);
  check('detail · 封面是绝对地址',
    () => /^https?:\/\//.test(String(detail.cover)), detail.cover);
  checkNoUndefined('detail', detail);

  // ---- play → Play ----
  const playRaw = await site.play(ctx, { ref: '2026476948', line: 'lz' });
  const played = HGModel.fromBridge(playRaw, 'play');
  if (dumpCap === 'play') { console.log(JSON.stringify(played, null, 2)); return; }

  check('play · url 是 m3u8', () => /\.m3u8/.test(String(played.url)), played.url);
  check('play · lines.length ≥ 1', () => played.lines.length >= 1, played.lines.length);
  check('play · 默认 url 在 lines 里',
    () => played.lines.some(one => one.url === played.url), played.lines.map(one => one.url));
  check('play · lines 每条都有 url 和线路名',
    () => played.lines.every(one => String(one.url).length > 0 && String(one.line).length > 0),
    played.lines);
  checkNoUndefined('play', played);

  // ---- 反例：未知能力名必须抛 ----
  let threw = false;
  try { HGModel.fromBridge({}, 'nope'); } catch (error) { threw = true; }
  check('fromBridge({}, \'nope\') 抛错', () => threw === true, { threw });

  // ---- 反例：空 payload 不许出 undefined（各能力都要能兜住）----
  checkNoUndefined('card({})', HGModel.card({}));
  checkNoUndefined('detail({})', HGModel.detail({}));
  checkNoUndefined('play({})', HGModel.play({}));
  checkNoUndefined('paged({})', HGModel.paged({}));

  if (failed) {
    console.error('✗ 共 ' + failed + ' 条失败');
    if (requested.length) {
      console.error('  spider 请求过的 URL：');
      for (const url of requested) console.error('   ' + url);
    }
    process.exit(1);
  }
  console.error('全部通过');
})();
