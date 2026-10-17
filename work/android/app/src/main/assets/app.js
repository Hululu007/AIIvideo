'use strict';

const callbacks = new Map();
let requestNumber = 0;
let contentSource = 'hongguo';
let contentReady;
const bridgeInstance = Date.now() + '-' + Math.random().toString(36).slice(2);
const privateMode = () => contentSource === 'huangguo';
// 「恢复出厂设置」跑到一半留下的标记。它只交代一件事：那一次登出如果没等到网络
// 回来，下一次启动补一刀（见 finishResetLogout）。住在 localStorage 里，所以
// **必须最后写** —— 写早了会被重置自己的那次全量 clear() 一起清掉。
const RESET_FLAG = 'hgpref:factoryReset';
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
// 播放层（hxm/http.js）要用同一条桥。它是本模块作用域里的东西，得显式挂出去。
window.HG_BRIDGE = bridgeCall;
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

// 播放器那几条要原生帮忙的指令（亮度 / 清晰度）。和 sourceCommand 同一个套路：
// 走 detail 桥上的 __player: 前缀 —— 加动作不用碰 MainActivity 那两张 dispatch 表。
async function playerCommand(command) {
  const reply = await bridgeCall('detail', { id: '__player:' + command });
  const result = typeof reply === 'string' ? JSON.parse(reply) : reply;
  if (result && result.ok === false) throw new Error(result.error || '播放指令失败');
  return result || {};
}

// ── 播放器设置 ────────────────────────────────────────────────────────
// 两条播放路径：WebView 里的 <video>（默认）和原生 libVLC（1.0.18 起）。
//
// 为什么需要第二条：<video> 用哪个解码器由系统 WebView 决定，页面上没有任何
// 旋钮。于是同一部片在 A 机上能放、B 机上 MediaError 4（SRC_NOT_SUPPORTED）——
// 差别只有那台设备有没有这个硬解、WebView 版本里带没带这个解码器，而软件解码
// 这条路在 WebView 里根本不存在。走内置播放器之后解码链在 App 里，和设备硬解、
// WebView 版本都脱钩。
const PLAYER_PREF_KEY = 'hgpref:player';
// 2026-10-17 起只用内置播放器（libVLC）。core 和 autoFallback 两个开关
// 留着是为了兼容老设备上存过的偏好键，逻辑上已经不起作用 —— 播放一律走原生。
const PLAYER_DEFAULTS = { core: 'native', decode: 'auto', cache: 1500, autoFallback: false };

function playerPrefs() {
  let saved = {};
  try { saved = JSON.parse(localStorage.getItem(PLAYER_PREF_KEY) || '{}') || {}; } catch (error) {}
  const prefs = { ...PLAYER_DEFAULTS, ...saved };
  prefs.core = 'native';
  if (prefs.decode !== 'sw') prefs.decode = 'auto';
  prefs.cache = [800, 1500, 3000].includes(Number(prefs.cache)) ? Number(prefs.cache) : 1500;
  prefs.autoFallback = false;
  return prefs;
}

function savePlayerPrefs(patch) {
  const next = { ...playerPrefs(), ...patch };
  try { localStorage.setItem(PLAYER_PREF_KEY, JSON.stringify(next)); } catch (error) {}
  return next;
}

// 内置播放器到底能不能用（APK 只带了 arm64-v8a）。问一次缓存一页。
// 这个探测会真的去加载 so —— 45MB 的映射，别反复调。
function nativePlayerInfo() {
  if (state.nativeInfo) return Promise.resolve(state.nativeInfo);
  return playerCommand('capability')
    .then(info => { state.nativeInfo = info || { native: false, abi: '' }; return state.nativeInfo; })
    .catch(() => { state.nativeInfo = { native: false, abi: '' }; return state.nativeInfo; });
}

// 内置播放器（VlcPlayerActivity）按需向页面要播放地址的口子。
// 解密、wasm、多线路全在这边，原生只发一个问题：这一集的 URL 是什么。
//
// 两条硬约束：
//   1) 必须是**同步**函数。原生用的是 evaluateJavascript，它不等 Promise ——
//      返回 Promise 的话那边收到的是字符串 "{}"，等于永远拿不到地址。
//   2) 结果要落在 window.__hgNativeEps 这个表里（外加 localStorage 缓存）。
//      原生每 0.8 秒问一次，靠这张表把「正在解」和「解出来了」区分开。
window.__hgNativeEps = window.__hgNativeEps || {};
window.__hgNativeEpsBusy = window.__hgNativeEpsBusy || {};

function hgNativeEpKey(id, ep) { return id + ':' + ep; }

function hgNativeResolve(id, ep) {
  const key = hgNativeEpKey(id, ep);
  if (window.__hgNativeEpsBusy[key]) return;   // 已经在解了，别重复打接口
  window.__hgNativeEpsBusy[key] = true;
  api('episode', { id, episode: ep }).then(info => {
    let url = info.source || info.url || '';
    const resolveWasm = url.indexOf('agedm-wasm:') === 0 && window.HG_AGEDM_PLAY
      ? (Array.isArray(info.lines) && info.lines.length
          ? window.HG_AGEDM_PLAY.resolveAny(info.lines, () => {})
          : window.HG_AGEDM_PLAY.resolve(url.slice('agedm-wasm:'.length), () => {}))
      : Promise.resolve(url);
    return Promise.resolve(resolveWasm).then(resolved => {
      if (!/^https?:\/\//.test(resolved)) return;
      window.__hgNativeEps[key] = resolved;
      try {
        const all = JSON.parse(localStorage.getItem('hgpref:epurl') || '{}');
        all[key] = { url: resolved, at: Date.now() };
        localStorage.setItem('hgpref:epurl', JSON.stringify(all));
      } catch (error) {}
    });
  }).catch(() => {}).then(() => { delete window.__hgNativeEpsBusy[key]; });
}

window.__hgNativeEpisode = (id, ep) => {
  const key = hgNativeEpKey(id, ep);
  const live = window.__hgNativeEps[key];
  if (live) return live;
  try {
    const hit = JSON.parse(localStorage.getItem('hgpref:epurl') || '{}')[key];
    if (hit && hit.url && Date.now() - (hit.at || 0) < 8 * 60 * 1000) {
      window.__hgNativeEps[key] = hit.url;
      return hit.url;
    }
  } catch (error) {}
  hgNativeResolve(id, ep);
  return '';
};

// 播放器模块（feed-player.js）从这里取设置和两条桥指令。挂 window 而不是加参数，
// 是因为那块代码已经有一份 deps 列表，再加三个字段要动整个 create() 的签名。
window.HG_PLAYER = {
  prefs: playerPrefs,
  save: savePlayerPrefs,
  command: playerCommand,
  info: nativePlayerInfo,
  last: () => playerCommand('last').catch(() => null)
};

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
  sourceGroup: '', sourceQueue: [], previous: null, compactHeld: false,
  // 正在改备注的那个源的地址（内容源页面用）。空串 = 没有在编辑。
  sourceNote: '',
  // 源市场 {loading, items, error}；TVBox 导入 {url, loading, items, error}
  market: null, marketReturn: null, tvbox: null,
  // 内置播放器能力 {native, abi}；null = 还没问过
  nativeInfo: null,
  // 内置代理核心的状态 {mode, running, port, host, node, error, effective, ...}；
  // 由 __proxy:status 合成，前端只读不算 —— 见 renderProxySettings 上面那段。
  proxyCore: null
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
  // 热更新 trial 的确认兜底：只要 JS 跑到这里，说明这一版前端能正常启动。
  // 正常路径是 renderHome 里那一枪；但停在首启设置页的机器永远到不了 renderHome，
  // 没有这个兜底，每个热更新都会被判失败、下次启动回滚并拉黑。
  setTimeout(() => { try { markHotUpdateHealthy(); } catch (error) {} }, 2500);
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
  if (state.route === 'player-settings') renderPlayerSettings();
  if (state.route === 'source-settings' && !content.querySelector('#source-form')?.contains(document.activeElement)) renderSourceSettings();
  scroll.scrollTop = savedScroll;
}
api('account').then(setAccount).catch(() => {});
// 上一次恢复出厂设置如果有件没干完的事（登出没等到网络），在这儿把它做完。
finishResetLogout();
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


// ── 主题 ──────────────────────────────────────────────────────────────
// 偏好存 localStorage，三档：system / light / dark。app.html 的头部脚本在
// CSS 之前已经按同一份偏好定过一次 data-theme（防冷启动闪白），这里负责
// 运行时切换、跟随系统深色，以及把明暗告诉原生（状态栏/导航栏图标）。
const THEME_KEY = 'hgpref:theme';
const THEME_MODES = ['system', 'light', 'dark'];
const themeMedia = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
const reducedMotionQuery = window.matchMedia ? window.matchMedia('(prefers-reduced-motion: reduce)') : null;

function themeMode() {
  try {
    const saved = localStorage.getItem(THEME_KEY);
    if (THEME_MODES.includes(saved)) return saved;
  } catch (error) {}
  return 'system';
}
function systemDark() { return Boolean(themeMedia && themeMedia.matches); }
function applyTheme(mode = themeMode(), darkOverride) {
  const dark = mode === 'dark' || (mode === 'system' && (darkOverride === undefined ? systemDark() : Boolean(darkOverride)));
  const root = document.documentElement;
  root.dataset.theme = dark ? 'dark' : 'light';
  root.dataset.themePref = mode;
  root.style.colorScheme = dark ? 'dark' : 'light';
  const meta = document.getElementById('theme-color');
  if (meta) meta.setAttribute('content', dark ? '#171b1d' : '#f5f2e9');
  window.HG_THEME_DARK = dark;
}
function syncNativeTheme(mode = themeMode()) {
  // 状态栏/导航栏不归 WebView 管。原生侧拿同一个 mode 决定图标明暗；
  // 这条桥失败也只是图标颜色不跟手，页面主题不受影响。
  bridgeCall('theme', { mode }).catch(() => {});
}
function setThemeMode(mode) {
  if (!THEME_MODES.includes(mode)) return;
  try { localStorage.setItem(THEME_KEY, mode); } catch (error) {}
  const commit = () => { applyTheme(mode); syncNativeTheme(mode); };
  // View Transition 可用时做一次整屏交叉淡化，比让每个元素各自变色干净。
  if (document.startViewTransition && !(reducedMotionQuery && reducedMotionQuery.matches)) {
    try { document.startViewTransition(commit); } catch (error) { commit(); }
  } else commit();
  if (state.route === 'settings') {
    const top = scroll.scrollTop;
    renderSettings();
    scroll.scrollTop = top;
    requestAnimationFrame(() => content.querySelector(`[data-theme-mode="${mode}"]`)?.focus({ preventScroll: true }));
  }
  toast(`外观已切换为${mode === 'system' ? '跟随系统' : mode === 'dark' ? '深色' : '浅色'}`);
}
// 系统深色变化时原生会调这个。用户明确选了浅/深就尊重用户的选择。
window.hgTheme = dark => {
  if (themeMode() === 'system') applyTheme('system', Boolean(dark));
  else applyTheme();
};
if (themeMedia) {
  const onSystemTheme = event => { if (themeMode() === 'system') applyTheme('system', event.matches); };
  if (themeMedia.addEventListener) themeMedia.addEventListener('change', onSystemTheme);
  else if (themeMedia.addListener) themeMedia.addListener(onSystemTheme);
}
applyTheme();
syncNativeTheme();
api('theme').then(window.hgTheme).catch(() => {});

