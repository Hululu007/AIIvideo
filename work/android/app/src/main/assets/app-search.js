/**
 * 搜索历史与搜索页：历史读写、面板渲染、搜索页外壳与加载态。
 *
 * <p>2026-10-23 从 app.js 拆出。app.js 4280 行、236 个顶层声明，其中 2435 行没有
 * 一条分节线，改一处得全文搜。这里只搬**顶层函数声明** —— 它们靠提升生效，
 * 搬走不改变任何加载期行为；顶层 `const` 与立即执行的语句仍留在 app.js。
 *
 * <p>classic script，不是模块：函数仍落在同一个全局作用域里，调用点一行没动。
 * app.html 里这七块排在 app.js 之前。
 */
'use strict';

function readSearchHistory() {
  try {
    const parsed = JSON.parse(localStorage.getItem(SEARCH_HISTORY_KEY) || '[]');
    return Array.isArray(parsed) ? parsed.filter(word => typeof word === 'string' && word) : [];
  } catch (error) { return []; }
}

function paintSearchHistory() {
  const words = readSearchHistory();
  // 用 data-* 带回原词，不放 textContent 里 —— 词里可能有引号或 & ，esc 转义之后
  // 读回来还是原文。按钮的可见文字也走 esc，长词由 CSS 截断加省略号。
  searchHistoryTags.innerHTML = words.map(word => `<button type="button" data-history-word="${esc(word)}">${esc(word)}</button>`).join('');
  return words.length;
}

function alignSearchHistory() {
  const rect = searchForm.getBoundingClientRect();
  searchHistoryPanel.style.left = rect.left + 'px';
  searchHistoryPanel.style.width = rect.width + 'px';
  searchHistoryPanel.style.right = 'auto';   // 有 width 了，right 得让位
  searchHistoryPanel.style.top = (rect.bottom + 8) + 'px';
}

function showSearchHistory() {
  if (LIBRARY_ROUTES.includes(state.route)) return;
  if (searchInput.value.trim()) return;
  if (!paintSearchHistory()) { searchHistoryPanel.hidden = true; return; }
  alignSearchHistory();
  searchHistoryPanel.hidden = false;
}

function hideSearchHistory() {
  if (!searchHistoryPanel.hidden) searchHistoryPanel.hidden = true;
}

function refreshSearchHistory() {
  if (searchHistoryPanel.hidden) return;
  if (!paintSearchHistory()) hideSearchHistory();
}

function recordSearchHistory(query) {
  const word = String(query || '').trim();
  if (!word) return;
  const rest = readSearchHistory().filter(one => one.toLowerCase() !== word.toLowerCase());
  rest.unshift(word);
  try { localStorage.setItem(SEARCH_HISTORY_KEY, JSON.stringify(rest.slice(0, SEARCH_HISTORY_LIMIT))); } catch (error) {}
  refreshSearchHistory();
}

function syncSearchHistory() {
  if (searchInput.value.trim()) { hideSearchHistory(); return; }
  if (document.activeElement === searchInput) showSearchHistory();
}

function renderSearch() {
  // 搜索结果就是搜索结果，**不带分类胶囊**。
  //
  // 这里原来有一排写死的过滤条：黄果源那套「成人短剧 / 成人漫剧 / AI换脸 / AI魔改」，
  // 别的源换成「短剧 / 漫剧」。两个问题：
  //
  //   ① 它跟搜索词毫无关系。搜「爱」也照样挂在标题下面，看起来像「你搜的结果被
  //      这几类分好了」。
  //   ② 那个过滤是拿标题猜的 —— `/AI\s*换脸/.test(标题)`、`/漫剧/.test(标题)`。
  //      猜错的结果是「少了几个结果」，不报错；用户只会觉得「搜索不全」。
  //      而用户自带的 spider 源每家分类都不一样，写死一份必然对不上。
  //
  // 要按分类找东西有「全部类型」，那里列的是源自己的分类。搜索只负责搜索。
  const items = asArray(state.search?.items);
  content.innerHTML = `<div class="search-page"><h1>搜索结果</h1><p>${esc(state.query)} · ${items.length} 部作品</p>${searchFeedback()}
    ${items.length ? `<div class="search-grid">${items.map(itemCard).join('')}</div>` : '<div class="empty-state">没有找到匹配的影片</div>'}</div>`;
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
