// 直接拿「萝球社！OAD」的真 token 跑，看错误信息变成什么样
const fs=require('fs'), crypto=require('crypto'), https=require('https'), vm=require('node:vm');
const REPO='/Users/zhaoruizhe/Desktop/project/T/hg/work/shells/cz_repack/assets';
require('/tmp/agedm/shim.cjs');
global.window.Domain='jx.wuzhoupai.com';
global.crypto=crypto.webcrypto;
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
  global.window.HG_HXM={encrypt:s=>Promise.resolve(globalThis.hxm_encrypt(s)),decrypt:s=>Promise.resolve(globalThis.hxm_decrypt(s))};
  vm.runInThisContext(fs.readFileSync(REPO+'/hxm/md5.js','utf8'));
  global.window.HG_HTTP={ get:u=>raw('GET',u.replace('https://'+HOST+':8443','')),
    post:(u,b,h)=>raw('POST',u.replace('https://'+HOST+':8443',''),b,h) };
  vm.runInThisContext(fs.readFileSync(REPO+'/hxm/agedm-play.js','utf8'));
  const PLAY=global.window.HG_AGEDM_PLAY;

  console.log('采样多部老番，看 224 位 Vurl 是不是必然 Code=9：\n');
  for(const id of ['20130107','20130106','20110045','20260286','20260700']){
    let v; try{ v=(await api('/v2/detail/'+id)).video; }catch(e){ continue; }
    if(!v) continue;
    const names=Object.keys(v.playlists||{});
    const lines=[]; for(const n of names){ const t=v.playlists[n][0][1]; if(t&&t.startsWith('age_')) lines.push([n,t]); }
    const steps=[];
    try{
      const url=await PLAY.resolveAny(lines, s=>steps.push(s));
      console.log(`${String(v.name).slice(0,24).padEnd(26)} ${names.length}线  ✓ ${url.slice(0,46)}`);
    }catch(e){
      console.log(`${String(v.name).slice(0,24).padEnd(26)} ${names.length}线  ✗ ${e.message}`);
    }
  }
})().catch(e=>console.log('崩了:',e.message));
