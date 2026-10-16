'use strict';

const callbacks = new Map();
let requestNumber = 0;
let contentSource = 'hongguo';
let contentReady;
const bridgeInstance = Date.now() + '-' + Math.random().toString(36).slice(2);
const privateMode = () => contentSource === 'huangguo';
window.NativeCallbacks = {
  resolve(id, value) { callbacks.get(id)?.resolve(value); callbacks.delete(id); },
  reject(id, message) { callbacks.get(id)?.reject(new Error(message)); callbacks.delete(id); }
};
// 接口给的封面有两种形态：/api/videos 是完整地址（尾上挂个带过期时间的 auth_key），
// /api/home 是裸相对路径 /upload_01/upload/xxx.jpg。相对路径直接塞进 <img src> 会被
// 解析到 appassets.androidplatform.net 底下，必然 404；而 Java 的 SiteProfile.coverUrl
// 要求 URI 必须是绝对 https + host 在 cover.allowedHosts 白名单里，喂相对路径第一关
// 就抛「无效封面地址」。所以这里统一补域名。实测图片 CDN 不校验 auth_key（拿伪造的
// 过期签名一样 200），顺手剥掉，换成永久地址。
//
// 注意：封面本身是 AES-CBC 加密的（site-default.json: key=f5d965df75336270,
// iv=97b60374abc2fbe1, AES/CBC/NoPadding + headerRepair=legacy-xor），补完域名之后
// 必须继续交给 Java 的 cover 动作解密，不能直接把地址丢给 <img>。
// URL 归一已移进 cover.js（IMG_ORIGIN 和补全逻辑都在那边），这里只留个别名，
// 因为 adaptVideo / adaptHome 拼卡片数据时要反复用。
const coverImageUrl = raw => window.HGCover.resolveUrl(raw);
function bridgeCall(action, args = {}) {
  return new Promise((resolve, reject) => {
    const id = bridgeInstance + ':' + (++requestNumber);
    callbacks.set(id, { resolve, reject });
    try { AndroidHost.request(id, action, JSON.stringify({ ...args, source: contentSource })); }
    catch (error) { callbacks.delete(id); reject(error); }
  });
}
// 源相关的桥指令统一入口。__source:* 走的是 detail 那条桥（MainActivity 的 dispatch
// 是按编号跳的 packed-switch，加动作要同时改两张表 —— 本工程最容易出事的地方），
// 这里包一层，调用方只管写指令。
async function sourceCommand(command) {
  const reply = await bridgeCall('detail', { id: '__source:' + command });
  const result = typeof reply === 'string' ? JSON.parse(reply) : reply;
  if (result && result.ok === false) throw new Error(result.error || '操作失败');
  return result || {};
}

// 换源之后必须让 Java 侧重建 Activity。SiteProfile 是在 onCreate 的链条里构造的
// （beginSession → new SiteProfile），而它每构造一次都重读 files/user-source.json ——
// 所以「重建 Activity」就等于「新配置生效」。只 location.reload() 不够：页面重载了
// 但 Activity 还在，配置还是旧的。
//
// 这里刻意不 await：Java 那边先 post 到主线程、200ms 后才 recreate，等它的话页面
// 会在被销毁前挂起，promise 永远不 resolve，界面就卡在「正在切换…」上。所以发出去
// 就不管了，只留一个兜底 —— 2.5 秒后这个页面还在，说明重建没发生，把状态改成人话。
function restartAfterSource(onStuck) {
  sessionStorage.clear();
  sourceCommand('restart').catch(() => {});
  window.setTimeout(() => { if (typeof onStuck === 'function') onStuck(); }, 2500);
}

function api(action, args = {}) {
  const run = async () => {
    // REST 接口统一走 Java api-json 桥，避免 WebView 自动附带 Origin 请求头。
    if (shape.apiPathFor(action, args)) {
      try { return await callApi(action, args); }
      catch (error) {
        console.log('[api] ' + action + ' 接口请求失败：' + (error.message || error));
        if (typeof noticeApiFailure === 'function') noticeApiFailure(action + ': ' + (error.message || error));
        // 绝不回退到旧 HTML 抓取：它仍可能访问被污染的 huangguoai.com，
        // 会把 2 秒级接口故障拖成 40 秒等待。让对应页面立即进入错误态。
        throw error;
      }
    }
    return bridgeCall(action, args);
  };
  return contentReady ? contentReady.then(run) : run();
}

const scroll = document.getElementById('scroll');
const content = document.getElementById('content');
// 封面模块（cover.js）。api 是函数声明会被提升，所以这里放在 content/scroll
// 之后就能拿到三样依赖，不用往后挪。
const cover = window.HGCover.create({ api, content, scroll });
// 站点接口的形状（adapters.js）：动作名 → REST 路径、响应 JSON → 内部结构。
// 纯逻辑，不碰 DOM 也不发请求，所以只需要一个封面地址归一函数。
const shape = window.HGShape.create({ resolveCoverUrl: cover.resolveUrl });
const avatarButton = document.getElementById('account-avatar');
const avatarImage = document.getElementById('account-avatar-image');
const authOverlay = document.getElementById('auth-overlay');
const compact = document.getElementById('compact-category');
const toastHost = document.getElementById('toast-host');
const searchInput = document.getElementById('search-input');
const headerSettings = document.getElementById('header-settings');
const confirmOverlay = document.getElementById('confirm-overlay');
const unlockOverlay = document.getElementById('unlock-overlay');
// 界面常量全部来自 site-default.json 的 web.ui 块。
//
// 这里**不在模块加载时捕获**，而是每次实时读 window.HG_SITE —— 顺序上的原因：
// site-web.js 是打包时从内置模板生成的，而用户可以在运行时换源，换完之后
// paths/categories/fields 可能都不一样。启动时 setup.js 会调 __source:web
// 把当前生效的 web 块写回 window.HG_SITE，实时读才看得到新值。
// 捕获成 const 的话，那次写入就白写了。
function siteUi() {
  return (window.HG_SITE && window.HG_SITE.ui) || {};
}

// ── 底部导航的中间几格 ──────────────────────────────────────────────
//
// 内置那套是黄果的「剧场 / AI」。换一个源这两个名字就没意义了 —— 苹果 CMS 的分法是
// 「电影/连续剧/综艺/动漫」，源里根本没有「AI」这种东西。两个入口指向同一份数据、
// 名字还跟内容对不上，就是这么来的。
//
// 所以这排按钮也归配置：web.ui.tabs = [{key, label, icon}]。
//   key   —— data-nav 的值，同时是 categoryIds / categoryLabels / categoryTitles
//            / categoryLeads 的键。定下来就别改，换名字等于丢分类。
//   label —— 按钮上的字
//   icon  —— dockIcons 里的名字，认不出来退回 grid
//
// 数量由配置决定，1 到 4 个都行（首页和「我的」不占这个额度）。dock 的列数是按
// 实际长度写进 style 的 —— CSS 里那个 repeat(4,1fr) 是给默认那套写死的，直接复用
// 会让 3 个按钮挤在左半边。
const dockIcons = {
  home: '<path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1z"/>',
  film: '<rect x="3" y="5" width="18" height="15" rx="2"/><path d="M3 10h18M8 5l3 5m3-5 3 5"/>',
  sparkle: '<path d="m12 2 1.8 6.2L20 10l-6.2 1.8L12 18l-1.8-6.2L4 10l6.2-1.8zM19 17l.6 1.4L21 19l-1.4.6L19 21l-.6-1.4L17 19l1.4-.6z"/>',
  user: '<circle cx="12" cy="8" r="3.5"/><path d="M4.5 21c.5-4.3 3-6.5 7.5-6.5s7 2.2 7.5 6.5"/>',
  grid: '<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>',
  fire: '<path d="M12 3c1.2 3.2 4 4.4 4 7.4a4 4 0 0 1-8 .2c0-3.4 3-4.2 4-7.6z"/><path d="M8.6 13.8A5.6 5.6 0 0 0 12 21a5.6 5.6 0 0 0 4.6-7.8"/>',
  tv: '<rect x="3" y="6" width="18" height="12" rx="2"/><path d="m9 3 3 3 3-3"/>',
  star: '<path d="m12 3 2.6 5.9 6.4.6-4.8 4.3 1.4 6.3L12 16.9 6.4 20l1.4-6.3L3 9.5l6.4-.6z"/>'
};

const DEFAULT_TABS = [
  { key: 'adult', label: '剧场', icon: 'film' },
  { key: 'ai', label: 'AI', icon: 'sparkle' }
];

const dockIcon = name => {
  const body = dockIcons[name] || dockIcons.grid;
  return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${body}</svg>`;
};

function siteTabs() {
  const configured = siteUi().tabs;
  if (!Array.isArray(configured) || !configured.length) return DEFAULT_TABS;
  const seen = new Set();
  const tabs = [];
  for (const tab of configured) {
    if (!tab || typeof tab.key !== 'string' || !tab.key) continue;
    if (seen.has(tab.key)) continue;          // 同一个 key 两次 = 两个入口同一份数据，就是现在的毛病
    seen.add(tab.key);
    tabs.push({
      key: tab.key,
      label: typeof tab.label === 'string' && tab.label ? tab.label : tab.key,
      icon: typeof tab.icon === 'string' && dockIcons[tab.icon] ? tab.icon : 'grid'
    });
    if (tabs.length === 5) break;             // 加首页和「我的」是 7 格，360dp 的机器上
                                              // 每格 ~52dp —— 再多 label 就要被压成两个字
  }
  return tabs.length ? tabs : DEFAULT_TABS;
}

const dockButton = (key, label, iconName) =>
  `<button type="button" data-nav="${esc(key)}"><span class="nav-icon">${dockIcon(iconName)}</span><span>${esc(label)}</span></button>`;

// 整排重画。必须在 startHome() 之前调 —— 配置是闸门那一步（HGSetup.ensure →
// applySiteConfig）写进 HG_SITE 的，所以第一帧拿到的就已经是最终的那份。
function renderDock() {
  const dock = document.getElementById('dock');
  if (!dock) return;
  const tabs = siteTabs();
  dock.style.gridTemplateColumns = `repeat(${tabs.length + 2}, 1fr)`;
  dock.innerHTML = dockButton('home', '首页', 'home')
    + tabs.map(tab => dockButton(tab.key, tab.label, tab.icon)).join('')
    + dockButton('library', '我的', 'user');
  const active = state.tab || 'home';
  dock.querySelectorAll('[data-nav]').forEach(button => button.classList.toggle('active', button.dataset.nav === active));
}


// 分类 tab 的显示名。默认那套是黄果专用的（成人短剧/成人漫剧…），而来源换成
// 苹果CMS 之后分类是「电影/电视剧/综艺」—— 标签必须跟着源走，所以配置里能覆盖
// （web.ui.categoryLabels），覆盖不了才退回默认。
function categoryTabs() {
  const configured = (siteUi().categoryLabels || {})[state.tab];
  if (configured && configured.length) return configured;
  const adult = state.tab === 'adult';
  if (!privateMode()) return [adult ? '短剧' : '漫剧'];
  return adult ? ['成人短剧', '成人漫剧'] : ['AI换脸', 'AI魔改'];
}

// 分类页标题，同样可被配置覆盖（web.ui.categoryTitles）。
function categoryTitle() {
  const configured = (siteUi().categoryTitles || {})[state.tab];
  // 只认字符串。配错了（比如塞了数组）就退回默认 —— 不然插进 innerHTML 会变成
  // 「电影片,连续剧,综艺片,…」几十个名字串成一行的巨大标题。
  if (typeof configured === 'string' && configured) return configured;
  const adult = state.tab === 'adult';
  if (!privateMode()) return adult ? '短剧' : '漫剧';
  return adult ? '成人剧场' : 'AI专区';
}

// 分类页副标题。默认那两句是黄果专用的（「短剧与漫剧，走进不同的人生。」），
// 换个源之后就是错的 —— 一样做成可配置。
function categoryLead() {
  const configured = (siteUi().categoryLeads || {})[state.tab];
  if (typeof configured === 'string' && configured) return configured;
  const adult = state.tab === 'adult';
  if (!privateMode()) return adult ? '真人演的看了不烧心' : '探索热门漫剧。';
  return adult ? '短剧与漫剧，走进不同的人生。' : '换脸与魔改，探索影像新可能。';
}
const state = {
  route: 'home', tab: 'home', sub: { adult: 0, ai: 0 }, home: null,
  category: null, categoryItems: [], detail: null, detailLoading: false, sourceItem: null,
  search: null, searchPending: false, searchError: '', query: '', filter: 'all', banner: 0, categoryBanner: 0,
  listing: null, tags: null, tagsReturn: null, listingReturn: null, settingsReturn: null,
  libraryTab: 'history', libraryQuery: '', accountReturn: null, account: { loggedIn: false },
  sourceGroup: '', sourceQueue: [], previous: null, compactHeld: false
};
// 子分类下标。state.sub 只预置了内置那套的两个 key，而换源之后 state.tab 是配置
// 里给的任意 key（苹果CMS 那边是 "1"/"2"/"3"…），取不到就得回 0 —— 不然
// categoryId() 会算出 undefined，请求落到 Java 桥撞上已抛异常的桩。
const subIndex = tab => state.sub[tab] || 0;
// 冷启动固定进黄果源：先解锁（密码内置），再确认年龄门槛，然后锁定 contentSource。
// 这里直接走桥、不经过 contentReady —— 否则 contentReady 会等 api()，而 api() 又在等 contentReady，死锁。
function bridgeDirect(action, args = {}) {
  return new Promise((resolve, reject) => {
    const id = bridgeInstance + ':' + (++requestNumber);
    callbacks.set(id, { resolve, reject });
    try { AndroidHost.request(id, action, JSON.stringify({ ...args, source: 'huangguo' })); }
    catch (error) { callbacks.delete(id); reject(error); }
  });
}
contentReady = (async () => {
  let status;
  try { status = await bridgeDirect('content-status'); }
  catch (error) { status = { source: 'huangguo', unlocked: true }; }
  if (status.source !== 'huangguo') {
    if (!status.unlocked) status = await bridgeDirect('enter-private', { password: 'ssgnb' });
    status = await bridgeDirect('confirm-private-age');
  }
  contentSource = 'huangguo';
  document.title = 'AIIvideo';
  return status;
})();

let routeToken = 0;
let toastTimer;
let listingObserver;
let categoryObserver;
const screenSnapshots = {};
let predictivePreview;
let predictiveTimer;
let predictiveSourceNode;
let predictiveFrame;
let pendingPredictiveProgress = 0;
let detailCard;
let bannerChanging = false;
let bannerTimer;
let lastDockTap = { tab: '', at: 0, navigated: false };
let appVersion = '';

const esc = value => String(value ?? '').replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character]);
const asArray = value => Array.isArray(value) ? value : [];
const icon = name => {
  const paths = {
    play: '<path d="m8 5 11 7-11 7z"/>',
    arrow: '<path d="m9 5 7 7-7 7"/>',
    back: '<path d="m15 5-7 7 7 7"/>'
  };
  return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name]}</svg>`;
};

