/**
 * 内容缓存、失败重试与接口调用。
 *
 * <p>2026-10-23 从 app.js 拆出。app.js 4280 行、236 个顶层声明，其中 2435 行没有
 * 一条分节线，改一处得全文搜。这里只搬**顶层函数声明** —— 它们靠提升生效，
 * 搬走不改变任何加载期行为；顶层 `const` 与立即执行的语句仍留在 app.js。
 *
 * <p>classic script，不是模块：函数仍落在同一个全局作用域里，调用点一行没动。
 * app.html 里这七块排在 app.js 之前。
 */
'use strict';

function scheduleHomeRetry(token, attempt = 0) {
  if (attempt >= HOME_RETRY_DELAYS.length) return;
  clearTimeout(scheduleHomeRetry.timer);
  scheduleHomeRetry.timer = setTimeout(async () => {
    // 用户已经离开首页就别再打了。但**已经拿到缓存也要重试** —— 缓存不是「更新成功」，
    // 只是「上次成功过」，而这次失败的内容用户正盯着看。
    if (token !== routeToken || state.route !== 'home') return;
    // 刚失败过就别在后台反复撞了。用户主动点还是照常响应（那条路不受冷却约束）——
    // 区别在于：主动点是「用户要一次答案」，后台重试是「系统自己猜要不要再试」。
    if (netCoolingDown()) return;
    try {
      const fresh = await api('home');
      if (token !== routeToken || state.route !== 'home') return;
      state.home = fresh;
      writeCache('home', fresh);
      renderHome();
    } catch (error) {
      if (token !== routeToken || state.route !== 'home') return;
      scheduleHomeRetry(token, attempt + 1);
    }
  }, HOME_RETRY_DELAYS[attempt]);
}

function scheduleCategoryRetry(token, id, attempt = 0) {
  if (attempt >= HOME_RETRY_DELAYS.length) return;
  clearTimeout(scheduleCategoryRetry.timer);
  scheduleCategoryRetry.timer = setTimeout(async () => {
    if (token !== routeToken || state.route !== 'category' || categoryId() !== id) return;
    if (netCoolingDown()) return;   // 同上：只拦后台重试
    // 这里原来有一句 `if (state.category) return;` —— 有缓存就不重试。
    // 结果是：网络抖一下（unexpected end of stream 在 5G 上很常见），界面停在旧内容，
    // 只弹一句 toast 就完了。用户看到的就是「点了没反应、内容还是上一个分类」。
    try {
      const fresh = await api('category', { id, page: 1 });
      if (token !== routeToken || state.route !== 'category' || categoryId() !== id) return;
      state.category = { ...fresh, refreshing: false, loading: false, error: false };
      state.categoryItems = asArray(fresh.items);
      writeCache(`category:${id}:1`, fresh);
      renderCategory(state.compactHeld);
      reconcileCards(state.categoryItems, () => {
        if (token !== routeToken || state.route !== 'category') return;
        const top = scroll.scrollTop;
        writeCache(`category:${id}:1`, state.category);
        renderCategory(state.compactHeld);
        scroll.scrollTop = top;
      });
    } catch (error) {
      if (token !== routeToken || state.route !== 'category') return;
      scheduleCategoryRetry(token, id, attempt + 1);
    }
  }, HOME_RETRY_DELAYS[attempt]);
}

async function fetchApi(path) {
  // WebView fetch 会附带 Origin: appassets.androidplatform.net；真机实测该头会让
  // API 入口连接超时。借用现有 detail 桥传入受控 /api/ 路径，由 Java 原生请求。
  const started = Date.now();
  try {
    const body = await bridgeCall('detail', { id: path });
    const payload = typeof body === 'string' ? JSON.parse(body) : body;
    if (payload && payload.status === 0) throw new Error(payload.msg || '接口返回错误');
    // 信封名也是配置（web.shapes.envelope）。缺省 'data'；配成空串表示
    // 「整份响应就是我要的那个对象」—— 有的站的接口是裸的，AGE动漫 就是。
    const envelope = shape.envelopeKey();
    if (!payload) throw new Error('接口响应为空');
    const data = envelope ? payload[envelope] : payload;
    if (data === undefined || data === null) {
      throw new Error(envelope ? ('接口响应缺少 ' + envelope) : '接口响应为空');
    }
    // 成功也要留痕：只记失败的话，出问题时没法区分「接口通了」和「压根没调接口」。
    console.log('[api] ' + path + ' ok ' + (Date.now() - started) + 'ms ' + (typeof body === 'string' ? body.length + 'B' : 'json'));
    return data;
  } catch (error) {
    console.log('[api] ' + path + ' FAIL ' + (Date.now() - started) + 'ms ' + (error.message || error));
    throw error;
  }
}

async function callApi(action, args) {
  const raw = await fetchApi(shape.apiPathFor(action, args));
  if (action === 'home') return shape.adaptHome(raw);
  if (action === 'detail') return shape.adaptDetail(raw);
  if (action === 'episode') return shape.adaptEpisode(raw);
  if (action === 'search') return shape.adaptSearch(raw);
  if (action === 'category') return shape.adaptCategory(raw);
  if (action === 'recommend' || action === 'newest') return shape.adaptCategory(raw);
  if (action === 'tag-list') return shape.adaptTagList(raw);
  if (action === 'tags') return shape.adaptTags(raw);
  return raw;
}

function noticeApiFailure(text) {
  console.log('[api] notice: ' + text);
  let debug = false;
  try { debug = localStorage.getItem('hgpref:apidebug') === '1'; } catch (error) {}
  if (!debug) return;
  let box = document.getElementById('api-note');
  if (!box) {
    box = document.createElement('div');
    box.id = 'api-note';
    box.style.cssText = 'position:fixed;left:8px;right:8px;bottom:96px;z-index:99999;background:rgba(120,0,0,.85);color:#fff;font:11px/1.5 monospace;padding:6px 8px;border-radius:8px;pointer-events:none';
    (document.getElementById('app') || document.body).appendChild(box);
  }
  box.textContent = '接口请求失败：' + text;
  clearTimeout(noticeApiFailure.timer);
  noticeApiFailure.timer = setTimeout(() => box.remove(), 6000);
}
