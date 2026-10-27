#!/usr/bin/env node
/**
 * 未捕获异常钩子的离线回归：给 `assets/js-errors.js` 一个假 window，灌几个真实的
 * ErrorEvent / rejection，看它发出的载荷对不对。
 *
 * ## 为什么要有这个脚本
 *
 * 这个钩子有三条**看不见的约束**，违反任何一条都不会报错，只会让数据悄悄消失：
 *
 *   ① 堆栈的键必须叫 `trace`。叫 `stack` 的话 Java 侧 Report.fit() 不认识这个键，
 *      收缩不了，整条顶穿 EVENT_BUDGET(3800) → 服务端 4096 拦下 → 进 dead/。
 *      症状：后台一条 JS 异常都没有，logcat 里只有一行 Log.w。
 *   ② 必须走 `AndroidHost.reportJs` 而不是 `bridgeCall('report')`。后者要回包，
 *      回包调 `window.NativeCallbacks.resolve`，而那个对象在 app.js 里 ——
 *      app.js 整份没执行时回包自己就抛 TypeError，钩子接住再报 → 死循环。
 *      症状：JS 异常一出现，页面就卡死或者直接闪退。
 *   ③ 不能带 `source`。带了就用假值覆盖掉 Java 侧回落的真实源标签。
 *      症状：后台「源」那一列全是 hongguo。
 *
 * 三条都是「静默」的，真机上要等出故障才看得出来。所以在构建期钉住。
 *
 * ## 用法
 *
 *   node scripts/js-errors-check.js           # 跑全部断言（对着源码树）
 *   node scripts/js-errors-check.js --dump    # 顺手打出第一份载荷
 *
 *   node scripts/js-errors-check.js \
 *        --file app/build/generated/assets/webview/js-errors.js \
 *        --html app/build/generated/assets/webview/app.html
 *
 * **构建链上必须传 --file / --html 指向生成目录。** 那三条约束（trace 键名、
 * 不走桥、不带 source）验的是「打进包的那份」，而降级之后包里的是 Babel 的输出，
 * 不是 `src/main/assets` 里这份。验源码等于没验 —— 而这三条失效全是静默的。
 *
 * 退出码：0 全过 / 1 有断言失败 / 2 环境问题（文件缺失、沙箱起不来）
 */
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');
// app.html 的内联脚本要按 ES5 验，见断言 13。
const acorn = require(path.resolve(__dirname, '../tools/acorn.js'));

const HERE = __dirname;

/** `--flag value` 取值。缺值就是环境问题，不要静默回落。 */
function arg(flag) {
  const index = process.argv.indexOf(flag);
  if (index < 0) return null;
  const value = process.argv[index + 1];
  if (!value || value.startsWith('--')) {
    console.error(flag + ' 后面要跟路径');
    process.exit(2);
  }
  return path.resolve(value);
}

const TARGET = arg('--file') || path.resolve(HERE, '../app/src/main/assets/js-errors.js');
const APK_HTML = arg('--html') || path.resolve(HERE, '../app/src/main/assets/app.html');
const usingSource = !process.argv.includes('--file');

const dump = process.argv.includes('--dump');

function fail(message) {
  console.error('✗ ' + message);
  process.exit(2);
}

if (!fs.existsSync(TARGET)) fail('js-errors.js 不存在: ' + TARGET);
if (!fs.existsSync(APK_HTML)) fail('app.html 不存在: ' + APK_HTML);

// 对着源码树跑的时候提醒一句：包里那份是降级后的，别以为这就验过了。
if (usingSource) {
  console.log('（对着 src/main/assets 跑。构建链上要传 --file/--html 指向生成目录）');
}

let passed = 0;
function check(name, condition, detail) {
  if (condition) {
    passed += 1;
    console.log('  ok  ' + name);
  } else {
    console.error('  FAIL ' + name + (detail ? ' — ' + detail : ''));
    process.exitCode = 1;
  }
}

/**
 * 造一个沙箱。每次都新建 —— 勾子里有会话级的计数（seen / sent / suppressed），
 * 复用同一个沙箱会让第二组断言看到第一组留下的数。
 *
 * 刻意**不给** TextEncoder：老内核没有它，走的是 encodeURIComponent 那条兜底，
 * 那条路径更该被测到。真要测两条就传 withEncoder。
 */
