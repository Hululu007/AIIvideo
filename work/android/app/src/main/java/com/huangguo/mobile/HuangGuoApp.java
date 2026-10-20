package com.huangguo.mobile;

import android.app.Application;
import com.parse.Parse;

public final class HuangGuoApp extends Application {
    @Override // android.app.Application
    public void onCreate() {
        super.onCreate();
        Parse.initialize(new Parse.Configuration.Builder(this).applicationId(getString(R.string.back4app_app_id)).clientKey(getString(R.string.back4app_client_key)).server(Endpoints.API_BASE).build());
        // 预热内置核心的运行环境（gojni 加载 + initCoreEnv），放后台线程。
        //
        // 这一步冷启动实测要 3 秒左右（日志里「核心就绪 Lib v31」那行之前）。
        // 以前它只在「保存并启动」时做，于是进程起来后第一次点测速要白等这 3 秒，
        // 用户看到的就是「明明只有一个节点，测速怎么这么慢」。
        // 放这儿预热不影响启动：不碰网络，只是把 so 和 Go 运行时加载进来。
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    ProxyCore.get(HuangGuoApp.this).ensureEnv();
                } catch (Throwable ignored) {}
            }
        }, "proxy-core-warmup").start();
    }
}
