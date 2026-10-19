#!/usr/bin/env node
/**
 * 源市场自检：一条命令确认「用户贴这个地址，八条能不能都装得上」。
 *
 * ## 为什么要这个
 *
 * 市场文件、源配置、spider 源码是**三层引用**：
 *
 *   市场 {sources:[{url}]}  →  源配置 {web:{spider: '<js 地址>'}}  →  js 源码
 *
 * 任何一层写错，症状都是「用户点了，然后什么都没有」—— 而三层里每一层单独看
 * 都是合法 JSON。2026-10-19 就踩过：市场里写的是 /admin/...（后台界面，401），
 * 配置里 web.spider 还是纯 id（靠同目录猜），两边都没人报错。
 *
 * 所以这个脚本把三层全走一遍：
 *
 *   1. 市场 JSON 能解析，每条有 name 和 https url
 *   2. 每条 url 能 200 拿到配置
 *   3. 配置里的 web.spider 是个 https 地址，能 200 拿到
 *   4. 拿到的 js 在沙箱里能加载、导出 __spiderSite、id 和文件名对得上
 *   5. js 声明的 hosts 非空（net 白名单，空了等于这个源什么都抓不到）
 *
 * ## 用法
 *
 *   node scripts/market-check.js <市场地址>
 *   node scripts/market-check.js https://149.104.21.177.sslip.io/market-spider.json
 *
 * 退出码：0 全过 / 1 有失败
 */
'use strict';

const vm = require('vm');

const marketUrl = process.argv[2];
if (!marketUrl) {
  console.error('用法: node scripts/market-check.js <市场地址>');
  process.exit(2);
}

async function getJson(url) {
  const res = await fetch(url, { redirect: 'follow' });
  if (!res.ok) throw new Error('HTTP ' + res.status);
  const text = await res.text();
  return JSON.parse(text);
}

async function getText(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error('HTTP ' + res.status);
  return await res.text();
}

/** 在沙箱里跑一遍 js，确认它能导出 __spiderSite。和真机的宿主页是同一套约束。 */
function loadSpider(source, expectId) {
  const sandbox = { __spiderSite: null, console: { log() {}, error() {} } };
  vm.createContext(sandbox);
  vm.runInContext(source, sandbox, { filename: expectId + '.js' });
  const site = sandbox.__spiderSite;
  if (!site) throw new Error('没有导出 __spiderSite');
  if (!Array.isArray(site.hosts) || site.hosts.length === 0) {
    throw new Error('hosts 是空的（net 白名单为空 = 这个源什么都抓不到）');
  }
  // 必需的五条：一个源少了任何一条，前端都会在某个页面撞上一句「没有实现」。
  const required = ['home', 'category', 'search', 'detail', 'play'];
  // 可选的两条（标签清单 / 类型列表）。不是每个站都有标签体系，缺了不算错，
  // 但**有的话要能看见** —— 91porn 就是补了 tags/tagList 却没人从这份检查里
  // 看出来（原来固定数 5，多实现了也显示 5）。
  const optional = ['tags', 'tagList'];
  const caps = required.filter(c => typeof site[c] === 'function');
  const extraCaps = optional.filter(c => typeof site[c] === 'function');
  return { site, caps, extraCaps };
}

(async () => {
  let failed = 0;
  const fail = msg => { failed++; console.log('  FAIL  ' + msg); };
  const ok = msg => console.log('  ok    ' + msg);

  console.log('市场：' + marketUrl);

  let market;
  try {
    market = await getJson(marketUrl);
  } catch (error) {
    console.error('  FAIL  市场取不到：' + error.message);
    process.exit(1);
  }
  if (!Array.isArray(market.sources) || market.sources.length === 0) {
    console.error('  FAIL  sources 不是非空数组');
    process.exit(1);
  }
  ok(market.sources.length + ' 条源，updated=' + (market.updated || '(未写)'));

  for (const item of market.sources) {
    const name = String(item.name || '(无名)');
    const url = String(item.url || '');
    console.log('\n[' + name + ']');
    if (!url.startsWith('https://')) { fail('url 不是 https：' + url); continue; }
    if (/\/admin\//.test(url)) { fail('url 指向后台界面（挂 basic_auth，App 拉不到）：' + url); continue; }

    let cfg;
    try {
      cfg = await getJson(url);
    } catch (error) {
      fail('配置取不到 ' + url + '（' + error.message + '）');
      continue;
    }
    const spider = String((cfg.web && cfg.web.spider) || '').trim();
    if (!spider) { fail('配置里没有 web.spider（这是 REST 源？不该出现在这份市场里）'); continue; }
    if (!spider.startsWith('https://')) {
      fail('web.spider 不是地址（旧写法）：' + spider + ' —— 应该写成完整 js 地址');
      continue;
    }
    ok('配置 ' + url.split('/').pop() + ' → web.spider 是地址');

    let source;
    try {
      source = await getText(spider);
    } catch (error) {
      fail('源码取不到 ' + spider + '（' + error.message + '）');
      continue;
    }

    // id 从地址末段推 —— 和 app.js 的 spiderIdOf 同一套规则
    const expectedId = spider.replace(/[?#].*$/, '').replace(/\/+$/, '')
      .split('/').pop().replace(/\.js$/i, '');
    let loaded;
    try {
      loaded = loadSpider(source, expectedId);
    } catch (error) {
      fail('源码加载失败 ' + expectedId + '.js：' + error.message);
      continue;
    }
    if (loaded.site.id !== expectedId) {
      fail('id 对不上：文件名 ' + expectedId + '，源码里写的是 ' + loaded.site.id);
      continue;
    }
    ok('源码 ' + expectedId + '.js 可加载，caps=' + loaded.caps.length
      + (loaded.extraCaps.length ? '(+' + loaded.extraCaps.join(',') + ')' : '')
      + '，hosts=' + loaded.site.hosts.length);

    if (loaded.caps.length < 5) {
      fail('少了能力：只实现了 ' + loaded.caps.join('/'));
    }
  }

  console.log('');
  if (failed) {
    console.error('✗ ' + failed + ' 条失败');
    process.exit(1);
  }
  console.log('全部通过 —— 这份市场装得上。');
})();
