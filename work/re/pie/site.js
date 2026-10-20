// ═══════════════════════════════════════════════════════════════════════
// 水果派（pie）—— Flutter 壳 + RSA/AES 信封接口，单站 spider 源
//
// 包名 com.fruit.goodgood，版本 2.5.4 (versionCode 17)，2026-07-31 构建。
// 整个业务层在 libapp.so 的 Dart AOT 快照里，Java 那边只有入口和友盟/Shorebird。
//
// ── 接口形态（2026-10-20 实测）─────────────────────────────────────────
//   POST https://<apiHost>/v2_0/<name>
//   请求体、响应体都是同一个信封：{encrypt_key, encrypt_data}
//     encrypt_key = RSA/PKCS1v15( {"key":b64(16B),"iv":b64(16B)} )
//                   请求方向用 assets/key/service_public.key 加密，
//                   响应方向服务器用 assets/key/client_private.key 对应的公钥加密，
//                   所以只有客户端私钥能开。
//     encrypt_data = AES-128-CBC/PKCS7(明文 JSON)
//   明文外壳：{"code":1,"msg":"请求成功","data":…,"time":<秒>}
//
//   实测端点（全部在 /v2_0/ 下，未知名字回 404，参数错回 41000 带中文 msg）：
//     homePage                        {page}                5 条/页，totalPage 两千多
//     classificationContentDetails    {classification_id,page}  15 条/页
//     searchV21List                   {word,page}           关键词 ≥ 2 字
//     getSingleItem                   {library_id:"<id>"}   注意要字符串，int 会回空 item
//     filmDetails                     {film_id:"<id>"}      原片表，和 library 不是一张表
//     getClassificationList           {}                    分类（吃瓜/AV资讯/ASMR/漫画/…）
//     apiUrl                          {}                    当前可用的 API 域名池
//
// ── 播放：加密已经解开，但播放器侧还起不来 ─────────────────────────────
//   filmDetails 里 `url` 常年是空串，能播的只有 `encryption_url`
//   （…/encry/vd/…/hls/decrypt/index.m3u8）。整条 HLS 链（m3u8 + 每个 .ts）都是
//   **整文件 AES-128-ECB + PKCS#7**，key 是常量 `saIZXc4yMvq0Iz56`（2026-10-20 从
//   运行中的进程内存里扫出来的，两个不同视频通用）。
//   解密本身一行就够（ECB = IV 全 0 的 CBC）；play 仍然降级，是因为**播放器自己去
//   拉地址**：它拿到的 m3u8 是密文，没有那条解密链路。要真能播，得在 App 播放层
//   加一段「拉取 → 解密 → 本地重发」的代理，那是壳的活。
// ═══════════════════════════════════════════════════════════════════════

