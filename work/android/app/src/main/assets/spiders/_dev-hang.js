// 阶段 0 的**超时样本**：home 永远不 resolve，用来验看门狗。
//
// 它存在的理由和 _dev-hello 一样 —— 把「卡住的 spider 会怎样」在真机上验一遍。
// 这条比跑通更重要：卡住的是渲染线程里的 Promise，Java 侧取消不掉，只能整只
// WebView 换掉；如果那条路没走通，用户看到的就是「首页一直转圈，重进也没用」。
//
//   adb shell am start -n com.huangguo.mobile/.MainActivity \
//       --es spider_demo 1 --es spider_asset spiders/_dev-hang.js --ei spider_timeout 3000
__spiderSite = {
  id: 'dev-hang',
  name: '超时样本',
  version: 1,
  requires: '>=1',
  hosts: [],
  async home(ctx) {
    ctx.call('log', { msg: 'hanging…' });
    await new Promise(function () {});   // 永不 resolve
    return { items: [] };
  }
};
