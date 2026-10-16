'use strict';

(function () {
  const SPEEDS = [0.75, 1, 1.25, 1.5, 2];
  const STORE_KEY = 'hgpref:progress';
  const URL_KEY = 'hgpref:epurl';
  const SPEED_KEY = 'hgpref:feedrate';
  const ORIENTATION_KEY = 'hgpref:feedorientation';
  const FLIP_KEY = 'hgpref:feedflip';
  const URL_TTL = 8 * 60 * 1000;
  const HOLD_MS = 280;
  const PRELOAD_DISTANCE = 1;
  const KEEP_DISTANCE = 3;
  const SEEK_SPAN = 120;   // 横向划满一屏 = 120 秒
  const SEEK_SLOP = 14;    // 横向死区（px），小于它不认成拖进度
  const SEEK_BIAS = 1.2;   // 横向位移要超过纵向的倍数，否则算纵向滑动（切集）

  function createFeedPlayer(deps) {
    let observer = null;
    // 当前可见那一集。相邻集会被预热/回收，它们同样会触发 play/pause 事件，
    // 所以判断「要不要收起外壳」必须只认这一集，不能看任意一集的 paused。
    let activePage = null;

    const {
      state, content, api, asArray, esc, icon, toast, readCache,
      bridgeCall, renderDetail, goHome, updateBackEnabled
    } = deps;

    function speedLabel(rate) {
      const text = String(Number(rate).toFixed(2)).replace(/\.00$/, '').replace(/0$/, '');
      return text + '×';
    }

    function selectedRate() {
      try {
        const rate = Number(localStorage.getItem(SPEED_KEY));
        return SPEEDS.includes(rate) ? rate : 1;
      } catch (error) { return 1; }
    }

    function selectedOrientation() {
      try { return localStorage.getItem(ORIENTATION_KEY) === 'landscape' ? 'landscape' : 'portrait'; }
      catch (error) { return 'portrait'; }
    }

    function rememberRate(rate) {
      try { localStorage.setItem(SPEED_KEY, String(rate)); } catch (error) {}
    }

    function rememberOrientation(mode) {
      try { localStorage.setItem(ORIENTATION_KEY, mode); } catch (error) {}
    }

    function updateRateUi(feed, rate = selectedRate()) {
      feed.querySelector('.feed-rate-toggle')?.replaceChildren(document.createTextNode(speedLabel(rate)));
      feed.querySelectorAll('.feed-rate-option').forEach(button => {
        const active = Number(button.dataset.rate) === rate;
        button.classList.toggle('active', active);
        button.setAttribute('aria-pressed', active ? 'true' : 'false');
      });
    }

    function applyRate(feed, rate = selectedRate()) {
      updateRateUi(feed, rate);
      feed.querySelectorAll('.feed-page video').forEach(video => {
        if (video.dataset.tempSpeed === '1') return;
        try { video.playbackRate = rate; } catch (error) {}
      });
    }

    // 播放中收起的播放器外壳：进度条 / 集数 / 时间 / 倍速·横屏按钮。
    // 返回键不在此列——它是导航，不占视野，留着省一次点击。
    // 收起时顺手把倍速面板关掉，否则下次暂停会看到上次留着展开的面板。
    function setChrome(feed, playing) {
      if (!feed) return;
      feed.classList.toggle('is-playing', playing);
      if (playing) feed.querySelector('.feed-rate-panel')?.classList.add('hidden');
    }

    function isLandscape(feed) {
      return feed.dataset.orientation === 'landscape';
    }

    // 滚动容器始终是竖向的（横屏也只转「内容舞台」，不转滚动容器本身），
    // 所以取当前集只看 scrollTop。这一点很关键：一旦把滚动容器旋转，
    // 触摸滚动方向就会和手指在屏幕上的实际滑动对不上，切集直接失效。
    function currentPage(feed) {
      const pages = [...feed.querySelectorAll('.feed-page')];
      if (!pages.length) return null;
      const center = feed.scrollTop + feed.clientHeight / 2;
      return pages.reduce((best, page) => Math.abs(page.offsetTop + page.offsetHeight / 2 - center) < Math.abs(best.offsetTop + best.offsetHeight / 2 - center) ? page : best, pages[0]);
    }

    function scrollToPage(feed, page, behavior = 'auto') {
      if (!feed || !page) return;
      feed.scrollTo({ top: page.offsetTop, left: 0, behavior });
    }

    // 横屏走「旋转舞台」而不是 Fullscreen API。
    // 原因：AndroidManifest 里 MainActivity 是 screenOrientation="portrait"，
    // 且 MainActivity$2 这个 WebChromeClient 没有实现 onShowCustomView，
    // 所以 requestFullscreen() 与 screen.orientation.lock() 在这个包里都不会生效。
    // 做法：只把每集的 .feed-stage 旋转 90°，用户把手机横过来就是满屏正片。
    // 滚动容器与页面高度都不变，因此竖滑切集、滚动吸附、可见性判定全部保持原样。
    function applyOrientation(feed, mode = selectedOrientation(), persist = false) {
      feed.dataset.orientation = mode;
      const button = feed.querySelector('.feed-orientation-toggle');
      if (button) {
        button.textContent = mode === 'landscape' ? '竖屏' : '横屏';
        button.setAttribute('aria-pressed', mode === 'landscape' ? 'true' : 'false');
      }
      if (persist) rememberOrientation(mode);
      const page = currentPage(feed);
      requestAnimationFrame(() => scrollToPage(feed, page));
    }

    // 手机从另一边横过来时（充电口朝左还是朝右），旋转方向要反过来，否则画面倒着。
    // 不用 deviceorientation 自动判断：WebView 里传感器事件不一定可用，且会抖动，
    // 手动一个「翻转」按钮更可靠，也更好验证。
    function applyFlip(feed, flip) {
      feed.dataset.flip = flip ? '1' : '0';
      try { localStorage.setItem(FLIP_KEY, flip ? '1' : '0'); } catch (error) {}
      const page = currentPage(feed);
      requestAnimationFrame(() => scrollToPage(feed, page));
    }

    function savedFlip() {
      try { return localStorage.getItem(FLIP_KEY) === '1'; } catch (error) { return false; }
    }

    function urlRecall(videoId, ep) {
      try {
        const hit = JSON.parse(localStorage.getItem(URL_KEY) || '{}')[videoId + ':' + ep];
        if (!hit || !hit.url) return '';
        if (Date.now() - (hit.at || 0) > URL_TTL) return '';
        return hit.url;
      } catch (error) { return ''; }
    }

    function urlRemember(videoId, ep, url) {
      if (!url) return;
      try {
        const all = JSON.parse(localStorage.getItem(URL_KEY) || '{}');
        all[videoId + ':' + ep] = { url, at: Date.now() };
        const keys = Object.keys(all);
        if (keys.length > 80) {
          keys.sort((a, b) => (all[a].at || 0) - (all[b].at || 0))
            .slice(0, keys.length - 80)
            .forEach(key => { delete all[key]; });
        }
        localStorage.setItem(URL_KEY, JSON.stringify(all));
      } catch (error) {}
    }

    function urlForget(videoId, ep) {
      try {
        const all = JSON.parse(localStorage.getItem(URL_KEY) || '{}');
        delete all[videoId + ':' + ep];
        localStorage.setItem(URL_KEY, JSON.stringify(all));
      } catch (error) {}
    }

    function storeAll() {
      try { return JSON.parse(localStorage.getItem(STORE_KEY) || '{}'); } catch (error) { return {}; }
    }

    function remember(videoId, ep, time, dur) {
      if (!videoId || !dur || dur < 30) return;
      try {
        const all = storeAll();
        all[videoId] = { ep, time, dur, at: Date.now() };
        localStorage.setItem(STORE_KEY, JSON.stringify(all));
      } catch (error) {}
    }

    // 自动同步的防抖定时器。
    //
    // 原生侧的 sync() 只在**登录时**和**点「立即同步片单」**时才会跑
    // （见 app.js 的 data-sync-now）。于是「看完一集直接退出」的进度永远不会
    // 传上去 —— 服务器上那份还是几天前的。这里在每次进度上报之后约 20 秒
    // 触发一次同步；连续上报会不断把它往后推，所以一次连续观看只同步一次。
    let syncTimer = null;
    function scheduleSync() {
      if (!state.account || !state.account.loggedIn) return;   // 没登录就没什么可同步的
      if (syncTimer) clearTimeout(syncTimer);
      syncTimer = setTimeout(() => {
        syncTimer = null;
        api('sync').catch(() => {});      // 同步失败不打扰用户，下次还会再来
      }, 20000);
    }

    // 进度上报的节流表：videoId → 上次送出去的秒数。
    // 不需要持久化 —— 它的作用只是「同一条进度别重复送十几次」。
    const reported = new Map();
    const REPORT_GAP = 10;

    /**
     * 把进度送到原生侧。
     *
     * remember() 那份 localStorage 记忆只够「这台设备上次看到哪」；送到
     * AppStore 之后才进得了「我的 → 观看历史」，登录了还会同步到账号后端。
     * 之前只写了 localStorage，「观看历史」永远是空的。
     *
     * 走 detail 那条桥上的 __progress: 指令（和 __source: 一个套路）——
     * MainActivity 的 dispatch 是 packed-switch 按编号跳的，加动作要同时改两张表。
     */
    function report(page, time, dur, force) {
      if (!bridgeCall || !page) return;
      const videoId = String(page.dataset.videoId || '');
      // 原生那边有 matches("\\d+") 这一关，非数字的 id 送过去是白跑一趟。
      if (!/^\d+$/.test(videoId)) return;
      if (!dur || !isFinite(dur) || dur < 30) return;

      const seconds = Math.floor(time || 0);
      const last = reported.get(videoId);
      // 拖进度条往回/往前大跳时也要送一次，不然「继续观看」会停在旧位置。
      if (!force && last != null && Math.abs(seconds - last) < REPORT_GAP) return;
      reported.set(videoId, seconds);

      // ⚠️ **单位是毫秒，不是秒。**
      // AppStore 的 library() 里有这么一段：
      //     if (!completed && position <= 5000) 跳过这条
      // 判的是「看了不到 5 秒的不算历史」。送秒进去的话，看了 456 秒会变成
      // 456「毫秒」，正好落在闸门下面 —— 记录写进去了（get-progress 读得回来），
      // 但「观看历史」里一条都不显示。界面上算「已看 N 分钟」用的也是
      // position / 60000，单位是毫秒这件事两边一致。
      const payload = JSON.stringify({
        id: videoId,
        episode: Number(page.dataset.ep) || 1,
        position: Math.floor((time || 0) * 1000),
        duration: Math.floor(dur * 1000),
        title: page.dataset.title || '',
        cover: page.dataset.cover || ''
      });
      bridgeCall('detail', { id: '__progress:set?payload=' + encodeURIComponent(payload) })
        .catch(() => {});
      scheduleSync();
    }

    function recall(videoId) {
      const entry = storeAll()[videoId];
      if (!entry) return null;
      if (Date.now() - (entry.at || 0) > 30 * 864e5) return null;
      return entry;
    }

    function fmtClock(sec) {
      sec = Math.max(0, Math.floor(Number(sec) || 0));
      const m = Math.floor(sec / 60);
      return m + ':' + String(sec % 60).padStart(2, '0');
    }

    function cleanup() {
      observer?.disconnect();
      observer = null;
      activePage = null;
      const feed = document.querySelector('.feed');
      if (feed) {
        feed.querySelectorAll('.feed-page video').forEach(video => {
          try { video.pause(); } catch (error) {}
          video.removeAttribute('src');
          try { video.load(); } catch (error) {}
        });
        feed.remove();
      }
    }

    function close() {
      if (!document.querySelector('.feed')) return false;
      cleanup();
      state.route = 'detail';
      if (state.detail) renderDetail(); else goHome();
      updateBackEnabled();
      return true;
    }

    function open(videoId, episode) {
      const id = videoId ?? state.detail?.id ?? state.sourceItem?.id;
      if (id == null) { toast('缺少剧集信息', true); return; }
      const memory = recall(String(id));
      const explicit = episode != null && Number(episode) > 0;
      const ep = explicit ? Number(episode) : (memory ? memory.ep : 1);
      render(String(id), Math.max(1, ep)).catch(error => toast(error.message || '播放失败，请重试', true));
    }

    function bindVideo(page) {
      const video = page.querySelector('video');
      if (!video || video.dataset.bound === '1') return;
      video.dataset.bound = '1';
      const fill = page.querySelector('.feed-bar-fill');
      const cur = page.querySelector('.feed-time-cur');
      const tot = page.querySelector('.feed-time-total');

      video.addEventListener('timeupdate', () => {
        const dur = video.duration;
        if (!dur || !isFinite(dur)) return;
        // 拖进度期间由手势接管进度条与当前时间，别让 timeupdate 把它覆盖回播放头的位置。
        const previewing = page.classList.contains('is-seek');
        if (fill && !previewing && !page.querySelector('.feed-bar')?.classList.contains('dragging')) {
          fill.style.width = ((video.currentTime / dur) * 100).toFixed(2) + '%';
        }
        if (cur && !previewing) cur.textContent = fmtClock(video.currentTime);
        if (tot) tot.textContent = fmtClock(dur);
        const tick = Math.floor(video.currentTime);
        if (tick % 5 === 0 && tick !== Number(video.dataset.lastSave)) {
          video.dataset.lastSave = String(tick);
          remember(page.dataset.videoId, Number(page.dataset.ep), video.currentTime, dur);
          report(page, video.currentTime, dur);
        }
      });
      video.addEventListener('ended', () => {
        remember(page.dataset.videoId, Number(page.dataset.ep), video.duration, video.duration);
        report(page, video.duration, video.duration, true);
        const next = page.nextElementSibling;
        if (next?.classList.contains('feed-page')) scrollToPage(page.parentElement, next, 'smooth');
        else toast('已经是最后一集了');
      });
      video.addEventListener('waiting', () => { if (!video.paused) page.querySelector('.feed-spinner')?.classList.remove('hidden'); });
      video.addEventListener('playing', () => page.querySelector('.feed-spinner')?.classList.add('hidden'));
      video.addEventListener('canplay', () => page.querySelector('.feed-spinner')?.classList.add('hidden'));
      // 起播即收起外壳、暂停即展开。用户点屏幕暂停/继续走的也是这两个事件。
      video.addEventListener('pause', () => {
        page.classList.add('is-paused');
        if (page === activePage) setChrome(page.closest('.feed'), false);
      });
      video.addEventListener('play', () => {
        page.classList.remove('is-paused');
        if (page === activePage) setChrome(page.closest('.feed'), true);
      });
      video.addEventListener('error', () => {
        if (video.dataset.ready !== '1') return;
        urlForget(page.dataset.videoId, Number(page.dataset.ep));
        video.dataset.ready = '';
        video.dataset.bound = '';
      });
    }

    function bindBar(page) {
      const bar = page.querySelector('.feed-bar');
      if (!bar || bar.dataset.bound === '1') return;
      bar.dataset.bound = '1';
      // 横屏时舞台被旋转 90°，进度条在屏幕上变成一条竖线，所以比例要按纵向算。
      // 由前面的映射反解：
      //   竖屏        local x = clientX - rect.left
      //   横屏非翻转  local x = clientY - rect.top        （视口 Y 就是局部 X）
      //   横屏翻转    local x = rect.bottom - clientY
      const ratioAt = point => {
        const feed = bar.closest('.feed');
        const rect = bar.getBoundingClientRect();
        const landscape = feed?.dataset.orientation === 'landscape';
        const flip = feed?.dataset.flip === '1';
        const span = landscape ? rect.height : rect.width;
        const offset = landscape
          ? (flip ? rect.bottom - point.clientY : point.clientY - rect.top)
          : point.clientX - rect.left;
        return Math.max(0, Math.min(1, offset / Math.max(1, span)));
      };
      bar.addEventListener('pointerdown', event => {
        event.stopPropagation();
        const video = page.querySelector('video');
        if (!video || !video.duration) return;
        const fill = page.querySelector('.feed-bar-fill');
        const move = ev => { if (fill) fill.style.width = (ratioAt(ev) * 100).toFixed(2) + '%'; };
        const up = ev => {
          const r = ratioAt(ev);
          try { video.currentTime = r * video.duration; } catch (error) {}
          window.removeEventListener('pointermove', move);
          window.removeEventListener('pointerup', up);
          window.removeEventListener('pointercancel', up);
          bar.classList.remove('dragging');
        };
        bar.classList.add('dragging');
        move(event);
        window.addEventListener('pointermove', move);
        window.addEventListener('pointerup', up);
        window.addEventListener('pointercancel', up);
      });
    }

    async function attach(page) {
      const video = page.querySelector('video');
      if (!video || video.dataset.ready === '1') return video;
      const ep = Number(page.dataset.ep);
      let url = urlRecall(page.dataset.videoId, ep);
      let cover = '';
      if (!url) {
        const info = await api('episode', { id: page.dataset.videoId, episode: ep });
        url = info.source || info.url;
        cover = info.cover || '';
        if (url) urlRemember(page.dataset.videoId, ep, url);
      }
      if (!url) throw new Error('未取得播放地址');
      video.dataset.ready = '1';
      const poster = cover || page.dataset.cover;
      if (poster && !video.poster) video.poster = poster;
      // 只走 WebView 原生 HLS：媒体请求不带 Origin，CDN 才给正片。
      // hls.js 那条路已删（它的 XHR 必带 Origin，CDN 见之返回 54 秒宣传片），
      // 连带把 CSP 的 connect-src / blob: 一起收回，见 build 说明。
      video.src = url;
      video.load();
      bindVideo(page);
      bindBar(page);
      applyRate(page.closest('.feed') || document, selectedRate());

      const episodeNo = Number(page.dataset.ep);
      const memory = recall(page.dataset.videoId);
      if (memory && memory.ep === episodeNo && memory.time > 5 && (!memory.dur || memory.time < memory.dur - 15)) {
        let tries = 0;
        const timer = setInterval(() => {
          if (video.duration && isFinite(video.duration)) {
            if (memory.time < video.duration - 5) { try { video.currentTime = memory.time; } catch (error) {} }
            clearInterval(timer);
          } else if (++tries > 30) clearInterval(timer);
        }, 400);
      }
      return video;
    }

    async function warm(page) {
      if (!page || page.querySelector('video')?.dataset.ready === '1') return;
      try { await attach(page); } catch (error) {}
    }

    function release(page) {
      const video = page?.querySelector('video');
      if (!video || video.dataset.ready !== '1' || !video.paused) return;
      video.removeAttribute('src');
      try { video.load(); } catch (error) {}
      video.dataset.ready = '';
      video.dataset.bound = '';
      video.removeAttribute('poster');
      page.querySelector('.feed-spinner')?.classList.remove('hidden');
      const fill = page.querySelector('.feed-bar-fill');
      if (fill) fill.style.width = '0%';
      const cur = page.querySelector('.feed-time-cur');
      if (cur) cur.textContent = '0:00';
    }

    function neighbors(page) {
      const feed = page.parentElement;
      if (!feed) return;
      const pages = [...feed.querySelectorAll('.feed-page')];
      const here = pages.indexOf(page);
      pages.forEach((item, index) => {
        const distance = Math.abs(index - here);
        if (distance === 0) return;
        if (distance <= PRELOAD_DISTANCE) warm(item);
        else if (distance > KEEP_DISTANCE) release(item);
      });
    }

    async function play(page) {
      if (!page?.classList.contains('feed-page')) return;
      const video = page.querySelector('video');
      if (!video) return;
      try {
        await attach(page);
        page.querySelector('.feed-error')?.classList.add('hidden');
        await video.play().catch(() => {});
      } catch (error) {
        page.querySelector('.feed-spinner')?.classList.add('hidden');
        const box = page.querySelector('.feed-error');
        if (box) {
          box.classList.remove('hidden');
          const label = box.querySelector('.feed-error-text');
          if (label) label.textContent = error.message || '播放失败';
        }
      }
      neighbors(page);
    }

    function pause(page) {
      const video = page.querySelector('video');
      if (!video || video.paused) return;
      try { video.pause(); } catch (error) {}
      remember(page.dataset.videoId, Number(page.dataset.ep), video.currentTime, video.duration);
      report(page, video.currentTime, video.duration, true);      // 暂停时必送
    }

    function controlsHtml() {
      return `<div class="feed-controls">
        <button type="button" class="feed-control feed-rate-toggle" aria-label="选择倍速">${speedLabel(selectedRate())}</button>
        <button type="button" class="feed-control feed-orientation-toggle" aria-label="切换横屏播放">横屏</button>
        <button type="button" class="feed-control feed-flip-toggle" aria-label="翻转画面方向">翻转</button>
        <div class="feed-rate-panel hidden" role="menu" aria-label="播放倍速">
          ${SPEEDS.map(rate => `<button type="button" class="feed-rate-option" data-rate="${rate}" role="menuitemcheckbox">${speedLabel(rate)}</button>`).join('')}
        </div>
      </div>`;
    }

    async function render(videoId, startEpisode = 1) {
      cleanup();
      state.route = 'feed';
      let detail = (state.detail && String(state.detail.id) === String(videoId) && asArray(state.detail.episodes).length) ? state.detail : null;
      if (!detail) {
        const cached = readCache(`detail:${videoId}`);
        if (cached?.data && asArray(cached.data.episodes).length) detail = cached.data;
      }
      if (!detail) {
        content.innerHTML = '<div class="feed-boot">正在加载…</div>';
        try { detail = await api('detail', { id: videoId }); }
        catch (error) { content.innerHTML = `<div class="feed-boot">${esc(error.message || '加载失败')}</div>`; return; }
      }
      const episodes = asArray(detail.episodes);
      if (!episodes.length) { content.innerHTML = '<div class="feed-boot">这部剧还没有可播放的选集</div>'; return; }

      const feed = document.createElement('div');
      feed.className = 'feed';
      feed.dataset.orientation = selectedOrientation();
      feed.dataset.flip = savedFlip() ? '1' : '0';
      // 返回键和倍速/横屏控件放在独立的 overlay 里，横屏时跟着舞台一起旋转。
      // 只放一份，不随集数重复。
      feed.innerHTML = `<div class="feed-overlay">
          <button type="button" class="feed-back" aria-label="返回">${icon('back')}</button>
          ${controlsHtml()}
        </div>`
        + episodes.map(entry => {
            const n = entry.number ?? entry.episode ?? entry.ep ?? 0;
            // 竖屏时 .feed-stage 就是整屏；横屏时只有它被旋转 90°，滚动容器不动。
            return `<section class="feed-page" data-ep="${n}" data-video-id="${esc(videoId)}" data-cover="${esc(detail.cover || '')}" data-title="${esc(detail.title || '')}">
              <div class="feed-stage">
                <video playsinline webkit-playsinline preload="none"></video>
                <div class="feed-spinner"></div>
                <div class="feed-error hidden"><span class="feed-error-text"></span><button type="button" class="feed-retry">重试</button></div>
                <div class="feed-speed">2×</div>
                <div class="feed-seek"><b class="feed-seek-delta">快进 0 秒</b><span class="feed-seek-clock">0:00 / 0:00</span></div>
                <div class="feed-hud"><b>第 ${n} 集</b><span>${esc(detail.title || '')}</span></div>
                <div class="feed-bar"><i class="feed-bar-fill"></i></div>
                <div class="feed-times"><span class="feed-time-cur">0:00</span><span class="feed-time-total">--:--</span></div>
              </div>
            </section>`;
          }).join('');
      (document.getElementById('app') || document.body).appendChild(feed);
      updateRateUi(feed);
      applyOrientation(feed, selectedOrientation(), false);

      observer = new IntersectionObserver(entries => {
        for (const entry of entries) {
          // 先登记当前集再起播：play 事件要拿它判断该不该收起外壳。
          if (entry.isIntersecting && entry.intersectionRatio >= 0.55) {
            activePage = entry.target;
            play(entry.target);
          } else {
            pause(entry.target);
          }
        }
      }, { root: feed, threshold: [0, 0.55, 1] });
      feed.querySelectorAll('.feed-page').forEach(page => observer.observe(page));

      let holdTimer = 0;
      let wentLong = false;
      let speedPage = null;
      const endHold = () => {
        clearTimeout(holdTimer);
        holdTimer = 0;
        if (speedPage) {
          const v = speedPage.querySelector('video');
          if (v) { delete v.dataset.tempSpeed; v.playbackRate = selectedRate(); }
          speedPage.classList.remove('is-speed');
          speedPage = null;
        }
      };

      feed.addEventListener('pointerdown', event => {
        if (event.target.closest('.feed-back, .feed-retry, .feed-bar, .feed-error, .feed-controls')) return;
        const page = event.target.closest('.feed-page');
        if (!page) return;
        wentLong = false;
        clearTimeout(holdTimer);
        holdTimer = setTimeout(() => {
          if (drag?.active) return;   // 已经在拖进度了，长按 2× 让位
          const v = page.querySelector('video');
          if (!v || v.paused || !v.duration) return;
          wentLong = true;
          speedPage = page;
          v.dataset.tempSpeed = '1';
          v.playbackRate = 2;
          page.classList.add('is-speed');
        }, HOLD_MS);
      });
      feed.addEventListener('pointerup', endHold);
      feed.addEventListener('pointercancel', endHold);
      feed.addEventListener('pointerleave', endHold);

      // 左右滑动拖动进度。轴的选择是这里唯一需要动脑的地方：
      // 滚动容器永远是竖向的，所以「屏幕横向」这条轴空着，拿它做 seek 不会跟上下滑切集抢。
      // 横屏虽然把画面转了 90°，手指位移仍在屏幕坐标系里，取横向照样能用。
      let drag = null;
      let seekSwipeAt = 0;

      const seekCeiling = video => {
        const dur = video.duration;
        if (dur && isFinite(dur) && dur > 0) return dur;
        // duration 还没定下来时（流刚起来那一瞬），退到浏览器给出的可跳范围。
        try {
          const ranges = video.seekable;
          if (ranges && ranges.length) return ranges.end(ranges.length - 1) || 0;
        } catch (error) {}
        return 0;
      };

      // 拖动中只改画面，不动播放头：进度条、当前时间、中央提示都按目标位置画，
      // 视频接着按原速播，松手才真正 seek。这样手上不会出现「跳过去又跳回来」。
      const paintDrag = () => {
        const { page, video, target, fromTime } = drag;
        const dur = video.duration || 0;
        const offset = Math.round(target - fromTime);
        const delta = page.querySelector('.feed-seek-delta');
        if (delta) delta.textContent = (offset < 0 ? '后退 ' : '快进 ') + Math.abs(offset) + ' 秒';
        const clock = page.querySelector('.feed-seek-clock');
        if (clock) clock.textContent = fmtClock(target) + ' / ' + fmtClock(dur);
        const fill = page.querySelector('.feed-bar-fill');
        if (fill && dur) fill.style.width = ((target / dur) * 100).toFixed(2) + '%';
        const cur = page.querySelector('.feed-time-cur');
        if (cur) cur.textContent = fmtClock(target);
      };

      const endDrag = commit => {
        if (!drag) return;
        const { page, video, fromTime, target, active, wasPlaying } = drag;
        drag = null;
        if (!active) return;
        page.classList.remove('is-seek');
        feed.classList.remove('is-seeking');
        if (!commit || Math.abs(target - fromTime) < 0.5) {
          // 手势被打断（pointercancel）也走这里：放弃这次 seek，但要把播放还回去，
          // 否则激活时暂停的那一下就成了"视频卡住不动"。
          if (wasPlaying) video.play().catch(() => {});
          return;
        }
        try { video.currentTime = target; } catch (error) {}
        if (wasPlaying) video.play().catch(() => {});
        const dur = video.duration;
        const fill = page.querySelector('.feed-bar-fill');
        if (fill && dur) fill.style.width = ((target / dur) * 100).toFixed(2) + '%';
        const cur = page.querySelector('.feed-time-cur');
        if (cur) cur.textContent = fmtClock(target);
      };

      feed.addEventListener('pointerdown', event => {
        if (drag) endDrag(false);
        if (event.target.closest('.feed-back, .feed-retry, .feed-bar, .feed-error, .feed-controls')) return;
        const page = event.target.closest('.feed-page');
        if (!page) return;
        const video = page.querySelector('video');
        if (!video || !video.duration || !isFinite(video.duration)) return;
        drag = { page, video, x: event.clientX, y: event.clientY, fromTime: video.currentTime, target: video.currentTime, active: false };
      });

      feed.addEventListener('pointermove', event => {
        if (!drag) return;
        const dx = event.clientX - drag.x;
        const dy = event.clientY - drag.y;
        if (!drag.active) {
          if (Math.abs(dx) < SEEK_SLOP || Math.abs(dx) < Math.abs(dy) * SEEK_BIAS) return;
          drag.active = true;
          endHold();                       // 拖进度与长按 2× 互斥，谁先成立算谁
          scrollToPage(feed, drag.page);   // 斜着划带起来的那几像素滚动，压回本集
          drag.page.classList.add('is-seek');
          feed.classList.add('is-seeking');
          // 按住进度时先停住播放头：画面静止，预览的那个位置才是真的位置。
          // 松手后按原状态恢复，本来是暂停的就别自作主张播起来。
          drag.wasPlaying = !drag.video.paused;
          if (drag.wasPlaying) { try { drag.video.pause(); } catch (error) {} }
        }
        const span = Math.max(240, window.innerWidth || feed.clientWidth || 1);
        const ceiling = seekCeiling(drag.video);
        const raw = drag.fromTime + (dx / span) * SEEK_SPAN;
        drag.target = Math.max(0, ceiling ? Math.min(ceiling, raw) : raw);
        paintDrag();
      });

      feed.addEventListener('pointerup', () => {
        if (drag?.active) seekSwipeAt = Date.now();
        endDrag(true);
      });
      feed.addEventListener('pointercancel', () => endDrag(false));

      feed.addEventListener('click', event => {
        if (event.target.closest('.feed-back')) { close(); return; }
        const controls = event.target.closest('.feed-controls');
        if (controls) {
          event.stopPropagation();
          const panel = feed.querySelector('.feed-rate-panel');
          if (event.target.closest('.feed-rate-toggle')) { panel?.classList.toggle('hidden'); return; }
          const rateButton = event.target.closest('.feed-rate-option');
          if (rateButton) {
            const rate = Number(rateButton.dataset.rate);
            if (SPEEDS.includes(rate)) { rememberRate(rate); applyRate(feed, rate); panel?.classList.add('hidden'); }
            return;
          }
          if (event.target.closest('.feed-orientation-toggle')) {
            const next = feed.dataset.orientation === 'landscape' ? 'portrait' : 'landscape';
            applyOrientation(feed, next, true);
            return;
          }
          if (event.target.closest('.feed-flip-toggle')) {
            applyFlip(feed, feed.dataset.flip !== '1');
            return;
          }
          return;
        }
        const retry = event.target.closest('.feed-retry');
        if (retry) {
          const page = retry.closest('.feed-page');
          release(page);
          page.querySelector('.feed-error')?.classList.add('hidden');
          play(page);
          return;
        }
        if (event.target.closest('.feed-bar')) return;
        if (Date.now() - seekSwipeAt < 400) return;   // 刚拖完进度，这一下不算暂停/继续
        if (wentLong) { wentLong = false; return; }
        const page = event.target.closest('.feed-page');
        if (!page) return;
        const video = page.querySelector('video');
        if (!video) return;
        if (video.paused) video.play().catch(() => {}); else video.pause();
      });

      feed.addEventListener('pointerdown', event => {
        if (event.target.closest('.feed-controls')) event.stopPropagation();
      }, true);

      const target = feed.querySelector(`.feed-page[data-ep="${startEpisode}"]`) || feed.querySelector('.feed-page');
      requestAnimationFrame(() => scrollToPage(feed, target));
      updateBackEnabled();
    }

    return { open, close, cleanup };
  }

  window.HGFeedPlayer = { create: createFeedPlayer };
})();
