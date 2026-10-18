/* 走 Java 桥的 HTTP。
 *
 * 为什么不直接 fetch：jx.wuzhoupai.com 的预检响应里只有 access-control-allow-origin，
 * 没有 access-control-allow-headers，浏览器不会放行带 Video-Parse-* 自定义头的请求。
 * Java 的 OkHttp 不受同源策略管，所以借 detail 那条桥转一手。
 *
 * 借桥而不是加动作，是因为 MainActivity 的 dispatch 是按编号跳的 packed-switch，
 * 加动作要同时改两张表 —— 这是本工程最容易出事的地方。沿用 __source / __progress 那招。
 */
(function () {
  function call(command) {
    if (typeof window.HG_BRIDGE !== 'function') return Promise.reject(new Error('桥没就绪'));
    return window.HG_BRIDGE('http', { id: command }).then(function (reply) {
      const r = typeof reply === 'string' ? JSON.parse(reply) : reply;
      if (!r || r.ok === false) throw new Error((r && r.error) || 'HTTP 失败');
      return r;
    });
  }

  window.HG_HTTP = {
    get: function (url) {
      return call('get?payload=' + encodeURIComponent(JSON.stringify({ url: url }))).then(function (r) { return r.body; });
    },
    post: function (url, body, headers) {
      const payload = JSON.stringify({ url: url, body: body, headers: headers || {} });
      return call('post?payload=' + encodeURIComponent(payload)).then(function (r) { return r.body; });
    }
  };
})();