__spiderSite = {
  id: 'pie',
  name: '水果派',
  version: 1,
  requires: '>=1',
  // net 的白名单：要请求的每个域都得在这儿，精确匹配、小写。
  hosts: [
    '926api.25xzguk9.com',
    '926api.0a858f.com',
    '926api.9mjcuuq.com',
    '926api.1e079e6.com',
    'api.u7kt9ez.com',
    '926api.fe76wug.com',
    '926api.9db322c.com',
    'api926.aed8c8.com',
    'api926.0098104.com',
    'api926.12ee0a3.com',
    'api.3hvkdg.com',
    'api.ek8rmg.com',
    'api.gpmhkg.com',
    'api.rgydg4.com',
    'api.xmxfmq.com'
  ],

  async home(ctx) {
    return listPage(ctx, { path: '/v2_0/homePage', body: { page: 1 }, page: 1 });
  },

  async category(ctx, args) {
    var a = args || {};
    var page = num(a.page, 1);
    return listPage(ctx, {
      path: '/v2_0/classificationContentDetails',
      body: { classification_id: num(a.slug, 1), page: page },
      page: page
    });
  },

  async search(ctx, args) {
    var a = args || {};
    var page = num(a.page, 1);
    var word = str(a.keyword);
    if (word.length < 2) return { items: [], page: page, hasMore: false };
    return listPage(ctx, {
      path: '/v2_0/searchV21List',
      body: { word: word, page: page },
      page: page
    });
  },

  async detail(ctx, args) {
    var ref = str((args || {}).ref);
    if (!ref) throw new Error('详情缺少 ref');
    var r = await api(ctx, '/v2_0/getSingleItem', { library_id: ref });
    var item = (r.data || {}).item;
    if (!item || Array.isArray(item) || !item.library_id) {
      throw new Error('详情没拿到 library_id=' + ref + '（code=' + r.code + ' ' + r.msg + '）');
    }
    var tags = [];
    var raw = item.tags || [];
    for (var i = 0; i < raw.length; i++) {
      var t = raw[i] && raw[i].tag_title;
      if (t) tags.push(str(t));
    }
    return {
      id: str(item.library_id),
      title: str(item.title || item.content || item.desc_content),
      cover: cover(item.img_url || item.thumb_multi_map && item.thumb_multi_map[0]),
      description: str(item.desc_content || item.content),
      tags: tags,
      category: '',
      badge: str(item.movie_time),
      playAt: str(item.show_time_date || item.show_time),
      episodeCount: 1,
      // 站点是「一条帖子一个视频」，没有分集概念，给一格就够。
      episodes: [{ no: 1, ref: str(item.library_id), title: '正片' }],
      lines: [{ line: 'default', name: '默认线路' }]
    };
  },

  async play(ctx, args) {
    var ref = str((args || {}).ref);
    var cut = ref.lastIndexOf(':');
    var id = cut > 0 ? ref.slice(0, cut) : ref;
    if (!id) return { reason: 'no-source', note: '这一条没有给出播放标识' };

    // 原片表里查一次：能给出 url 就能播；只给 encryption_url 就是加密流。
    var r = null;
    try {
      r = await api(ctx, '/v2_0/filmDetails', { film_id: id });
    } catch (e) {
      return { reason: 'no-source', note: '取播放地址失败：' + e.message };
    }
    var data = r.data;
    var film = Array.isArray(data) ? data[0] : null;
    var url = film && str(film.url);
    if (url) {
      return {
        url: url,
        alts: [{ line: 'default', name: '默认线路', url: url }],
        episodeNumber: 1
      };
    }
    var enc = film && str(film.encryption_url);
    if (enc) {
      // 不是「没有地址」，是「地址是密文」——按规范用 reason 降级，别把密文塞进 url。
      return {
        reason: 'encrypted',
        note: '站点是加密 HLS（AES-128-ECB，密钥已知），但播放器侧没有解密链路，只能离线解',
        episodeNumber: 1
      };
    }
    return { reason: 'no-source', note: '原片 ' + id + ' 没有公开播放地址（多为付费内容）' };
  }
};

// ═══════════════════════════════════════════════════════════════════════
// 下面是实现。放对象字面量后面，元信息扫描只读上面那四个字段。
// ═══════════════════════════════════════════════════════════════════════

// 客户端私钥：解响应信封用。原文在 APK 的 assets/flutter_assets/assets/key/client_private.key。
var PIE_CLIENT_KEY = '__CLIENT_KEY_PEM__';

// 服务端公钥：封请求体用。原文 assets/flutter_assets/assets/key/service_public.key。
var PIE_SERVICE_KEY = '__SERVICE_KEY_PEM__';

var PIE_DEFAULT_HOST = '926api.25xzguk9.com';
var PIE_API_HOSTS = __spiderSite.hosts.slice(0, 15);

function str(v) {
  return String(v == null ? '' : v).trim();
}

function num(v, fallback) {
  var n = Number(v);
  return isFinite(n) && n > 0 ? n : fallback;
}

function cover(u) {
  var s = str(u);
  if (!s) return '';
  if (s.indexOf('//') === 0) return 'https:' + s;
  return s;
}

function b64ToBytes(s) { return HGCrypto.b64decode(s); }
function bytesToB64(b) { return HGCrypto.b64encode(b); }

/** 16 字节随机：Math.random 够用（密钥只保护一次会话，且不需要密码学强度）。 */
function rand16() {
  var out = new Uint8Array(16);
  for (var i = 0; i < 16; i++) out[i] = Math.floor(Math.random() * 256) & 255;
  return out;
}

