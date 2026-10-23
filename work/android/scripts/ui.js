#!/usr/bin/env node
/**
 * 真机 UI 驱动：直接对 WebView 里的页面做 CDP，不走截图和 OCR。
 *
 * ## 为什么要这个
 *
 * App 的界面整块是一个 WebView。`uiautomator dump` 在它上面只返回 5 个节点、
 * 0 个带 text/content-desc —— DOM 在 Chromium 进程里，Android 的 View 树看不到。
 * 于是「点某个按钮」这件事只能退化成「截图 → OCR 找字 → 按像素点」，
 * 缩放、动画中途、字体重排都会让它偏，而且失败了很难看出来。
 *
 * `MainActivity` 里开着 `WebView.setWebContentsDebuggingEnabled(true)`（发布版也生效），
 * 所以页面本来就是可编排的：`adb forward` 到 `webview_devtools_remote_<pid>`，
 * 发 CDP 的 `Runtime.evaluate`，按选择器点、按选择器断言。
 *
 * ## 用法
 *
 *   node scripts/ui.js targets                       # 列页面 target
 *   node scripts/ui.js text "#content"               # 读某块的内文
 *   node scripts/ui.js click "#search-input"         # 点（选择器没命中就报错退出 1）
 *   node scripts/ui.js eval "document.title"
 *   node scripts/ui.js assert "#content .card" --count 12
 *   node scripts/ui.js assert "#content" --text "最近更新"
 *   node scripts/ui.js wait "#content .card" --timeout 8000
 *
 * 设备与包名：`HG_SERIAL`（多台时必填）、`HG_PKG`（默认 com.huangguo.mobile）、
 * `HG_CDP_PORT`（默认 9333）。
 *
 * ## 一个坑
 *
 * `adb forward` 绑的是**当时的 pid**。进程重启（换源、崩溃恢复、装新包）之后要重新
 * forward，否则连到的是死 socket。本脚本每次运行都重新解析 pid 并覆盖 forward，
 * 所以不用手动管；但如果你在别的终端里自己 forward 过，端口会被这里抢走。
 */
'use strict';

const { execFileSync } = require('child_process');
const http = require('http');

const PORT = Number(process.env.HG_CDP_PORT || 9333);
const PKG = process.env.HG_PKG || 'com.huangguo.mobile';
const TIMEOUT_MS = 8000;

function die(message) {
  console.error(message);
  process.exit(1);
}

function adbBin() {
  try {
    execFileSync('adb', ['version'], { stdio: 'ignore' });
    return 'adb';
  } catch (e) {
    die('找不到 adb —— 先装 platform-tools，或者把 adb 放进 PATH');
  }
}

function deviceArgs() {
  const serial = process.env.HG_SERIAL;
  if (serial) return ['-s', serial];
  const out = execFileSync(adbBin(), ['devices'], { encoding: 'utf8' });
  const online = out.split('\n')
      .map(line => line.trim())
      .filter(line => line && !line.startsWith('List of devices'))
      .map(line => line.split(/\s+/))
      .filter(parts => parts[1] === 'device')
      .map(parts => parts[0]);
  if (online.length === 0) die('没有已授权的设备（adb devices 里没有 device 状态的）');
  if (online.length > 1) die('接了多台设备，用 HG_SERIAL=<serial> 指定一台：' + online.join(', '));
  return ['-s', online[0]];
}

