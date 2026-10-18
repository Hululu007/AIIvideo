'use strict';
// 自 app.js 拆出的一屏：应用内 APK 更新的 UI 与下载状态。
// 原生那半截没搬（UpdateManager、check-update / download-update 两个桥指令）。
// 入口还留在 app.js 的 renderSettings 里，那边嵌一个 renderUpdateCheck() 的槽位；
// 两边靠全局作用域通信，和 cover.js / adapters.js 一个做法。
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
  const tail = document.getElementById('update-check-tail');
  if (!tail) return;
  tail.innerHTML = updateTailMarkup();
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