function makeSandbox(options = {}) {
  const listeners = Object.create(null);
  const posted = [];

  const win = {
    addEventListener(type, handler) {
      (listeners[type] = listeners[type] || []).push(handler);
    },
    AndroidHost: {
      reportJs(json) {
        posted.push(JSON.parse(json));
      }
    }
  };

  if (options.withEncoder !== false) {
    win.TextEncoder = TextEncoder;
  }

  class FakeErrorEvent {
    constructor(message, filename, lineno, colno, error) {
      this.message = message;
      this.filename = filename;
      this.lineno = lineno;
      this.colno = colno;
      this.error = error;
      this.target = null;
    }
  }

  const document = {
    getElementById() {
      return { dataset: { route: options.route === undefined ? 'home' : options.route } };
    }
  };

  const sandbox = {
    window: win,
    document,
    ErrorEvent: FakeErrorEvent,
    TextEncoder: options.withEncoder !== false ? TextEncoder : undefined,
    encodeURIComponent,
    String,
    Object,
    JSON,
    Promise,
    console
  };
  vm.createContext(sandbox);
  vm.runInContext(fs.readFileSync(TARGET, 'utf8'), sandbox, { filename: 'js-errors.js' });

  return {
    win,
    posted,
    route: options.route,
    /** 派发一个 ErrorEvent（模拟 window.onerror）。 */
    error(message, filename, lineno, colno, error) {
      const event = new FakeErrorEvent(message, filename, lineno, colno, error);
      (listeners.error || []).forEach(handler => handler(event));
    },
    /** 派发一个资源加载错误（<img> 404 那种，target 是元素）。 */
    resourceError() {
      const event = new FakeErrorEvent('', '', 0, 0, null);
      event.target = { tagName: 'IMG' };
      (listeners.error || []).forEach(handler => handler(event));
    },
    rejection(reason) {
      (listeners.unhandledrejection || []).forEach(handler => handler({ reason }));
    },
    get hasRejectionListener() {
      return (listeners.unhandledrejection || []).length > 0;
    },
    get hasErrorListener() {
      return (listeners.error || []).length > 0;
    }
  };
}

function realError(message) {
  const error = new TypeError(message);
  // 真机上 Chrome 的 stack 第一行是 "TypeError: msg"，后面每帧一行。造得像一点。
  error.stack = 'TypeError: ' + message + '\n'
    + Array.from({ length: 40 }, (_, i) => '    at fn' + i + ' (appassets.androidplatform.net/assets/app.js:' + (100 + i) + ':9)').join('\n');
  return error;
}

function bytesOf(value) {
  return Buffer.byteLength(JSON.stringify(value), 'utf8');
}

// ── 断言 ────────────────────────────────────────────────────────────────

console.log('js-errors.js 离线回归');

// 0. 两个监听都挂上了
{
  const s = makeSandbox();
  check('挂了 error 监听', s.hasErrorListener);
  check('挂了 unhandledrejection 监听', s.hasRejectionListener);
}

// 1. 未捕获异常 → 一条 kind=js / stage=onerror 的事件
{
  const s = makeSandbox({ route: 'detail' });
  s.error('x is not a function', 'https://appassets.androidplatform.net/assets/app.js', 1234, 7, realError('x is not a function'));
  check('抛一次就发一条', s.posted.length === 1, '实际 ' + s.posted.length);
  const event = s.posted[0];
  if (dump && event) console.log(JSON.stringify(event, null, 2));
  check('kind 是 js', event.kind === 'js', String(event.kind));
  check('name 是 js_error', event.name === 'js_error', String(event.name));
  check('stage 是 onerror', event.props.stage === 'onerror', String(event.props.stage));
  check('code 是构造器名', event.props.code === 'TypeError', String(event.props.code));
  check('带上了路由', event.props.route === 'detail', String(event.props.route));
  check('where 是文件名:行:列', event.props.where === 'app.js:1234:7', String(event.props.where));
  check('where 不含 origin', !String(event.props.where).includes('appassets'), String(event.props.where));
}

// 2. 约束 ①：堆栈在 trace 键上，且整条收得住预算
{
  const s = makeSandbox();
  s.error('boom', 'https://appassets.androidplatform.net/assets/app.js', 9, 1, realError('boom'));
  const event = s.posted[0];
  check('堆栈在 props.trace 上', typeof event.props.trace === 'string' && event.props.trace.length > 0);
  check('没有 props.stack', !Object.prototype.hasOwnProperty.call(event.props, 'stack'));
  check('40 帧被裁到 12 帧以内',
    (event.props.trace.match(/\n/g) || []).length <= 12,
    '实际 ' + ((event.props.trace.match(/\n/g) || []).length + 1) + ' 行');
  // Report.EVENT_BUDGET = 3800，服务端 MAX_EVENT_BYTES = 4096。
  // 这里按「Java 侧再补 at/id/session/device/source 大约 200 字节」估算余量。
  const size = bytesOf(event) + 200;
  check('整条估算 ' + size + ' 字节 ≤ 3800', size <= 3800);
}