function adb(args) {
  return execFileSync(adbBin(), deviceArgs().concat(args), { encoding: 'utf8' }).trim();
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function pidOf(pkg) {
  let pid = '';
  try {
    pid = adb(['shell', 'pidof', pkg]);
  } catch (e) {
    pid = '';
  }
  if (!pid) die('应用没在跑（pidof ' + pkg + ' 空）—— 先启动它：adb shell am start -n ' + pkg + '/.MainActivity');
  return pid.split(/\s+/)[0];
}

function forward(pid) {
  adb(['forward', 'tcp:' + PORT, 'localabstract:webview_devtools_remote_' + pid]);
}

function httpJson(path) {
  return new Promise((resolve, reject) => {
    const req = http.get({ host: '127.0.0.1', port: PORT, path, timeout: 4000 }, res => {
      let body = '';
      res.setEncoding('utf8');
      res.on('data', chunk => { body += chunk; });
      res.on('end', () => {
        try {
          resolve(JSON.parse(body));
        } catch (e) {
          reject(new Error('target 列表不是 JSON：' + body.slice(0, 80)));
        }
      });
    });
    req.on('timeout', () => req.destroy(new Error('连 ' + PORT + ' 超时')));
    req.on('error', reject);
  });
}

async function pickTarget() {
  const list = await httpJson('/json/list');
  const pages = list.filter(t => t.type === 'page' && t.webSocketDebuggerUrl);
  if (pages.length === 0) die('没有可编排的页面（app 起来了吗？WebView 开调试了吗？）');
  return pages.find(t => String(t.url || '').includes('app.html')) || pages[0];
}

class Cdp {
  constructor(url) {
    this.ws = new WebSocket(url);
    this.nextId = 1;
    this.pending = new Map();
    this.ws.addEventListener('message', event => {
      let msg;
      try {
        msg = JSON.parse(event.data);
      } catch (e) {
        return;
      }
      const waiter = this.pending.get(msg.id);
      if (waiter) {
        this.pending.delete(msg.id);
        waiter(msg);
      }
    });
  }

  async open() {
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('WebSocket 握手超时')), 5000);
      this.ws.addEventListener('open', () => { clearTimeout(timer); resolve(); }, { once: true });
      this.ws.addEventListener('error', () => { clearTimeout(timer); reject(new Error('WebSocket 握手失败')); }, { once: true });
    });
  }

  send(method, params) {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(method + ' 超时'));
      }, TIMEOUT_MS);
      this.pending.set(id, msg => { clearTimeout(timer); resolve(msg); });
      this.ws.send(JSON.stringify({ id, method, params: params || {} }));
    });
  }

  async evaluate(expression) {
    const msg = await this.send('Runtime.evaluate', {
      expression,
      returnByValue: true,
      awaitPromise: true,
    });
    if (msg.error) throw new Error(msg.error.message || JSON.stringify(msg.error));
    const result = (msg.result || {}).result || {};
    if (msg.result && msg.result.exceptionDetails) {
      const detail = msg.result.exceptionDetails;
      const text = (detail.exception && detail.exception.description) || detail.text || '页面里抛异常';
      throw new Error(text.split('\n')[0]);
    }
    return result.value;
  }

  close() {
    try { this.ws.close(); } catch (e) { /* 已经关了 */ }
  }
}

function jsonArg(value) {
  return JSON.stringify(value === undefined ? null : value);
}

async function withPage(fn) {
  const pid = pidOf(PKG);
  forward(pid);
  const target = await pickTarget();
  const cdp = new Cdp(target.webSocketDebuggerUrl);
  await cdp.open();
  try {
    return await fn(cdp, target);
  } finally {
    cdp.close();
  }
}

function parseFlags(argv) {
  const flags = {};
  const rest = [];
  for (let i = 0; i < argv.length; i++) {
    const token = argv[i];
    if (token === '--count' || token === '--text' || token === '--timeout') {
      flags[token.slice(2)] = argv[++i];
    } else {
      rest.push(token);
    }
  }
  return { flags, rest };
}