function toast(message, info = false) {
  // 每条提示都落到 logcat（MainActivity 的 onConsoleMessage 会转发），
  // 真机上"一闪而过的红条"只能靠这个抓 —— 事后看 DOM 分不清是这次弹的还是上次留的。
  console.log('[toast] ' + message);
  clearTimeout(toastTimer);
  toastHost.innerHTML = `<div class="toast ${info ? 'info' : ''}"><i></i><span>${esc(message)}</span></div>`;
  toastHost.classList.add('visible');
  toastTimer = setTimeout(() => toastHost.classList.remove('visible'), 3000);
}

let finishConfirmation;
function closeConfirmation(accepted) {
  if (confirmOverlay.hidden) return;
  confirmOverlay.hidden = true;
  updateBackEnabled();
  finishConfirmation?.(accepted);
  finishConfirmation = null;
}
function askConfirmation(title, message, action = '清空') {
  document.getElementById('confirm-title').textContent = title;
  document.getElementById('confirm-message').textContent = message;
  document.getElementById('confirm-accept').textContent = action;
  confirmOverlay.hidden = false;
  updateBackEnabled();
  document.getElementById('confirm-cancel').focus();
  return new Promise(resolve => { finishConfirmation = resolve; });
}
document.getElementById('confirm-cancel').addEventListener('click', () => closeConfirmation(false));
document.getElementById('confirm-accept').addEventListener('click', () => closeConfirmation(true));
confirmOverlay.addEventListener('click', event => { if (event.target === confirmOverlay) closeConfirmation(false); });

function setAccount(profile) {
  const next = profile || { loggedIn: false };
  if (JSON.stringify(state.account) === JSON.stringify(next)) return;
  const savedScroll = scroll.scrollTop;
  const userChanged = state.account.userId !== next.userId;
  state.account = next;
  if (userChanged) { state.library = null; if (state.route === 'home') { updateContinueWatching(); refreshHomeHistory(); } }
  avatarImage.hidden = !profile?.avatar;
  avatarButton.querySelector('.account-avatar-default').hidden = Boolean(profile?.avatar);
  if (profile?.avatar) avatarImage.src = profile.avatar;
  else avatarImage.removeAttribute('src');
  avatarButton.classList.toggle('is-signed-in', Boolean(profile?.loggedIn));
  avatarButton.setAttribute('aria-label', profile?.loggedIn ? `打开${profile.username}的账号管理` : '登录账号');
  if (state.route === 'library') renderLibrary();
  if (state.route === 'settings') renderSettings();
  if (state.route === 'account-management' && !content.querySelector('#password-form')?.contains(document.activeElement)) renderAccountManagement();
  if (state.route === 'proxy-settings' && !content.querySelector('#proxy-form')?.contains(document.activeElement)) renderProxySettings();
  if (state.route === 'source-settings' && !content.querySelector('#source-form')?.contains(document.activeElement)) renderSourceSettings();
  scroll.scrollTop = savedScroll;
}
api('account').then(setAccount).catch(() => {});
window.hgWindowInsets = height => {
  document.getElementById('app').style.setProperty('--status-bar-height', `${Math.max(0, Number(height) || 0)}px`);
};
api('window-insets').then(window.hgWindowInsets).catch(() => {});
api('screen-corners').then(corners => {
  const app = document.getElementById('app');
  for (const name of ['topLeft', 'topRight', 'bottomRight', 'bottomLeft']) {
    const radius = Number(corners?.[name]);
    if (radius > 0) app.style.setProperty(`--screen-corner-${name}`, `${radius}px`);
  }
}).catch(() => {});

let authMode = 'login';
let authPending = false;
function setAuthPending(pending, mode = authMode) {
  const submit = document.getElementById('auth-submit');
  submit.disabled = pending;
  submit.classList.toggle('is-loading', pending);
  submit.setAttribute('aria-busy', String(pending));
  submit.textContent = pending ? (mode === 'login' ? '正在登录…' : '正在注册…') : (mode === 'login' ? '登录' : '注册并登录');
  document.querySelectorAll('[data-auth-mode]').forEach(button => { button.disabled = pending; });
}
function showAuth(mode = 'login') {
  if (authPending) mode = authMode;
  authMode = mode;
  authOverlay.hidden = false;
  updateBackEnabled();
  document.getElementById('auth-title').textContent = mode === 'login' ? '登录应用账号' : '注册应用账号';
  setAuthPending(authPending, mode);
  document.getElementById('auth-password').autocomplete = mode === 'login' ? 'current-password' : 'new-password';
  document.querySelectorAll('[data-auth-mode]').forEach(button => button.classList.toggle('selected', button.dataset.authMode === mode));
  document.getElementById('auth-error').hidden = true;
}
function hideAuth() {
  authOverlay.hidden = true;
  updateBackEnabled();
  document.getElementById('auth-form').reset();
}
avatarButton.addEventListener('click', () => {
  if (state.account.loggedIn) goAccountManagement();
  else showAuth();
});
headerSettings.addEventListener('click', goSettings);
document.getElementById('auth-close').addEventListener('click', hideAuth);
document.querySelectorAll('[data-auth-mode]').forEach(button => button.addEventListener('click', () => showAuth(button.dataset.authMode)));
document.getElementById('auth-form').addEventListener('submit', async event => {
  event.preventDefault();
  if (authPending) return;
  const mode = authMode;
  const username = document.getElementById('auth-username').value.trim();
  const password = document.getElementById('auth-password').value;
  const error = document.getElementById('auth-error');
  authPending = true;
  setAuthPending(true, mode);
  error.hidden = true;
  try {
    const profile = await api(mode, { username, password });
    hideAuth();
    setAccount(profile);
    if (state.route === 'library') goLibrary(state.libraryTab);
    toast(mode === 'login' ? '登录成功，片单正在同步' : '注册成功，片单正在同步');
  } catch (failure) {
    if (authOverlay.hidden) toast(failure.message || '请稍后重试', true);
    else { error.textContent = failure.message || '请稍后重试'; error.hidden = false; }
  } finally { authPending = false; setAuthPending(false, mode); }
});
window.hgAccountRefreshed = profile => {
  if (profile?.userId && state.account.userId === profile.userId) setAccount(profile);
};
window.hgAvatarUpdated = profile => { setAccount(profile); toast('头像已更新'); };
window.hgAvatarError = message => toast(message || '头像保存失败', true);
window.hgCloudSynced = () => {
  if (state.route === 'home') refreshHomeHistory();
  else if (state.route === 'library') {
    const token = routeToken;
    api('library').then(data => { if (token !== routeToken) return; state.library = data; renderLibrary(); }).catch(() => {});
  }
};

// ── 应用更新面板已移除（2026-10-16）──────────────────────────────────────
// 原来这里是一整套「发现新版本 → 展示更新说明 → 下载并安装」的 UI：
// setAvailableUpdate / showUpdate / checkUpdate / hgUpdateProgress /
// hgUpdateInstallPermissionDenied / hgUpdateInstallError，外加下载按钮和
// 「稍后再说」的监听，以及 app.html 里的 #update-overlay 区块和 app.css 的
// 对应样式。全部删掉。
//
// 原因是它背后的通道已经死了：check-update → UpdateManager →
// github.com/MrSsg/HuangguoAPP-for-Andriod（已 404）。checkUpdate 又已经
// 没有任何调用点，所以这一整套连「永远不显示」都算不上，是纯粹的悬空代码。
// 设置页那个版本号按钮保留，它挂着 data-secret-entry，是独立功能。


window.hgTheme = dark => { document.documentElement.dataset.theme = dark ? 'dark' : 'light'; };
api('theme').then(window.hgTheme).catch(() => {});

function itemCard(item) {
  const id = esc(item.id);
  const title = esc(item.title || '影片');
  const info = esc(item.episodeLabel || asArray(item.tags).slice(0, 2).join(' · ') || '打开详情');
  return `<button class="film-card" type="button" data-open-id="${id}" aria-label="查看${title}">
    <span class="poster loading"><span class="poster-letter">${title.slice(0, 1)}</span><img data-cover="${esc(item.cover || '')}" alt=""><span class="poster-score" ${item.score ? '' : 'hidden'}>${esc(item.score || '')}</span></span>
    <span class="film-title">${title}</span><span class="film-info">${info}</span></button>`;
}

function section(title, items, kind = '') {
  if (!items.length) return '';
  return `<section class="film-section"><div class="section-heading"><h2>${esc(title)}</h2>${kind ? `<button class="more" data-more="${kind}" type="button">更多 ${icon('arrow')}</button>` : ''}</div><div class="film-row">${items.map(itemCard).join('')}</div></section>`;
}
function homeTagBar() {
  const ui = siteUi();
  const choices = privateMode() ? (ui.popularTags || []) : (ui.publicPopularTags || []);
  return `<nav class="home-tags" aria-label="按类型浏览"><button type="button" class="all-tags-link" data-all-tags="1">全部类型 ${icon('arrow')}</button>
    <div class="home-tag-scroll">${choices.map(([slug, name]) => `<button type="button" class="home-tag" data-tag-slug="${slug}" data-tag-name="${name}">${name}</button>`).join('')}</div></nav>`;
}

function homeSignature(data) {
  return JSON.stringify([asArray(data.featured), asArray(data.homepageRecommend), asArray(data.homepageNewest), asArray(data.recommend?.items), asArray(data.newest?.items)]
    .map(list => list.map(item => [item.id, item.title, item.episodeLabel, item.score])));
}
function listSignature(data) {
  return JSON.stringify(asArray(data.items).map(item => [item.id, item.title, item.episodeLabel, item.score]));
}

