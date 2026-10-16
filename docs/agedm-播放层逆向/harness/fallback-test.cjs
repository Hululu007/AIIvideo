// 把 App 里那个 assets/hxm/agedm-play.js **原样**在 node 里跑起来，
// 喂它 [坏线路, 好线路]，看 resolveAny 会不会真的换下一条。
const fs=require('fs'), crypto=require('crypto'), https=require('https'), vm=require('node:vm');
const REPO='/Users/zhaoruizhe/Desktop/project/T/hg/work/shells/cz_repack/assets';
require('/tmp/agedm/shim.cjs');
global.window.Domain='jx.wuzhoupai.com';
global.crypto=crypto.webcrypto;                       // aesCbcDecrypt 用 subtle
vm.runInThisContext(fs.readFileSync('/tmp/agedm/tinygo_exec.js','utf8'));

const HOST='jx.wuzhoupai.com', PORT=8443;
const UA='Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36';
function raw(method,path,body,headers){return new Promise((res,rej)=>{
  const r=https.request({hostname:HOST,port:PORT,path,method,rejectUnauthorized:false,
    headers:{'User-Agent':UA,...headers}},x=>{let d='';x.on('data',c=>d+=c);x.on('end',()=>res(d));});
  r.on('error',rej); if(body)r.write(body); r.end();});}
function api(p){return new Promise((res,rej)=>{https.get('https://api.agedm.io'+p,{headers:{'User-Agent':UA},rejectUnauthorized:false},
  r=>{let d='';r.on('data',c=>d+=c);r.on('end',()=>res(JSON.parse(d)));}).on('error',rej);});}

const go=new global.Go();
(async()=>{
  const {instance}=await WebAssembly.instantiate(fs.readFileSync('/tmp/agedm/jx.wuzhoupai.com.wasm'),go.importObject);
  go.run(instance).catch(()=>{}); await new Promise(r=>setTimeout(r,1000));

  // 补上 App 里那几个全局：wasm 的加解密、md5、走桥的 HTTP（这里直接发）
  global.window.HG_HXM={encrypt:s=>Promise.resolve(globalThis.hxm_encrypt(s)),decrypt:s=>Promise.resolve(globalThis.hxm_decrypt(s))};
  vm.runInThisContext(fs.readFileSync(REPO+'/hxm/md5.js','utf8'));
  global.window.HG_HTTP={
    get:u=>raw('GET',u.replace('https://'+HOST+':8443','')).then(b=>b),
    post:(u,b,h)=>raw('POST',u.replace('https://'+HOST+':8443',''),b,h).then(r=>r)
  };
  // 真的把 App 那个文件加载进来
  vm.runInThisContext(fs.readFileSync(REPO+'/hxm/agedm-play.js','utf8'));
  const PLAY=global.window.HG_AGEDM_PLAY;
  console.log('HG_AGEDM_PLAY 导出:', Object.keys(PLAY));
  console.log('  有 resolveAny:', typeof PLAY.resolveAny === 'function');

  // 拿一部真片：5 条线路
  const v=(await api('/v2/detail/20260286')).video;
  const names=Object.keys(v.playlists);
  console.log('\n测试片:', v.name, '| 线路', names.join(','));
  const lines=[];
  for(const n of names){ const t=v.playlists[n][0][1]; if(t&&t.startsWith('age_')) lines.push([n,t]); }
  console.log('可用线路', lines.length, '条');

  const steps=[];
  console.log('\n=== ① 正常：全部线路都在 ===');
  let url=await PLAY.resolveAny(lines, s=>steps.push(s));
  console.log('  →', url.slice(0,80));

  console.log('\n=== ② 把第一条换成坏密文（模拟第一条线路服务端不受理）===');
  const broken=[['假的坏线路', lines[0][1].slice(0,60)], ...lines];
  steps.length=0;
  url=await PLAY.resolveAny(broken, s=>steps.push(s));
  console.log('  进度文字:'); steps.forEach(s=>console.log('   ',s));
  console.log('  →', url.slice(0,80));

  console.log('\n=== ③ 全部线路都坏（模拟「萝球社！OAD」那种只有一条且不通）===');
  steps.length=0;
  try{ await PLAY.resolveAny([['tt', lines[0][1].slice(0,60)]], s=>steps.push(s)); console.log('  没抛错？'); }
  catch(e){ console.log('  抛错:', e.message); }
})().catch(e=>console.log('崩了:',e.message));
