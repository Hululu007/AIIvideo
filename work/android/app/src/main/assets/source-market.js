'use strict';
// 自 app.js 拆出：源市场、TVBox 订阅导入、源设置页。
// 这三块只碰「源的地址清单」，不碰当前生效配置的内容（那是 Java 侧 user-source.json）。
// 入口还留在 app.js，这里提供 renderMarket / goMarket / renderSourceSettings / goSourceSettings。
// 内容源管理。
//
// 存的是**地址清单**，配置内容只有当前生效那一份（Java 侧 user-source.json）。
// 切换 = 拿那个地址的本地缓存 + 覆盖当前配置，跟着第一次填走完全一样的校验 ——
// 校验和干跑一个都不少（那才是「别把应用锁死」的那道闸），少的只是**网络那一段**。
// 缓存不在时（第一次切到某个源）照旧现场拉一次，拉回来的那份会存成缓存。
// ── 源市场与 TVBox 订阅导入 ────────────────────────────────────────────
// 市场是一份静态清单（服务器上的 market.json），点一下等于走一遍和手动粘贴
// 完全相同的 set 流程 —— 校验链一条不少，市场只是把「从哪找地址」这件事
// 替你办了。TVBox 单仓配置同理：拉回来解析出 type 0/1 的源，逐个可加。
// 市场地址不内置 —— App 出厂不带任何地址，谁来维护清单由用户自己决定。
// 用户粘一份清单地址，存在本地；换地址随时改。清单格式见 market.json 约定。
const MARKET_KEY = 'hgmarket:v1';
const MARKET_SRC_KEY = 'hgmarket:src';
const MARKET_TTL = 6 * 3600 * 1000;

function marketSource() {
  try { return String(localStorage.getItem(MARKET_SRC_KEY) || ''); } catch (error) { return ''; }
}
function setMarketSource(url) {
  try { localStorage.setItem(MARKET_SRC_KEY, url); localStorage.removeItem(MARKET_KEY); } catch (error) {}
}

// 经原生 HTTP 抓任意 https 文本（__source:fetch）。WebView 直接 fetch 会被 CORS
// 拦，第三方源清单不会给我们带 ACAO 头。
async function fetchTextViaBridge(url) {
  const result = await sourceCommand('fetch?url=' + encodeURIComponent(url));
  return String(result.body || '');
}