function activeCampaigns() {
  // 热更新已切断：活动位原本由远端 theme.json 的 campaigns 数组驱动（r16 里
  // 是国庆活动，窗口 2026-10-01 ~ 10-08，早过期）。现在恒为空，首页轮播
  // 回落到正常内容位。保留函数是因为 homeSlides / campaignArtwork / goCampaign
  // 那一串调用链还在，删掉要连带改渲染分支，收益不值。
  return [];
}
function campaignArtwork(item) {
  return new URL(item.artwork, 'https://appassets.androidplatform.net/assets/').href;
}
function homeSlides() {
  const featured = asArray(state.home?.featured);
  const videos = featured.length ? featured : asArray(state.home?.recommend?.items).slice(0, 5);
  return [...activeCampaigns().map(item => ({ ...item, kind: 'campaign', cover: '' })), ...videos];
}
function campaignSlide(item, index, count) {
  return '<div class="hero-slide hero-campaign" data-index="' + index + '">'
    + '<img class="hero-image campaign-image" src="' + esc(campaignArtwork(item)) + '" alt="">'
    + '<button class="hero-cover-button" type="button" data-open-campaign="' + esc(item.id) + '" aria-label="查看国庆庆祝页"></button>'
    + '<div class="hero-copy"><h2>' + esc(item.title) + '</h2><p>' + esc(item.description || '').replaceAll('\n', '<br>') + '</p>'
    + '<button class="hero-action" type="button" data-open-campaign="' + esc(item.id) + '">' + esc(item.button || '查看庆祝页') + '</button></div>'
    + '<span class="campaign-date">' + esc(item.dateLabel || '') + '</span>'
    + '<div class="hero-index"><b>' + String(index + 1).padStart(2, '0') + '</b> / ' + String(count).padStart(2, '0') + '</div></div>';
}
function goCampaign(item) {
  state.campaignReturn = captureScreen();
  ++routeToken;
  setRoute('campaign');
  state.campaign = item;
  content.innerHTML = '<article class="campaign-page"><button class="campaign-back" type="button" data-back>' + icon('back') + ' 返回首页</button>'
    + '<section class="campaign-poster"><img src="' + esc(campaignArtwork(item)) + '" alt="">'
    + '<div><h1>' + esc(item.title) + '</h1><p>' + esc(item.description || '').replaceAll('\n', '<br>') + '</p><span>' + esc(item.dateLabel || '') + '</span></div></section>'
    + '<section class="campaign-message"><h2>共庆好时光</h2><p>' + esc(item.message || '') + '</p><button type="button" data-back>返回首页</button></section></article>';
  scroll.scrollTop = 0;
}
function isBannerRoute() { return state.route === 'home' || (state.route === 'category' && !privateMode()); }
function bannerSlides() { return state.route === 'category' ? asArray(state.category?.items).slice(0, 5) : homeSlides(); }
function bannerIndex() { return state.route === 'category' ? state.categoryBanner : state.banner; }
function restoreBannerIndex() {
  const index = Number(content.querySelector('.hero-slide:last-child')?.dataset.index || 0);
  if (state.route === 'category') state.categoryBanner = index;
  else if (state.route === 'home') state.banner = index;
}
function categorySlide(item, index, count) {
  return `<div class="hero-slide" data-index="${index}"><img class="hero-image" data-cover="${esc(item.cover || '')}" alt=""><button class="hero-cover-button" type="button" data-open-id="${esc(item.id)}" aria-label="查看${esc(item.title)}详情"></button><div class="hero-shade"></div><div class="category-spotlight-copy"><h2>${esc(item.title)}</h2><button type="button" data-open-id="${esc(item.id)}">查看作品 ${icon('arrow')}</button></div><div class="hero-index"><b>${String(index + 1).padStart(2, '0')}</b> / ${String(count).padStart(2, '0')}</div></div>`;
}
function stopBannerAuto() {
  clearTimeout(bannerTimer);
  bannerTimer = null;
}
function scheduleBannerAuto() {
  stopBannerAuto();
  if (!isBannerRoute() || document.hidden || bannerSlides().length < 2 || !content.querySelector('.hero')) return;
  bannerTimer = setTimeout(() => {
    bannerTimer = null;
    if (!isBannerRoute() || document.hidden) return;
    const count = bannerSlides().length;
    if (count > 1) changeBanner((bannerIndex() + 1) % count, 1, true);
  }, 3000);
}
document.addEventListener('visibilitychange', () => {
  if (document.hidden) stopBannerAuto();
  else scheduleBannerAuto();
});
function heroSlide(item, index, count) {
  if (item.kind === 'campaign') return campaignSlide(item, index, count);
  return `<div class="hero-slide" data-index="${index}"><img class="hero-image" data-cover="${esc(item.cover || '')}" alt=""><button class="hero-cover-button" type="button" data-open-id="${esc(item.id || '')}" aria-label="查看${esc(item.title || '作品')}详情"></button><div class="hero-shade"></div>
    <div class="hero-copy"><span class="hero-label">本周焦点 <i></i> 独家精选</span><h2>${esc(item.title || '为你推荐')}</h2><p>${esc(item.description || item.episodeLabel || '')}</p><button class="hero-action" data-open-id="${esc(item.id || '')}" type="button">${icon('play')} 立即观看</button></div>
    <div class="hero-index"><b>${String(index + 1).padStart(2, '0')}</b> / ${String(count).padStart(2, '0')}</div></div>`;
}
async function changeBanner(targetIndex, direction, automatic = false) {
  const slides = bannerSlides();
  const route = state.route, token = routeToken;
  const currentRoute = () => state.route === route && routeToken === token && !document.hidden;
  const hero = content.querySelector('.hero');
  if (!isBannerRoute() || !hero || !slides.length || bannerChanging || targetIndex === bannerIndex()) return;
  stopBannerAuto();
  bannerChanging = true;
  let incoming = null;
  try {
    const item = slides[targetIndex];
    const data = item.kind === 'campaign' ? campaignArtwork(item) : await cover.get(item.cover);
    const template = document.createElement('template');
    template.innerHTML = route === 'category' ? categorySlide(item, targetIndex, slides.length) : heroSlide(item, targetIndex, slides.length);
    incoming = template.content.firstElementChild;
    const image = incoming.querySelector('img');
    image.src = data;
    if (!hero.isConnected || !currentRoute()) return;
    incoming.style.transform = `translateX(${direction > 0 ? 100 : -100}%)`;
    hero.appendChild(incoming);
    await image.decode();
    if (!hero.isConnected || !currentRoute()) { incoming.remove(); return; }
    if (route === 'home' && item.kind !== 'campaign') cover.applyPalette(image);
    const current = hero.querySelector('.hero-slide:not(:last-child)');
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    incoming.classList.add('animating');
    current.classList.add('animating');
    incoming.style.transform = 'translateX(0)';
    current.style.transform = `translateX(${direction > 0 ? -100 : 100}%)`;
    await new Promise(resolve => setTimeout(resolve, 330));
    current.remove();
    incoming.classList.remove('animating');
    incoming.style.transform = '';
    if (currentRoute()) {
      if (route === 'category') state.categoryBanner = targetIndex;
      else state.banner = targetIndex;
    }
    content.querySelectorAll('.hero-dots [data-slide]').forEach(dot => dot.classList.toggle('active', Number(dot.dataset.slide) === targetIndex));
  } catch (error) {
    incoming?.remove();
    hero.querySelectorAll('.hero-slide').forEach(slide => { slide.classList.remove('animating'); slide.style.transform = ''; });
    if (!automatic) toast('封面加载失败，请重试', true);
  }
  finally { bannerChanging = false; scheduleBannerAuto(); }
}
function continueWatchingItem() {
  return asArray(state.library?.progress)[0] || null;
}
function continueWatchingMarkup() {
  const item = continueWatchingItem();
  if (!item) return '';
  const episode = Math.max(1, Number(item.episode) || 1);
  const total = Number(item.episodeCount) || 0;
  const finished = Boolean(item.completed) && total > 0 && episode >= total;
  const seconds = Math.floor(Math.max(0, Number(item.position) || 0) / 1000);
  const time = `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
  return `<section class="continue-section"><div class="section-heading"><h2>继续观看</h2><button type="button" class="more" data-all-history="1">全部历史 ${icon('arrow')}</button></div>
    <button type="button" class="continue-card" data-continue-id="${esc(item.id)}" aria-label="${esc(item.title)}，${finished ? '打开详情' : '继续观看'}">
      <span class="continue-poster poster loading"><span class="poster-letter">${esc(String(item.title || '影片').slice(0, 1))}</span><img data-cover="${esc(item.cover || '')}" alt=""></span>
      <span class="continue-copy"><strong>${esc(item.title || '影片')}</strong><span>已看到第 ${episode} 集${total ? ` · 共 ${total} 集` : ''}</span><span class="continue-time">${finished ? '全剧已看完' : item.completed ? '本集已看完，接着看下一集' : `上次看到 ${time}`}</span></span>
      <span class="continue-action">${icon('play')}${finished ? '详情' : item.completed ? '继续看' : '续播'}</span>
    </button></section>`;
}
function updateContinueWatching() {
  const host = content.querySelector('.home-continue');
  if (!host) return;
  const markup = continueWatchingMarkup();
  if (host.continueMarkup === markup) return;
  const savedScroll = scroll.scrollTop;
  host.innerHTML = markup;
  host.continueMarkup = markup;
  host.querySelectorAll('img[data-cover]').forEach(cover.loadInto);
  scroll.scrollTop = savedScroll;
}
async function refreshHomeHistory() {
  const token = routeToken, source = contentSource, userId = state.account.userId;
  try {
    const library = await api('library');
    if (token !== routeToken || source !== contentSource || userId !== state.account.userId) return;
    state.library = library;
    if (state.route === 'home') updateContinueWatching();
  } catch (_) { }
}
async function resumeFromHome(id) {
  const item = continueWatchingItem();
  if (!item || String(item.id) !== id) return;
  const source = contentSource;
  const episode = Math.max(1, Number(item.episode) || 1) + (item.completed ? 1 : 0);
  const token = routeToken + 1;
  await goDetail(id);
  if (routeToken !== token || contentSource !== source || state.route !== 'detail' || state.detailLoading || String(state.detail?.id) !== id) return;
  if (episode <= asArray(state.detail.episodes).length) feedFrom(id, episode);
}

function renderHome() {
  const data = state.home;
  if (!data) { renderSkeleton(); return; }
  const slides = homeSlides();
  const index = Math.min(state.banner, Math.max(0, slides.length - 1));
  const selected = slides[index] || {};
  content.innerHTML = `<div class="home-page"><div class="hero" data-hero="1">${heroSlide(selected, index, slides.length)}</div>
      <div class="hero-dots">${slides.map((_, i) => `<i class="${i === index ? 'active' : ''}" data-slide="${i}"></i>`).join('')}</div>
      ${homeTagBar()}
      <div class="home-continue">${continueWatchingMarkup()}</div>
      ${section('精选推荐', asArray(data.homepageRecommend).length ? asArray(data.homepageRecommend) : asArray(data.recommend?.items).slice(0, 12), 'recommend')}
      ${section(privateMode() ? '最近上新' : '热门漫剧', asArray(data.homepageNewest).length ? asArray(data.homepageNewest) : asArray(data.newest?.items).slice(0, 12), 'newest')}</div>`;
  cover.hydrate();
  slides.forEach(item => { if (item.cover) cover.get(item.cover).catch(() => {}); });
  scheduleBannerAuto();
}

function renderTags() {
  const tags = asArray(state.tags?.items);
  const sections = privateMode()
    ? (siteUi().tagGroups || [])
    : [['short', '短剧类型'], ['comic', '漫剧类型']];
  const groups = sections.map(([id, title]) => {
    const members = tags.filter(tag => tag.group === id);
    if (!members.length) return '';
    return `<section class="tags-group"><h2>${title}</h2><div class="tags-grid">${members.map(tag => {
      const name = String(tag.name || tag.slug);
      const size = name.length > 8 ? 'wide' : name.length > 4 ? 'medium' : '';
      return `<button type="button" class="tag-choice ${size}" data-tag-slug="${esc(tag.slug)}" data-tag-name="${esc(name)}">${esc(name)}</button>`;
    }).join('')}</div></section>`;
  }).join('');
  content.innerHTML = `<div class="tags-page"><div class="tags-heading"><button type="button" data-back="1" aria-label="返回上一页">${icon('back')}</button><h1>全部类型</h1><span>${tags.length} 个${privateMode() ? '标签' : '大类'}</span></div>
    <p class="tags-intro">${privateMode() ? '点选标签，发现相关作品' : '按短剧、漫剧大类，发现相关作品'}</p>${groups}</div>`;
}

function refreshMissingImages(items) {
  const byId = new Map(items.filter(item => item?.id && item?.cover).map(item => [String(item.id), item.cover]));
  content.querySelectorAll('[data-open-id]').forEach(button => {
    const image = button.querySelector('img[data-cover]') || button.closest('.hero-slide,.category-spotlight')?.querySelector('img[data-cover]');
    // 这个局部别叫 cover —— 会遮住模块级那个 cover 对象（第 80 行），
    // 于是下面 cover.loadInto 变成「对字符串取属性」，抛 TypeError。
    // 症状极具误导性：首页接口明明 200，界面上却弹「内容更新失败，正在显示缓存」。
    const url = byId.get(button.dataset.openId);
    if (!image || !url || image.naturalWidth > 0) return;
    image.dataset.cover = url;
    delete image.dataset.loading;
    cover.loadInto(image);
  });
}

function categoryId() {
  const ids = siteUi().categoryIds || {};
  return (ids[state.tab] || [])[subIndex(state.tab)];
}
function compactMarkup() {
  if (!privateMode()) return '';
  const adult = state.tab === 'adult';
  const tabs = categoryTabs();
  return `<strong>${categoryTitle()}</strong><div class="compact-tabs${tabs.length > 2 ? ' many' : ''}" role="tablist">${tabs.map((name, index) => `<button type="button" role="tab" aria-selected="${index === subIndex(state.tab)}" data-sub="${index}" class="${index === subIndex(state.tab) ? 'selected' : ''}">${name}</button>`).join('')}</div>`;
}
function updateCompact() {
  const eligible = privateMode() && state.route === 'category';
  if (!eligible || scroll.scrollTop <= 0) state.compactHeld = false;
  else if (scroll.scrollTop > 92) state.compactHeld = true;
  const visible = eligible && state.compactHeld;
  compact.classList.toggle('visible', visible);
  compact.setAttribute('aria-hidden', String(!visible));
  compact.inert = !visible;
  content.querySelector('.category-page')?.classList.toggle('is-collapsed', visible);
}
scroll.addEventListener('scroll', updateCompact, { passive: true });

function renderCategory(keepCompact = false) {
  categoryObserver?.disconnect();
  const adult = state.tab === 'adult';
  const tabs = categoryTabs();
  const items = asArray(state.category?.items);
  const lead = items[0] || null;
  const slides = privateMode() ? [] : items.slice(0, 5);
  const banner = Math.min(state.categoryBanner, Math.max(0, slides.length - 1));
  state.categoryBanner = banner;
  const spotlight = !privateMode() && slides.length ? `<div class="hero category-spotlight category-carousel">${categorySlide(slides[banner], banner, slides.length)}</div><div class="hero-dots">${slides.map((_, index) => `<i class="${index === banner ? 'active' : ''}" data-slide="${index}"></i>`).join('')}</div>` : lead ? `<div class="category-spotlight"><img data-cover="${esc(lead.cover)}" alt=""><div class="category-spotlight-copy"><h2>${esc(lead.title)}</h2><button type="button" data-open-id="${esc(lead.id)}">查看作品 ${icon('arrow')}</button></div></div>` : '';
  // 分类多于两个时挂 `many`：CSS 那边 .segmented 是写死的两列网格（黄果就两个），
  // 塞八个进去会塌成四行、高度还卡在 51px，于是盖住下面的卡片。
  // 挂上类之后改成横向滚动，两个的源看起来跟原来一模一样。
  const switcher = privateMode() ? `<div class="segmented${tabs.length > 2 ? ' many' : ''}" role="tablist">${tabs.map((name, index) => `<button type="button" role="tab" data-sub="${index}" aria-selected="${index === subIndex(state.tab)}" class="${index === subIndex(state.tab) ? 'selected' : ''}">${name}</button>`).join('')}</div>` : '';
  content.innerHTML = `<div class="category-page"><div class="category-head"><h1>${categoryTitle()}</h1><p>${categoryLead()}</p></div>
    ${switcher}
    ${spotlight}
    <section class="category-section"><div class="section-heading"><h2>${tabs[subIndex(state.tab)]}作品</h2><span class="category-count">已显示 ${items.length} 部</span></div>
    <div class="search-grid category-grid">${items.map(itemCard).join('')}</div><div class="category-footer">${categoryFooter()}</div></section></div>`;
  compact.innerHTML = compactMarkup();
  scroll.scrollTop = keepCompact ? 150 : 0;
  state.compactHeld = keepCompact;
  updateCompact();
  cover.hydrate();
  observeCategoryEnd();
  slides.forEach(item => { if (item.cover) cover.get(item.cover).catch(() => {}); });
  scheduleBannerAuto();
}

function categoryFooter() {
  const category = state.category;
  if (category?.error) return '<button type="button" data-retry-category="1">加载失败，点击重试</button>';
  if (category?.loading) return '<span role="status">正在加载更多…</span>';
  if (category?.refreshing) return '<span role="status">正在更新列表…</span>';
  if (category?.nextPage) return '<span class="category-sentinel" aria-hidden="true"></span>';
  return '<span>已经到底了</span>';
}

function observeCategoryEnd() {
  categoryObserver?.disconnect();
  const category = state.category;
  if (state.route !== 'category' || !category?.nextPage || category.loading || category.refreshing || category.error) return;
  const sentinel = content.querySelector('.category-sentinel');
  if (!sentinel) return;
  if (!categoryObserver) {
    categoryObserver = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) loadMoreCategory();
    }, { root: scroll, rootMargin: '0px 0px 320px 0px' });
  }
  categoryObserver.observe(sentinel);
}

function updateCategoryFooter() {
  const footer = content.querySelector('.category-footer');
  if (!footer) return;
  footer.innerHTML = categoryFooter();
  observeCategoryEnd();
}

function renderLibrary() {
  const data = state.library || { progress: [], bookmarks: [] };
  const query = state.libraryQuery.trim().toLocaleLowerCase();
  const matches = item => String(item.title || '').toLocaleLowerCase().includes(query);
  const progress = asArray(data.progress).filter(matches);
  const bookmarks = asArray(data.bookmarks).filter(matches);
  const historyActive = state.libraryTab === 'history';
  const historyCards = progress.map(item => {
    const completed = Boolean(item.completed);
    const episode = Number(item.episode) || 1;
    const percentage = completed ? 100 : Math.max(0, Math.min(100, item.duration ? item.position / item.duration * 100 : 0));
    return `<button type="button" class="history-card" data-history-id="${esc(item.id)}" data-history-episode="${episode}" data-history-completed="${completed}" aria-label="${esc(item.title)}，第 ${episode} 集，${completed ? '已看完，打开详情' : '继续观看'}">
      <span class="history-poster poster loading"><span class="poster-letter">${esc(String(item.title || '影片').slice(0, 1))}</span><img data-cover="${esc(item.cover || '')}" alt=""></span><span class="history-copy"><span class="history-status">${completed ? '已看完' : '继续观看'} · 第 ${episode} 集</span>
      <strong>${esc(item.title || '影片')}</strong><span class="progress"><i style="width:${percentage}%"></i></span><span class="history-hint">${completed ? '打开详情选择剧集' : `已看 ${Math.floor(item.position / 60000)} 分钟`}</span></span></button>`;
  }).join('');
  content.innerHTML = `<div class="library-page"><div class="my-heading"><h1>我的</h1></div>
    ${state.account.loggedIn ? '' : `<button type="button" class="my-account" data-account-action="1">点击头像登录，同步收藏和观看历史 ${icon('arrow')}</button>`}
    ${query ? `<div class="library-results"><p class="library-count">在观看历史中找到 ${progress.length} 部，在我的收藏中找到 ${bookmarks.length} 部</p>
      <h2>观看历史</h2>${progress.length ? `<div class="history-list">${historyCards}</div>` : '<p class="library-no-match">没有匹配的观看记录</p>'}
      <h2>我的收藏</h2>${bookmarks.length ? `<div class="search-grid library-bookmark-grid">${bookmarks.map(itemCard).join('')}</div>` : '<p class="library-no-match">没有匹配的收藏</p>'}</div>` : `
    <div class="segmented library-tabs" role="tablist" aria-label="我的片单分类"><button type="button" role="tab" data-library-tab="history" aria-selected="${historyActive}" class="${historyActive ? 'selected' : ''}">观看历史</button>
    <button type="button" role="tab" data-library-tab="bookmarks" aria-selected="${!historyActive}" class="${!historyActive ? 'selected' : ''}">我的收藏</button></div>
    <p class="library-count">${historyActive ? `最近观看的 ${progress.length} 部作品` : `已收藏 ${bookmarks.length} 部作品`}</p>
    ${historyActive ? (progress.length ? `<div class="history-list">${historyCards}</div>` : '<div class="empty-state">还没有观看记录，播放作品后会出现在这里</div>') :
      (bookmarks.length ? `<div class="search-grid library-bookmark-grid">${bookmarks.map(itemCard).join('')}</div>` : '<div class="empty-state">还没有收藏作品</div>')}`}</div>`;
  cover.hydrate();
}

function renderSettings() {
  content.innerHTML = `<div class="settings-page"><button type="button" class="listing-back" data-back="1">${icon('back')} 返回我的</button><h1>设置</h1>
    <section><button type="button" class="settings-link" data-account-management="1"><span><strong>账号管理</strong><small>${state.account.loggedIn ? esc(state.account.username) : '登录或注册账号'}</small></span>${icon('arrow')}</button></section>
    <section class="settings-library"><h2>片单管理</h2><p>${state.account.loggedIn ? '清空后会同步到当前账号的其他设备。' : '清空当前设备保存的片单。'}</p>
      <button type="button" data-clear="history" ${asArray(state.library?.progress).length ? '' : 'disabled'}>清空观看历史</button>
      <button type="button" data-clear="bookmarks" ${asArray(state.library?.bookmarks).length ? '' : 'disabled'}>清空我的收藏</button></section>
    <section class="update-settings"><div><h2>应用更新</h2><button type="button" class="app-version" data-secret-entry aria-label="当前版本 ${esc(appVersion)}"><span class="app-version-text">版本 ${appVersion ? `v${esc(appVersion)}` : '读取中…'}</span></button><p>当前安装包版本。</p></div></section>
    <section class="settings-storage"><h2>本地存储</h2><button type="button" data-clear="cache">清理内容缓存</button></section>
      <section><button type="button" class="settings-link" data-source-settings="1"><span><strong>内容源</strong><small>${state.sourceName ? esc(state.sourceName + '（' + state.sourceCount + ' 个已保存）') : '未配置'}</small></span>${icon('arrow')}</button></section>
      <section><button type="button" class="settings-link" data-proxy-settings="1"><span><strong>网络代理</strong><small>${state.proxy ? esc(state.proxy) : '直连。有机场的话填这里可以绕开慢链路'}</small></span>${icon('arrow')}</button></section></div>`;
}

// 网络代理。实测直连那条链六次里四次 9~18 秒（cf-ray 落在 LAX），而换路由
// 是客户端唯一能真正改变延迟的手段 —— DoH、连接复用、预热都只是把已经确定的
// 那段延迟挪个位置，换路才是换一条路。
// 内容源管理。
//
// 存的是**地址清单**，配置内容只有当前生效那一份（Java 侧 user-source.json）。
// 切换 = 重新拉那个地址 + 覆盖当前配置，跟第一次填走完全一样的校验 —— 这样
// 切到一个已经失效的源会被当场拒绝，而不是留一份坏配置在盘上等下次启动闪退。
function renderSourceSettings() {
  const items = asArray(state.sources);
  const rows = items.map(item => `<li class="source-row${item.active ? ' is-active' : ''}">
      <button type="button" class="source-pick" data-source-use="${esc(item.url)}" ${item.active ? 'disabled' : ''}>
        <span><strong>${esc(item.name || item.url)}</strong><small>${esc(item.url)}</small></span>
        ${item.active ? '<em>当前</em>' : icon('arrow')}
      </button>
      <button type="button" class="source-drop" data-source-forget="${esc(item.url)}" aria-label="删除 ${esc(item.name || item.url)}">×</button>
    </li>`).join('');
  content.innerHTML = `<div class="settings-page"><button type="button" class="listing-back" data-back="1">${icon('back')} 返回设置</button><h1>内容源</h1>
    <section class="settings-account">
      <p>切换源会重新拉取那份配置并重启应用。想看别的内容就换一个。</p>
      <ul class="source-list">${rows || '<li class="source-empty">还没有保存过任何源</li>'}</ul>
      <form id="source-form" class="password-form">
        <label for="source-value">添加一个源</label>
        <input id="source-value" type="text" inputmode="url" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="https://example.com/source.json">
        <p class="field-hint">地址必须以 https:// 开头。支持我们自己的 JSON 和苹果CMS 的接口地址。</p>
        <p id="source-error" class="auth-error" role="alert" hidden></p>
        <button type="submit">添加并切换</button>
      </form>
    </section></div>`;
}

async function refreshSources() {
  const listing = await sourceCommand('list');
  state.sources = asArray(listing.items);
  const active = state.sources.find(item => item.active);
  state.sourceName = active ? (active.name || active.url) : '';
  state.sourceCount = state.sources.length;
}

function goSourceSettings() {
  if (state.route === 'source-settings') return;
  if (state.route !== 'settings') goSettings();
  state.sourceReturn = captureScreen();
  setRoute('source-settings');
  renderSourceSettings();
  refreshSources().then(() => { if (state.route === 'source-settings') renderSourceSettings(); }).catch(() => {});
  content.querySelector('#source-value')?.focus();
}

function renderProxySettings() {
  const current = state.proxy || '';
  content.innerHTML = `<div class="settings-page"><button type="button" class="listing-back" data-back="1">${icon('back')} 返回设置</button><h1>网络代理</h1>
    <section class="settings-account">
      <p>填了之后所有请求都走它。适合有代理的人用来绕开慢链路。</p>
      <form id="proxy-form" class="password-form">
        <label for="proxy-value">代理地址</label>
        <input id="proxy-value" type="text" inputmode="url" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="127.0.0.1:7890 或 socks5://127.0.0.1:7891" value="${esc(current)}">
        <p class="field-hint">支持 host:port、http://host:port、socks5://host:port。留空表示直连。</p>
        <p id="proxy-error" class="auth-error" role="alert" hidden></p>
        <button type="submit">保存</button>
      </form>
    </section></div>`;
}

function goProxySettings() {
  if (state.route === 'proxy-settings') return;
  if (state.route !== 'settings') goSettings();
  state.proxyReturn = captureScreen();
  setRoute('proxy-settings');
  renderProxySettings();
  content.querySelector('#proxy-value')?.focus();
}

function renderAccountManagement() {
  const account = state.account;
  content.innerHTML = `<div class="settings-page account-page"><button type="button" class="listing-back" data-back="1">${icon('back')} 返回设置</button><h1>账号管理</h1>
    ${account.loggedIn ? `<section class="account-identity"><img src="${esc(account.avatar || 'user.svg')}" alt=""><div><strong>${esc(account.username)}</strong><span>已登录应用账号</span></div></section>
      <section class="settings-account"><h2>个人资料</h2><button type="button" data-choose-avatar="1">从相册更换头像</button></section>
      <section class="settings-account"><h2>修改密码</h2><p>先验证旧密码，再设置至少 8 位的新密码。</p>
        <form id="password-form" class="password-form">
          <label for="old-password">旧密码</label><input id="old-password" type="password" autocomplete="current-password" required>
          <label for="new-password">新密码</label><input id="new-password" type="password" autocomplete="new-password" minlength="8" required>
          <label for="confirm-password">再次输入新密码</label><input id="confirm-password" type="password" autocomplete="new-password" minlength="8" required>
          <p id="password-error" class="auth-error" role="alert" hidden></p><button type="submit">确认修改</button>
        </form></section>
      <section class="settings-account"><h2>同步与登录</h2><button type="button" data-sync-now="1">立即同步片单</button><button type="button" data-logout="1" aria-live="polite">退出登录</button></section>` : `<section class="settings-account"><p>登录后可同步收藏和观看历史，并管理头像与密码。</p><button type="button" data-account-action="1">登录或注册</button></section>`}</div>`;
}

function renderSearch() {
  const filters = privateMode() ? [['all', '全部'], ['ai-duanju', '成人短剧'], ['ai-manju', '成人漫剧'], ['ai-huanlian', 'AI换脸'], ['ai-mogai', 'AI魔改']] : [['all', '全部'], ['short', '短剧'], ['comic', '漫剧']];
  const all = asArray(state.search?.items);
  const items = state.filter === 'all' ? all : all.filter(item => {
    if (item.category) return item.category === state.filter;
    const title = String(item.title || '');
    if (state.filter === 'ai-huanlian') return /AI\s*换脸/.test(title);
    if (state.filter === 'ai-mogai') return /AI\s*魔改/.test(title);
    if (state.filter === 'ai-manju') return /漫剧/.test(title);
    return !/AI\s*(换脸|魔改)|漫剧/.test(title);
  });
  content.innerHTML = `<div class="search-page"><h1>搜索结果</h1><p>${esc(state.query)} · ${items.length} 部作品</p>${searchFeedback()}<div class="filter-row">${filters.map(([id, label]) => `<button type="button" data-filter="${id}" class="${state.filter === id ? 'active' : ''}">${label}</button>`).join('')}</div>
    ${items.length ? `<div class="search-grid">${items.map(itemCard).join('')}</div>` : '<div class="empty-state">没有找到匹配的影片</div>'}</div>`;
  cover.hydrate();
}

function listingFooter() {
  const listing = state.listing;
  if (listing.error) return '<button type="button" data-retry-listing="1">加载失败，点击重试</button>';
  if (listing.loading) return '<span role="status">正在加载更多…</span>';
  if (listing.refreshing) return '<span role="status">正在更新列表…</span>';
  if (listing.nextPage) return listing.kind === 'tag-list' ? '<span class="listing-sentinel" aria-hidden="true"></span>' : '<span class="listing-sentinel">继续下滑加载更多</span>';
  return '<span>已经到底了</span>';
}

function observeListingEnd() {
  listingObserver?.disconnect();
  if (state.route !== 'listing' || !state.listing?.nextPage || state.listing.loading ||
      state.listing.refreshing || state.listing.error) return;
  const sentinel = content.querySelector('.listing-sentinel');
  if (!sentinel) return;
  if (!listingObserver) {
    listingObserver = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) loadMoreListing();
    }, { root: scroll, rootMargin: '0px 0px 320px 0px' });
  }
  listingObserver.observe(sentinel);
}

function updateListingFooter() {
  const footer = content.querySelector('.listing-footer');
  if (!footer) return;
  footer.innerHTML = listingFooter();
  observeListingEnd();
}

function renderListing() {
  const listing = state.listing;
  if (!listing?.items.length && listing?.refreshing) { renderSkeleton(); return; }
  const tagName = String(listing.label || listing.slug).replace(/^#+/, '');
  const tagTitle = privateMode() ? `#${tagName}` : `${listing.slug?.startsWith('comic:') ? '漫剧' : '短剧'} · ${tagName}`;
  const title = listing.kind === 'tag-list' ? tagTitle : listing.kind === 'recommend' ? '精选推荐' : privateMode() ? '最近上新' : '热门漫剧';
  const backLabel = listing.kind === 'tag-list' && state.listingReturn?.route === 'tags' ? '返回全部类型' : '返回首页';
  content.innerHTML = `<div class="listing-page"><button class="listing-back" type="button" data-back="1">${icon('back')} ${backLabel}</button>
    <h1>${esc(title)}</h1><p>已显示 ${listing.items.length} 部作品</p>
    ${listing.items.length ? `<div class="search-grid listing-grid">${listing.items.map(itemCard).join('')}</div>` : '<div class="empty-state">这个标签下暂无作品</div>'}
    <div class="listing-footer">${listingFooter()}</div></div>`;
  cover.hydrate();
  observeListingEnd();
}