// 3. 没有 TextEncoder 时也不炸，且截断仍然有效（老内核兜底路径）
{
  const s = makeSandbox({ withEncoder: false });
  s.error('中文错误「测试」', 'https://x/assets/a.js', 3, 4, realError('中文错误'.repeat(50)));
  check('没有 TextEncoder 也发得出去', s.posted.length === 1);
  const trace = s.posted[0] && s.posted[0].props.trace;
  check('兜底路径下堆栈仍被裁住', typeof trace === 'string' && Buffer.byteLength(trace, 'utf8') <= 2000,
    '实际 ' + (trace ? Buffer.byteLength(trace, 'utf8') : 0) + ' 字节');
  check('没有切出替换字符', !String(trace).includes('\uFFFD'));
}

// 4. 约束 ②：只调 reportJs，绝不碰桥（NativeCallbacks / request 都不该被用到）
{
  const s = makeSandbox();
  let bridgeTouched = false;
  s.win.NativeCallbacks = { resolve() { bridgeTouched = true; }, reject() { bridgeTouched = true; } };
  s.win.AndroidHost.request = function () { bridgeTouched = true; };
  s.error('boom', 'https://x/assets/a.js', 1, 1, realError('boom'));
  check('没有回包、没有走 request 桥', bridgeTouched === false);
  check('仍然发出去了一条', s.posted.length === 1);
}

// 5. 约束 ③：不带 source（让 Java 回落到真实源标签）
{
  const s = makeSandbox();
  s.error('boom', 'https://x/assets/a.js', 1, 1, realError('boom'));
  check('载荷里没有 source', !Object.prototype.hasOwnProperty.call(s.posted[0], 'source'));
}

// 6. rejection 的各种 reason 都不炸
{
  const s = makeSandbox({ route: 'home' });
  s.rejection(realError('async 挂了'));
  check('Error 形态的 rejection 上报',
    s.posted.length === 1 && s.posted[0].props.stage === 'unhandledrejection',
    String(s.posted[0] && s.posted[0].props.stage));

  const s2 = makeSandbox();
  s2.rejection('就是个字符串');
  check('字符串 reason 不炸', s2.posted.length === 1);
  check('字符串 reason 的 code 是 typeof',
    s2.posted[0].props.code === 'string', String(s2.posted[0].props.code));
  check('字符串 reason 的 msg 是那句话',
    s2.posted[0].props.msg === '就是个字符串', String(s2.posted[0].props.msg));

  const s3 = makeSandbox();
  s3.rejection(undefined);
  check('undefined reason 不炸', s3.posted.length === 1);
  check('undefined 有可读的 code', s3.posted[0].props.code === 'undefined');

  const s4 = makeSandbox();
  s4.rejection(null);
  check('null reason 不炸', s4.posted.length === 1);

  const s5 = makeSandbox();
  const cyclic = {};
  cyclic.self = cyclic;
  s5.rejection(cyclic);
  check('循环引用 reason 不炸', s5.posted.length === 1);
}

// 7. 资源加载失败（<img> 404）不算 JS 异常
{
  const s = makeSandbox();
  s.resourceError();
  check('图片 404 不产生 js_error', s.posted.length === 0,
    '实际发了 ' + s.posted.length + ' 条（cover.js 已经按封面失败报过一遍，这里不能重复）');
}

// 8. 防刷：同一错误连抛 30 次，只在第 1 / 10 次上报
{
  const s = makeSandbox();
  for (let i = 0; i < 30; i += 1) {
    s.error('同一个错误', 'https://x/assets/a.js', 5, 1, realError('同一个错误'));
  }
  check('30 次同错只发 2 条（第 1、10 次）', s.posted.length === 2, '实际 ' + s.posted.length);
  check('首条不带 seen', !Object.prototype.hasOwnProperty.call(s.posted[0].props, 'seen'));
  check('第 10 次带 seen=10', s.posted[1].props.seen === 10, String(s.posted[1].props.seen));
  check('第 10 次带 suppressed 计数', s.posted[1].props.suppressed === 8, String(s.posted[1].props.suppressed));
}

// 9. 会话硬上限：第 51 条起不再发
{
  const s = makeSandbox();
  for (let i = 0; i < 200; i += 1) {
    // 每条 message 都不一样 → 每条都是首犯 → 直接撞上限
    s.error('错误 #' + i, 'https://x/assets/b.js', i, 1, realError('错误 #' + i));
  }
  check('200 条不同错误被截到 50 条', s.posted.length === 50, '实际 ' + s.posted.length);
}