// ── 前端热更新 ────────────────────────────────────────────────────────
// 原生 HotUpdateManager 负责验签、下载、暂存和回滚；这里只做三件事：
// 显示状态、触发检查、告诉原生现在能不能 recreate。
let hotStatus = null;
let hotCheckPending = false;
// 原生 HotUpdateManager 在 minVersionCode 高于已装版本时抛「资源包需要更新应用版本」。
// 这条不是「没有更新」，是「先装新版 APK」，得和普通错误分开说。
let hotNeedsNewApp = '';
let hotNoticeRevision = 0;
let hotHealthyMarked = false;

function hotUpdateLabel(status = hotStatus) {
  if (!status) return '读取中…';
  const revision = Number(status.revision) || 0;
  if (!revision) return '内置界面';
  return `热更新 r${revision}${status.version ? ` · ${status.version}` : ''}`;
}
function syncHotStatus(status) {
  if (!status || typeof status !== 'object') return;
  hotStatus = status;
  if (state.route === 'settings') renderSettings();
  const pending = Number(status.pending) || 0;
  if (pending && pending !== hotNoticeRevision) {
    hotNoticeRevision = pending;
    toast(`前端更新 r${pending} 已下载，下次启动生效`);
  }
}
window.hgHotUpdateStatus = syncHotStatus;
// 原生 applyPendingResources() 会在 onResume 里问这一句。只在设置页返回 true，
// 避免用户在首页滚到一半被 recreate 打断；其他页面等下次冷启动自动切换。
window.hgCanApplyHotUpdate = () => Boolean(hotStatus && Number(hotStatus.pending) > 0)
  && state.route === 'settings' && !document.hidden;

function hotUpdateSection() {
  const status = hotStatus;
  const pending = Number(status?.pending) || 0;
  const blocked = Number(status?.blocked) || 0;
  const showBlocked = blocked > (Number(status?.revision) || 0);
  const checking = hotCheckPending || Boolean(status?.checking);
  return `<section class="settings-hot"><h2>前端热更新</h2>
    <p>界面文件（HTML / CSS / JS）走签名包更新，改版不用重装 APK。当前运行：${esc(hotUpdateLabel(status))}。</p>
    <div class="hot-update-meta"><i class="${pending ? 'is-ready' : ''}"></i><span>${pending ? `已下载 r${pending}，下次启动自动生效` : checking ? '正在检查更新…' : (hotNeedsNewApp || '没有待应用的更新')}</span></div>
    <div class="hot-update-actions">
      <button type="button" data-hot-check="1" ${checking ? 'disabled' : ''}>${checking ? '正在检查…' : '检查更新'}</button>
      ${pending ? '<button type="button" class="hot-apply" data-hot-apply="1">立即应用并重启</button>' : ''}
    </div>
    ${hotNeedsNewApp ? '<div class="hot-update-actions"><button type="button" class="hot-apply" data-goto-app-update="1">去更新应用</button></div>' : ''}
    ${showBlocked ? `<p class="hot-update-note">r${blocked} 没能正常启动，已回退到上一版；发一个更高的 revision 即可覆盖。</p>` : ''}
  </section>`;
}
async function checkHotUpdate() {
  if (hotCheckPending) return;
  hotCheckPending = true;
  if (state.route === 'settings') renderSettings();
  try {
    const status = await api('check-hot-update');
    hotStatus = status;
    hotNeedsNewApp = '';
    if (Number(status?.pending) > 0) toast(`发现前端更新 r${status.pending}，下次启动生效`);
    else toast('已经是最新界面');
  } catch (error) {
    const message = String(error.message || '');
    if (message.indexOf('需要更新应用版本') >= 0) {
      hotNeedsNewApp = '新的界面需要更新的应用版本，先装新版 APK';
      toast('这个界面需要新版应用，先去「应用更新」', true);
    } else {
      toast(message || '检查更新失败', true);
    }
  } finally {
    hotCheckPending = false;
    if (state.route === 'settings') renderSettings();
  }
}
function applyHotUpdate() {
  if (!hotStatus || !Number(hotStatus.pending)) return;
  api('apply-hot-update').catch(error => toast(error.message || '应用更新失败', true));
}
function markHotUpdateHealthy() {
  if (hotHealthyMarked) return;
  const runtime = window.HotRuntime || {};
  if (!runtime.revision || !runtime.bootId) return;
  // 「启动成功」的判据不能只有首页：还没配源的机器会一直停在设置页，
  // 首页永远不出现 —— 那样每个热更新都会被当成启动失败，下次启动回滚并拉黑。
  // 设置页能显示出来，就说明 HTML/CSS/JS 都跑通了，算健康。
  const setupOpen = !document.getElementById('setup-overlay')?.hidden;
  const homeReady = Boolean(document.querySelector('#content .home-page'));
  if (!setupOpen && !homeReady) return;
  hotHealthyMarked = true;
  bridgeCall('hot-ready', { revision: runtime.revision, bootId: runtime.bootId }).catch(() => { hotHealthyMarked = false; });
}
api('hot-update-status').then(syncHotStatus).catch(() => {});

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
  // 要在当前源里挑。不挑的话，换源之后首页那张「继续观看」卡片还是上一个源的
  // 片子，点下去就是「找不到这个 id」—— 和历史列表同一个坑。
  return asArray(state.library?.progress).find(sameSource) || null;
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
  // 热更新 session 的 trial 只有在首页真的画出来之后才算健康；否则下次启动
  // beginSession 会把它回滚到上一版。延迟一拍，等首屏图片和新 DOM 稳定。
  setTimeout(markHotUpdateHealthy, 400);
}