function renderDetail() {
  const data = state.detail;
  if (!data) { renderSkeleton(); return; }
  const loading = state.detailLoading;
  window.AndroidHost?.setDetailMode?.(true);
  const episodes = asArray(data.episodes);
  content.innerHTML = `<div class="detail-page"><button class="detail-back" type="button" data-back="1">${icon('back')} 返回</button>
    <div class="detail-art"><img data-cover="${esc(data.cover)}" alt=""></div><div class="detail-copy"><h1>${esc(data.title)}</h1><p>${loading ? '正在获取内容…' : `${esc(data.score)} ${esc(data.episodeLabel)}`}</p><p class="detail-description">${esc(data.description || '')}</p>
    <div class="detail-buttons"><button class="detail-play" type="button" data-play="1" ${loading ? 'disabled' : ''}>${loading ? '正在加载…' : '立即播放'}</button><button class="bookmark-button" type="button" data-bookmark="1" aria-label="${state.bookmarked ? '取消收藏' : '收藏'}" ${loading ? 'disabled' : ''}>${state.bookmarked ? '★' : '☆'}</button></div>
    ${loading ? '<div class="detail-loading" role="status">正在获取选集…</div>' : episodes.length ? `<h2>选集</h2><div class="episode-grid">${episodes.map((item, index) => `<button type="button" data-episode="${item.number}" class="${index === 0 ? 'active' : ''}">${item.number}</button>`).join('')}</div>` : ''}</div></div>`;
  cover.hydrate();
}

function searchFeedback() {
  if (state.searchPending) return '<div class="search-feedback" role="status" aria-live="polite"><span class="search-spinner" aria-hidden="true"></span><span>正在更新搜索结果…</span></div>';
  if (state.searchError) return `<div class="search-feedback search-feedback--error" role="status"><span>更新失败，当前显示缓存结果</span><button type="button" data-retry="search">重试</button></div>`;
  return '';
}
function renderSearchLoading() {
  content.innerHTML = `<section class="search-loading" role="status" aria-live="polite" aria-busy="true"><span class="search-spinner search-spinner--large" aria-hidden="true"></span><h1>正在搜索…</h1><p>${esc(state.query)}</p></section>`;
}
function renderSkeleton() {
  content.innerHTML = '<div class="skeleton-home" role="status" aria-label="正在加载内容"><div class="skeleton skeleton-hero"></div><div class="skeleton skeleton-title"></div><div class="skeleton-row"><div class="skeleton skeleton-poster"></div><div class="skeleton skeleton-poster"></div><div class="skeleton skeleton-poster"></div></div><div class="skeleton skeleton-line"></div></div>';
}
function renderError(error, retry) {
  if (state.route === 'detail') window.AndroidHost?.setDetailMode?.(false);
  if (/站点连接失败|站点请求失败|failed to connect|unable to resolve host|unknownhost|connectexception|sockettimeoutexception|timed? out|network is unreachable|connection reset|connection refused|网络|连接失败|无法连接/i.test(error?.message || '')) {
    content.innerHTML = `<div class="error-state error-state--network" role="alert">
      <div class="error-main">
        <svg class="error-icon" viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="24" cy="24" r="21"/><path d="m12 24 6-5 6 5 6-5 6 5"/></svg>
        <h1>网络走丢了</h1>
      </div>
      <div class="error-footer"><p>正在自动重试…也可以点下面的按钮立即重试。</p><button type="button" data-retry="${esc(retry)}">重新尝试</button></div>
    </div>`;
    return;
  }
  content.innerHTML = `<div class="error-state">${esc(error.message || '加载失败，请稍后重试')}<button type="button" data-retry="${esc(retry)}">重试</button></div>`;
}

