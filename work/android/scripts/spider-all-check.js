#!/usr/bin/env node
/**
 * 八家一起跑：扫 `app/src/test/resources/spiders/*.js`，逐个喂给 `spider-check.js`
 * 的同一套沙箱与断言，最后打一张表。
 *
 * ## 这张表是给谁看的
 *
 * 给「我现在能不能删 Java 适配器」这个问题看的。八个源分两组在写，写的时候
 * 有人先交 home、有人只交了骨架。所以这张表的第一职责不是判红绿，是**一眼看出
 * 谁好了谁没好**：
 *
 *   ✓3     三条断言全过
 *   ✗2/5   五条里挂了两条
 *   —      未实现（源里没这个函数）
 *   ⊘      未配置（配了入参没配断言，或者连入参都没有）
 *   ⚠      缺夹具（目录没有 / 路由对不上 / 那个 URL 没有文件）
 *   !      抛错（源自己报的错，比如 missav 要过盾）
 *
 * **只有 ✗ 和 ! 影响退出码。** —／⊘／⚠ 一律不算失败 —— 半成品源是这一轮的
 * 正常状态，让它们把 CI 变红，等于逼所有人忽略这张表。
 *
 * ## 用法
 *
 *   node scripts/spider-all-check.js                 # 跑全部
 *   node scripts/spider-all-check.js --only yhdm,porn91
 *   node scripts/spider-all-check.js --verbose       # 把每个源的失败断言逐条打出来
 *   node scripts/spider-all-check.js --json          # 机器可读（给别的脚本吃）
 *
 * 退出码：0 没有源在「已配置断言」上失败 / 1 有源失败 / 2 环境问题（目录不存在）
 */
'use strict';

const fs = require('fs');
const path = require('path');

const HERE = __dirname;
const {
  CAPS,
  FIXTURES,
  describeProfile,
  listSpiders,
  runSpider,
} = require('./spider-check.js');

// ── 参数 ────────────────────────────────────────────────────────────────────
const argv = process.argv.slice(2);

function flag(name) {
  return argv.indexOf('--' + name) >= 0;
}

function value(name) {
  const i = argv.indexOf('--' + name);
  return i >= 0 ? argv[i + 1] : null;
}

const verbose = flag('verbose');
const asJson = flag('json');
const onlyRaw = value('only');
const only = onlyRaw ? onlyRaw.split(',').map(s => s.trim()).filter(Boolean) : null;

const SPIDER_DIR = path.join(FIXTURES, 'spiders');
if (!fs.existsSync(SPIDER_DIR)) {
  console.error('✗ spiders 目录不存在: ' + SPIDER_DIR);
  process.exit(2);
}

// ── 单元格文案 ──────────────────────────────────────────────────────────────
//
// 宽度是固定的：这张表会被贴进聊天和 issue，列一歪就没法扫读了。
const WIDTH = 7;

/** 每个 status 一个短标记。表头里有一行图例，别让读的人去翻源码。 */
function cell(entry) {
  if (!entry) return pad('—');
  switch (entry.status) {
    case 'pass':
      return pad('✓' + entry.checks.length);
    case 'fail':
      return pad('✗' + entry.failed + '/' + entry.checks.length);
    case 'unimplemented':
      return pad('—');
    case 'not-configured':
      return pad('⊘');
    case 'no-fixtures':
      return pad('⚠');
    case 'error':
      return pad('!');
    default:
      return pad('?');
  }
}

/**
 * 「不该红」的 status：半成品与缺夹具不是回归失败。
 *
 * 单列成函数是因为它有两个用处 —— 判退出码，和判「备注里要不要写抛错」。
 */
function countsAsFailure(entry) {
  if (!entry) return false;
  return entry.status === 'fail' || entry.status === 'error';
}

/**
 * 宽度按**显示宽度**算，不是 length。
 *
 * ✓ ✗ — ⊘ ⚠ 这些在等宽字体里占两格，`String.length` 只算一格 —— 直接 padEnd
 * 出来的表在终端里是歪的（第一版就是这么歪的）。这里按字符是否在「宽字符区」
 * 逐个加宽。
 */