function renderTags() {
  const tags = asArray(state.tags?.items);
  // ── 2026-10-17 修：非 private 模式原来**不看配置** ──────────────────
  //
  // 原代码：
  //   const sections = privateMode() ? (siteUi().tagGroups || []) : [['short','短剧类型'],['comic','漫剧类型']];
  //
  // 而 privateMode() 只对内置的黄果源为 true（contentSource === 'huangguo'）。
  // 于是**所有用户自带源**都被按死在那一对短剧/漫剧上：配置里写了什么
  // tagGroups 都没用，标签的 group 只要不是 short/comic 就一个都渲染不出来。
  //
  // 症状极具误导性：接口明明拿到了 240 个标签（右上角就写着「240 个标签」），
  // 页面却是空的，而且不报错 —— 正是站点配置参考里警告过的那种静默失败。
  //
  // 现在的顺序：**配置优先**。只有配置里没写 tagGroups 时才退回内置分组，
  // 这样黄果那套和旧的短剧/漫剧源行为不变。
  const configured = siteUi().tagGroups || [];
  const sections = configured.length ? configured
    : (privateMode() ? [] : [['short', '短剧类型'], ['comic', '漫剧类型']]);
  const groups = sections.map(([id, title]) => {
    const members = tags.filter(tag => tag.group === id);
    if (!members.length) return '';
    return `<section class="tags-group"><h2>${title}</h2><div class="tags-grid">${members.map(tag => {
      const name = String(tag.name || tag.slug);
      const size = name.length > 8 ? 'wide' : name.length > 4 ? 'medium' : '';
      return `<button type="button" class="tag-choice ${size}" data-tag-slug="${esc(tag.slug)}" data-tag-name="${esc(name)}">${esc(name)}</button>`;
    }).join('')}</div></section>`;
  }).join('');
  content.innerHTML = `<div class="tags-page"><div class="tags-heading"><button type="button" data-back="1" aria-label="返回上一页">${icon('back')}</button><h1>全部类型</h1><span>${tags.length} 个标签</span></div>
    <p class="tags-intro">点选标签，发现相关作品</p>${groups}</div>`;
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

// 和 UserSource.identity() 的前缀对齐。改了那边这里也要改。
const IDENTITY_PREFIX = 'hgsrc:';

function renderLibrary() {
  const data = state.library || { progress: [], bookmarks: [] };
  const query = state.libraryQuery.trim().toLocaleLowerCase();
  const matches = item => String(item.title || '').toLocaleLowerCase().includes(query);
  // 不是当前内容源的记录直接不显示 —— 它们的 id 在这个源里没有意义，露出来
  // 只会让人点进去撞错。记录本身没删，切回那个源还在。
  const progress = asArray(data.progress).filter(sameSource).filter(matches);
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

function appearanceSection() {
  const mode = themeMode();
  const choices = [
    ['system', '跟随系统', '随手机切换'],
    ['light', '浅色', '暖纸白'],
    ['dark', '深色', '夜间低亮']
  ];
  return `<section class="settings-appearance"><h2>外观</h2><p>给眼睛挑一个舒服的亮度。跟随系统会随手机的深色模式自动切换。</p>
    <div class="theme-choice" role="radiogroup" aria-label="界面主题">${choices.map(([value, label, hint]) => `<button type="button" role="radio" aria-checked="${mode === value}" class="theme-choice-item ${mode === value ? 'selected' : ''}" data-theme-mode="${value}">
      <span class="theme-swatch swatch-${value}" aria-hidden="true"><i></i><b></b></span>
      <span class="theme-choice-copy"><strong>${label}</strong><small>${hint}</small></span>
    </button>`).join('')}</div></section>`;
}

// ── 应用内 APK 更新 ─────────────────────────────────────────────────
// 原生 UpdateManager 一直是活的：清单校验、SHA-256、下载进度回调、拉起系统
// 安装器全都在（check-update / download-update 两个桥指令）。死掉的只有两样 ——
// 老仓库的 URL（已换到自己 VPS 的 /updates/）和这一层 UI（2026-10-16 整块删了，
// 理由写着「通道已死」；通道活回来之后 UI 就得跟着回来）。
const updateCheck = { phase: 'idle', info: null, progress: 0, message: '' };

// 下载中的状态要活过页面重建。
//
// 之前这里是纯内存的：用户点「下载并安装」，切到首页再切回设置，`updateCheck`
// 已经被重新渲染成「检查更新…」，进度条没了，看着像下载被取消了（其实原生
// 线程还在下）。所以把 phase 钉进 localStorage，重建后接着显示进度。
const UPDATE_STATE_KEY = 'hgupd:phase';

function rememberUpdatePhase() {
  try {
    localStorage.setItem(UPDATE_STATE_KEY, JSON.stringify({
      phase: updateCheck.phase, progress: updateCheck.progress, at: Date.now()
    }));
  } catch (error) {}
}

function restoreUpdatePhase() {
  try {
    const saved = JSON.parse(localStorage.getItem(UPDATE_STATE_KEY) || 'null');
    if (!saved || saved.phase !== 'downloading') return;
    // 下载中最多认 10 分钟。过了就当它断了，别让界面永远卡在 37% 那种数字上。
    if (Date.now() - (Number(saved.at) || 0) > 600000) {
      localStorage.removeItem(UPDATE_STATE_KEY);
      return;
    }
    updateCheck.phase = 'downloading';
    updateCheck.progress = Number(saved.progress) || 0;
  } catch (error) {}
}
restoreUpdatePhase();

function renderUpdateCheck() {
  const area = document.getElementById('update-check-area');
  if (!area) return;
  const u = updateCheck;
  if (u.phase === 'checking') { area.innerHTML = '<p class="update-note">正在检查更新…</p>'; return; }
  if (u.phase === 'none') { area.innerHTML = '<p class="update-note">已是最新版本</p>'; return; }
  if (u.phase === 'error') {
    area.innerHTML = `<p class="update-note">${esc(u.message || '检查失败')}</p><button type="button" data-check-update="1">重试</button>`;
    return;
  }
  if (u.phase === 'available') {
    const size = u.info?.size ? ` · ${(u.info.size / 1048576).toFixed(1)} MB` : '';
    area.innerHTML = `<div class="update-available"><div class="update-available-head"><strong>新版本 v${esc(u.info?.version || '')}</strong><small>${esc(size)}</small></div>${u.info?.notes ? `<p>${esc(u.info.notes)}</p>` : ''}<button type="button" data-download-update="1">下载并安装</button></div>`;
    return;
  }
  if (u.phase === 'downloading') {
    const pct = Math.min(100, Math.floor(u.progress));
    let detail = `下载中 ${pct}%`;
    if (u.progress < 100) {
      // 速度和剩余时间是「等下值不值」的唯一线索。60MB 的包在 400KB/s 下要
      // 两分半，只给百分比的话用户分不清是在下还是卡死了。
      const speed = fmtSpeed(updateSpeed.bps);
      if (speed) detail += ` · ${speed}`;
      const left = remainingSeconds(pct);
      if (left > 0) detail += ` · 约 ${fmtLeft(left)}`;
    }
    area.innerHTML = `<div class="update-progress"><div class="update-progress-bar"><i style="width:${pct}%"></i></div><small>${u.progress >= 100 ? '下载完成，正在拉起安装…' : detail}</small></div>`;
    return;
  }
  area.innerHTML = '<button type="button" data-check-update="1">检查更新</button>';
}

async function checkAppUpdate() {
  // 下载中就别重新检查了：那会把进度条换成「正在检查更新…」，看起来像点了没反应。
  if (updateCheck.phase === 'downloading') return;
  updateCheck.phase = 'checking'; renderUpdateCheck();
  try {
    // manual:true 让原生跳过 30 分钟自动检查节流 —— 用户点了按钮就是要立即查。
    const result = await api('check-update', { manual: true });
    const status = String(result?.status || 'none');
    if (status === 'available') {
      updateCheck.phase = 'available';
      updateCheck.info = result.available_manifest || result;
    } else if (status === 'throttled') {
      // 原生那边一小时只放行 5 次手动检查。以前这里一律落到 none，
      // 于是「点太多被限流」和「真的没有新版本」显示同一句话「已是最新版本」——
      // 用户越急越点，越点越看到「已是最新」，最后只能来问人。
      updateCheck.phase = 'error';
      updateCheck.message = '检查太频繁了，过几分钟再试。';
    } else if (status === 'unpublished') {
      updateCheck.phase = 'error';
      updateCheck.message = '服务器上没有发布信息。';
    } else {
      updateCheck.phase = 'none';
    }
  } catch (error) {
    updateCheck.phase = 'error'; updateCheck.message = error.message;
  }
  renderUpdateCheck();
}

async function downloadAppUpdate() {
  updateSpeed.pct = null; updateSpeed.at = 0; updateSpeed.bps = 0;
  updateCheck.phase = 'downloading'; updateCheck.progress = 0; renderUpdateCheck();
  rememberUpdatePhase();
  try {
    await api('download-update');
    updateCheck.progress = 100;
    rememberUpdatePhase();
  } catch (error) {
    updateCheck.phase = 'error'; updateCheck.message = error.message;
    try { localStorage.removeItem(UPDATE_STATE_KEY); } catch (ignore) {}
  }
  renderUpdateCheck();
}
// 原生下载线程每收到一块数据就推一次百分比；装包环节的两种失败各有一个钩子。
// 下载速度。原生只推百分比，速度得自己按两次回调的差算。
// 平滑一下：直接除会抖成 0.1 / 9.8 / 2.3 这种，看着像坏了。
const updateSpeed = { pct: null, at: 0, bps: 0 };

function fmtSpeed(bps) {
  if (!bps || bps <= 0) return '';
  if (bps >= 1048576) return (bps / 1048576).toFixed(1) + ' MB/s';
  if (bps >= 1024) return Math.round(bps / 1024) + ' KB/s';
  return Math.round(bps) + ' B/s';
}

function remainingSeconds(pct) {
  const size = Number(updateCheck.info?.size) || 0;
  if (!size || !updateSpeed.bps || pct >= 100) return 0;
  return Math.max(0, Math.round((size * (100 - pct) / 100) / updateSpeed.bps));
}

function fmtLeft(sec) {
  if (sec >= 60) return Math.floor(sec / 60) + ' 分 ' + (sec % 60) + ' 秒';
  return sec + ' 秒';
}

window.hgUpdateProgress = percent => {
  if (updateCheck.phase !== 'downloading') updateCheck.phase = 'downloading';
  const now = Date.now();
  const size = Number(updateCheck.info?.size) || 0;
  if (updateSpeed.pct != null && size > 0) {
    const dp = percent - updateSpeed.pct;
    const dt = (now - updateSpeed.at) / 1000;
    if (dp > 0 && dt > 0.2) {
      const bps = (dp / 100) * size / dt;
      updateSpeed.bps = updateSpeed.bps ? updateSpeed.bps * 0.65 + bps * 0.35 : bps;
    }
  }
  updateSpeed.pct = percent;
  updateSpeed.at = now;
  updateCheck.progress = percent;
  rememberUpdatePhase();
  renderUpdateCheck();
};
window.hgUpdateInstallError = () => toast('无法拉起系统安装程序', true);
// 原生那边下载失败会把 null 传回来，现在它走这个钩子而不是直接去装（那条路会 NPE）。
window.hgUpdateFailed = () => {
  updateCheck.phase = 'error';
  updateCheck.message = '下载失败，检查一下网络再试。';
  try { localStorage.removeItem(UPDATE_STATE_KEY); } catch (ignore) {}
  renderUpdateCheck();
};
window.hgUpdateInstallPermissionDenied = () => toast('没有「安装未知应用」权限，请在系统设置里允许本应用', true);

function renderSettings() {
  content.innerHTML = `<div class="settings-page"><button type="button" class="listing-back" data-back="1">${icon('back')} 返回我的</button><h1>设置</h1>
    <section><button type="button" class="settings-link" data-account-management="1"><span><strong>账号管理</strong><small>${state.account.loggedIn ? esc(state.account.username) : '登录或注册账号'}</small></span>${icon('arrow')}</button></section>
    ${appearanceSection()}
    ${hotUpdateSection()}
    <section class="settings-library"><h2>片单管理</h2><p>${state.account.loggedIn ? '清空后会同步到当前账号的其他设备。' : '清空当前设备保存的片单。'}</p>
      <button type="button" data-clear="history" ${asArray(state.library?.progress).length ? '' : 'disabled'}>清空观看历史</button>
      <button type="button" data-clear="bookmarks" ${asArray(state.library?.bookmarks).length ? '' : 'disabled'}>清空我的收藏</button></section>
    <section class="update-settings"><div><h2>应用更新</h2><button type="button" class="app-version" data-secret-entry aria-label="当前版本 ${esc(appVersion)}"><span class="app-version-text">版本 ${appVersion ? `v${esc(appVersion)}` : '读取中…'}</span></button><p>当前安装包版本。</p></div><div class="update-check-area" id="update-check-area"><button type="button" data-check-update="1">检查更新</button></div></section>
    <section class="settings-storage"><h2>本地存储</h2><button type="button" data-clear="cache">清理内容缓存</button></section>
      <section><button type="button" class="settings-link" data-market="1"><span><strong>源市场</strong><small>粘一个源清单地址，点卡片就能用</small></span>${icon('arrow')}</button></section>
      <section><button type="button" class="settings-link" data-source-settings="1"><span><strong>内容源</strong><small>${state.sourceName ? esc(state.sourceName + '（' + state.sourceCount + ' 个已保存）') : '未配置'}</small></span>${icon('arrow')}</button></section>
      <section><button type="button" class="settings-link" data-proxy-settings="1"><span><strong>网络代理</strong><small>${esc(proxySummary())}</small></span>${icon('arrow')}</button></section>
      <section><button type="button" class="settings-link" data-player-settings="1"><span><strong>播放器</strong><small>${playerSummary()}</small></span>${icon('arrow')}</button></section>
    <section class="settings-reset"><h2>恢复出厂设置</h2>
      <p>退出登录，并清掉这台设备上的收藏、观看历史、内容缓存、内容源和代理，回到刚装好、还没填源的样子。账号本身和云端的片单不受影响，重新登录还能拿回来。</p>
      <button type="button" data-factory-reset="1">恢复出厂设置</button></section></div>`;
}

// 网络代理。实测直连那条链六次里四次 9~18 秒（cf-ray 落在 LAX），而换路由
// 是客户端唯一能真正改变延迟的手段 —— DoH、连接复用、预热都只是把已经确定的
// 那段延迟挪个位置，换路才是换一条路。
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
  return `<div class="tvbox-block">
    <h2>导入 TVBox 配置</h2>
    <p>粘贴一个 TVBox 单仓地址。只导入其中的苹果CMS源（type 0/1），需要下载执行的 spider 会跳过。</p>
    ${box && box.error ? `<p class="auth-error" role="alert">${esc(box.error)}</p>` : ''}
    <form id="tvbox-form" class="password-form${box && box.loading ? ' is-loading' : ''}">
      <input id="tvbox-value" type="text" inputmode="url" autocomplete="off" spellcheck="false" placeholder="https://example.com/tvbox.json" value="${esc(box?.url || '')}">
      <button type="submit" ${box && box.loading ? 'disabled' : ''}>${box && box.loading ? '正在解析…' : '解析配置'}</button>
    </form>
    ${rows}
  </div>`;
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
  content.innerHTML = `<div class="settings-page"><button type="button" class="listing-back" data-back="1">${icon('back')} 返回设置</button><h1>内容源</h1>
    <section class="settings-account">
      <button type="button" class="source-refetch" data-market="1">打开源市场<small>粘一份清单地址，点一下就能加</small></button>
      <p>切换源会重启应用。第二次之后走本地缓存，不用再等网络。</p>
      <ul class="source-list">${rows || '<li class="source-empty">还没有保存过任何源</li>'}</ul>
      ${active ? `<button type="button" class="source-refetch" data-source-refetch="${esc(String(active.url || ''))}">
        重新拉取这个源的配置
        <small>服务器上改了配置、这边一直用旧的，点这里。要联网，会重启。</small>
      </button>` : ''}
      <form id="source-form" class="password-form">
        <label for="source-value">添加一个源</label>
        <input id="source-value" type="text" inputmode="url" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="https://example.com/source.json">
        <label for="source-alias">备注（可留空）</label>
        <input id="source-alias" type="text" maxlength="40" autocomplete="off" placeholder="给这个源起个名字，比如「量子」">
        <p class="field-hint">地址必须以 https:// 开头。支持我们自己的 JSON 和苹果CMS 的接口地址。备注只是给人看的，随时能改。</p>
        <p id="source-error" class="auth-error" role="alert" hidden></p>
        <button type="submit">添加并切换</button>
      </form>
      ${tvboxSection()}
    </section></div>`;
}

/** 写/清一个源的备注。空串 = 清掉，退回自动取的名字。 */
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
  content.querySelector('#source-value')?.focus();
}