function rememberScreen() {
  if (state.route === 'detail') return;
  const previewContent = content.cloneNode(true);
  previewContent.removeAttribute('id');
  screenSnapshots[state.route] = {
    content: previewContent, scrollTop: scroll.scrollTop,
    horizontalScroll: [...content.querySelectorAll('.film-row,.filter-row,.home-tag-scroll')].map(row => row.scrollLeft)
  };
}
function captureScreen() {
  rememberScreen();
  return {
    route: state.route, tab: state.tab, pageNode: content.firstElementChild,
    scrollTop: scroll.scrollTop, searchValue: searchInput.value,
    listing: state.route === 'listing' ? state.listing : null,
    listingReturn: state.listingReturn, tagsReturn: state.tagsReturn,
    compactHeld: state.compactHeld,
    horizontalScroll: [...content.querySelectorAll('.film-row,.filter-row,.home-tag-scroll')].map(row => row.scrollLeft)
  };
}
function restoreScreen(snapshot) {
  if (!snapshot?.pageNode) { goHome(); return; }
  ++routeToken;
  listingObserver?.disconnect();
  categoryObserver?.disconnect();
  setRoute(snapshot.route);
  state.tab = snapshot.tab;
  state.compactHeld = snapshot.compactHeld;
  if (snapshot.listing) state.listing = snapshot.listing;
  state.listingReturn = snapshot.listingReturn;
  state.tagsReturn = snapshot.tagsReturn;
  content.replaceChildren(snapshot.pageNode);
  if (snapshot.route === 'home') refreshHomeHistory();
  if (snapshot.route === 'library') renderLibrary();
  if (snapshot.route === 'settings') renderSettings();
  searchInput.value = snapshot.searchValue || '';
  document.querySelectorAll('#dock [data-nav]').forEach(button => button.classList.toggle('active', button.dataset.nav === state.tab));
  scroll.scrollTop = snapshot.scrollTop || 0;
  content.querySelectorAll('.film-row,.filter-row,.home-tag-scroll').forEach((row, index) => {
    row.scrollLeft = snapshot.horizontalScroll?.[index] || 0;
  });
  cover.hydrate();
  if (isBannerRoute()) { restoreBannerIndex(); scheduleBannerAuto(); }
  if (snapshot.route === 'category') { compact.innerHTML = compactMarkup(); updateCompact(); observeCategoryEnd(); }
  if (snapshot.route === 'listing') observeListingEnd();
}

function updateBackEnabled() {
  // 兜底：不管哪条路径把路由切走了，都不能把全屏播放层留在屏幕上。
  // feedClose() 会先清掉 .feed 再回调这里，所以不会递归。
  if (state.route !== 'feed' && document.querySelector('.feed')) feedClose();
  // 设置页也算进来。它不是普通弹窗 —— 没配源时它是唯一的一屏，而
  // setBackEnabled(false) 会让 Java 把返回回调整个注销掉，之后按返回就是
  // 系统默认行为（退到后台），连 window.hgBack() 都不会被调用。
  const modalOpen = [unlockOverlay, authOverlay, confirmOverlay, document.getElementById('setup-overlay')]
    .some(overlay => overlay && !overlay.hidden);
  window.AndroidHost?.setBackEnabled(modalOpen || !isRootColumn());
}

function setRoute(route) {
  stopBannerAuto();
  if (state.route === 'detail' && route !== 'detail') { detailCard?.dispose(); detailCard = null; }
  const libraryArea = ['library', 'settings', 'account-management'].includes(route);
  if (state.route === 'library' && route !== 'library') searchInput.value = '';
  state.route = route;
  document.getElementById('app').dataset.route = route;
  document.getElementById('app').classList.toggle('route-detail', route === 'detail');
  window.AndroidHost?.setDetailMode?.(route === 'detail');
  headerSettings.hidden = route !== 'library';
  searchInput.placeholder = libraryArea ? '搜索观看历史和收藏' : privateMode() ? '搜索剧名或 #标签' : '搜索剧名';
  searchInput.setAttribute('aria-label', libraryArea ? '搜索观看历史和收藏' : '搜索剧名或标签');
  if (route === 'library') searchInput.value = state.libraryQuery;
  updateBackEnabled();
}

function isRootColumn() { return ['home', 'category', 'library'].includes(state.route); }

function backDestination() {
  if (isRootColumn()) return null;
  if (state.route === 'campaign') return state.campaignReturn?.route || 'home';
  if (state.route === 'detail') return state.previous?.route || 'home';
  if (state.route === 'account-management') return state.accountReturn?.route || 'settings';
  if (state.route === 'proxy-settings') return state.proxyReturn?.route || 'settings';
  if (state.route === 'source-settings') return state.sourceReturn?.route || 'settings';
  if (state.route === 'settings') return state.settingsReturn?.route || 'library';
  if (state.route === 'tags') return state.tagsReturn?.route || 'home';
  if (state.route === 'listing' && state.listing?.kind === 'tag-list') return state.listingReturn?.route || 'home';
  return state.route === 'home' ? null : 'home';
}

function clearPredictivePreview() {
  clearTimeout(predictiveTimer);
  if (predictiveFrame) cancelAnimationFrame(predictiveFrame);
  predictiveFrame = null;
  if (predictiveSourceNode && predictivePreview?.contains(predictiveSourceNode)) predictiveSourceNode.remove();
  predictiveSourceNode = null;
  predictivePreview?.remove();
  predictivePreview = null;
  document.getElementById('app').classList.remove('predictive-active');
  scroll.style.removeProperty('transition');
  scroll.style.removeProperty('transform');
}

window.hgPredictiveBackStart = fromRight => {
  clearPredictivePreview();
  if (state.route === 'detail' && detailCard && authOverlay.hidden && confirmOverlay.hidden && unlockOverlay.hidden) {
    detailCard.fromRight = fromRight;
    return detailCard.begin();
  }
  const destination = backDestination();
  const fromDetail = state.route === 'detail' && state.previous?.route === destination && state.previous.pageNode;
  const snapshot = fromDetail ? state.previous : screenSnapshots[destination];
  if (!snapshot) return false;
  const preview = document.createElement('div');
  preview.className = 'back-preview';
  preview.setAttribute('aria-hidden', 'true');
  if (state.route === 'detail' || state.route === 'settings') {
    const header = document.querySelector('#app > .app-top').cloneNode(true);
    const settings = header.querySelector('.header-settings');
    settings.hidden = destination !== 'library';
    if (destination === 'library') {
      const input = header.querySelector('.search-input');
      input.placeholder = '搜索观看历史和收藏';
      input.value = state.libraryQuery;
    }
    header.querySelectorAll('[id]').forEach(node => node.removeAttribute('id'));
    preview.appendChild(header);
  }
  if (fromDetail) {
    const holder = document.createElement('div');
    holder.style.transform = `translateY(${-snapshot.scrollTop}px)`;
    predictiveSourceNode = snapshot.pageNode;
    holder.appendChild(predictiveSourceNode);
    preview.appendChild(holder);
  } else {
    predictiveSourceNode = snapshot.content;
    predictiveSourceNode.style.transform = `translateY(${-snapshot.scrollTop}px)`;
    preview.appendChild(predictiveSourceNode);
  }
  document.getElementById('app').appendChild(preview);
  preview.querySelectorAll('.film-row,.filter-row,.home-tag-scroll').forEach((row, index) => {
    row.scrollLeft = snapshot.horizontalScroll?.[index] || 0;
  });
  document.getElementById('app').classList.add('predictive-active');
  scroll.style.transition = 'none';
  predictivePreview = preview;
  predictivePreview.fromRight = fromRight;
  return true;
};

window.hgPredictiveBackProgress = progress => {
  if (state.route === 'detail' && detailCard?.phase === 'dragging') {
    detailCard.predictive(Math.max(0, Math.min(1, progress)), detailCard.fromRight);
    return;
  }
  if (!predictivePreview) return;
  pendingPredictiveProgress = Math.max(0, Math.min(1, progress));
  if (predictiveFrame) return;
  predictiveFrame = requestAnimationFrame(() => {
    predictiveFrame = null;
    if (!predictivePreview) return;
    const direction = predictivePreview.fromRight ? -1 : 1;
    scroll.style.transform = `translate3d(${direction * pendingPredictiveProgress * 100}%,0,0)`;
  });
};

window.hgPredictiveBackCancel = () => {
  if (state.route === 'detail' && detailCard?.phase === 'dragging') { detailCard.cancel(); return; }
  if (!predictivePreview) return;
  if (predictiveFrame) cancelAnimationFrame(predictiveFrame);
  predictiveFrame = null;
  scroll.style.transition = 'transform 180ms ease-out';
  scroll.style.transform = 'translate3d(0,0,0)';
  predictiveTimer = setTimeout(clearPredictivePreview, 180);
};

window.hgPredictiveBackCommit = () => {
  if (state.route === 'detail' && detailCard && authOverlay.hidden && confirmOverlay.hidden && unlockOverlay.hidden) {
    detailCard.close(); return true;
  }
  if (!predictivePreview) return window.hgBack();
  if (predictiveFrame) cancelAnimationFrame(predictiveFrame);
  predictiveFrame = null;
  const direction = predictivePreview.fromRight ? -1 : 1;
  scroll.style.transition = 'transform 150ms ease-out';
  scroll.style.transform = `translate3d(${direction * 100}%,0,0)`;
  predictiveTimer = setTimeout(() => {
    const oldPage = content.firstElementChild;
    if (!window.hgBack()) { clearPredictivePreview(); return; }
    if (content.firstElementChild !== oldPage) { clearPredictivePreview(); return; }
    const changed = new MutationObserver(() => {
      if (content.firstElementChild === oldPage) return;
      changed.disconnect();
      clearPredictivePreview();
    });
    changed.observe(content, { childList: true });
    predictiveTimer = setTimeout(() => { changed.disconnect(); clearPredictivePreview(); }, 1500);
  }, 150);
  return true;
};

function setNav(tab) {
  listingObserver?.disconnect();
  categoryObserver?.disconnect();
  document.querySelectorAll('#dock [data-nav]').forEach(button => button.classList.toggle('active', button.dataset.nav === tab));
  compact.classList.remove('visible');
  compact.inert = true;
  state.compactHeld = false;
  scroll.scrollTop = 0;
}

async function goHome() {
  const token = ++routeToken;
  stopBannerAuto();
  setRoute('home'); state.tab = 'home'; setNav('home');
  // 三级兜底，顺序不能反：内存 → localStorage → 骨架屏。
  // localStorage 是同步读的，冷启动第一帧就有内容，不用等网络也不用等桥。
  if (!state.home) {
    const cached = readCache('home');
    if (cached) state.home = cached.data;
  }
  if (state.home) renderHome(); else renderSkeleton();
  const refreshing = api('home');
  const library = await api('library').catch(() => null);
  if (token !== routeToken) return;
  state.library = library;
  let fresh;
  try {
    fresh = await refreshing;
  } catch (error) {
    // 接口挂了但手上有缓存：留着旧内容，只提示一下。把已经画好的首页换成
    // 错误页是最糟的选择——用户看到的是一片空白，而内容其实就在本地。
    if (token !== routeToken) return;
    if (!state.home) {
      renderError(error, 'home');
      // 这条线路是间歇性的（实测同一个域名三次里有一次能通），所以失败后
      // 自动退避重试，而不是把「重新尝试」丢给用户点。
      scheduleHomeRetry(token);
    } else toast('内容更新失败，正在显示缓存', true);
    return;
  }
  if (token !== routeToken) return;
  try {
    const changed = state.home && homeSignature(state.home) !== homeSignature(fresh);
    const firstLoad = !state.home;
    state.home = fresh;
    writeCache('home', fresh);
    if (firstLoad || changed) renderHome();
    else refreshMissingImages([...asArray(fresh.featured), ...asArray(fresh.recommend?.items), ...asArray(fresh.newest?.items)]);
  } catch (error) {
    // ⚠️ 渲染和网络必须分开 catch。以前这两段在同一个 try 里，后面几句抛出来，
    // 用户看到的也是「内容更新失败，正在显示缓存」—— 实测日志是
    //   [api] /api/home ok 2076ms
    //   [toast] 内容更新失败，正在显示缓存      ← 成功之后 3ms
    // 真实原因（refreshMissingImages 里变量遮蔽）跟网络毫无关系，却害人顺着
    // 「接口不稳」查了半天。拿到了数据就说明接口是好的 —— 只打日志，不弹提示。
    console.log('[home] 渲染失败（接口是好的）：' + ((error && error.stack) || error));
    state.home = fresh;   // 数据是好的，先认下来，别因为一次渲染失败丢掉它
  }
}

async function goTags() {
  const token = ++routeToken;
  if (state.route !== 'tags') state.tagsReturn = captureScreen();
  setRoute('tags'); state.tab = 'home'; setNav('home');
  const refreshing = api('tags');
  const cached = readCache('tags');
  if (token !== routeToken) return;
  state.tags = cached?.data || state.tags;
  if (state.tags) renderTags(); else renderSkeleton();
  try {
    const fresh = await refreshing;
    if (token !== routeToken) return;
    const changed = JSON.stringify(state.tags?.items) !== JSON.stringify(fresh.items);
    const previousScroll = scroll.scrollTop;
    state.tags = fresh;
    writeCache('tags', fresh);
    if (changed) { renderTags(); scroll.scrollTop = previousScroll; }
  } catch (error) {
    if (token !== routeToken) return;
    if (!state.tags) renderError(error, 'tags');
    else toast('标签更新失败，正在显示缓存', true);
  }
}