function displayWidth(s) {
  let width = 0;
  for (const ch of String(s)) {
    const code = ch.codePointAt(0);
    // 这些符号在中文字体里按全角渲染
    if (code >= 0x1100 && (
      code <= 0x115f
      || code === 0x2329 || code === 0x232a
      || (code >= 0x2e80 && code <= 0xa4cf)
      || (code >= 0xac00 && code <= 0xd7a3)
      || (code >= 0xf900 && code <= 0xfaff)
      || (code >= 0xfe30 && code <= 0xfe6f)
      || (code >= 0xff00 && code <= 0xff60)
      || (code >= 0xffe0 && code <= 0xffe6)
    )) width += 2;
    else width += 1;
  }
  return width;
}

function pad(s, width) {
  const target = width === undefined ? WIDTH : width;
  const gap = target - displayWidth(s);
  return s + ' '.repeat(Math.max(0, gap));
}

/** 失败的第一句：断言不过用断言标签，抛错用错误首行。备注那一列只放一句。 */
function noteOf(result) {
  if (result.error) return '执行失败: ' + result.error;
  if (!result.configured) return '未配置回归（源可能还在写）';

  const parts = [];
  for (const cap of CAPS) {
    const entry = result.caps[cap];
    if (!entry) continue;
    if (entry.status === 'error') {
      parts.push(cap + ': ' + entry.error);
    } else if (entry.status === 'fail' && entry.firstFailure) {
      parts.push(cap + ': ' + entry.firstFailure.label);
    } else if (entry.status === 'unimplemented') {
      parts.push(cap + ' 未实现');
    } else if (entry.status === 'not-configured') {
      // 「没配断言」在 agedm / cms / openlist 上会连着出现五次，压成一句话。
      // 但只有**每一格都没配**时才压 —— 否则 missav 这种「只有 search 没配」
      // 会被误读成整个源没配（第一版就是这么写的，表上看着像全空）。
      const everyCellBare = CAPS.every(one => {
        const cell = result.caps[one];
        return !cell || cell.status === 'not-configured';
      });
      if (everyCellBare) {
        if (!parts.some(one => one.indexOf('全部未配置') === 0)) parts.push('全部未配置');
      } else {
        parts.push(cap + ' 未配置');
      }
    }
  }

  if (!result.hasFixtures && result.configured) {
    parts.push('缺夹具目录 ' + fixtureDirName(result.fixtureDir));
  }
  if (result.missed && result.missed.length) {
    parts.push('缺 ' + result.missed.length + ' 份夹具');
  }
  if (!parts.length) return '—';
  return parts.join('；');
}

/** 只留 `resources/` 之后那一段 —— 绝对路径在表里太吵。 */
function fixtureDirName(dir) {
  const marker = 'resources/';
  const at = String(dir || '').indexOf(marker);
  return at >= 0 ? String(dir).slice(at + marker.length) : String(dir || '');
}

