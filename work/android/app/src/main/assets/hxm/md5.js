/* 极简 MD5。AGE动漫 播放层的密钥是 md5(Code+id1+id2+Appkey+Version) 拆两半，
 * 只要十六进制小写输出。 */
(function () {
  const S = [7,12,17,22, 7,12,17,22, 7,12,17,22, 7,12,17,22,
             5, 9,14,20, 5, 9,14,20, 5, 9,14,20, 5, 9,14,20,
             4,11,16,23, 4,11,16,23, 4,11,16,23, 4,11,16,23,
             6,10,15,21, 6,10,15,21, 6,10,15,21, 6,10,15,21];
  const K = new Uint32Array(64);
  for (let i = 0; i < 64; i++) K[i] = Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296);

  function toBytes(str) {
    const out = [];
    for (let i = 0; i < str.length; i++) {
      let c = str.charCodeAt(i);
      if (c < 0x80) out.push(c);
      else if (c < 0x800) { out.push(0xc0 | (c >> 6), 0x80 | (c & 63)); }
      else if (c < 0xd800 || c >= 0xe000) { out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63)); }
      else {   // 代理对
        i++;
        c = 0x10000 + (((c & 0x3ff) << 10) | (str.charCodeAt(i) & 0x3ff));
        out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
      }
    }
    return out;
  }

  function md5(str) {
    const msg = toBytes(String(str));
    const len = msg.length;
    msg.push(0x80);
    while (msg.length % 64 !== 56) msg.push(0);
    const bits = len * 8;
    for (let i = 0; i < 8; i++) msg.push((bits / Math.pow(2, i * 8)) & 0xff);

    let a0 = 0x67452301, b0 = 0xefcdab89, c0 = 0x98badcfe, d0 = 0x10325476;
    const M = new Uint32Array(16);
    for (let off = 0; off < msg.length; off += 64) {
      for (let i = 0; i < 16; i++) {
        M[i] = msg[off + i*4] | (msg[off + i*4+1] << 8) | (msg[off + i*4+2] << 16) | (msg[off + i*4+3] << 24);
      }
      let A = a0, B = b0, C = c0, D = d0;
      for (let i = 0; i < 64; i++) {
        let F, g;
        if (i < 16)      { F = (B & C) | (~B & D);        g = i; }
        else if (i < 32) { F = (D & B) | (~D & C);        g = (5*i + 1) % 16; }
        else if (i < 48) { F = B ^ C ^ D;                 g = (3*i + 5) % 16; }
        else             { F = C ^ (B | ~D);              g = (7*i) % 16; }
        F = (F + A + K[i] + M[g]) | 0;
        A = D; D = C; C = B;
        B = (B + ((F << S[i]) | (F >>> (32 - S[i])))) | 0;
      }
      a0 = (a0 + A) | 0; b0 = (b0 + B) | 0; c0 = (c0 + C) | 0; d0 = (d0 + D) | 0;
    }
    const hex = n => {
      let s = '';
      for (let i = 0; i < 4; i++) s += ((n >>> (i * 8)) & 0xff).toString(16).padStart(2, '0');
      return s;
    };
    return hex(a0) + hex(b0) + hex(c0) + hex(d0);
  }

  window.HG_MD5 = md5;
})();