async function goTag(slug, label) {
  const token = ++routeToken;
  if (state.route !== 'listing' || state.listing?.kind !== 'tag-list' || state.listing?.slug !== slug) state.listingReturn = captureScreen();
  setRoute('listing'); state.tab = 'home'; setNav('home');
  searchInput.value = `#${privateMode() ? '' : (slug.startsWith('comic:') ? '漫剧·' : '短剧·')}${String(label).replace(/^#+/, '')}`;
  const refreshing = api('tag-list', { slug, label, page: 1 });
  const cached = readCache(`tag:${slug}:1`);
  if (token !== routeToken) return;
  const firstLoad = !cached?.data;
  state.listing = cached?.data ? { ...cached.data, kind: 'tag-list', slug, label, refreshing: true, loading: false, error: false } :
    { items: [], nextPage: null, kind: 'tag-list', slug, label, refreshing: true, loading: false, error: false };
  renderListing();
  try {
    const fresh = await refreshing;
    if (token !== routeToken) return;
    const changed = listSignature(state.listing) !== listSignature(fresh) || state.listing.nextPage !== fresh.nextPage;
    const previousScroll = scroll.scrollTop;
    state.listing = { ...fresh, kind: 'tag-list', slug, label, refreshing: false, loading: false, error: false };
    writeCache(`tag:${slug}:1`, fresh);
    if (firstLoad || changed) { renderListing(); scroll.scrollTop = previousScroll; }
    else updateListingFooter();
  } catch (error) {
    if (token !== routeToken) return;
    if (!state.listing.items.length) renderError(error, 'tag-list');
    else { state.listing.refreshing = false; updateListingFooter(); toast('列表更新失败，已显示缓存内容', true); }
  }
}

