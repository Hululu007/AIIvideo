/*
 * AIIvideo —— 未捕获异常与未处理 rejection 的唯一出口。
 *
 * 这是埋点链上最后一块缺的（TODO 里 C 批那条「WebView 侧 window.onerror /
 * unhandledrejection 经桥转成 js_error」）：report 桥、服务端 events 表、
 * ReportQueue 全都现成，只差把浏览器异常送进去的这一个钩子。
 *
 * ── 四条硬约束，动之前先读完 ───────────────────────────────────────────
 *
 * ① **必须排在 app.html 的第一个**。放后面就漏掉前面所有脚本的解析错误，而
 *    「某个分片没打进包 / 语法坏了导致页面白屏」正是它最该抓到的那类故障。
 *
 * ② **不走 bridgeCall('report')，走 AndroidHost.reportJs**。桥那套回包会灌一句
 *    window.NativeCallbacks.resolve(id, …)，而 NativeCallbacks 定义在 app.js ——
 *    app.js 整份没执行时这一句自己就是个 TypeError，被钩子接住再报就是死循环。
 *    偏偏「app.js 没跑起来」就是最该抓的那个场景，所以这条路不能有回程。
 *
 * ③ **堆栈的键叫 trace，不叫 stack**。Java 侧 Report.fit() 只收缩 trace 和 msg
 *    两个键，其余它看都不看。用 stack 当键名就是整条顶穿 EVENT_BUDGET(3800)，
 *    服务端 4096 一拦进 dead/，日志里只剩一行 Log.w —— 2.0.22 刚修掉的那种
 *    「数据在看不见的地方消失」会原样复现。
 *
 * ④ **不传 source**。不传，Java 侧就回落到 SiteProfile 装配时挂上的真实源标签
 *    （51cg / mrds66）；传了等于拿这里的值覆盖它。而 app.js 里那个同名变量
 *    contentSource 恒为 'hongguo'，播出去的「源」那一列就全是假的。
 *
 * ── 为什么要有防刷 ─────────────────────────────────────────────────────
 * 封面那 1340 条「解密结果无效」就是「同一个坏东西被重试 16 次」刷出来的。
 * 一个坏掉的渲染循环比那猛两个数量级：每帧一条，几秒钟就能把队列撑满。
 * 所以同一类错误只在第 1 / 10 / 100 / 1000 次上报，每个会话另有 50 条硬顶。
 * 抓到的是「有没有这回事」，不是「刷了多少次」—— 后者由服务端按事件数看。
 */