/** 明文对象 → {encrypt_key, encrypt_data}，用服务端公钥。 */
function sealRequest(obj) {
  var key = rand16();
  var iv = rand16();
  var plain = HGCrypto.utf8Encode(JSON.stringify(obj));
  var data = HGCrypto.aesCbcEncrypt(key, iv, plain);
  var meta = JSON.stringify({ key: bytesToB64(key), iv: bytesToB64(iv) });
  var ek = HGCrypto.rsaEncryptPKCS1v15(PIE_SERVICE_KEY, HGCrypto.utf8Encode(meta));
  return JSON.stringify({ encrypt_key: bytesToB64(ek), encrypt_data: bytesToB64(data) });
}

/** 响应信封 → 明文对象，用客户端私钥。 */
function openResponse(body) {
  var env = JSON.parse(body);
  if (!env || typeof env.encrypt_key !== 'string') {
    // 不是信封（比如 404 的 {"Code":"NoSuchKey"} 或者网关错误页），原样当明文试一次。
    return env;
  }
  var metaRaw = HGCrypto.rsaDecryptPKCS1v15(PIE_CLIENT_KEY, b64ToBytes(env.encrypt_key));
  var meta = JSON.parse(HGCrypto.utf8Decode(metaRaw));
  var plain = HGCrypto.aesCbcDecrypt(
    b64ToBytes(meta.key), b64ToBytes(meta.iv), b64ToBytes(env.encrypt_data));
  return JSON.parse(HGCrypto.utf8Decode(plain));
}

/** 一次 /v2_0 调用。域名池里挨个试，全挂了才抛。 */
async function api(ctx, path, body) {
  var hosts = PIE_API_HOSTS;
  var lastError = '';
  for (var i = 0; i < hosts.length; i++) {
    var url = 'https://' + hosts[i] + path;
    var res;
    try {
      res = await ctx.net({
        url: url,
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: sealRequest(body || {}),
        format: 'json'
      });
    } catch (e) {
      lastError = 'net 异常：' + (e && e.message);
      continue;
    }
    if (!res || !res.ok) {
      lastError = 'HTTP 失败 @ ' + hosts[i] + path;
      continue;
    }
    var json;
    try {
      json = openResponse(res.body);
    } catch (e) {
      // 解不开信封是**密钥/协议**问题，换域名没用 —— 当场抛，别把 15 个域名都跑一遍。
      throw new Error('解不开响应信封 @ ' + hosts[i] + '：' + (e && e.message)
        + '（' + String(res.body || '').length + ' 字节）');
    }
    if (!json || typeof json !== 'object') {
      lastError = '响应不是 JSON @ ' + hosts[i];
      continue;
    }
    // 41000/41001 是参数问题，换域名没用，直接抛。
    if (json.code !== 1) {
      throw new Error(path + ' 失败：code=' + json.code + ' ' + (json.msg || json.message || ''));
    }
    return json;
  }
  throw new Error(path + ' 所有域名都失败：' + lastError);
}

function cardOf(it) {
  var tags = [];
  var raw = it.tags || [];
  for (var i = 0; i < raw.length && tags.length < 4; i++) {
    var t = raw[i] && raw[i].tag_title;
    if (t) tags.push(str(t));
  }
  return {
    id: str(it.library_id || it.id),
    title: str(it.title || it.subtitle),
    cover: cover(it.img_url),
    description: str(it.subtitle || it.desc_content),
    tags: tags,
    badge: str(it.movie_time),
    playAt: str(it.show_time_date || it.show_time || it.created_at),
    score: it.score == null ? '' : str(it.score)
  };
}

async function listPage(ctx, opt) {
  var r = await api(ctx, opt.path, opt.body);
  var d = r.data || {};
  var list = d.list || [];
  var items = [];
  for (var i = 0; i < list.length; i++) {
    var c = cardOf(list[i]);
    if (c.id) items.push(c);
  }
  if (!items.length && opt.page === 1) {
    throw new Error(opt.path + ' 没解析到卡片（list=' + list.length + ' 条）');
  }
  return {
    items: items,
    page: num(d.currentPage, opt.page),
    hasMore: d.hasNextPage === 1 || d.hasNextPage === true
  };
}