// 10. AndroidHost 不在（普通浏览器里打开 app.html）时静默
{
  const s = makeSandbox();
  delete s.win.AndroidHost;
  let threw = false;
  try {
    s.error('boom', 'https://x/assets/a.js', 1, 1, realError('boom'));
    s.rejection('x');
  } catch (error) {
    threw = true;
  }
  check('没有 AndroidHost 时静默不抛', threw === false);
}

// 11. 钩子自身出错也不能把原始错误吃掉（卫兵）
{
  const s = makeSandbox();
  s.win.AndroidHost.reportJs = function () { throw new Error('上报自己炸了'); };
  let threw = false;
  try {
    s.error('原始错误', 'https://x/assets/a.js', 1, 1, realError('原始错误'));
    s.error('第二条', 'https://x/assets/a.js', 2, 1, realError('第二条'));
  } catch (error) {
    threw = true;
  }
  check('上报抛异常不外泄，钩子还能继续用', threw === false);
}

// 12. app.html 里 js-errors.js 必须是第一个 <script>
{
  const html = fs.readFileSync(APK_HTML, 'utf8');
  const scripts = [...html.matchAll(/<script[^>]*src="([^"]+)"/g)].map(m => m[1]);
  check('app.html 引用了 js-errors.js', scripts.includes('js-errors.js'), scripts.slice(0, 3).join(', '));
  check('它是第一个外部脚本', scripts[0] === 'js-errors.js', '第一个是 ' + scripts[0]);
  // 内联 script 位置也要在它后面：head 里的主题脚本不能抢在它前面。
  const inlineAt = html.indexOf('<script>');
  const hookAt = html.indexOf('js-errors.js');
  check('排在 head 那个内联主题脚本之前', hookAt > 0 && hookAt < inlineAt,
    'inline@' + inlineAt + ' hook@' + hookAt);
}

// 13. app.html 里的内联 <script> 必须是 ES5
//
// 那块白屏兜底要照顾的正是「旧 WebView 连 app.js 都解析不了」的机器 —— 内联脚本
// 不经过 Babel（transpileAssets 只处理 .js 文件），所以它自己必须是最保守的语法。
// 这里出现一个箭头函数或者 const，兜底自己就成了新的白屏，而且是在最该说话的那种
// 机器上。按 ES5 解析，不按 ES2017：内联脚本的约定就是「引擎再老也读得动」。
{
  const html = fs.readFileSync(APK_HTML, 'utf8');
  const inline = [...html.matchAll(/<script(?![^>]*\bsrc=)[^>]*>([\s\S]*?)<\/script>/g)].map(m => m[1]);
  check('app.html 里有内联脚本（白屏兜底 + 主题）', inline.length >= 2, '找到 ' + inline.length + ' 段');
  let es5 = true;
  let detail = '';
  for (const code of inline) {
    try {
      acorn.parse(code, { ecmaVersion: 5, sourceType: 'script' });
    } catch (error) {
      es5 = false;
      detail = error.message.slice(0, 80);
      break;
    }
  }
  check('内联脚本全部是 ES5（兜底不能自己白屏）', es5, detail);
}

// 14. 订阅流程不许预置 subTesting / freeTesting = true
//
// 2.0.24 及以前，「更新订阅 → 拉完顺手测一次」这条流程是**永久卡死**的：
// 订阅那个 handler 把 subTesting 预置成 true，紧接着调 runSpeedTest()，
// 而它第一句就是「已经在测就直接 return」—— 于是一次都不跑，finally 不执行，
// 按钮永远「测速中…」且 disabled，只有刷新页面才解得开。用户实测挂了近一小时，
// 还以为是在测速。
//
// 这条断言只认一件事：测速开关只归 runSpeedTest 自己管。别人预置就是死锁。
{
  const src = fs.readFileSync(path.resolve(HERE, '../app/src/main/assets/app.js'), 'utf8');
  // 先剥注释：这条不变量的说明文字里本身就写着 `subTesting: true`，
  // 不剥的话断言会被自己的注释判失败。
  const code = src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');
  const bad = code.match(/subTesting:\s*true|freeTesting:\s*true/g) || [];
  check('没有别处预置测速开关（会和 runSpeedTest 的守卫死锁）', bad.length === 0,
    bad.join(', ') + ' —— 删掉它，让 runSpeedTest 自己置位');
}

if (process.exitCode) {
  console.error('\n有断言没通过');
} else {
  console.log('\n全部 ' + passed + ' 条断言通过');
}