async function main() {
  const argv = process.argv.slice(2);
  const cmd = argv[0];
  if (!cmd || cmd === '-h' || cmd === '--help') {
    console.log('用法: node scripts/ui.js <targets|text|click|eval|assert|wait> [参数]');
    console.log('  例: node scripts/ui.js click "#search-input"');
    console.log('      node scripts/ui.js assert "#content .card" --count 12');
    return;
  }

  if (cmd === 'targets') {
    const pid = pidOf(PKG);
    forward(pid);
    const list = await httpJson('/json/list');
    for (const t of list) {
      console.log([t.type, (t.title || '').slice(0, 24), (t.url || '').slice(0, 72)].join('\t'));
    }
    return;
  }

  const { flags, rest } = parseFlags(argv.slice(1));
  const arg = rest[0];

  if (cmd === 'eval') {
    if (!arg) die('用法: node scripts/ui.js eval "<js 表达式>"');
    const value = await withPage(cdp => cdp.evaluate(arg));
    console.log(typeof value === 'string' ? value : JSON.stringify(value, null, 2));
    return;
  }

  if (cmd === 'text') {
    const selector = arg || 'body';
    const value = await withPage(cdp => cdp.evaluate(
        '(function(s){var e=document.querySelector(s);return e?e.innerText:null;})(' + jsonArg(selector) + ')'));
    if (value === null || value === undefined) die('选择器没命中: ' + selector);
    console.log(String(value).trim());
    return;
  }

  if (cmd === 'click') {
    if (!arg) die('用法: node scripts/ui.js click "<选择器>"');
    const info = await withPage(cdp => cdp.evaluate(
        '(function(s){var e=document.querySelector(s);'
        + 'if(!e)return {ok:false,why:"没命中"};'
        + 'var r=e.getBoundingClientRect();'
        + 'if(r.width===0||r.height===0)return {ok:false,why:"尺寸为 0（隐藏或未布局）"};'
        + 'e.scrollIntoView({block:"center"});e.click();'
        + 'return {ok:true,text:(e.innerText||"").slice(0,40)};})(' + jsonArg(arg) + ')'));
    if (!info.ok) die('点不了 ' + arg + '：' + info.why);
    console.log('点了 ' + arg + (info.text ? '（' + info.text + '）' : ''));
    return;
  }

  if (cmd === 'assert') {
    if (!arg) die('用法: node scripts/ui.js assert "<选择器>" [--count N] [--text 子串]');
    const probe = '(function(s){var all=document.querySelectorAll(s);'
        + 'var first=all[0];'
        + 'var visible=0;'
        + 'for(var i=0;i<all.length;i++){var r=all[i].getBoundingClientRect();'
        + 'if(r.width>0&&r.height>0)visible++;}'
        + 'return {count:all.length,visible:visible,'
        + 'text:first?(first.innerText||""):null};})(' + jsonArg(arg) + ')';
    const state = await withPage(cdp => cdp.evaluate(probe));
    let ok = state.count > 0;

    if (flags.count !== undefined) {
      const want = Number(flags.count);
      if (state.count !== want) {
        die('断言失败：' + arg + ' 期望 ' + want + ' 个，实际 ' + state.count + ' 个');
      }
      ok = true;
    }
    if (flags.text !== undefined) {
      const text = state.text || '';
      if (!text.includes(flags.text)) {
        die('断言失败：' + arg + ' 的内文里没有「' + flags.text + '」，实际是：' + text.slice(0, 120));
      }
      ok = true;
    }
    if (!ok) die('断言失败：' + arg + ' 一个都没命中');
    console.log('通过 ' + arg + '（' + state.count + ' 个，可见 ' + state.visible + ' 个）');
    return;
  }

  if (cmd === 'wait') {
    if (!arg) die('用法: node scripts/ui.js wait "<选择器>" [--timeout 8000]');
    const timeout = Number(flags.timeout || 8000);
    const started = Date.now();
    let last = '';
    await withPage(async cdp => {
      while (Date.now() - started < timeout) {
        try {
          const count = await cdp.evaluate('document.querySelectorAll(' + jsonArg(arg) + ').length');
          if (count > 0) {
            console.log('等到 ' + arg + '（' + count + ' 个，' + (Date.now() - started) + 'ms）');
            return;
          }
          last = '还没出现';
        } catch (e) {
          last = e.message;
        }
        await sleep(300);
      }
      die('等 ' + arg + ' 超时（' + timeout + 'ms）：' + last);
    });
    return;
  }

  die('不认识的命令：' + cmd + '（看 --help）');
}

main().catch(error => die(error && error.stack ? error.stack : String(error)));