// ── 跑 ──────────────────────────────────────────────────────────────────────
(async () => {
  const files = listSpiders(SPIDER_DIR);
  const results = [];

  // 进度行走 stderr，一行覆盖（\r），跑完清掉 —— stdout 留给那张表。
  //
  // 为什么必须有：最坏情况 8 源 × 5 cap × 5s = 200 秒，而中间一段是完全静默的。
  // 静默 60 秒之后人分不清「在跑」和「挂了」，只会去 kill —— 那这一步的看门狗
  // 就白装了。一行「正在跑 x/N 源名 cap」成本几乎为零，能把这个问题消掉。
  const total = files.filter(file => {
    const id = path.basename(file, '.js');
    return !only || only.includes(id);
  }).length;

  const progress = (line) => {
    if (!process.stderr.isTTY) return;   // 重定向到文件时不刷 \r，免得日志变脏
    process.stderr.write('\r\x1b[K' + line);
  };

  let done = 0;
  for (const file of files) {
    const id = path.basename(file, '.js');
    if (only && !only.includes(id)) continue;
    progress('正在跑 ' + (done + 1) + '/' + total + '  ' + id + ' …');
    // isolate: 每个能力单独起子进程。理由见 spider-check.js 的 runIsolated ——
    // 一家写出同步死循环，不能让整张表打不出来。
    const result = await runSpider(file, { isolate: true });
    results.push(result);
    done++;
    progress('跑完 ' + done + '/' + total + '  ' + id);
  }
  if (process.stderr.isTTY) process.stderr.write('\r\x1b[K');

  if (asJson) {
    console.log(JSON.stringify(results.map(one => ({
      id: one.id,
      version: one.version,
      ok: one.ok,
      error: one.error,
      configured: one.configured,
      hasFixtures: one.hasFixtures,
      caps: Object.keys(one.caps).reduce((acc, cap) => {
        const entry = one.caps[cap];
        acc[cap] = {
          status: entry.status,
          failed: entry.failed,
          total: entry.checks.length,
          error: entry.error,
          failedLabels: entry.checks.filter(c => !c.ok).map(c => c.label),
        };
        return acc;
      }, {}),
    })), null, 2));
    process.exit(0);
  }

  // ── 表头 ────────────────────────────────────────────────────────────────
  const header = [
    pad('源', 10),
    ...CAPS.map(cap => pad(cap, 9)),
    '备注',
  ].join(' ');
  const rule = '─'.repeat(displayWidth(header));

  console.log('');
  console.log(header);
  console.log(rule);

  let failing = 0;
  let configuredSources = 0;
  let readySources = 0;

  for (const result of results) {
    const cells = [pad(result.id, 10)];
    for (const cap of CAPS) cells.push(pad(cell(result.caps[cap]), 9));
    cells.push(noteOf(result));
    console.log(cells.join(' '));

    if (result.configured) configuredSources++;
    let anyFailure = false;
    for (const cap of CAPS) {
      if (countsAsFailure(result.caps[cap])) anyFailure = true;
    }
    if (anyFailure || !result.ok) failing++;
    if (result.ok && result.configured && !anyFailure) readySources++;
  }

  console.log(rule);
  console.log('  ✓N 全过   ✗N/M 挂了 N 条(M 是总条数)   — 未实现   ⊘ 未配置   ⚠ 缺夹具   ! 抛错');
  console.log('  失败只看 ✗ 和 ! —— 未实现/未配置/缺夹具是半成品的正常状态，不算回归失败');
  console.log('');

  // ── 每个源配了什么 ──────────────────────────────────────────────────────
  console.log('每个源的回归配置：');
  for (const result of results) {
    const mark = result.ok && result.configured ? ' ' : ' ';
    console.log(' ' + mark + pad(result.id, 10) + describeProfile(result.id));
  }
  console.log('');

  // ── 夹具盘点 ────────────────────────────────────────────────────────────
  //
  // 「哪个源缺夹具」必须单独列出来：这就是接下来要补的东西，混在表格里看不见。
  console.log('夹具盘点：');
  for (const result of results) {
    if (!result.configured) {
      console.log('  ' + pad(result.id, 10) + '未配置回归（无夹具需求）');
      continue;
    }
    if (!result.hasFixtures) {
      console.log('  ' + pad(result.id, 10) + '缺整个夹具目录 ' + fixtureDirName(result.fixtureDir));
      continue;
    }
    const files = result.fixtureFiles || [];
    if (!files.length) {
          console.log('  ' + pad(result.id, 10) + '夹具目录是空的 ' + fixtureDirName(result.fixtureDir));
      continue;
    }
    const missed = result.missed || [];
    const line = files.length + ' 份夹具';
    if (missed.length) {
      const urls = missed.map(one => one.url).slice(0, 3).join(' , ');
      console.log('  ' + pad(result.id, 10) + line + '；还有 ' + missed.length
        + ' 个请求没有路由（如 ' + urls + '）');
    } else {
      console.log('  ' + pad(result.id, 10) + line + '；全部请求都有路由');
    }
  }
  console.log('');

  // ── verbose：逐条失败 ───────────────────────────────────────────────────
  if (verbose && failing) {
    console.log('失败明细：');
    for (const result of results) {
      for (const cap of CAPS) {
        const entry = result.caps[cap];
        if (!entry) continue;
        if (entry.status === 'error') {
          console.log('  ' + result.id + ' · ' + cap + ' 抛错：' + entry.error);
          for (const one of entry.requested || []) {
            console.log('      请求过：' + one.method + ' ' + one.url);
          }
          continue;
        }
        if (entry.status !== 'fail') continue;
        console.log('  ' + result.id + ' · ' + cap);
        for (const one of entry.checks) {
          if (one.ok) continue;
          console.log('      ✗ ' + one.label);
        }
        if (entry.firstFailure) {
          console.log('      实际: ' + entry.firstFailure.actual);
        }
      }
    }
    console.log('');
  }

  // ── 结论 ────────────────────────────────────────────────────────────────
  console.log('共 ' + results.length + ' 个源，' + configuredSources + ' 个配了回归，'
    + readySources + ' 个全过，' + failing + ' 个失败');

  process.exit(failing ? 1 : 0);
})();
