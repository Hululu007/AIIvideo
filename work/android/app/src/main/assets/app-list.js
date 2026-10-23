/**
 * 底部导航（dock）、首页启动与贴片尺寸。
 *
 * <p>2026-10-23 从 app.js 拆出。app.js 4280 行、236 个顶层声明，其中 2435 行没有
 * 一条分节线，改一处得全文搜。这里只搬**顶层函数声明** —— 它们靠提升生效，
 * 搬走不改变任何加载期行为；顶层 `const` 与立即执行的语句仍留在 app.js。
 *
 * <p>classic script，不是模块：函数仍落在同一个全局作用域里，调用点一行没动。
 * app.html 里这七块排在 app.js 之前。
 */
'use strict';

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

function startHome() {
  goHome();
  setTimeout(() => { if (typeof autoCheckUpdate === 'function') autoCheckUpdate(); }, 4000);
}

function closeUnlock() {
  unlockOverlay.hidden = true;
  updateBackEnabled();
}

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
