// 浏览器环境的最小替身。逐次跑，缺什么补什么。
global.window = global;
global.self = global;
global.document = { createElement: () => ({ style: {}, setAttribute(){}, appendChild(){} }),
                    head: { appendChild(){} }, body: { appendChild(){} },
                    documentElement: { style: {} }, addEventListener(){} };
global.navigator = { userAgent: 'Mozilla/5.0', platform: 'MacIntel' };
global.location = { href: 'https://m.example/', protocol: 'https:', host: 'm.example' };
global.localStorage = { getItem: () => null, setItem(){}, removeItem(){} };
global.XMLHttpRequest = class { open(){} setRequestHeader(){} send(){} addEventListener(){} };
