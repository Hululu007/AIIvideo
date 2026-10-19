// spider 宿主的跨进程通道。
//
// 为什么是同步 String 进 / String 出：调用方（SpiderClient）本来就在后台线程上
// 阻塞等结果，契约（docs/2026-10-19-spider阶段0契约.md §三）的入参/出参也都是
// 一个 JSONObject。Binder 的线程池天然承受这种阻塞调用，一次往返刚好对齐
// 「一次能力调用」的生命周期，不需要引入回调/监听那套异步机器。
//
// 返回值统一包一层：
//   {"ok":true,"payload":{…}}   —— spider 成功，payload 是契约里的返回值
//   {"ok":false,"error":"…"}    —— 失败，error 是能直接给用户/日志看的那一句
package com.huangguo.mobile;

interface ISpiderChannel {
    String call(String requestJson);
}