async function loadMarket(force = false) {
  const source = marketSource();
  if (!source) throw new Error('还没有填写源市场地址');
  if (!force) {
    try {
      const cached = JSON.parse(localStorage.getItem(MARKET_KEY) || 'null');
      if (cached && cached.at && Date.now() - cached.at < MARKET_TTL) return cached;
    } catch (error) {}
  }
  const text = await fetchTextViaBridge(source);
  const parsed = JSON.parse(text);
  const items = asArray(parsed.sources).filter(item => item && /^https:\/\//.test(String(item.url || '')));
  if (!items.length) throw new Error('市场清单是空的');
  const cache = { at: Date.now(), updated: parsed.updated || '', items };
  try { localStorage.setItem(MARKET_KEY, JSON.stringify(cache)); } catch (error) {}
  return cache;
}

// 加源并在成功后重启（换源必须重建 Activity，理由见 restartAfterSource）。
async function addSourceFromMarket(url) {
  const result = await sourceCommand('set?url=' + encodeURIComponent(url));
  if (!result || !result.ok) throw new Error((result && result.error) || '设置失败');
  toast('已切换到这个源，正在重启…');
  restartAfterSource(() => { try { location.reload(); } catch (error) {} });
}

function renderMarket() {
  const source = marketSource();
  const m = state.market || { loading: true };
  const saved = new Set(asArray(state.sources).map(item => item.url));
  const active = asArray(state.sources).find(item => item.active);
  if (!source) {
    content.innerHTML = `<div class="listing-page"><button type="button" class="listing-back" data-back="1">${icon('back')} 返回设置</button>
      <h1>源市场</h1>
      <p>粘一个源清单地址。清单由提供者维护，App 不内置任何地址。</p>
      <form id="market-form" class="password-form">
        <label for="market-value">源市场地址</label>
        <input id="market-value" type="text" inputmode="url" autocomplete="off" spellcheck="false" placeholder="https://example.com/market.json">
        <p class="field-hint">地址必须以 https:// 开头，返回 JSON：{"sources":[{"name":"…","desc":"…","url":"…"}]}</p>
        <p id="market-error" class="auth-error" role="alert" hidden></p>
        <button type="submit">使用这个市场</button>
      </form>
      <p class="market-footnote">没有清单地址？可以在「内容源」里手动添加单个源，或导入 TVBox 配置。</p>
    </div>`;
    return;
  }
  const cards = m.loading
    ? '<div class="market-loading"><span class="search-spinner" aria-hidden="true"></span><p>正在获取源清单…</p></div>'
    : m.error
      ? `<div class="market-loading"><p>${esc(m.error)}</p><button type="button" data-market-reload="1">重试</button></div>`
      : asArray(m.items).map(item => {
          const url = String(item.url || '');
          const isActive = active && active.url === url;
          const isSaved = saved.has(url);
          return `<li class="market-item">
            <div class="market-copy"><strong>${esc(item.name || url)}</strong><small>${esc(item.desc || '')}</small></div>
            <button type="button" class="market-add" data-market-use="${esc(url)}" ${isActive ? 'disabled' : ''}>${isActive ? '使用中' : isSaved ? '切换' : '添加并使用'}</button>
          </li>`;
        }).join('');
  content.innerHTML = `<div class="listing-page"><button type="button" class="listing-back" data-back="1">${icon('back')} 返回设置</button>
    <h1>源市场</h1>
    <p>清单里的源，点一下就会添加并切换过去，需要重启应用。</p>
    <ul class="market-list">${cards}</ul>
    <p class="market-footnote">清单来自 ${esc(source)}。<button type="button" class="market-change" data-market-change="1">更换地址</button></p>
  </div>`;
}

function goMarket() {
  state.marketReturn = captureScreen();
  state.market = { loading: Boolean(marketSource()) };
  setRoute('market');
  renderMarket();
  if (!marketSource()) return;
  loadMarket(true).then(cache => {
    state.market = { loading: false, items: cache.items };
    if (state.route === 'market') renderMarket();
  }).catch(error => {
    state.market = { loading: false, error: error.message || '获取失败' };
    if (state.route === 'market') renderMarket();
  });
}

// 拉一份 TVBox 单仓配置并解析。结果留在 state.tvbox 里，页面重渲染不会丢。
async function importTvBox(url) {
  state.tvbox = { url, loading: true, items: [], error: '' };
  if (state.route === 'source-settings') renderSourceSettings();
  try {
    const text = await fetchTextViaBridge(url);
    const items = parseTvBox(text);
    state.tvbox = { url, loading: false, items, error: '' };
    try { localStorage.setItem(TVBOX_KEY, JSON.stringify({ url, at: Date.now() })); } catch (error) {}
  } catch (error) {
    state.tvbox = { url, loading: false, items: [], error: '解析失败：' + (error.message || error) };
  }
  if (state.route === 'source-settings') renderSourceSettings();
}

// TVBox 单仓配置：{sites:[{type,key,name,api}]}。只认 type 0/1（苹果CMS 采集），
// spider JAR 那类需要下载执行代码的，明确标不支持、不显示。
const TVBOX_KEY = 'tvbox:v1';
function parseTvBox(text) {
  const parsed = JSON.parse(text);
  const sites = asArray(parsed.sites);
  if (!sites.length) throw new Error('这份配置里没有 sites 列表');
  return sites
    .filter(site => site && [0, 1, '0', '1'].includes(site.type))
    .map(site => ({
      name: String(site.name || site.key || '未命名'),
      api: String(site.api || '').trim(),
      group: String(site.group || ''),
    }))
    .filter(site => /^https?:\/\//.test(site.api));
}


// TVBox 订阅区：粘一个单仓地址，解析出里面能用的 type 0/1 源。
// 解析结果存在 state.tvbox 里，重新进设置页也还在（同一会话内），不用反复粘贴。
function tvboxSection() {
  const box = state.tvbox;
  const rows = box && box.items && box.items.length
    ? `<ul class="tvbox-list">${box.items.map(site => `
        <li class="tvbox-item"><div class="tvbox-copy"><strong>${esc(site.name)}</strong><small>${esc(site.api)}</small></div>
        <button type="button" class="tvbox-add" data-tvbox-use="${esc(site.api)}">添加并使用</button></li>`).join('')}</ul>`
    : box && box.items
      ? '<p class="field-hint">这份配置里没有能直接用的苹果CMS源（spider 类不支持）。</p>'
      : '';
  return `<section class="settings-card" aria-label="TVBox 导入">
    <div class="settings-row is-static">
      <span class="settings-icon">${icon('update')}</span>
      <span class="settings-body"><strong>TVBox 配置</strong><small>只导入苹果CMS源，spider 类会跳过</small></span>
    </div>
    <form id="tvbox-form" class="password-form settings-form${box && box.loading ? ' is-loading' : ''}">
      <input id="tvbox-value" type="text" inputmode="url" autocomplete="off" spellcheck="false" placeholder="https://example.com/tvbox.json" value="${esc(box?.url || '')}" aria-label="TVBox 地址">
      ${box && box.error ? `<p class="auth-error" role="alert">${esc(box.error)}</p>` : ''}
      <button type="submit" ${box && box.loading ? 'disabled' : ''}>${box && box.loading ? '正在解析…' : '解析配置'}</button>
    </form>
    ${rows}
  </section>`;
}

// ── spider 源（阶段 1）─────────────────────────────────────────────
//
// 这一段是「加源不发版」第一次露在界面上：源不再是配置里的一个地址，而是一个
// 可以随时下发的 js 文件。列表来自 Java 的 SpiderStore（热更 ui/spiders/ 优先，
// APK assets 兜底），每一条都带 ok/reason —— 被 ABI 门禁拒掉的也在里面，因为
// 「为什么这个源用不了」比「它不在列表里」有用得多。
//
// 点一下跑 home，只报卡片数。阶段 1 的目标是证明这条链路通了，不是做播放器；
// 等模型定稿（阶段 2）再把结果接进真正的渲染路径。

async function loadSpiderSources(force = false) {
  if (state.spiders && !force) return state.spiders;
  try {
    const reply = await bridgeCall('spider', { op: 'list' });
    state.spiders = { abi: Number(reply?.abi) || 0, items: asArray(reply?.spiders), error: '' };
  } catch (error) {
    state.spiders = { abi: 0, items: [], error: error.message || String(error) };
  }
  return state.spiders;
}

function spiderSection() {
  const box = state.spiders;
  const items = asArray(box?.items);
  const rows = items.map(item => {
    const ok = item.ok === true || item.ok === 'true';
    const origin = item.source === 'user' ? '已安装' : (item.source === 'hot' ? '热更' : '内置');
    const meta = [item.id, 'v' + (item.version || 0), origin].filter(Boolean).join(' · ');
    const chip = ok ? '可用' : '不可用';
    const user = item.source === 'user';
    return `<li class="source-row${ok ? '' : ' is-disabled'}">
      <button type="button" class="source-pick" data-spider-call="${esc(item.id)}" ${ok ? '' : 'disabled'}>
        <span><strong>${esc(item.name || item.id || '未命名')}</strong><small>${esc(meta)}</small></span>
        <em class="${ok ? 'is-ok' : 'is-warn'}">${esc(chip)}</em>
      </button>
      ${user ? `<button type="button" class="source-drop" data-spider-remove="${esc(item.id)}" aria-label="删除 ${esc(item.name || item.id)}">×</button>` : ''}
      ${ok ? '' : `<p class="settings-form-hint">${esc(item.reason || '加载不了')}</p>`}
    </li>`;
  }).join('');
  const hint = box == null
    ? '正在读源清单…'
    : (items.length ? `${items.length} 个 · 点一个跑一次 home` : '这个版本还没装任何 spider 源');
  return `<section class="settings-card" aria-label="spider 源">
    <div class="settings-row is-static">
      <span class="settings-icon">${icon('source')}</span>
      <span class="settings-body"><strong>spider 源</strong><small>${esc(box?.error || hint)}</small></span>
      <span class="settings-tail"><button type="button" class="settings-mini" data-spider-refresh="1">刷新</button></span>
    </div>
    ${items.length ? `<ul class="source-list">${rows}</ul>` : ''}
    <form class="password-form settings-form" data-spider-install="1">
      <input id="spider-url" type="text" inputmode="url" autocomplete="off" autocapitalize="off"
             spellcheck="false" placeholder="https://…/source.js" aria-label="spider 源地址">
      <button type="submit">添加源</button>
    </form>
    <p class="settings-form-hint">${box ? 'ABI ' + box.abi + ' · 贴一个 js 地址即可，不用更新应用' : ''}</p>
  </section>`;
}

function renderSourceSettings() {
  const items = asArray(state.sources);
  const active = items.find(item => item.active);
  const rows = items.map(item => {
    const url = String(item.url || '');
    const label = item.alias || item.name || url;
    // 备注编辑：就地展开，不弹窗。备注只是给人看的一行字，改它不值得一个模态。
    if (state.sourceNote === url) {
      return `<li class="source-row is-editing">
        <form class="source-note-form" data-source-note-form="${esc(url)}">
          <label for="source-note">备注（可留空）</label>
          <input id="source-note" type="text" maxlength="40" autocomplete="off" placeholder="${esc(item.name || url)}" value="${esc(item.alias || '')}">
          <div class="source-note-actions">
            <button type="submit">保存</button>
            <button type="button" data-source-note-clear="${esc(url)}" ${item.alias ? '' : 'disabled'}>清空</button>
            <button type="button" data-source-note-cancel="1">取消</button>
          </div>
        </form>
      </li>`;
    }
    return `<li class="source-row${item.active ? ' is-active' : ''}">
      <button type="button" class="source-pick" data-source-use="${esc(url)}" ${item.active ? 'disabled' : ''}>
        <span><strong>${esc(label)}</strong><small>${esc(url)}</small></span>
        ${item.active ? '<em>当前</em>' : icon('arrow')}
      </button>
      <button type="button" class="source-note" data-source-note="${esc(url)}" aria-label="改 ${esc(label)} 的备注">备注</button>
      <button type="button" class="source-drop" data-source-forget="${esc(url)}" aria-label="删除 ${esc(label)}">×</button>
    </li>`;
  }).join('');

  content.innerHTML = `<div class="settings-page">
    <button type="button" class="listing-back" data-back="1">${icon('back')} 返回设置</button>
    <h1>内容源</h1>

    <section class="settings-card" aria-label="已保存的源">
      <div class="settings-row is-static">
        <span class="settings-icon">${icon('source')}</span>
        <span class="settings-body"><strong>已保存</strong><small>${items.length ? `${items.length} 个 · 点一个切过去` : '还没有保存过任何源'}</small></span>
        <span class="settings-tail"><button type="button" class="settings-mini" data-market="1">源市场</button></span>
      </div>
      ${items.length ? `<ul class="source-list">${rows}</ul>` : ''}
      <p class="settings-form-hint">切换源会重启应用。第二次之后走本地缓存，不用再等网络。</p>
      ${active ? `<div class="settings-row is-action"><button type="button" class="settings-quiet" data-source-refetch="${esc(String(active.url || ''))}">重新拉取这个源的配置</button></div>` : ''}
    </section>

    <section class="settings-card" aria-label="添加源">
      <div class="settings-row is-static">
        <span class="settings-icon">${icon('market')}</span>
        <span class="settings-body"><strong>添加一个源</strong><small>地址必须以 https:// 开头</small></span>
      </div>
      <form id="source-form" class="password-form settings-form">
        <input id="source-value" type="text" inputmode="url" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="https://example.com/source.json" aria-label="源地址">
        <input id="source-alias" type="text" maxlength="40" autocomplete="off" placeholder="备注（可留空），比如「量子」" aria-label="备注">
        <p id="source-error" class="auth-error" role="alert" hidden></p>
        <button type="submit">添加并切换</button>
      </form>
    </section>

    ${tvboxSection()}
    ${spiderSection()}
  </div>`;
}

/** 写/清一个源的备注。空串 = 清掉，退回自动取的名字。 */
// spider 的点击与刷新走 document 上的委托：这几行是这个文件自己的一小块，
// 不值得再往 app.js 那个巨大的点击处理器里塞一条分支。
document.addEventListener('submit', async event => {
  const form = event.target.closest('[data-spider-install]');
  if (!form) return;
  event.preventDefault();
  const input = form.querySelector('#spider-url');
  const url = String(input?.value || '').trim();
  if (!/^https:\/\//i.test(url)) { toast('地址必须以 https:// 开头'); return; }
  input.disabled = true;
  toast('正在下载源…', true);
  try {
    const meta = await bridgeCall('spider', { op: 'install', url });
    toast('已添加 ' + (meta?.name || meta?.id || '源'));
    input.value = '';
    state.spiders = null;
    await loadSpiderSources(true);
  } catch (error) {
    toast('添加失败：' + (error.message || error));
  } finally {
    input.disabled = false;
    if (state.route === 'source-settings') renderSourceSettings();
  }
});

document.addEventListener('click', async event => {
  const drop = event.target.closest('[data-spider-remove]');
  if (drop) {
    event.preventDefault();
    const id = drop.getAttribute('data-spider-remove');
    const accepted = await askConfirmation('删除这个源', '删掉之后它就不再出现在列表里，可以重新添加。', '删除');
    if (!accepted) return;
    try {
      await bridgeCall('spider', { op: 'remove', id });
      state.spiders = null;
      await loadSpiderSources(true);
    } catch (error) {
      toast('删除失败：' + (error.message || error));
    }
    if (state.route === 'source-settings') renderSourceSettings();
    return;
  }
  const call = event.target.closest('[data-spider-call]');
  const refresh = event.target.closest('[data-spider-refresh]');
  if (!call && !refresh) return;
  event.preventDefault();
  if (refresh) {
    state.spiders = null;
    await loadSpiderSources(true);
    if (state.route === 'source-settings') renderSourceSettings();
    return;
  }
  const id = call.getAttribute('data-spider-call');
  const label = call.querySelector('strong')?.textContent || id;
  // 一次点按跑完整条链：home → detail（第一张卡的详情）→ play（第一集）。
  //
  // 只跑 home 证明不了什么 —— 选集和播放地址才是这条链路容易断的地方
  // （集号是站点自己的字符串、线路要现取、地址在一段时间内有效）。
  // 三段都过，这个源才算真的能用。
  toast(label + ' · 正在跑 home…', true);
  const t0 = Date.now();
  try {
    const home = await bridgeCall('spider', { op: 'call', id, cap: 'home', timeoutMs: 45000 });
    const cards = asArray(home?.items);
    if (!cards.length) throw new Error('home 没有返回卡片');
    const ref = String(cards[0].id || '');
    toast(label + ' · ' + cards.length + ' 张卡 · 取详情…', true);

    const detail = await bridgeCall('spider', { op: 'call', id, cap: 'detail', args: { ref }, timeoutMs: 45000 });
    const eps = asArray(detail?.episodes);
    const lines = asArray(detail?.lines);
    if (!eps.length) throw new Error('detail 没有返回剧集');

    const first = eps[0];
    const line = first.line || (lines[0] && lines[0].line) || 'default';
    toast(label + ' · ' + eps.length + ' 集 · 取播放地址…', true);
    const play = await bridgeCall('spider', {
      op: 'call', id, cap: 'play', args: { ref, line }, timeoutMs: 45000
    });
    const url = String(play?.url || '');
    if (!url) throw new Error('play 没有返回地址');
    const secs = ((Date.now() - t0) / 1000).toFixed(1);
    toast(label + ' · ' + cards.length + ' 卡 / ' + eps.length + ' 集 / ' + asArray(play.alts).length
      + ' 备选 · ' + secs + 's');
  } catch (error) {
    toast(label + ' · 失败：' + (error.message || error));
  }
});

async function saveSourceNote(url, text) {
  const clean = String(text == null ? '' : text).trim();
  try {
    const result = await sourceCommand('note?url=' + encodeURIComponent(url) + '&value=' + encodeURIComponent(clean));
    state.sources = asArray(result.items);
    state.sourceNote = '';
    renderSourceSettings();
    toast(clean ? '备注已保存' : '备注已清空');
  } catch (error) {
    toast(error.message, true);
  }
}

async function refreshSources() {
  const listing = await sourceCommand('list');
  state.sources = asArray(listing.items);
  const active = state.sources.find(item => item.active);
  state.sourceName = active ? (active.alias || active.name || active.url) : '';
  state.sourceCount = state.sources.length;
  // 挂出去给 feed-player.js 用：上报观看进度时要带上「这条属于哪个源」。
  //
  // 存的是**源身份**（Java 侧 UserSource.identity() 算的），不是源名字。
  // 规则：这套配置把 /api/videos/{id} 翻成哪个地址，把 {id} 挖掉剩下的那串。
  // 名字会被人改、同一个站还能挂两个名字，靠名字的话改一次名历史就全断；
  // 而 id 只在源内部唯一，只有「翻到哪儿」才决定了历史能不能互通。
  window.HG_SOURCE_KEY = listing.identity || '';
}

// 这条历史记录属不属于当前内容源。
//
// 三种情况当「算」，都按当前源显示：
//   · 记录压根没记来源   —— 这次改造之前的老记录
//   · 记的不是源身份     —— 早期版本存的是「源名字」，没有 hgsrc: 前缀。
//                          不加这条，升级之后所有人的历史会一次性消失。
//   · 当前源身份还没读出来 —— 首屏那几帧
function sameSource(item) {
  const mine = window.HG_SOURCE_KEY || '';
  const theirs = String(item?.source || '');
  if (!theirs || !mine) return true;
  if (theirs.indexOf(IDENTITY_PREFIX) !== 0) return true;
  return theirs === mine;
}

function goSourceSettings() {
  if (state.route === 'source-settings') return;
  if (state.route !== 'settings') goSettings();
  state.sourceReturn = captureScreen();
  setRoute('source-settings');
  renderSourceSettings();
  refreshSources().then(() => { if (state.route === 'source-settings') renderSourceSettings(); }).catch(() => {});
  // spider 清单是本地文件 IO，很快；读到之后单独重画一次，不挡源列表。
  loadSpiderSources().then(() => { if (state.route === 'source-settings') renderSourceSettings(); }).catch(() => {});
  content.querySelector('#source-value')?.focus();
}