// ── 网络代理 ──────────────────────────────────────────────────────────
//
// 三条路：直连 / 内置核心 / 外部地址。前面那条老链路（手填一个地址自己搭代理）
// 还留着，因为它是唯一不依赖核心能不能在这台机器上跑起来的办法 —— 老设备没
// arm64，内置核心根本起不来（ProxyCore.unavailableReason() 会给原因）。
//
// 页面不自己推导「现在到底走哪条」：Java 的 __proxy:status 已经把核心状态、
// 用户手填值、以及 AiiHttp.effectiveProxy() 的结论合成了一份。这里只负责显示，
// 因为「模式是内置、核心没起来、于是回落到外部」这三种事实的交叉判断只该有一处。
const PROXY_MODES = [
  { value: 'direct', label: '直连', note: '所有请求都不走代理。' },
  { value: 'builtin', label: '内置核心', note: '粘一条分享链接，App 自己在本机起 SOCKS5。' },
  { value: 'external', label: '外部地址', note: '自己有一个代理（路由器、电脑上跑的那个）。' }
];

// __proxy: 走 detail 桥，和 __source: 同一个套路。加动作不用碰 dispatch 那两张表。
async function proxyCoreCommand(command) {
  const reply = await bridgeCall('detail', { id: '__proxy:' + command });
  const result = typeof reply === 'string' ? JSON.parse(reply) : reply;
  return result || {};
}

// 状态只放一份在 state 上，渲染和操作都读它，操作完把新状态整块换掉。
function proxyState() {
  if (!state.proxyCore) state.proxyCore = { mode: 'direct', running: false, loaded: false };
  return state.proxyCore;
}

function applyProxyCore(status) {
  // 合并不是覆盖：失败答复（ok:false 只带 error）不该把上一次读到的 mode/port
  // 抹成 undefined —— 那样页面会在「内置核心」和「直连」之间闪一下。
  const patch = {};
  Object.keys(status || {}).forEach(key => { if (status[key] !== undefined) patch[key] = status[key]; });
  state.proxyCore = { ...proxyState(), ...patch, loaded: true };
  if (state.route === 'proxy-settings') renderProxySettings();
  return state.proxyCore;
}

// 冷启动恢复一次：盘上是「内置核心」就顺手拉起，不然用户重启完 App 会发现
// 昨晚开的代理今天没了，而页面上还写着「内置核心」。
//
// force = 每次进设置页都要重问一次。状态是会**自己变**的（核心可能崩了、用户可能
// 在别处点了停止），所以「刚才查过」不能当成「现在还这样」—— 只靠启动时那一次，
// 进设置页看到的可能还是启动那一刻的画面。页面重画时读内存那份，避免闪烁。
function bootProxyCore(force) {
  const core = proxyState();
  if (core.booting) return core.booting;
  if (core.loaded && !force) return Promise.resolve(core);
  core.booting = proxyCoreCommand(force ? 'status' : 'boot')
    .then(applyProxyCore)
    .catch(error => { core.booting = null; core.error = error.message || '代理状态读不到'; applyProxyCore({}); })
    .then(value => { core.booting = null; return value; });
  return core.booting;
}

/** 设置页那一行的副标题。三种模式 + 核心在不在跑，一句话说完。 */
function proxySummary() {
  const core = proxyState();
  const mode = core.mode || 'direct';
  if (mode === 'builtin') {
    if (core.running) return '内置核心 · ' + (core.host || '') + ' 已连接' + (core.node ? ' · ' + core.node : '');
    return core.error ? '内置核心没能启动：' + core.error : '内置核心 · 未启动';
  }
  if (mode === 'external') return core.external ? '外部地址 · ' + core.external : '外部地址 · 还没填';
  return '直连。有机场或自建代理的话在这儿换一条路';
}

/**
 * 订阅导入 + 测速的状态。
 *
 * 和 proxyState 分开放：代理状态是「核心在不在跑」（Java 侧的事实），
 * 订阅状态是「这一页正在干什么」（纯前端），混一起会让 applyProxyCore
 * 的合并逻辑把测速进度冲掉。
 */
function subState() {
  if (!state.proxySub) {
    state.proxySub = { url: '', count: 0, summary: '', speedNote: '', speed: [], testing: false, busy: false };
  }
  return state.proxySub;
}

/**
 * 测速结果列表。只列「通了」的，按延迟升序（Java 侧已经排好）。
 *
 * 不通的不显示 —— 用户要的是「点哪个能用」，不是一份体检报告。
 * 数量写在上面的提示行里，看得到「40 个里通了 6 个」这个事实。
 */
/**
 * 重画设置页但保住滚动位置。
 *
 * 订阅和测速这两步是**原地更新**的（不换路由），如果直接 renderProxySettings()，
 * 整页 innerHTML 换掉会让滚动条弹回顶部 —— 用户刚往下滚到测速列表，一刷新就
 * 被扔回「返回设置」，体验很糟。
 */
function renderProxySettingsKeepScroll() {
  const scroll = document.scrollingElement ? document.scrollingElement.scrollTop : 0;
  renderProxySettings();
  if (document.scrollingElement) document.scrollingElement.scrollTop = scroll;
}

/**
 * 跑一轮测速。
 *
 * 单独抽出来是因为有两个入口：点「测速并挑选」，以及导入订阅之后自动跑一次。
 * 两处各写一遍的话，进度文案和失败处理迟早分叉。
 */
async function runSpeedTest() {
  const sub = subState();
  if (sub.busy) return;
  sub.busy = true;
  sub.testing = true;
  sub.speedNote = '正在测速，最多 20 秒…';
  sub.speed = [];
  renderProxySettingsKeepScroll();
  try {
    const result = await proxyCoreCommand('speed');
    sub.busy = false;
    sub.testing = false;
    if (result.ok === false) throw new Error(result.error || '测速失败');
    sub.speed = Array.isArray(result.nodes) ? result.nodes : [];
    sub.count = result.total || sub.count;
    // 说清测的是哪一份：内置那份是 App 自带，订阅那份是用户自己导的。
    const from = result.source === 'subscription' ? '你的订阅' : '内置的免费节点';
    sub.speedNote = '从' + from + '里挑了 ' + (result.total || 0)
      + ' 个来测，通了 ' + (result.usable || 0) + ' 个。点一行直接连。';
  } catch (problem) {
    sub.busy = false;
    sub.testing = false;
    sub.speedNote = problem.message;
  }
  renderProxySettingsKeepScroll();
}

function renderSpeedList(sub) {
  const usable = (sub.speed || []).filter(item => item.ok);
  if (!usable.length) return '';
  return `<div class="speed-list">${usable.slice(0, 12).map(item => `
    <button type="button" class="speed-item" data-proxy-use="${item.index}">
      <span class="speed-ms">${item.ms}ms</span>
      <span class="speed-node">${esc(item.node)}</span>
    </button>`).join('')}</div>`;
}

