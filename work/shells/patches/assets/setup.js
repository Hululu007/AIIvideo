/*
 * AIIvideo —— 首次设置页
 *
 * 出厂的 APK 里不带任何站点配置：装完是空的，用户自己填一个源地址才有内容。
 * 这个文件负责那一屏，以及「还没配源就别往下启动」这个闸门。
 *
 * ── 为什么单独一个文件 ──────────────────────────────────────────────
 * app.js 已经 1900 行，而这块逻辑跟播放、缓存、导航都无关，只跟源配置有关。
 * 而且它需要在**启动之前**跑完，跟 app.js 的启动顺序是两件事。
 *
 * ── 闸门怎么用 ──────────────────────────────────────────────────────
 * app.js 的启动改成先等 HGSetup.ensure()：
 *     HGSetup.ensure().then(() => contentReady).then(() => startHome())
 * 没配源时 ensure() 不 resolve，界面停在设置页，不会去拉一个注定失败的接口。
 */
(() => {
  'use strict';

  const overlay = document.getElementById('setup-overlay');
  const input = document.getElementById('setup-input');
  const button = document.getElementById('setup-submit');
  const errorBox = document.getElementById('setup-error');
  const hint = document.getElementById('setup-hint');

  let resolveReady = null;
  const ready = new Promise(resolve => { resolveReady = resolve; });
  let prompting = false;

  function showError(message) {
    errorBox.textContent = message || '';
    errorBox.hidden = !message;
  }

  // 软键盘会把「获取并应用」盖住 —— 面板垂直居中，而输入框一聚焦键盘就顶上来，
  // 按钮正好落在键盘区域里，用户填完得先手动收键盘才点得到。
  //
  // visualViewport 能拿到键盘占掉的高度（内层视口变矮 + 整体上移）。
  // 把这段高度转成容器的 padding-bottom，居中区域缩到键盘以上，面板就自己上移了。
  // 只在设置页可见时挂监听，别让它在别的页面上白跑。
  function syncKeyboardInset() {
    const viewport = window.visualViewport;
    if (!viewport) return;
    const covered = Math.max(0, window.innerHeight - viewport.height - viewport.offsetTop);
    overlay.style.setProperty('--kb', covered + 'px');
  }

  function show(showIt) {
    overlay.hidden = !showIt;
    syncKeyboardInset();
    // 通知 Java 重算返回键归属。设置页显示时必须保持「JS 接管」，
    // 否则 Java 会把返回回调注销掉，按返回直接退到后台，连 hgBack() 都不会被调。
    if (showIt) window.AndroidHost?.setBackEnabled?.(true);
    if (showIt) {
      // 自动聚焦会让安卓把软键盘顶起来，短暂挡住半屏。延迟一点等过渡结束。
      setTimeout(() => { try { input.focus(); } catch (error) {} }, 320);
    }
  }

  /** 问 Java 现在配没配源。走 detail 桥的 __source:get 指令。 */
  async function current() {
    try {
      const reply = await bridgeCall('detail', { id: '__source:get' });
      return typeof reply === 'string' ? JSON.parse(reply) : reply;
    } catch (error) {
      // 桥本身出问题（比如 detail 那个分支没打进去）时不能装死，
      // 否则用户看到的是一个永远转圈、也永远没有提示的设置页。
      return { ok: false, error: '无法读取配置：' + (error.message || error) };
    }
  }

  async function submit() {
    const url = (input.value || '').trim();
    if (!url) { showError('请填写源地址'); return; }

    button.disabled = true;
    const original = button.textContent;
    button.textContent = '正在获取…';
    showError('');

    try {
      const reply = await bridgeCall('detail', { id: '__source:set?url=' + encodeURIComponent(url) });
      const result = typeof reply === 'string' ? JSON.parse(reply) : reply;
      if (!result || !result.ok) {
        showError((result && result.error) || '设置失败');
        return;
      }
      // 成功。这里**必须重建 Activity**，光 reload 页面是不够的 —— SiteProfile 是在
      // Activity.onCreate 里建的，只在启动时读一次配置，页面重载了 Activity 还在，
      // 拿到的还是上一份 profile。表现是：源填完了、首页却报一个跟自己填的地址毫无
      // 关系的错（「Expected URL scheme … but no scheme was found for /api/home」），
      // 用户只能自己把应用退掉重开。第一版就是 location.reload()，而这一屏恰好是
      // 「恢复出厂设置」之后必经的那一屏，所以这里跟换源那条路走同一个动作。
      //
      // 不 await：Java 那边先回包、200ms 后才 recreate，等它的话页面会在被销毁前挂起。
      // 留一个兜底 —— 2.5 秒后页面还在，说明重建没发生，退回重载页面。
      button.textContent = '已保存，正在重启…';
      if (typeof window.restartAfterSource === 'function') {
        window.restartAfterSource(() => { try { location.reload(); } catch (error) {} });
      } else {
        setTimeout(() => { try { location.reload(); } catch (error) {} }, 200);
      }
      return;
    } catch (error) {
      showError('设置失败：' + (error.message || error));
    } finally {
      if (!button.disabled) return;
      if (button.textContent !== '已保存，正在重启…') {
        button.disabled = false;
        button.textContent = original;
      }
    }
  }


  /**
   * 把**当前生效**的 web 块（域名之外的站点描述：paths / categories / fields / ui）
   * 写回 window.HG_SITE。
   *
   * 为什么需要：assets/site-web.js 是打包时从内置模板生成的，而用户换源之后
   * 这些值可能完全不同 —— 路径不一样、分类 id 不一样、字段名不一样。
   * 不重新拉一次，前端读的还是打包时那份，表现是「换了源但界面里的东西对不上」。
   *
   * 合并本身在 Java 侧做完了（SiteProfile 构造时 merge(内置, 用户配置)），
   * 这里只是把结果取回来盖上，不重复实现一遍。
   */
  async function applySiteConfig() {
    try {
      const reply = await bridgeCall('detail', { id: '__source:web' });
      const result = typeof reply === 'string' ? JSON.parse(reply) : reply;
      if (!result || !result.ok || !result.web) return;
      Object.assign(window.HG_SITE = window.HG_SITE || {}, result.web);
      console.log('[setup] 已套用源里的 web 配置');
    } catch (error) {
      // 拿不到就继续用打包时那份。不阻断启动 —— 同一套 schema 换个域名的话，
      // 两份其实一模一样，这里失败是完全无害的。
      console.log('[setup] web 配置拉取失败，沿用内置：' + (error.message || error));
    }
  }

  /** 等一个可用的源。没配就弹设置页，配好了立刻 resolve。 */
  async function ensure() {
    if (prompting) return ready;
    prompting = true;

    const state = await current();
    if (state && state.ok && state.configured) {
      // 先把缓存作用域钉住，再放行。
      //
      // 顺序是硬要求：goHome() 是**同步**读 localStorage 的（冷启动第一帧就要有内容，
      // 不能等网络也不能等桥），所以作用域必须在 startHome() 之前就写好。
      // 写到 url 上——那是用户填的配置地址，换源就变，而且是唯一稳定的标识：
      // CMS 源的 origin 是空的，只有 url 能区分。
      window.HG_CACHE_SCOPE = state.url || state.origin || '';
      await applySiteConfig();
      prompting = false;
      return;                       // 已经有源，直接放行
    }
    if (state && !state.ok) {
      // 连读都读不了：仍然显示设置页，但把原因说清楚，
      // 别让用户对着一个沉默的输入框猜。
      showError(state.error);
    }
    show(true);
    return ready;                   // 等用户在页面上提交成功
  }

  if (window.visualViewport) {
    window.visualViewport.addEventListener('resize', syncKeyboardInset);
    window.visualViewport.addEventListener('scroll', syncKeyboardInset);
  }

  if (button) button.addEventListener('click', submit);
  if (input) {
    input.addEventListener('keydown', event => {
      if (event.key === 'Enter') { event.preventDefault(); submit(); }
    });
  }

  window.HGSetup = {
    ensure,
    submit,
    applySiteConfig,
    /** 手动重开设置页（「我的」里的换源入口用）。 */
    open() {
      showError('');
      input.value = '';
      show(true);
      current().then(state => {
        if (state && state.ok && state.url) {
          input.value = state.url;
          hint.textContent = '当前源：' + state.origin;
        }
      });
    },
    close() { show(false); resolveReady(); },
  };
})();