async function goTagQuery(query) {
  const name = query.replace(/^[#＃]\s*/, '').trim();
  if (!name) { goTags(); return; }
  let tags = asArray(state.tags?.items);
  if (!tags.length) {
    const cached = readCache('tags');
    tags = asArray(cached?.data?.items);
  }
  if (!tags.length) {
    try { const fresh = await api('tags'); state.tags = fresh; writeCache('tags', fresh); tags = asArray(fresh.items); }
    catch { toast('标签暂时无法加载，请重试', true); return; }
  }
  const normalize = value => String(value || '').replace(/^#+/, '').trim().toLocaleLowerCase();
  const matches = item => normalize(item.name) === normalize(name) || (!privateMode() &&
    normalize(`${item.group === 'comic' ? '漫剧' : '短剧'}·${item.name}`) === normalize(name));
  let tag = tags.find(matches);
  if (!tag) {
    try { const fresh = await api('tags'); state.tags = fresh; tag = asArray(fresh.items).find(matches); }
    catch { /* 已有清单仍可用于搜索 */ }
  }
  if (tag) goTag(tag.slug, tag.name);
  else toast('没有找到这个标签', true);
}

async function openListing(kind) {
  if (kind !== 'recommend' && kind !== 'newest') return;
  const token = ++routeToken;
  rememberScreen();
  listingObserver?.disconnect();
  setRoute('listing'); state.tab = 'home';
  setNav('home');
  const initial = state.home?.[kind];
  state.listing = {
    kind, items: [...asArray(initial?.items)], nextPage: initial?.nextPage || null,
    refreshing: true, loading: false, error: false
  };
  renderListing();
  try {
    const fresh = await api(kind, { page: 1 });
    if (token !== routeToken) return;
    state.listing.items = asArray(fresh.items);
    state.listing.nextPage = fresh.nextPage;
    state.listing.refreshing = false;
    const previousScroll = scroll.scrollTop;
    renderListing();
    scroll.scrollTop = previousScroll;
  } catch (error) {
    if (token !== routeToken) return;
    state.listing.refreshing = false;
    if (!state.listing.items.length) renderError(error, 'listing');
    else { updateListingFooter(); toast('列表更新失败，已显示缓存内容', true); }
  }
}

async function loadMoreListing() {
  const listing = state.listing;
  if (state.route !== 'listing' || !listing?.nextPage || listing.refreshing || listing.loading || listing.error) return;
  const token = routeToken;
  const pageNumber = listing.nextPage;
  listing.loading = true;
  updateListingFooter();
  try {
    const page = await api(listing.kind, { page: pageNumber, slug: listing.slug, label: listing.label });
    if (token !== routeToken || state.listing !== listing || state.route !== 'listing') return;
    const known = new Set(listing.items.map(item => String(item.id)));
    const added = asArray(page.items).filter(item => {
      const id = String(item.id);
      if (known.has(id)) return false;
      known.add(id);
      return true;
    });
    listing.items.push(...added);
    listing.nextPage = page.items?.length && page.nextPage > pageNumber ? page.nextPage : null;
    const grid = content.querySelector('.listing-grid');
    if (grid && added.length) {
      grid.insertAdjacentHTML('beforeend', added.map(itemCard).join(''));
      const images = [...grid.querySelectorAll('img[data-cover]')].slice(-added.length);
      cover.hydrateList(images, 8);
    }
    const count = content.querySelector('.listing-page > p');
    if (count) count.textContent = `已显示 ${listing.items.length} 部作品`;
  } catch (error) {
    if (token === routeToken && state.listing === listing && state.route === 'listing') listing.error = true;
  } finally {
    listing.loading = false;
    if (state.listing === listing && state.route === 'listing') updateListingFooter();
  }
}

async function goCategory(tab, sub = subIndex(tab), keepCompact = false) {
  const token = ++routeToken;
  categoryObserver?.disconnect();
  if (state.route === 'home') rememberScreen();
  setRoute('category'); state.tab = tab; state.sub[tab] = sub; state.category = null; state.categoryBanner = 0;
  if (!keepCompact) setNav(tab);
  renderSkeleton();   // 同理：先出骨架，别让上一个页面一直挂着
  const id = categoryId();
  const refreshing = api('category', { id, page: 1 });
  // 缓存先画：分类页要等接口 + 20 张封面，先出上一次的列表能省掉整段白屏。
  const cached = readCache(`category:${id}:1`);
  if (token !== routeToken) return;
  state.category = cached?.data ? { ...cached.data, refreshing: true, loading: false, error: false } : null;
  if (state.category) renderCategory(keepCompact); else renderSkeleton();
  try {
    const fresh = await refreshing;
    if (token !== routeToken) return;
    const changed = state.category && listSignature(state.category) !== listSignature(fresh);
    const firstLoad = !state.category;
    const previousScroll = scroll.scrollTop;
    state.category = { ...fresh, refreshing: false, loading: false, error: false };
    state.categoryItems = asArray(fresh.items);
    writeCache(`category:${id}:1`, fresh);
    if (firstLoad || changed) {
      renderCategory(keepCompact || state.compactHeld);
      if (!firstLoad) scroll.scrollTop = previousScroll;
    } else {
      refreshMissingImages(asArray(fresh.items));
      updateCategoryFooter();
    }
  } catch (error) {
    if (token !== routeToken) return;
    if (!state.category) {
      renderError(error, 'category');
      scheduleCategoryRetry(token, id);
    } else { state.category.refreshing = false; updateCategoryFooter(); toast('列表更新失败，已显示缓存内容', true); }
  }
}

async function goLibrary(tab = 'history') {
  const token = ++routeToken;
  if (state.route === 'home') rememberScreen();
  state.libraryTab = tab;
  setRoute('library'); state.tab = 'library'; setNav('library');
  // 观看记录和收藏都是本地数据，手上已经有的先画出来。
  // 桥调用统一排在 4 个线程的池子里，而真机上网络请求要 25 秒才超时 ——
  // 池子被慢请求占满时这里就得排队，干等骨架屏就成了「点我的要加载很久」。
  if (state.library) renderLibrary(); else renderSkeleton();
  try {
    const library = await api('library');
    if (token !== routeToken) return;
    state.library = library;
    renderLibrary();
  } catch (error) {
    if (token === routeToken && !state.library) renderError(error, 'library');
  }
}

function goSettings() {
  state.settingsReturn = captureScreen();
  setRoute('settings'); state.tab = 'library'; setNav('library');
  renderSettings();
}

function goAccountManagement() {
  if (state.route === 'account-management') return;
  if (state.route !== 'settings') goSettings();
  state.accountReturn = captureScreen();
  setRoute('account-management');
  renderAccountManagement();
}

async function goSearch(query) {
  const token = ++routeToken;
  if (state.route === 'home') rememberScreen();
  state.previous = { route: state.route, tab: state.tab };
  setRoute('search'); state.query = query.trim(); state.filter = 'all'; state.search = null;
  state.searchPending = true; state.searchError = '';
  scroll.scrollTop = 0;
  renderSearchLoading();
  let timeout;
  const refreshing = api('search', { query: state.query });
  // 同步读缓存：上一次搜同一个词的记录先顶上，不用等接口。
  const cachedSearch = readCache(`search:${state.query}`);
  if (cachedSearch?.data) { state.search = cachedSearch.data; renderSearch(); }
  try {
    const fresh = await Promise.race([refreshing, new Promise((_, reject) => {
      timeout = setTimeout(() => reject(new Error('搜索连接超时，请重试')), 25000);
    })]);
    if (token !== routeToken) return;
    state.searchPending = false; state.search = fresh;
    writeCache(`search:${state.query}`, fresh);
    renderSearch();
  } catch (error) {
    if (token !== routeToken) return;
    state.searchPending = false;
    if (state.search) { state.searchError = error.message || '搜索刷新失败'; renderSearch(); }
    else renderError(error, 'search');
  } finally {
    clearTimeout(timeout);
  }
}

function sourceItem(id) {
  const candidates = [state.listing?.items, state.home?.featured, state.home?.homepageRecommend, state.home?.homepageNewest, state.home?.recommend?.items, state.home?.newest?.items, state.category?.items, state.search?.items, state.library?.bookmarks, state.library?.progress].flatMap(asArray);
  return candidates.find(item => item.id === id) || { id, title: `剧集 ${id}` };
}
async function goDetail(id, trigger) {
  if (state.route === 'detail' && state.sourceItem?.id === id) {
    // Retry in place; preserve the original source card and return destination.
    state.detailLoading = true; renderDetail();
    const token = ++routeToken;
    try { const fresh = await api('detail', { id }); if (token === routeToken) { state.detail = fresh; state.detailLoading = false; renderDetail(); } }
    catch (error) { if (token === routeToken) { state.detailLoading = false; renderError(error, 'detail'); } }
    return;
  }
  const token = ++routeToken;
  const entrySource = bannerChanging && trigger?.closest('.hero') ? null : HGCardTransition.capture(trigger);
  rememberScreen();
  state.previous = {
    route: state.route, tab: state.tab, sub: subIndex(state.tab), query: state.query,
    filter: state.filter, scrollTop: scroll.scrollTop, compactHeld: state.compactHeld,
    horizontalScroll: [...content.querySelectorAll('.film-row,.filter-row,.home-tag-scroll')].map(row => row.scrollLeft),
    pageNode: content.firstElementChild
  };
  if (state.route === 'listing' && state.listing) state.listing.refreshing = false;
  listingObserver?.disconnect();
  categoryObserver?.disconnect();
  state.sourceItem = sourceItem(id);
  state.sourceGroup = state.route === 'category' ? categoryId() : state.sourceItem.category || '';
  const aiGroup = state.sourceGroup === 'ai-huanlian' || state.sourceGroup === 'ai-mogai';
  const candidates = state.route === 'category' ? asArray(state.category?.items) : state.route === 'search' ? asArray(state.search?.items) : state.route === 'listing' ? asArray(state.listing?.items) :
    [...asArray(state.home?.recommend?.items), ...asArray(state.home?.newest?.items)];
  state.sourceQueue = aiGroup ? candidates.filter(item => item.category === state.sourceGroup) : [];
  setRoute('detail'); state.detail = { ...state.sourceItem }; state.detailLoading = true; state.bookmarked = false;
  scroll.scrollTop = 0;
  updateCompact();
  renderDetail();
  if (entrySource) {
    detailCard = new HGCardTransition({
      app: document.getElementById('app'), scroll, content, source: entrySource, snapshot: state.previous,
      isDetail: () => state.route === 'detail',
      onClose: () => { returnFromDetail(); trigger?.focus({ preventScroll: true }); }
    });
    detailCard.open();
  }
  const refreshing = api('detail', { id });
  const cached = readCache(`detail:${id}`);
  if (token !== routeToken) return;
  let hasDetailData = Boolean(cached?.data);
  if (hasDetailData) { state.detail = cached.data; state.detailLoading = false; renderDetail(); }
  api('bookmark-state', { id }).then(saved => { if (token === routeToken) { state.bookmarked = saved; if (state.detail) renderDetail(); } }).catch(() => {});
  try {
    const fresh = await refreshing;
    if (token === routeToken) { state.detail = fresh; state.detailLoading = false; writeCache(`detail:${id}`, fresh); renderDetail(); }
  } catch (error) {
    if (!hasDetailData && token === routeToken) { state.detailLoading = false; renderError(error, 'detail'); }
  }
}

function returnFromDetail() {
  const previous = state.previous || { route: 'home', tab: 'home' };
  if (!previous.pageNode) { goHome(); return; }
  ++routeToken;
  setRoute(previous.route);
  state.tab = previous.tab;
  if (previous.route === 'category') state.sub[previous.tab] = previous.sub;
  state.query = previous.query || '';
  state.filter = previous.filter || 'all';
  state.compactHeld = previous.compactHeld;
  content.replaceChildren(previous.pageNode);
  state.previous = null;
  content.querySelectorAll('.film-row,.filter-row,.home-tag-scroll').forEach((row, index) => {
    row.scrollLeft = previous.horizontalScroll?.[index] || 0;
  });
  document.querySelectorAll('#dock [data-nav]').forEach(button =>
    button.classList.toggle('active', button.dataset.nav === state.tab));
  scroll.scrollTop = previous.scrollTop || 0;
  if (previous.route === 'category') compact.innerHTML = compactMarkup();
  updateCompact();
  cover.hydrate();
  if (previous.route === 'home') refreshHomeHistory();
  if (previous.route === 'category') observeCategoryEnd();
  if (previous.route === 'listing') observeListingEnd();
  if (isBannerRoute()) { restoreBannerIndex(); scheduleBannerAuto(); }
}

window.hgBack = () => {
  // 源设置页挡在最前面。这里必须 return true（告诉 Java「我处理了」）而不是
  // 什么都不做就往下走 —— 往下走会落到最后那句 return false，Java 就退出应用了。
  // 用户按返回多半是想收键盘或误触，不该被踢回桌面；而且再进来还是同一屏，
  // 看着像应用坏了。所以这里吞掉返回，不做任何可见动作。
  const setupOverlayForBack = document.getElementById('setup-overlay');
  if (setupOverlayForBack && !setupOverlayForBack.hidden) return true;
  if (!unlockOverlay.hidden) { closeUnlock(); return true; }
  if (!authOverlay.hidden) { hideAuth(); return true; }
  if (!confirmOverlay.hidden) { closeConfirmation(false); return true; }
  // 播放层是独立的全屏图层，盖在 #content 之上，必须在这里显式收掉，
  // 否则路由已经切走了、播放器还在上面盖着，看着像"返回没反应"。
  if (state.route === 'feed' || document.querySelector('.feed')) return feedClose();
  if (isRootColumn()) return false;
  if (state.route === 'campaign') { restoreScreen(state.campaignReturn); return true; }
  if (state.route === 'account-management') { restoreScreen(state.accountReturn); return true; }
  if (state.route === 'settings') { restoreScreen(state.settingsReturn); return true; }
  if (state.route === 'detail') { if (detailCard) detailCard.close(); else returnFromDetail(); return true; }
  if (state.route === 'listing' && state.listing?.kind === 'tag-list') { restoreScreen(state.listingReturn); return true; }
  if (state.route === 'tags') { restoreScreen(state.tagsReturn); return true; }
  if (state.route === 'listing') { goHome(); return true; }
  if (state.route === 'search') { searchInput.value = ''; goHome(); return true; }
  if (state.route !== 'home') { goHome(); return true; }
  return false;
};
window.hgResume = () => {
  cover.retry(true);
  if (state.route === 'library') goLibrary(state.libraryTab);
  if (state.route === 'home') refreshHomeHistory();
  if (state.route === 'detail' && state.detail) api('bookmark-state', { id: state.detail.id }).then(value => { state.bookmarked = value; renderDetail(); });
};

// ---- 竖屏沉浸流：一屏一集，上下滑切换，自动续播 ----
// 播放器逻辑已拆到 feed-player.js；app.js 只保留路由入口，避免主文件继续膨胀。
const feedPlayer = window.HGFeedPlayer.create({
  state, content, api, asArray, esc, icon, toast, readCache,
  // bridgeCall 是给播放进度上报用的（feed-player 里的 report()）。
  // 它走的是 detail 那条桥上的 __progress: 指令，不是新动作。
  bridgeCall,
  renderDetail, goHome, updateBackEnabled
});
function feedCleanup() { feedPlayer.cleanup(); }
function feedClose() { return feedPlayer.close(); }
function feedFrom(videoId, episode) { feedPlayer.open(videoId, episode); }

// openPlayer() 已删除（2026-10-16）。它是接原生 PlayerActivity 的入口，
// 而那个类连同 59 个附属类一起摘掉了（63 个文件 660KB），播放走 feedFrom()。
// 桥动作 open-player 在 Java 侧还留着编号（packed-switch 的索引不能动），
// 但 body 已收成空返回，所以就算有人再调也不会启动任何 Activity。

async function loadMoreCategory() {
  const category = state.category;
  if (state.route !== 'category' || !category?.nextPage || category.loading || category.refreshing || category.error) return;
  const token = routeToken;
  const next = category.nextPage;
  const id = categoryId();
  category.loading = true;
  updateCategoryFooter();
  try {
    const page = await api('category', { id, page: next });
    if (token !== routeToken || state.category !== category || state.route !== 'category' || categoryId() !== id) return;
    const existing = new Set(asArray(category.items).map(item => String(item.id)));
    const added = asArray(page.items).filter(item => {
      const itemId = String(item.id);
      if (existing.has(itemId)) return false;
      existing.add(itemId);
      return true;
    });
    category.items.push(...added);
    category.nextPage = page.items?.length && page.nextPage > next ? page.nextPage : null;
    const grid = content.querySelector('.category-grid');
    if (grid && added.length) {
      grid.insertAdjacentHTML('beforeend', added.map(itemCard).join(''));
      cover.hydrateList([...grid.querySelectorAll('img[data-cover]')].slice(-added.length), 8);
    }
    const count = content.querySelector('.category-count');
    if (count) count.textContent = `已显示 ${category.items.length} 部`;
  } catch (error) {
    if (token === routeToken && state.category === category && state.route === 'category') category.error = true;
  } finally {
    category.loading = false;
    if (state.category === category && state.route === 'category') updateCategoryFooter();
  }
}

document.getElementById('search-form').addEventListener('submit', event => {
  event.preventDefault();
  const query = searchInput.value.trim();
  searchInput.blur();
  if (['library', 'settings', 'account-management'].includes(state.route)) {
    state.libraryQuery = query;
    if (state.route === 'library') renderLibrary();
    else goLibrary(state.libraryTab);
    scroll.scrollTop = 0;
    return;
  }
  if (/^[#＃]/.test(query)) goTagQuery(query);
  else if (query) goSearch(query);
});
searchInput.addEventListener('input', () => {
  if (state.route !== 'library') return;
  state.libraryQuery = searchInput.value;
  renderLibrary();
  scroll.scrollTop = 0;
});

document.addEventListener('submit', async event => {
  if (event.target.id === 'proxy-form') {
    event.preventDefault();
    const input = document.getElementById('proxy-value');
    const error = document.getElementById('proxy-error');
    const button = event.target.querySelector('button[type="submit"]');
    if (!input || !button) return;
    button.disabled = true;
    button.textContent = '正在保存…';
    if (error) error.hidden = true;
    try {
      const saved = await sourceCommand('proxy:set?value=' + encodeURIComponent(input.value.trim()));
      state.proxy = String(saved.proxy ?? '');
      setRoute('settings'); renderSettings();
      toast(state.proxy ? '已走代理 ' + state.proxy : '已改回直连');
    } catch (problem) {
      if (error) { error.textContent = problem.message; error.hidden = false; }
    } finally {
      if (button.isConnected) { button.disabled = false; button.textContent = '保存'; }
    }
    return;
  }
  if (event.target.id === 'source-form') {
    event.preventDefault();
    const input = document.getElementById('source-value');
    const error = document.getElementById('source-error');
    const button = event.target.querySelector('button[type="submit"]');
    if (!input || !button) return;
    button.disabled = true;
    button.textContent = '正在拉取…';
    if (error) error.hidden = true;
    try {
      await sourceCommand('use?url=' + encodeURIComponent(input.value.trim()));
      restartAfterSource(() => {
        if (error) { error.textContent = '已切换，但没能自动重开，请退出后重新打开'; error.hidden = false; }
        button.disabled = false;
        button.textContent = '添加并切换';
      });
    } catch (problem) {
      if (error) { error.textContent = problem.message; error.hidden = false; }
      button.disabled = false;
      button.textContent = '添加并切换';
    }
    return;
  }
  if (event.target.id !== 'password-form') return;
  event.preventDefault();
  const form = event.target;
  const oldPassword = form.querySelector('#old-password').value;
  const newPassword = form.querySelector('#new-password').value;
  const repeated = form.querySelector('#confirm-password').value;
  const error = form.querySelector('#password-error');
  const button = form.querySelector('[type="submit"]');
  error.hidden = true;
  if (newPassword !== repeated) { error.textContent = '两次输入的新密码不一致'; error.hidden = false; return; }
  if (newPassword === oldPassword) { error.textContent = '新密码不能与旧密码相同'; error.hidden = false; return; }
  button.disabled = true;
  try { setAccount(await api('change-password', { oldPassword, newPassword })); toast('密码已修改'); }
  catch (failure) { error.textContent = failure.message || '修改失败，请稍后重试'; error.hidden = false; button.disabled = false; }
});

function navigateDock(tab) {
  if (state.route === 'detail') state.previous = null;
  if (tab === 'home') goHome();
  else if (tab === 'library') goLibrary();
  else goCategory(tab);
}

function tapDock(button) {
  const tab = button.dataset.nav;
  const now = performance.now();
  if (lastDockTap.tab === tab && now - lastDockTap.at < 320) {
    const alreadyNavigated = lastDockTap.navigated;
    lastDockTap = { tab: '', at: 0, navigated: false };
    const icon = button.querySelector('.nav-icon');
    icon?.classList.remove('dock-bounce');
    void icon?.offsetWidth;
    icon?.classList.add('dock-bounce');
    icon?.addEventListener('animationend', () => icon.classList.remove('dock-bounce'), { once: true });
    scroll.scrollTop = 0;
    if (!alreadyNavigated) navigateDock(tab);
    return;
  }
  const route = tab === 'home' ? 'home' : tab === 'library' ? 'library' : 'category';
  const navigated = state.route !== route || state.tab !== tab;
  lastDockTap = { tab, at: now, navigated };
  if (navigated) navigateDock(tab);
}

document.addEventListener('click', async event => {
  const campaignButton = event.target.closest('[data-open-campaign]');
  if (campaignButton) {
    if (campaignButton.closest('.hero') && performance.now() < heroSuppressClickUntil) return;
    const item = activeCampaigns().find(item => item.id === campaignButton.dataset.openCampaign);
    if (item) goCampaign(item);
    return;
  }
  if (event.target.closest('[data-secret-entry]')) { handleVersionTap(); return; }
  if (event.target.closest('[data-account-management]')) { goAccountManagement(); return; }
  if (event.target.closest('[data-proxy-settings]')) { goProxySettings(); return; }
  if (event.target.closest('[data-source-settings]')) { goSourceSettings(); return; }
  if (event.target.closest('[data-source-forget]')) {
    const dropButton = event.target.closest('[data-source-forget]');
    const dropUrl = dropButton.dataset.sourceForget;
    if (!dropUrl) return;
    dropButton.disabled = true;
    try {
      await sourceCommand('forget?url=' + encodeURIComponent(dropUrl));
      await refreshSources();
      renderSourceSettings();
      toast('已删除');
    } catch (problem) { toast(problem.message, true); }
    return;
  }
  if (event.target.closest('[data-source-use]')) {
    const pickButton = event.target.closest('[data-source-use]');
    const pickUrl = pickButton.dataset.sourceUse;
    if (!pickUrl || pickButton.disabled) return;
    pickButton.disabled = true;
    const status = document.getElementById('source-error');
    if (status) { status.hidden = false; status.textContent = '正在切换…'; }
    try {
      await sourceCommand('use?url=' + encodeURIComponent(pickUrl));
      restartAfterSource(() => {
        if (status) status.textContent = '已切换，但没能自动重开，请退出后重新打开';
        pickButton.disabled = false;
      });
    } catch (problem) {
      if (status) status.textContent = problem.message;
      pickButton.disabled = false;
    }
    return;
  }
  if (event.target.closest('[data-choose-avatar]')) { api('choose-avatar').catch(error => toast(error.message, true)); return; }
  if (event.target.closest('[data-account-action]')) {
    if (state.account.loggedIn) goAccountManagement();
    else showAuth();
    return;
  }
  if (event.target.closest('[data-sync-now]')) {
    try { await api('sync'); state.library = await api('library'); toast('片单已同步'); }
    catch (error) { toast(`同步失败：${error.message}`, true); }
    return;
  }
  if (event.target.closest('[data-logout]')) {
    const button = event.target.closest('[data-logout]');
    if (button.disabled) return;
    button.disabled = true;
    button.classList.add('is-loading');
    button.setAttribute('aria-busy', 'true');
    button.textContent = '正在退出…';
    try {
      setAccount(await api('logout'));
      state.library = await api('library');
      setRoute('settings'); renderSettings();
      toast('已退出登录');
    }
    catch (error) { toast(error.message, true); }
    finally {
      if (button.isConnected) {
        button.disabled = false;
        button.classList.remove('is-loading');
        button.removeAttribute('aria-busy');
        button.textContent = '退出登录';
      }
    }
    return;
  }
  const nav = event.target.closest('[data-nav]');
  if (nav) { tapDock(nav); return; }
  const libraryTab = event.target.closest('[data-library-tab]');
  if (libraryTab) { state.libraryTab = libraryTab.dataset.libraryTab; renderLibrary(); scroll.scrollTop = 0; return; }
  if (event.target.closest('[data-all-history]')) { state.libraryQuery = ''; goLibrary('history'); return; }
  const continueCard = event.target.closest('[data-continue-id]');
  if (continueCard) { resumeFromHome(continueCard.dataset.continueId).catch(error => toast(error.message || '续播失败，请重试', true)); return; }
  const historyCard = event.target.closest('[data-history-id]');
  if (historyCard) {
    const id = historyCard.dataset.historyId;
    if (historyCard.dataset.historyCompleted === 'true') goDetail(id, historyCard);
    else goDetail(id).then(() => feedFrom(id, Number(historyCard.dataset.historyEpisode || 1)));
    return;
  }
  if (event.target.closest('[data-all-tags]')) { goTags(); return; }
  const tagChoice = event.target.closest('[data-tag-slug]');
  if (tagChoice) { goTag(tagChoice.dataset.tagSlug, tagChoice.dataset.tagName); return; }
  const sub = event.target.closest('[data-sub]');
  if (sub) { goCategory(state.tab, Number(sub.dataset.sub), !!sub.closest('#compact-category')); return; }
  const card = event.target.closest('[data-open-id]');
  if (card?.dataset.openId) {
    if (card.closest('.hero') && performance.now() < heroSuppressClickUntil) return;
    goDetail(card.dataset.openId, card); return;
  }
  const filter = event.target.closest('[data-filter]');
  if (filter) { state.filter = filter.dataset.filter; renderSearch(); return; }
  const slide = event.target.closest('[data-slide]');
  if (slide) { const target = Number(slide.dataset.slide); changeBanner(target, target > bannerIndex() ? 1 : -1); return; }
  if (event.target.closest('[data-back]')) { window.hgBack(); return; }
  const episode = event.target.closest('[data-episode]');
  if (episode) { feedFrom(state.detail?.id ?? state.sourceItem?.id, Number(episode.dataset.episode)); return; }
  if (event.target.closest('[data-play]')) { feedFrom(state.detail?.id ?? state.sourceItem?.id, 1); return; }
  if (event.target.closest('[data-bookmark]')) {
    if (!state.detail) return;
    try { state.bookmarked = await api('bookmark', state.detail); renderDetail(); toast(state.bookmarked ? '已加入我的片单' : '已取消收藏'); }
    catch (error) { toast(error.message, true); }
    return;
  }
  if (event.target.closest('[data-retry-category]')) { state.category.error = false; loadMoreCategory(); return; }
  const more = event.target.closest('[data-more]');
  if (more) { openListing(more.dataset.more); return; }
  if (event.target.closest('[data-retry-listing]')) { state.listing.error = false; loadMoreListing(); return; }
  const retry = event.target.closest('[data-retry]');
  if (retry) {
    if (retry.dataset.retry === 'home') goHome();
    else if (retry.dataset.retry === 'category') goCategory(state.tab);
    else if (retry.dataset.retry === 'library') goLibrary();
    else if (retry.dataset.retry === 'search') goSearch(state.query);
    else if (retry.dataset.retry === 'listing') openListing(state.listing.kind);
    else if (retry.dataset.retry === 'tags') goTags();
    else if (retry.dataset.retry === 'tag-list') goTag(state.listing.slug, state.listing.label);
    else if (retry.dataset.retry === 'detail') goDetail(state.sourceItem.id);
    return;
  }
  const clear = event.target.closest('[data-clear]');
  if (clear) {
    if (clear.dataset.clear === 'cache') {
      if (!await askConfirmation('清理内容缓存', '将删除已缓存的目录和封面。收藏与观看进度会保留。', '清理')) return;
      clearContentCache();
      await api('clear-content-cache').catch(() => {});
      toast('内容缓存已清空');
    } else if (clear.dataset.clear === 'history' || clear.dataset.clear === 'bookmarks') {
      const history = clear.dataset.clear === 'history';
      if (!await askConfirmation(history ? '清空观看历史' : '清空我的收藏', history ? '所有观看记录会被删除，我的收藏会保留。' : '所有收藏作品会被移除，观看历史会保留。')) return;
      await api(history ? 'clear-history' : 'clear-bookmarks');
      state.library = await api('library');
      renderSettings();
      toast(history ? '观看历史已清空' : '收藏已清空');
    }
  }
});

let heroTouch;
let heroSuppressClickUntil = 0;
content.addEventListener('touchstart', event => {
  if (!event.target.closest('.hero') || event.touches.length !== 1) return;
  stopBannerAuto();
  const touch = event.touches[0];
  heroTouch = { x: touch.clientX, y: touch.clientY, moved: false };
}, { passive: true });
content.addEventListener('touchmove', event => {
  if (!heroTouch || !event.touches.length) return;
  const touch = event.touches[0];
  heroTouch.moved ||= Math.abs(touch.clientX - heroTouch.x) > 10 || Math.abs(touch.clientY - heroTouch.y) > 10;
}, { passive: true });
content.addEventListener('touchend', event => {
  if (!heroTouch) return;
  const touch = event.changedTouches[0];
  const move = touch.clientX - heroTouch.x;
  const vertical = touch.clientY - heroTouch.y;
  if (heroTouch.moved || Math.abs(move) > 10 || Math.abs(vertical) > 10) heroSuppressClickUntil = performance.now() + 400;
  heroTouch = null;
  scheduleBannerAuto();
  if (Math.abs(move) < 55 || Math.abs(move) <= Math.abs(vertical) || !isBannerRoute()) return;
  const count = bannerSlides().length;
  if (!count) return;
  const direction = move < 0 ? 1 : -1;
  changeBanner((bannerIndex() + direction + count) % count, direction);
}, { passive: true });
content.addEventListener('touchcancel', () => {
  if (!heroTouch) return;
  heroTouch = null; heroSuppressClickUntil = performance.now() + 400;
  scheduleBannerAuto();
}, { passive: true });

api('app-version').then(version => {
  appVersion = String(version || '');
  const label = content.querySelector('.app-version-text');
  if (label) label.textContent = `版本 v${appVersion}`;
}).catch(() => {});
// 启动后原来会 setTimeout 3 秒去 api('check-update') —— 那条链路指向
// github.com/MrSsg/HuangguoAPP-for-Andriod 的 Releases，而那个仓库已经 404。
// 每次冷启动白打一次外网，拿不回任何东西，已移除。见 UpdateManager.check 的注释。
function startHome() { goHome(); }
document.getElementById('age-confirm').addEventListener('click', async () => { await api('confirm-private-age'); location.reload(); });
document.getElementById('age-exit').textContent = '暂不进入';
document.getElementById('age-exit').addEventListener('click', () => { document.getElementById('age-gate').hidden = true; });
// 启动链：contentReady → 源配置闸门 → startHome。
//
// 顺序不能颠倒。contentReady 负责把 contentSource 钉成 'huangguo'（并解锁年龄门槛），
// 而 bridgeCall 会把它带进每个请求；先过闸门的话，设置页那次 __source:get 会带着
// 默认的 'hongguo' 发出去，被路由到红果源，报一个跟源配置毫无关系的错。
// 闸门放在它之后：既不影响 contentReady，又能在拉接口之前把没配源的用户拦下。
contentReady
  .then(() => (window.HGSetup ? window.HGSetup.ensure() : undefined))
  .then(() => {
      // 闸门过了 = HG_SITE 已经是这个源的配置，这时候画底部导航才画得对。
      // app.html 里那两格是内置默认，配置不一样就在这一步被换掉。
      renderDock();
      // 代理设置只为设置页那一行文案服务，不阻塞启动 —— 拿不到就当中直连。
      sourceCommand('proxy:get').then(result => { state.proxy = String(result.proxy || ''); }).catch(() => {});
      refreshSources().catch(() => {});
    })
    .then(() => startHome())
  .catch(error => renderError(error, 'home'));

// ── 热更新已切断（2026-10-16）──────────────────────────────────────────
// 原来这里是一整套「等首页空闲再切换到已下载资源」的调度器：pendingResourceRevision
// / resourceApplyTimer / tryApplyResources / scheduleResourceApply，外加一组
// 交互监听来算用户是否空闲。它服务的是远端下发的站点规则与主题，而那两样
// 对现在这个走 /api/ REST 数据层的版本已经没有意义了（见 HotUpdateManager.check
// 的注释）。全部删掉。
//
// 只保留 window.hgCanApplyHotUpdate 这一个全局：Java 侧
// MainActivity.applyPendingResources() 会 evaluateJavascript 调它，拿返回的
// 字符串跟 "true" 比。恒返回 false，等于明确告诉原生层「没有可切换的资源」，
// 顺带保证 recreate() 永远不会因为资源切换被触发。
window.hgCanApplyHotUpdate = () => false;


let versionTapCount = 0, versionTapAt = 0;
function closeUnlock() {
  unlockOverlay.hidden = true;
  updateBackEnabled();
  document.getElementById('unlock-password').value = '';
  content.querySelector('[data-secret-entry]')?.focus();
}
async function enterPrivate(password = '') {
  const result = await api('enter-private', { password });
  closeUnlock();
  if (result.needsAgeConfirmation) document.getElementById('age-gate').hidden = false;
  else location.reload();
}
async function handleVersionTap() {
  const now = performance.now();
  versionTapCount = now - versionTapAt <= 1500 ? versionTapCount + 1 : 1;
  versionTapAt = now;
  if (versionTapCount < 5) return;
  versionTapCount = 0;
  if (privateMode()) return;
  try {
    const status = await api('content-status');
    if (status.unlocked) { await enterPrivate(); return; }
    document.getElementById('unlock-error').hidden = true;
    unlockOverlay.hidden = false;
    updateBackEnabled();
    document.getElementById('unlock-password').focus();
  } catch (error) { toast(error.message, true); }
}
document.getElementById('unlock-close').addEventListener('click', closeUnlock);
document.getElementById('unlock-form').addEventListener('submit', async event => {
  event.preventDefault();
  const button = event.target.querySelector('button[type="submit"]');
  button.disabled = true;
  try { await enterPrivate(document.getElementById('unlock-password').value); }
  catch (error) { const label = document.getElementById('unlock-error'); label.textContent = error.message; label.hidden = false; }
  finally { button.disabled = false; }
});

// ---- 卡片铺满：数据不足一屏时撑开，避免右侧留白 ----
// 布局是「固定 2 行 + 横向滚动、每列 106px」，412px 宽能排 3 列。
// 「精选推荐」这类区块如果只返回 4 条，就只占 2 列，右边空掉一块。
// 这里在「撑满后每格仍不小于原尺寸」时才加 is-fit 让它铺开；列数够多时保持原样滚动。
let filmFitTimer = 0;
function fitFilmRows() {
  if (!content) return;
  content.querySelectorAll('.film-row').forEach(row => {
    const columns = Math.ceil(row.children.length / 2);
    if (!columns) { row.classList.remove('is-fit'); return; }
    const style = getComputedStyle(row);
    const gap = parseFloat(style.columnGap) || 10;
    const padding = (parseFloat(style.paddingLeft) || 0) + (parseFloat(style.paddingRight) || 0);
    const available = row.clientWidth - padding;
    const minimum = 106;
    const needed = columns * minimum + (columns - 1) * gap;
    row.classList.toggle('is-fit', needed < available - 4);
  });
}
function scheduleFilmFit() {
  clearTimeout(filmFitTimer);
  filmFitTimer = setTimeout(fitFilmRows, 60);
}
if (content) {
  // 只监听子节点增删，不监听属性，否则自身加的 class 会把回调再触发一遍
  new MutationObserver(scheduleFilmFit).observe(content, { childList: true, subtree: true });
  window.addEventListener('resize', scheduleFilmFit);
  scheduleFilmFit();
}

// ---- 前端内容缓存 --------------------------------------------------------
// 原版把内容缓存写在 Java 的 AppStore.saveCache()，而那是 dispatch() 在桥调用
// 返回之后执行的。数据层改成 REST 之后，home/detail/category 这些动作不再经过
// dispatch()，saveCache 的调用点就全失效了 —— cache:home 永远是空的，冷启动
// 必然没数据可画。所以缓存必须由前端自己维护。
//
// 另一个好处：localStorage 是同步读的，首屏不用等任何网络往返。原版走
// api('cached') 桥调用虽然快，也要过一次异步往返。
//
// key 沿用原来的命名（home / tags / detail:<id> / category:<id>:<page> /
// tag:<slug>:<page> / search:<query>），这样旧缓存还能接着用。
const CACHE_PREFIX = 'hgcache:';

// 缓存的作用域 —— 当前是哪个源。换源必须换这一层，否则切换之后第一帧画出来的
// 是**上一个源的内容**：封面地址对不上新源的 allowedHosts（Java 侧会抛
// 「无效封面域名」），标题和集数也是别人的。这不是理论问题，从苹果CMS 切回黄果时
// 实测撞到过：31 条封面失败，DOM 里挂的还是上一个源的 img.lzipic.com。
//
// 由 setup.js 在放行启动之前写入（window.HG_CACHE_SCOPE）——顺序是对的：
// ensure() → applySiteConfig() 写作用域 → startHome() → goHome() 读缓存。
// 没配源时是空串，跟改造前行为一致。
function cacheScope() {
  const raw = (typeof window !== 'undefined' && window.HG_CACHE_SCOPE) || '';
  // 配置地址可能是任意长度，压成一个短且稳定的标识当键前缀。
  // 用简单的 32 位散列：这里只需要「不同源 → 不同键」，不需要抗碰撞。
  if (!raw) return '';
  let h = 0;
  for (let i = 0; i < raw.length; i += 1) h = (h * 31 + raw.charCodeAt(i)) | 0;
  return (h >>> 0).toString(36);
}
const CACHE_LIMIT = 80;
function cacheKeyOf(key) {
  const scope = cacheScope();
  return CACHE_PREFIX + (scope ? scope + ':' : '') + key;
}
function readCache(key) {
  try {
    const raw = localStorage.getItem(cacheKeyOf(key));
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (!parsed || !parsed.data) return null;
    return parsed;
  } catch (error) { return null; }
}
function writeCache(key, data) {
  if (!key || !data) return;
  try {
    localStorage.setItem(cacheKeyOf(key), JSON.stringify({ at: Date.now(), data }));
    // 控制条目数，避免把 localStorage 撑爆。
    //
    // 跨作用域一起淘汰、按时间最老的先走。原来这里豁免 home（「首页那条不参与淘汰」），
    // 那是因为全局只有一个 home；加了作用域之后每个源各有一条，再豁免就等于
    // 换几次源就永久留下几份首页数据。按时间淘汰天然保留当前源那份 ——
    // 它每次启动都会被 writeCache 刷新，时间戳最新。
    const keys = [];
    for (let index = 0; index < localStorage.length; index += 1) {
      const name = localStorage.key(index);
      if (name && name.startsWith(CACHE_PREFIX)) keys.push(name);
    }
    if (keys.length <= CACHE_LIMIT) return;
    keys.map(name => {
      let at = 0;
      try { at = JSON.parse(localStorage.getItem(name) || '{}').at || 0; } catch (error) {}
      return { name, at };
    }).sort((a, b) => a.at - b.at)
      .slice(0, keys.length - CACHE_LIMIT)
      .forEach(entry => localStorage.removeItem(entry.name));
  } catch (error) {}
}
function dropCache(key) { try { localStorage.removeItem(cacheKeyOf(key)); } catch (error) {} }
// 清空全部前端内容缓存（设置页的「清理内容缓存」要连这边一起清，
// 否则清完 Java 侧的 cache:*，前端这份还会把旧内容继续画出来）。
function clearContentCache() {
  try {
    const names = [];
    for (let index = 0; index < localStorage.length; index += 1) {
      const name = localStorage.key(index);
      if (name && name.startsWith(CACHE_PREFIX)) names.push(name);
    }
    names.forEach(name => localStorage.removeItem(name));
  } catch (error) {}
}

// 首页接口失败后的自动退避重试。
// 站点入口走 Cloudflare，实测在同一条移动链路上时通时不通（三次里大约一次能通），
// 所以「失败就停在错误页」等于把偶发故障变成永久故障。这里按 2s / 5s / 12s 退避
// 重试三次，任何一次成功页面就自动恢复；期间用户也能手动点「重新尝试」。
const HOME_RETRY_DELAYS = [2000, 5000, 12000];
function scheduleHomeRetry(token, attempt = 0) {
  if (attempt >= HOME_RETRY_DELAYS.length) return;
  clearTimeout(scheduleHomeRetry.timer);
  scheduleHomeRetry.timer = setTimeout(async () => {
    // 用户已经离开首页、或已经拿到内容了，就别再打了
    if (token !== routeToken || state.route !== 'home' || state.home) return;
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

// 分类页同理。它比首页更容易超时 —— 20 条列表 JSON 比首页大，而首屏已经
// 降到 8 条，重试成功率明显更高。失败就自己退避重来，用户不用手点。
function scheduleCategoryRetry(token, id, attempt = 0) {
  if (attempt >= HOME_RETRY_DELAYS.length) return;
  clearTimeout(scheduleCategoryRetry.timer);
  scheduleCategoryRetry.timer = setTimeout(async () => {
    if (token !== routeToken || state.route !== 'category' || categoryId() !== id) return;
    if (state.category) return;
    try {
      const fresh = await api('category', { id, page: 1 });
      if (token !== routeToken || state.route !== 'category' || categoryId() !== id) return;
      state.category = { ...fresh, refreshing: false, loading: false, error: false };
      state.categoryItems = asArray(fresh.items);
      writeCache(`category:${id}:1`, fresh);
      renderCategory(state.compactHeld);
    } catch (error) {
      if (token !== routeToken || state.route !== 'category') return;
      scheduleCategoryRetry(token, id, attempt + 1);
    }
  }, HOME_RETRY_DELAYS[attempt]);
}

// 内容缓存跟数据来源绑定：换接口版本时把整批旧缓存丢掉，避免旧结构渲染出错。
const DATA_SOURCE_VERSION = 'api-v3';
try {
  if (localStorage.getItem('hgpref:dataver') !== DATA_SOURCE_VERSION) {
    localStorage.setItem('hgpref:dataver', DATA_SOURCE_VERSION);
    const stale = [];
    for (let index = 0; index < localStorage.length; index += 1) {
      const name = localStorage.key(index);
      if (name && name.startsWith(CACHE_PREFIX)) stale.push(name);
    }
    stale.forEach(name => localStorage.removeItem(name));
    api('clear-content-cache').catch(() => {});
  }
} catch (error) {}

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

// 接口字段 → 原渲染层认的字段

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

// 接口挂了就在页面上留一行提示，方便真机上肉眼确认（不影响功能）
// 接口失败的页面提示。默认静默 —— 失败已经由各页面自己处理（有缓存就继续显示、
// 没有就出错误页并自动重试），再叠一条红条只会打扰用户。
// 排查时在 WebView 控制台执行 localStorage.setItem('hgpref:apidebug','1') 打开。
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