function renderProxySettings() {
  const core = proxyState();
  const sub = subState();
  const mode = core.mode || 'direct';
  const option = (item, on) =>
    `<button type="button" data-proxy-mode="${item.value}" aria-checked="${on ? 'true' : 'false'}" class="${on ? 'is-on' : ''}">${item.label}</button>`;

  // 状态行。核心在跑时给端口，没跑时给原因 —— 「未启动」和「起不来」对用户是两件事。
  let statusLine;
  if (mode === 'builtin') {
    if (core.running) statusLine = `${esc(core.host || '127.0.0.1')} 已连接${esc(core.node ? ' · ' + core.node : '')}`;
    else if (core.unavailable) statusLine = '这台设备跑不了内置核心：' + esc(core.unavailable);
    else if (core.error) statusLine = esc(core.error);
    else statusLine = '未启动';
  } else if (mode === 'external') {
    statusLine = core.external ? '走 ' + esc(core.external) : '还没填地址，等于直连';
  } else {
    statusLine = '直连';
  }
  const statusTone = core.running && mode === 'builtin' ? ' is-live' : (core.error && mode === 'builtin' ? ' is-bad' : '');

  content.innerHTML = `<div class="settings-page"><button type="button" class="listing-back" data-back="1">${icon('back')} 返回设置</button><h1>网络代理</h1>
    <section class="settings-player">
      <h2>连接方式</h2>
      <p>${esc(PROXY_MODES.find(item => item.value === mode)?.note || '')}</p>
      <div class="player-options">${PROXY_MODES.map(item => option(item, item.value === mode)).join('')}</div>
      <p class="field-hint${statusTone}">状态：${statusLine}</p>
      ${core.effective ? `<p class="field-hint">当前实际出口：${esc(core.effective)}</p>` : ''}
      <p id="proxy-core-error" class="auth-error" role="alert" hidden></p>
    </section>
    ${mode === 'builtin' ? `<section class="settings-player">
      <h2>节点配置</h2>
      <p>粘一条分享链接（vmess / vless / trojan / ss 都行）。核心只在本机 127.0.0.1 开一个 SOCKS5，不接管系统流量，别的 App 不受影响。</p>
      <form id="proxy-core-form" class="password-form">
        <label for="proxy-link">分享链接</label>
        <input id="proxy-link" type="text" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="vmess:// 、vless:// 、trojan:// 、ss://" value="${esc(core.link || '')}">
        <p class="field-hint">这一条会存在 App 的私有目录里，包含 uuid / 密码，卸载即清。</p>
        <p id="proxy-core-form-error" class="auth-error" role="alert" hidden></p>
        <button type="submit">保存并启动</button>
      </form>
      <div class="player-options">
        <button type="button" data-proxy-core="stop"${core.running ? '' : ' disabled'}>停止</button>
        <button type="button" data-proxy-core="test"${core.running ? '' : ' disabled'}>测试连通性</button>
      </div>
      <p id="proxy-core-test" class="field-hint" hidden></p>
      <p class="field-hint">核心会一直跑到你手动停止或重启手机。切到后台不停 —— 后台可能在放音频。</p>
    </section>
    <section class="settings-player">
      <h2>免费节点</h2>
      <p>App 自带一组公开共享节点。点一下测速，挑快的直接连 —— 不用注册、不用填任何东西。</p>
      <div class="player-options">
        <button type="button" data-proxy-sub="speed">${sub.testing ? '测速中…' : '测速并挑选'}</button>
      </div>
      <p id="proxy-sub-speed" class="field-hint"${sub.speedNote ? '' : ' hidden'}>${esc(sub.speedNote || '')}</p>
      ${renderSpeedList(sub)}
      <p class="field-hint">公开共享节点，随时可能失效；流量会经过陌生人的服务器，别用来登录重要账号。连不上就过一会儿再试，或者往下面用自己的订阅。</p>
    </section>
    <section class="settings-player">
      <h2>用自己的订阅</h2>
      <p>有机场的话，粘它的订阅地址，App 会拉下来测速，比免费节点稳得多。</p>
      <form id="proxy-sub-form" class="password-form">
        <label for="proxy-sub-url">订阅地址</label>
        <input id="proxy-sub-url" type="text" inputmode="url" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="https://…/sub" value="${esc(sub.url || '')}">
        <p class="field-hint">支持节点列表和 base64 两种格式。Clash 的 YAML 不支持，用机场后台的「V2Ray 订阅」链接。</p>
        <button type="submit">拉取并测速</button>
      </form>
      <p id="proxy-sub-status" class="field-hint"${sub.summary ? '' : ' hidden'}>${esc(sub.summary || '')}</p>
      ${sub.url ? '<div class="player-options"><button type="button" data-proxy-sub="clear">改回用免费节点</button></div>' : ''}
    </section>` : ''}
    ${mode === 'external' ? `<section class="settings-account">
      <h2>代理地址</h2>
      <p>填了之后所有请求都走它。适合有代理的人用来绕开慢链路。</p>
      <form id="proxy-form" class="password-form">
        <label for="proxy-value">代理地址</label>
        <input id="proxy-value" type="text" inputmode="url" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="127.0.0.1:7890 或 socks5://127.0.0.1:7891" value="${esc(state.proxy || '')}">
        <p class="field-hint">支持 host:port、http://host:port、socks5://host:port。留空表示直连。</p>
        <p id="proxy-error" class="auth-error" role="alert" hidden></p>
        <button type="submit">保存</button>
      </form>
    </section>` : ''}
    ${mode === 'direct' ? `<section class="settings-player">
      <p class="field-hint">直接连。之前配过的节点和地址都还留着，切回去就能用。</p>
    </section>` : ''}
  </div>`;
}

function playerSummary() {
  const prefs = playerPrefs();
  const core = prefs.core === 'native' ? '内置（VLC）' : '网页';
  const decode = prefs.core === 'native' && prefs.decode === 'sw' ? ' · 软解' : '';
  return core + decode + (prefs.autoFallback ? ' · 失败自动切内置' : '');
}

/**
 * 播放器设置。
 *
 * 这一页存在的理由就是「不要在设置里没得选」：编码解不了这件事，用户能做的
 * 只有换播放器，而换播放器不该等于去外面装一个 App。
 */
function renderPlayerSettings() {
  const prefs = playerPrefs();
  const info = state.nativeInfo;
  const nativeState = info == null
    ? '正在检测…'
    : info.native
      ? '可用（' + esc(info.abi || 'arm64-v8a') + '）'
      : '这个安装包没带本机架构的解码库，只会用网页播放器';
  const option = (key, value, label, on, disabled) =>
    `<button type="button" data-player-set="${key}:${value}" aria-checked="${on ? 'true' : 'false'}"${disabled ? ' disabled' : ''} class="${on ? 'is-on' : ''}">${label}</button>`;
  content.innerHTML = `<div class="settings-page"><button type="button" class="listing-back" data-back="1">${icon('back')} 返回设置</button><h1>播放器</h1>
    <section class="settings-player">
      <h2>播放内核</h2>
      <p>只用内置播放器（libVLC，自带完整软解）。网页播放器那条路已经移除 —— 它靠系统解码器，同一个片在有的手机上就是放不出来。</p>
      <p class="field-hint">解码库：${nativeState}</p>
    </section>
    <section class="settings-player">
      <h2>解码方式</h2>
      <p>自动会优先用设备的硬件解码，失败时再退回软解。画面花屏、绿屏、或者能出声没画面时改成软解试试。</p>
      <div class="player-options">
        ${option('decode', 'auto', '自动（推荐）', prefs.decode === 'auto')}
        ${option('decode', 'sw', '一律软解', prefs.decode === 'sw')}
      </div>
      <p class="field-hint">只对内置播放器生效。网页播放器的解码器是系统给的，改不了。</p>
    </section>
    <section class="settings-player">
      <h2>网络缓存</h2>
      <p>缓存越大越抗抖动，但拖动进度条时反应会慢一点。</p>
      <div class="player-options">
        ${option('cache', '800', '起播优先', prefs.cache === 800)}
        ${option('cache', '1500', '标准（推荐）', prefs.cache === 1500)}
        ${option('cache', '3000', '抗抖优先', prefs.cache === 3000)}
      </div>
      <p class="field-hint">只对内置播放器生效。</p>
    </section>
    <section class="settings-player">
      <h2>操作</h2>
      <p>点任意一集直接进播放器。上下滑切下一集 / 上一集，左右滑拖进度，单击出控件，倍速在最右边那个胶囊里选。</p>
      <p class="field-hint">进度会写回观看历史，下次进来接着看。</p>
    </section></div>`;
}

function goPlayerSettings() {
  if (state.route === 'player-settings') return;
  if (state.route !== 'settings') goSettings();
  state.playerReturn = captureScreen();
  setRoute('player-settings');
  renderPlayerSettings();
  // 页面先用「正在检测…」渲染出来，探测结果回来再补一次 —— 不让这一页等 so 加载。
  nativePlayerInfo().then(() => { if (state.route === 'player-settings') renderPlayerSettings(); });
}

