/**
 * 详情拉取与播放器衔接，外加分类页的翻页。
 *
 * <p>2026-10-23 从 app.js 拆出。app.js 4280 行、236 个顶层声明，其中 2435 行没有
 * 一条分节线，改一处得全文搜。这里只搬**顶层函数声明** —— 它们靠提升生效，
 * 搬走不改变任何加载期行为；顶层 `const` 与立即执行的语句仍留在 app.js。
 *
 * <p>classic script，不是模块：函数仍落在同一个全局作用域里，调用点一行没动。
 * app.html 里这七块排在 app.js 之前。
 */
'use strict';

function feedCleanup() { feedPlayer.cleanup(); }

function feedClose() { return feedPlayer.close(); }

function feedFrom(videoId, episode) { feedPlayer.open(videoId, episode); }

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