(() => {
  'use strict';

  /** 每个页面会话最多发这么多条。远低于 ReportQueue 的 MAX_BACKLOG(600)，撑不爆队列。 */
  const SESSION_CAP = 50;

  /** 堆栈字节上限。和 Report.fit() 的收缩档位（1200/600/200）同一个量级，自己先收住。 */
  const MAX_TRACE = 2000;

  /** 同一 key 只在这几个出现次数上上报。1 是首犯，10/100/1000 是「还在犯」。 */
  const MARKS = [1, 10, 100, 1000];

  /** 堆栈只留前这么多帧 —— 12 帧足够看出是哪个模块，40 帧只是把预算吃光。 */
  const MAX_FRAMES = 12;

  const seen = Object.create(null);
  let sent = 0;
  let suppressed = 0;

  /**
   * 卫兵：钩子自身的任何异常都必须吞掉。
   *
   * 钩子里再抛异常 → 触发钩子 → 再抛，Chromium 会直接放弃并且原始错误也从
   * console 里消失。宁可少报一条，也不能把用户页面的原始错误弄丢。
   */
  let busy = false;

  function utf8Length(text) {
    try {
      return new TextEncoder().encode(text).length;
    } catch (noEncoder) {
      // 老内核没有 TextEncoder。encodeURIComponent 对非 ASCII 每个字节出 3 个字符
      // （%XX），所以用它算字节数，只是慢一点。
      try {
        return encodeURIComponent(text).replace(/%[0-9A-F]{2}/g, 'x').length;
      } catch (bad) {
        return text.length;
      }
    }
  }

  /** 按字节截断，且不切在字符中间。超了补一个省略号，和 Java 侧 shortenBytes 同口径。 */
  function cutBytes(text, max) {
    const source = String(text == null ? '' : text);
    if (utf8Length(source) <= max) return source;
    let out = '';
    let used = 0;
    for (const ch of source) {
      const size = utf8Length(ch);
      if (used + size > max - 1) break;          // 留 1 个字节给省略号
      out += ch;
      used += size;
    }
    return out + '…';
  }

  /** 错误消息截到 200 字，和 Java 侧 Report.text(msg, 200) 一致。 */
  function clipMessage(text) {
    const source = String(text == null ? '' : text).replace(/\s+/g, ' ').trim();
    return source.length <= 200 ? source : source.slice(0, 199) + '…';
  }

  /** 堆栈裁到前 12 帧，再按字节收住。 */
  function trimStack(stack) {
    const source = String(stack == null ? '' : stack);
    if (!source) return '';
    const lines = source.split('\n');
    return cutBytes(lines.slice(0, MAX_FRAMES + 1).join('\n'), MAX_TRACE);
  }

  /**
   * 当前在哪个页面。**每次实时读**，不在加载期缓存 —— 这个文件比 app.js 早得多，
   * 加载期 DOM 还不存在；而且 app.js 会把 dataset.route 改来改去。
   */
  function currentRoute() {
    try {
      const app = document.getElementById('app');
      return (app && app.dataset && app.dataset.route) || '';
    } catch (ignored) {
      return '';
    }
  }

  /** 脚本名只留最后一段，省掉 appassets.androidplatform.net 那半行。 */
  function baseName(url) {
    const source = String(url == null ? '' : url);
    if (!source) return '';
    const cut = source.split('?')[0].split('/').pop();
    return cut || '';
  }

  /**
   * 把任意 reason 归一成 {code, message, stack}。
   *
   * rejection 的 reason 可以是任何东西：Error、字符串、undefined、一个普通对象。
   * 这里绝不假设它有 message，也绝不抛。
   */
  function describe(reason) {
    if (reason && typeof reason === 'object' && typeof reason.message === 'string') {
      return {
        code: (reason.name || 'Error'),
        message: clipMessage(reason.message),
        stack: trimStack(reason.stack)
      };
    }
    if (reason === undefined) {
      return { code: 'undefined', message: '（rejection 的 reason 是 undefined）', stack: '' };
    }
    if (reason === null) {
      return { code: 'null', message: '（rejection 的 reason 是 null）', stack: '' };
    }
    if (typeof reason === 'object') {
      let text;
      try { text = JSON.stringify(reason); } catch (cyclic) { text = Object.prototype.toString.call(reason); }
      return { code: (reason.constructor && reason.constructor.name) || 'Object', message: clipMessage(text), stack: '' };
    }
    return { code: typeof reason, message: clipMessage(String(reason)), stack: '' };
  }

  /** 真要发出去的那一下。所有失败都吞掉，调用点不用 try/catch。 */
  function post(payload) {
    try {
      if (window.AndroidHost && typeof window.AndroidHost.reportJs === 'function') {
        window.AndroidHost.reportJs(JSON.stringify(payload));
      }
      // 没有 AndroidHost（在普通浏览器里打开这个页面）就什么都不做。这是正常场景，
      // 不是错误 —— 前端调试时经常直接在 Chrome 里开 app.html。
    } catch (ignored) {
    }
  }

  function send(stage, code, message, stack, where) {
    if (busy) return;
    busy = true;
    try {
      const key = [stage, code, message, where].join('|');
      const count = (seen[key] || 0) + 1;
      seen[key] = count;

      if (count > SESSION_CAP) {
        suppressed += 1;
        return;
      }
      if (MARKS.indexOf(count) < 0) {
        suppressed += 1;
        return;
      }
      if (sent >= SESSION_CAP) {
        suppressed += 1;
        return;
      }
      sent += 1;

      const props = {
        stage: stage,
        code: code,
        msg: message
      };
      if (stack) props.trace = stack;
      if (where) props.where = where;
      const route = currentRoute();
      if (route) props.route = route;
      // seen > 1 才有意义：等于 1 是首犯，写进去只是噪音。
      if (count > 1) props.seen = count;
      // 有被压掉的才带上，让服务端知道「这一条代表的不止一次」。
      if (suppressed > 0) props.suppressed = suppressed;

      post({
        kind: 'js',
        name: 'js_error',
        // 故意不带 source：让 Java 侧回落到 SiteProfile 挂上的真实源标签。
        props: props
      });
    } catch (ignored) {
    } finally {
      busy = false;
    }
  }

  // ── 两个入口 ──────────────────────────────────────────────────────────

  /*
   * 用捕获阶段（第三个参数 true）而不是冒泡：脚本解析错误和资源加载错误都不冒泡，
   * 只有捕获阶段能拿到。代价是 <img> 404 这类也会进来，所以下面必须过滤掉。
   */
  window.addEventListener('error', function (event) {
    try {
      // 资源加载失败（<img>/<script>/<link> 的 404）：event.target 是那个元素，
      // 不是 window。这些不是 JS 异常，而且封面那条路 cover.js 已经按自己的口径
      // 报过一遍了（带域名和字节数），这里再报一遍只是把同一个故障说两遍。
      if (event.target && event.target !== window) return;
      // 拿不到 ErrorEvent 的老内核退化成「认 target」的判据，已经过滤过了。
      if (typeof ErrorEvent !== 'undefined' && !(event instanceof ErrorEvent)) return;

      const error = event.error;
      const described = error ? describe(error) : { code: 'Error', message: clipMessage(event.message), stack: '' };
      const where = event.lineno
        ? baseName(event.filename) + ':' + event.lineno + (event.colno ? ':' + event.colno : '')
        : baseName(event.filename);
      send('onerror', described.code, described.message || clipMessage(event.message), described.stack, where);
    } catch (ignored) {
    }
  }, true);

  /*
   * 不调 event.preventDefault()：默认行为（console 里打一行）保持原样。
   * 这个改动只增加一条上报，不改变任何既有的调试体验。
   */
  window.addEventListener('unhandledrejection', function (event) {
    try {
      const described = describe(event.reason);
      send('unhandledrejection', described.code, described.message, described.stack, currentRoute());
    } catch (ignored) {
    }
  });
})();