function goProxySettings() {
  if (state.route === 'proxy-settings') return;
  if (state.route !== 'settings') goSettings();
  state.proxyReturn = captureScreen();
  setRoute('proxy-settings');
  // 先用上次那份状态画一屏（不然会白一下），再问 Java 要一次真实的。
  renderProxySettings();
  bootProxyCore(true);
  // 只有「外部地址」模式下才有那个输入框 —— 别的模式下别抢焦点弹键盘。
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

// ── 恢复出厂设置 ──────────────────────────────────────────────────────
//
// 「像刚装好一样」拆开是四件事：没有登录、没有片单、没有缓存、没有源。账号本身
// 和云端的片单不动 —— 重新登录还能拿回来。
//
// 顺序是硬要求：
//   ① 先退出登录。ParseUser 的当前用户活在**内存**里，而重启走的是 Activity 重建、
//      进程不死；MainActivity 在 onCreate 里照样能从它拿到 objectId，界面照样是
//      登录态。所以「把盘上那份账号文件删掉」不等于退出登录，必须让 logout 真的
//      跑完（这正是它排在清盘前面的原因，而不是反过来）。
//   ② 再让 Java 清盘（__source:reset）：源、片单、代理、头像、缓存目录，外加 WebView
//      的 Cookie —— HttpOnly 的 Cookie 前端看不见也删不掉，只有 CookieManager 够得着。
//      这一步又必须在①之后：清盘会把 cacheDir 端掉，而 Parse 的登录态就在那底下，
//      先清它就等于让①没得退。
//   ③ 然后清前端自己这份（localStorage 为主）。排在②之后才有效 —— 反过来的话，
//      ②里那次 cacheDir 清理正好会抽掉③还在写的文件。
//   ④ 最后重启。「配置」是 MainActivity.onCreate 那条链里读的，源没了，重进来的
//      那一屏就是「设置源」，和第一次装好打开时一模一样。
async function runFactoryReset(button) {
  const accepted = await askConfirmation('恢复出厂设置',
    '退出登录，并清掉这台设备上的收藏、观看历史、内容缓存、内容源和代理。清完会重启，回到刚装好、还没填源的样子（要重新填一次源地址）。账号本身和云端片单不受影响。',
    '确认重置');
  if (!accepted) return;

  const original = button.textContent;
  button.disabled = true;
  button.classList.add('is-loading');
  button.textContent = '正在重置…';
  try {
    // ① 退出登录。离线时 Parse 的登出要等它自己的超时，而后面三步全是本地操作，
    //    没理由陪着一起等 —— 给它封个顶：只有**明确回来了**才算退干净，超时（undefined）
    //    和报错（false）都当没退，下一次启动补（finishResetLogout）。
    let loggedOut = true;
    if (state.account.loggedIn) {
      loggedOut = await settleWithin(api('logout').then(() => true).catch(() => false), 6000) === true;
    }
    // ② Java 清盘
    await sourceCommand('reset');
    // ③ 前端存储
    await wipeWebStorage();
    // ④ 没退干净的记一笔给下一次启动（必须在③之后写），然后重启
    if (!loggedOut) {
      try { localStorage.setItem(RESET_FLAG, String(Date.now())); } catch (error) {}
    }
    toast('已恢复出厂设置，正在重启…');
    restartAfterSource(() => { location.reload(); });
  } catch (error) {
    console.log('[reset] 重置中断：' + (error.message || error));
    toast('重置没做完：' + (error.message || error), true);
    button.disabled = false;
    button.classList.remove('is-loading');
    button.textContent = original;
  }
}

/** 给一个动作封顶：到点就往下走，不管它做没做完。做没做完由调用方自己判断。 */
function settleWithin(promise, ms) {
  return Promise.race([promise, new Promise(resolve => window.setTimeout(resolve, ms))]);
}

/**
 * 前端这一侧的存储。Java 那边清的是 Cookie 和 DOM storage（见 __source:reset），
 * 这里把页面自己看得见的全部清空 —— 重点是 localStorage：片单、接口缓存、
 * 首页骨架屏、缓存作用域、倍速档位，全在它里面。
 *
 * Cache Storage 和 IndexedDB 现在没人用，但「像刚装好一样」要成立，就不能按
 * 「我猜没用到」来清；两个 API 在某些 WebView 上会缺席，各自包一层。
 */
async function wipeWebStorage() {
  try { localStorage.clear(); } catch (error) {}
  try { sessionStorage.clear(); } catch (error) {}
  try {
    document.cookie.split(';').forEach(item => {
      const name = item.split('=')[0].trim();
      if (name) document.cookie = name + '=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
    });
  } catch (error) {}
  try { await Promise.all((await caches.keys()).map(name => caches.delete(name))); } catch (error) {}
  try {
    const databases = await indexedDB.databases();
    (databases || []).forEach(db => { if (db && db.name) indexedDB.deleteDatabase(db.name); });
  } catch (error) {}
}

/**
 * 上一次恢复出厂设置如果没能当场退出登录（离线时网络那一截要等超时），这里补一刀。
 * 标记读一次就删 —— 它记的是「还有件事没干完」，不是配置。
 *
 * 为什么不能省：登出没回来，内存里那个 ParseUser 就还在；Activity 重建之后界面
 * 照样是登录态、只是片单空了 —— 「恢复出厂设置」这句承诺就不成立。
 */
function finishResetLogout() {
  let pending = '';
  try { pending = localStorage.getItem(RESET_FLAG) || ''; } catch (error) {}
  if (!pending) return;
  try { localStorage.removeItem(RESET_FLAG); } catch (error) {}
  console.log('[reset] 上一次重置的登出没跑完，补一次');
  api('logout').then(setAccount).catch(() => {});
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
// 重试的统一入口：错误页按钮和自动重试都走这里，保证语义一致。
function performRetry(retry) {
  if (retry === 'home') goHome();
  else if (retry === 'category') goCategory(state.tab);
  else if (retry === 'library') goLibrary();
  else if (retry === 'search') goSearch(state.query);
  else if (retry === 'listing') openListing(state.listing.kind);
  else if (retry === 'tags') goTags();
  else if (retry === 'tag-list') goTag(state.listing.slug, state.listing.label);
  else if (retry === 'detail') goDetail(state.sourceItem.id);
}

// 网络类错误的判定和展示是两件事，分开。
const NETWORK_ERROR_RE = /站点连接失败|站点请求失败|failed to connect|unable to resolve host|unknownhost|connectexception|sockettimeoutexception|timed? out|network is unreachable|connection reset|connection refused|网络|连接失败|无法连接/i;

// 把桥上抛上来的原始错误翻译成人话。原文太技术（「接口响应缺少 data」「HTTP 404」），
// 直接糊用户一脸没有信息量；原文收进小字里，排查时还有得看。
function friendlyError(error) {
  const raw = (error?.message || '').trim();
  const m = raw.toLowerCase();
  if (/404|not found|不存在|查不到/.test(m)) {
    return { icon: 'gone', title: '内容不见了', detail: '这条内容可能已下架，或当前源没有收录它。', raw };
  }
  if (/超时|timeout|timed out/.test(m)) {
    return { icon: 'slow', title: '源站响应太慢', detail: '等的时间有点长，可以稍后再试，或者到设置里换一个源。', raw };
  }
  if (/为空|缺少|no data|invalid|格式|parse|json/.test(m)) {
    return { icon: 'gone', title: '源站返回了看不懂的数据', detail: '多半是源站接口变了或这条内容是空的，可以重试或换个源。', raw };
  }
  return { icon: 'alert', title: '加载失败', detail: '出了点小问题，重试一次；总不行就到设置里换个源。', raw };
}

const ERROR_ICONS = {
  network: '<svg class="error-icon" viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="24" cy="24" r="21"/><path d="m12 24 6-5 6 5 6-5 6 5"/></svg>',
  gone: '<svg class="error-icon" viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="24" cy="24" r="21"/><circle cx="18" cy="21" r="1.2" fill="currentColor" stroke="none"/><circle cx="30" cy="21" r="1.2" fill="currentColor" stroke="none"/><path d="M17 31c2-2.4 4.4-3.6 7-3.6s5 1.2 7 3.6"/></svg>',
  slow: '<svg class="error-icon" viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="24" cy="24" r="21"/><path d="M24 13v11l7 5"/></svg>',
  alert: '<svg class="error-icon" viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="24" cy="24" r="21"/><path d="M24 14v13"/><circle cx="24" cy="33" r="1.2" fill="currentColor" stroke="none"/></svg>'
};

// 真的会跳的自动重试。原错误页写着「正在自动重试…」其实什么都没重试 —— 用户干等。
// 只对安全页面（首页/分类/标签这类）补刀；详情和搜索有自己的重试节奏，不掺和。
const errorAutoRetry = { timer: 0 };
function renderError(error, retry) {
  if (state.route === 'detail') window.AndroidHost?.setDetailMode?.(false);
  // 详情页出错时**必须**留条退路。正常详情页顶部有个「返回」按钮，可出错时整屏
  // 被换成只有「重试」的错误页 —— 那个按钮跟着没了，用户就卡在一屏什么都做不了
  // 的界面上。最典型的触发：换了内容源之后点观看历史里上一个源的条目，那个 id
  // 在新源里查不到，详情必失败。
  const escape = retry === 'detail' ? '<button type="button" data-back="1">返回</button>' : '';
  clearTimeout(errorAutoRetry.timer);
  const isNetwork = NETWORK_ERROR_RE.test(error?.message || '');
  const routeAtRender = state.route;
  if (isNetwork) {
    content.innerHTML = `<div class="error-state error-state--network" role="alert">
      <div class="error-main">
        ${ERROR_ICONS.network}
        <h1>网络走丢了</h1>
      </div>
      <div class="error-footer"><p>几秒后会自动重试，也可以现在手动来。</p>${escape}<button type="button" data-retry="${esc(retry)}">重新尝试</button></div>
    </div>`;
    if (['home', 'category', 'listing', 'tags', 'tag-list', 'library'].includes(retry)) {
      errorAutoRetry.timer = setTimeout(() => {
        // 用户已经离开这一页、或错误页已被别的内容换掉，就不再补刀。
        if (state.route !== routeAtRender || !content.querySelector(`[data-retry="${retry}"]`)) return;
        performRetry(retry);
      }, 5000);
    }
    return;
  }
  const info = friendlyError(error);
  const hint = retry === 'detail' ? '<p>这条内容可能已下架，或当前源没有收录。</p>' : '';
  content.innerHTML = `<div class="error-state error-state--network" role="alert">
    <div class="error-main">
      ${ERROR_ICONS[info.icon]}
      <h1>${esc(info.title)}</h1>
    </div>
    <div class="error-footer">
      ${hint}<p>${esc(info.detail)}</p>
      ${info.raw ? `<p class="error-raw">${esc(info.raw)}</p>` : ''}
      ${escape}<button type="button" data-retry="${esc(retry)}">重试</button>
    </div>
  </div>`;
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

let viewEnterTimer;
function playViewEnter(route) {
  const app = document.getElementById('app');
  // 详情页的进场由 card-transition 自己接管，这里再叠一层会两个动画抢同一个元素。
  // 从详情返回时 state.previous 还在，卡片刚合上再淡入一次首页会像抖了两下。
  if (route === 'detail' || state.previous || (reducedMotionQuery && reducedMotionQuery.matches)) {
    app.classList.remove('view-enter');
    return;
  }
  // 只有上一段动画还在（快速连按）时才需要强制一次布局让移除生效；
  // 平常这条路径不读 offsetWidth，省掉导航时的一次额外布局。
  const restarting = app.classList.contains('view-enter');
  app.classList.remove('view-enter');
  if (restarting) void app.offsetWidth;
  app.classList.add('view-enter');
  clearTimeout(viewEnterTimer);
  viewEnterTimer = setTimeout(() => app.classList.remove('view-enter'), 560);
}

function setRoute(route) {
  stopBannerAuto();
  const changed = state.route !== route;
  if (state.route === 'detail' && route !== 'detail') { detailCard?.dispose(); detailCard = null; }
  const libraryArea = ['library', 'settings', 'account-management'].includes(route);
  if (state.route === 'library' && route !== 'library') searchInput.value = '';
  state.route = route;
  const app = document.getElementById('app');
  app.dataset.route = route;
  app.classList.toggle('route-detail', route === 'detail');
  window.AndroidHost?.setDetailMode?.(route === 'detail');
  headerSettings.hidden = route !== 'library';
  searchInput.placeholder = libraryArea ? '搜索观看历史和收藏' : privateMode() ? '搜索剧名或 #标签' : '搜索剧名';
  searchInput.setAttribute('aria-label', libraryArea ? '搜索观看历史和收藏' : '搜索剧名或标签');
  if (route === 'library') searchInput.value = state.libraryQuery;
  updateBackEnabled();
  if (changed) playViewEnter(route);
}

function isRootColumn() { return ['home', 'category', 'library'].includes(state.route); }

function backDestination() {
  if (isRootColumn()) return null;
  if (state.route === 'campaign') return state.campaignReturn?.route || 'home';
  if (state.route === 'detail') return state.previous?.route || 'home';
  if (state.route === 'account-management') return state.accountReturn?.route || 'settings';
  if (state.route === 'proxy-settings') return state.proxyReturn?.route || 'settings';
  if (state.route === 'source-settings') return state.sourceReturn?.route || 'settings';
  if (state.route === 'market') return state.marketReturn?.route || 'settings';
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
// 详情请求的统一入口：同 id 在飞请求直接复用，成功后**无条件**落缓存 ——
// 原来写缓存在「用户还停在详情页」的判断里，等不及退出来，这次抓取就白跑了，
// 再进去还是从零等。预取（pointerdown）和 goDetail 都走这里。
const detailInflight = new Map();
function fetchDetail(id) {
  const key = String(id);
  if (!detailInflight.has(key)) {
    const promise = api('detail', { id }).then(fresh => {
      writeCache(`detail:${key}`, fresh);
      detailInflight.delete(key);
      return fresh;
    }).catch(error => { detailInflight.delete(key); throw error; });
    detailInflight.set(key, promise);
  }
  return detailInflight.get(key);
}

async function goDetail(id, trigger) {
  if (state.route === 'detail' && state.sourceItem?.id === id) {
    // Retry in place; preserve the original source card and return destination.
    state.detailLoading = true; renderDetail();
    const token = ++routeToken;
    try { const fresh = await fetchDetail(id); if (token === routeToken) { state.detail = fresh; state.detailLoading = false; renderDetail(); } }
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
  const refreshing = fetchDetail(id);
  const cached = readCache(`detail:${id}`);
  if (token !== routeToken) return;
  let hasDetailData = Boolean(cached?.data);
  if (hasDetailData) { state.detail = cached.data; state.detailLoading = false; renderDetail(); }
  api('bookmark-state', { id }).then(saved => { if (token === routeToken) { state.bookmarked = saved; if (state.detail) renderDetail(); } }).catch(() => {});
  try {
    const fresh = await refreshing;
    if (token === routeToken) { state.detail = fresh; state.detailLoading = false; renderDetail(); }
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
  // 从内置播放器（VlcPlayerActivity）回来：把那一场的位置收下、写回进度。
  // 放在这儿而不是只听 visibilitychange：WebView 被 Activity 盖住时
  // document.hidden 经常一直是 false，那条事件根本不发。
  feedPlayer.resume?.();
  // 播放全程在原生里，页面这边没有 feed 页可回 —— 退出播放器后落回详情页。
  if (state.route === 'feed' && !document.querySelector('.feed')) {
    const back = state.feedReturnId || state.detail?.id;
    if (back) goDetail(back); else goHome();
    return;
  }
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
  // 亮度走原生（CSS 蒙黑只能变暗），清晰度要取 m3u8 文本（页面 fetch 不了）——
  // 两件都要过桥，包一层在这里给播放器用。
  playerCommand,
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
    const aliasInput = document.getElementById('source-alias');
    const error = document.getElementById('source-error');
    const button = event.target.querySelector('button[type="submit"]');
    if (!input || !button) return;
    const url = input.value.trim();
    const alias = String(aliasInput?.value || '').trim();
    button.disabled = true;
    button.textContent = '正在拉取…';
    if (error) error.hidden = true;
    try {
      // 新填进来的地址必然没有本地缓存，这一次一定要等网络（也就这一次）。
      await sourceCommand('use?url=' + encodeURIComponent(url));
      // 备注单独一条指令写：用户可以随时改，且地址本身不认识它。
      // 写失败不该拦住切换 —— 备注只是给人看的。
      if (alias) await sourceCommand('note?url=' + encodeURIComponent(url) + '&value=' + encodeURIComponent(alias)).catch(() => {});
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
  if (event.target.id === 'market-form') {
    event.preventDefault();
    const input = document.getElementById('market-value');
    const error = document.getElementById('market-error');
    const button = event.target.querySelector('button[type="submit"]');
    const url = String(input?.value || '').trim();
    if (!/^https:\/\//.test(url)) {
      if (error) { error.textContent = '地址必须以 https:// 开头'; error.hidden = false; }
      return;
    }
    button.disabled = true; button.textContent = '正在获取…';
    setMarketSource(url);
    state.market = { loading: true };
    renderMarket();
    loadMarket(true).then(cache => {
      state.market = { loading: false, items: cache.items };
      if (state.route === 'market') renderMarket();
    }).catch(failure => {
      state.market = { loading: false, error: failure.message || '获取失败' };
      if (state.route === 'market') renderMarket();
    });
    return;
  }
  if (event.target.id === 'tvbox-form') {
    event.preventDefault();
    const url = String(document.getElementById('tvbox-value')?.value || '').trim();
    if (!/^https:\/\//.test(url)) { state.tvbox = { url, loading: false, items: [], error: '地址必须以 https:// 开头' }; renderSourceSettings(); return; }
    importTvBox(url);
    return;
  }
  const noteForm = event.target.closest('[data-source-note-form]');
  if (noteForm) {
    event.preventDefault();
    await saveSourceNote(noteForm.dataset.sourceNoteForm, noteForm.querySelector('#source-note')?.value);
    return;
  }
  // 内置核心的节点配置。和上面 proxy-form 的区别：这条不仅要存，还要**启动**，
  // 而且失败时不改路由 —— Java 那边起不来就原样回来一句错，页面只显示不动别的。
  if (event.target.id === 'proxy-core-form') {
    event.preventDefault();
    const input = document.getElementById('proxy-link');
    const error = document.getElementById('proxy-core-form-error');
    const button = event.target.querySelector('button[type="submit"]');
    if (!input || !button) return;
    button.disabled = true;
    button.textContent = '正在启动…';
    if (error) error.hidden = true;
    try {
      const status = await proxyCoreCommand('start?link=' + encodeURIComponent(input.value.trim()));
      applyProxyCore(status);
      if (status.ok === false) throw new Error(status.error || '核心没能启动');
      toast(status.running ? '已连接 ' + (status.host || '') : '已保存，但核心没起来');
    } catch (problem) {
      if (error) { error.textContent = problem.message; error.hidden = false; }
    } finally {
      if (button.isConnected) { button.disabled = false; button.textContent = '保存并启动'; }
    }
    return;
  }
  // 订阅导入。拉取和测速分成两步：拉取几秒就回来，测速有大半分钟的可能，
  // 合起来会让用户点一次等到底还不知道卡在哪一步。
  if (event.target.id === 'proxy-sub-form') {
    event.preventDefault();
    const input = document.getElementById('proxy-sub-url');
    const statusLine = document.getElementById('proxy-sub-status');
    const button = event.target.querySelector('button[type="submit"]');
    if (!input || !button) return;
    const url = input.value.trim();
    if (!url) { toast('先填一个订阅地址', true); return; }
    button.disabled = true;
    button.textContent = '正在拉取…';
    const sub = subState();
    if (statusLine) { statusLine.hidden = false; statusLine.classList.remove('is-bad'); statusLine.textContent = '正在拉取订阅…'; }
    try {
      const result = await proxyCoreCommand('sub?url=' + encodeURIComponent(url));
      if (result.ok === false) throw new Error(result.error || '订阅没拉到');
      // 换了订阅，上一次的测速结果整套作废 —— 下标对不上了。
      state.proxySub = { url: result.url || url, count: result.count || 0,
                         summary: result.summary || '', speedNote: '正在测速，最多 20 秒…',
                         speed: [], testing: true, busy: true };
      renderProxySettingsKeepScroll();
      // 拉完直接测，用户不用再点一次 —— 「拉取」这个动作的意图就是要连上。
      runSpeedTest();
    } catch (problem) {
      if (statusLine) { statusLine.hidden = false; statusLine.classList.add('is-bad'); statusLine.textContent = problem.message; }
      button.disabled = false;
      button.textContent = '拉取节点';
    }
    return;
  }
  const subAction = event.target.closest('[data-proxy-sub]');
  if (subAction) {
    const action = subAction.dataset.proxySub;
    if (action === 'clear') {
      subAction.disabled = true;
      proxyCoreCommand('subclear').then(() => {
        state.proxySub = { url: '', count: 0, summary: '', speedNote: '', speed: [], testing: false, busy: false };
        renderProxySettingsKeepScroll();
        toast('已改回用内置的免费节点');
      }).catch(problem => { toast(problem.message, true); subAction.disabled = false; });
      return;
    }
    runSpeedTest();
    return;
  }
  const useNode = event.target.closest('[data-proxy-use]');
  if (useNode) {
    const index = useNode.dataset.proxyUse;
    useNode.disabled = true;
    try {
      const status = await proxyCoreCommand('use?index=' + encodeURIComponent(index));
      if (status.ok === false) throw new Error(status.error || '这个节点没连上');
      applyProxyCore(status);
      toast(status.running ? '已连接 ' + (status.host || '') : '已切换');
    } catch (problem) {
      toast(problem.message, true);
      useNode.disabled = false;
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
  // 必须限定 button：主题偏好在 <html> 上另有一个 data-theme-pref，
  // 但曾经它叫 data-theme-mode，结果 closest() 把每一次点击都匹配到 <html>，
  // 整个应用的点击全被主题切换吞掉。这里再挡一道。
  const themeChoice = event.target.closest('button[data-theme-mode]');
  if (themeChoice) {
    if (themeChoice.getAttribute('aria-checked') !== 'true') setThemeMode(themeChoice.dataset.themeMode);
    return;
  }
  if (event.target.closest('[data-hot-check]')) { checkHotUpdate(); return; }
  if (event.target.closest('[data-goto-app-update]')) {
    const area = document.getElementById('update-check-area');
    area?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    checkAppUpdate();
    return;
  }
  if (event.target.closest('[data-hot-apply]')) { applyHotUpdate(); return; }
  if (event.target.closest('[data-secret-entry]')) { handleVersionTap(); return; }
  if (event.target.closest('[data-account-management]')) { goAccountManagement(); return; }
  if (event.target.closest('[data-proxy-settings]')) { goProxySettings(); return; }
  if (event.target.closest('[data-player-settings]')) { goPlayerSettings(); return; }
  const playerSet = event.target.closest('[data-player-set]');
  if (playerSet) {
    const [key, value] = String(playerSet.dataset.playerSet || '').split(':');
    if (key === 'core') savePlayerPrefs({ core: value });
    else if (key === 'decode') savePlayerPrefs({ decode: value });
    else if (key === 'cache') savePlayerPrefs({ cache: Number(value) });
    else if (key === 'autoFallback') savePlayerPrefs({ autoFallback: value === 'on' });
    renderPlayerSettings();
    return;
  }
  const proxyModeButton = event.target.closest('[data-proxy-mode]');
  if (proxyModeButton) {
    const value = proxyModeButton.dataset.proxyMode;
    // 已经是这个模式就别再走一趟：切到 builtin 会真的去启核心，
    // 而用户点一下已选中的按钮只是想「确认」，不该触发一次启停。
    if (value === (proxyState().mode || 'direct')) return;
    proxyModeButton.disabled = true;
    proxyCoreCommand('mode?value=' + encodeURIComponent(value))
      .then(applyProxyCore)
      .catch(problem => toast(problem.message, true))
      .then(() => { if (state.route === 'proxy-settings') renderProxySettings(); });
    return;
  }
  const coreAction = event.target.closest('[data-proxy-core]');
  if (coreAction) {
    const action = coreAction.dataset.proxyCore;
    coreAction.disabled = true;
    if (action === 'stop') {
      proxyCoreCommand('stop')
        .then(applyProxyCore)
        .catch(problem => toast(problem.message, true));
      return;
    }
    // 测试连通性单独一条：它不重画整页（那样会把刚写进 status 的毫秒数擦掉），
    // 只把结果写到下面那行提示里。
    const label = document.getElementById('proxy-core-test');
    if (label) { label.hidden = false; label.classList.remove('is-bad'); label.textContent = '正在测…'; }
    proxyCoreCommand('test').then(result => {
      if (!label) return;
      label.hidden = false;
      if (result.ok && Number(result.ms) > 0) {
        label.classList.remove('is-bad');
        label.textContent = `通了 · ${result.ms}ms`;
      } else {
        label.classList.add('is-bad');
        label.textContent = result.error || '测不通';
      }
    }).catch(problem => {
      if (label) { label.hidden = false; label.classList.add('is-bad'); label.textContent = problem.message; }
    }).then(() => { coreAction.disabled = !proxyState().running; });
    return;
  }
  if (event.target.closest('[data-source-settings]')) { goSourceSettings(); return; }
  if (event.target.closest('[data-market]')) { goMarket(); return; }
  if (event.target.closest('[data-market-reload]')) { goMarket(); return; }
  if (event.target.closest('[data-market-change]')) { setMarketSource(''); state.market = null; renderMarket(); return; }
  const marketUse = event.target.closest('[data-market-use]');
  if (marketUse) {
    const url = marketUse.dataset.marketUse;
    marketUse.disabled = true; marketUse.textContent = '正在切换…';
    addSourceFromMarket(url).catch(error => { toast(error.message, true); marketUse.disabled = false; marketUse.textContent = '添加并使用'; });
    return;
  }
  const tvboxUse = event.target.closest('[data-tvbox-use]');
  if (tvboxUse) {
    const url = tvboxUse.dataset.tvboxUse;
    tvboxUse.disabled = true; tvboxUse.textContent = '正在切换…';
    addSourceFromMarket(url).catch(error => { toast(error.message, true); tvboxUse.disabled = false; tvboxUse.textContent = '添加并使用'; });
    return;
  }
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
  if (event.target.closest('[data-source-refetch]')) {
    const refetchButton = event.target.closest('[data-source-refetch]');
    const refetchUrl = refetchButton.dataset.sourceRefetch;
    if (!refetchUrl || refetchButton.disabled) return;
    refetchButton.disabled = true;
    const status = document.getElementById('source-error');
    if (status) { status.hidden = false; status.textContent = '正在重新拉取…'; }
    try {
      // 两步，顺序不能反。
      //
      // refresh 是「把服务器上那份重新下下来，只写本地缓存，不动生效那份」——
      // 它本来就是为了后台悄悄更新缓存而写的，单独的它救不了「我现在就要用上」。
      // 跟着的 use 再从缓存取一遍：刚写进去的正好是新的，于是这次切源拿到的
      // 就是新配置，顺带把重启也带了。
      //
      // 想一步到位得给 Java 加个新动作，那要同时改 MainActivity 的 packed-switch
      // 两张表 —— 本工程最容易出事的地方，为这点事不值得。
      await sourceCommand('refresh?url=' + encodeURIComponent(refetchUrl));
      if (status) status.textContent = '已拉回来，正在应用…';
      const result = await sourceCommand('use?url=' + encodeURIComponent(refetchUrl));
      const spent = Number(result && result.ms) || 0;
      // use 走了缓存分支（cached=true）是**正常的**：新配置刚被 refresh 写进缓存，
      // 这里就是要那份。所以不按「本地缓存/刚拉了」措辞，直接说清楚做过什么。
      if (status) status.textContent = `已重新拉取并应用（${spent}ms），正在重启…`;
      restartAfterSource(() => {
        if (status) status.textContent = '已重新拉取，但没能自动重开，请退出后重新打开';
        refetchButton.disabled = false;
      });
    } catch (problem) {
      if (status) status.textContent = problem.message;
      refetchButton.disabled = false;
    }
    return;
  }
  const noteEdit = event.target.closest('[data-source-note]');
  if (noteEdit) {
    state.sourceNote = noteEdit.dataset.sourceNote;
    renderSourceSettings();
    content.querySelector('#source-note')?.focus();
    return;
  }
  if (event.target.closest('[data-source-note-cancel]')) {
    state.sourceNote = '';
    renderSourceSettings();
    return;
  }
  const noteClear = event.target.closest('[data-source-note-clear]');
  if (noteClear) {
    if (noteClear.disabled) return;
    await saveSourceNote(noteClear.dataset.sourceNoteClear, '');
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
      const result = await sourceCommand('use?url=' + encodeURIComponent(pickUrl));
      // 把「这次是吃缓存还是等网络」说出来。换源快不快是用户能直接感觉到的，
      // 而这两个数字（缓存命中 / 拉取耗时）是唯一能解释它的东西。
      const spent = Number(result && result.ms) || 0;
      const how = result && result.cached ? `本地缓存 ${spent}ms` : `刚从服务器拉了 ${spent}ms`;
      if (status) status.textContent = `已切换（${how}），正在重启…`;
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
    const episode = Math.max(1, Number(historyCard.dataset.historyEpisode) || 1);
    const wantsPlay = historyCard.dataset.historyCompleted !== 'true';
    const source = contentSource;
    const token = routeToken + 1;
    // 和 resumeFromHome 走同一条路：**先确认详情真的加载出来了**再决定要不要进
    // 播放器。goDetail 内部把错误 catch 掉了、照样 resolve，所以原来那句
    // `goDetail(id).then(() => feedFrom(...))` 在详情失败时也会进播放器 ——
    // 播放器拿不到地址，又盖在错误页上，两个错误叠一起还退不出来。
    Promise.resolve(wantsPlay ? goDetail(id) : goDetail(id, historyCard)).then(() => {
      if (!wantsPlay) return;
      if (routeToken !== token || contentSource !== source || state.route !== 'detail'
          || state.detailLoading || String(state.detail?.id) !== id) return;
      if (episode > asArray(state.detail.episodes).length) {
        toast('这一集在当前内容源里没有，可能在别的源里', true);
        return;
      }
      feedFrom(id, episode);
    }).catch(error => toast(error.message || '打开失败，请重试', true));
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
  if (retry) { clearTimeout(errorAutoRetry.timer); performRetry(retry.dataset.retry); return; }
  if (event.target.closest('[data-check-update]')) { checkAppUpdate(); return; }
  if (event.target.closest('[data-download-update]')) { downloadAppUpdate(); return; }
  const reset = event.target.closest('[data-factory-reset]');
  if (reset) {
    if (!reset.disabled) runFactoryReset(reset);
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
// 预取：手指按下的瞬间就发详情请求。click 要等抬起、等合成，这一个提前量
// 通常有一两百毫秒；滑列表时顺手按到的卡片，详情页点进去就是秒开。
// 已有缓存或在飞的请求直接跳过，不浪费流量。
content.addEventListener('pointerdown', event => {
  const id = event.target.closest('[data-open-id]')?.dataset.openId
    ?? event.target.closest('[data-history-id]')?.dataset.historyId;
  if (!id || readCache(`detail:${id}`) || detailInflight.has(String(id))) return;
  fetchDetail(id).catch(() => {});
}, { capture: true, passive: true });
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
//
// 代理核心**不能挂在闸门后面**：HGSetup.ensure() 在没配源的机器上永远不 resolve
// （它故意吊着等用户在设置页填源），于是「内置核心」模式下的用户在填源之前，
// 重新打开 App 会发现昨晚开的代理没了 —— 而代理跟内容源毫无关系。
// 所以按盘上模式恢复核心这一步单独挂在 contentReady 上，和闸门并行。
contentReady.then(() => bootProxyCore());
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

// 热更新的状态、检查和切换入口统一放在文件上方的「前端热更新」块里
// （window.hgCanApplyHotUpdate 也是在那里定义的）。这里不再挂恒 false 的
// 旧实现，否则会把真正可用的更新入口覆盖掉。


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
