// 阶段 0 自检源：只验证「宿主页 → bridge → done」这条链路通不通。
// 不声明 hosts、不联网，返回三张写死的卡就是全部作用。
// 正式源以后从热更的 ui/spiders/ 下发，这个文件留在 assets 里只是让新装的壳
// 第一次启动就能点一次 demo，不参与业务。
// 约定：直接给 __spiderSite 赋值（不是 window.__spiderSite），非严格模式下
// 这就是写全局；也不要 export / module.exports，宿主页是当 <script> 注入的。
__spiderSite = {
  id: 'dev-hello',
  name: '自检源',
  version: 1,
  requires: '>=1',
  hosts: [],            // 阶段 0 不联网，白名单留空

  async home(ctx) {
    // 先打一条日志，验证 bridge 的同步往返；log 失败不影响后面返回卡片。
    ctx.call('log', { msg: 'hello from dev-hello' });
    return {
      items: [
        { id: 'dev-a', title: '卡片 A', cover: '' },
        { id: 'dev-b', title: '卡片 B', cover: '' },
        { id: 'dev-c', title: '卡片 C', cover: '' }
      ]
    };
  }
};
