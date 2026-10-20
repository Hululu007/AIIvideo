// 水果派（pie）spider 源 —— 内置 RSA/AES 信封实现（无外部依赖，只用 BigInt/typed array）。
// 生成方式：work/re/pie/build.sh（crypto.js + site.js），别手改这个文件。

/*
 * crypto.js — dependency-free crypto for a sandboxed ECMAScript engine
 * (QuickJS-like). Single file, no imports, no host injections.
 *
 * ENGINE ASSUMPTIONS
 *   - ES2020 BigInt global is available: BigInt(x), 0n literals, >>, %, *
 *     This file uses the BigInt() CONSTRUCTOR everywhere (BigInt(0), BigInt(16),
 *     BigInt('0x'+hex)) and never a `123n` literal, so a host parser that chokes
 *     on the `n` suffix is not a risk.
 *   - Uint8Array, Math, JSON, String/Array built-ins. That is the whole surface.
 *   - NOTHING else is used: no Buffer, no atob/btoa, no TextEncoder/TextDecoder,
 *     no crypto.getRandomValues, no WebAssembly, no Date (timing is external).
 *
 * SURFACE (assigned to globalThis.HGCrypto)
 *   rsaDecryptPKCS1v15(privateKeyPem, cipherBytes[, opts]) -> Uint8Array
 *   rsaEncryptPKCS1v15(publicKeyPem, messageBytes[, rngOrOpts]) -> Uint8Array
 *   aesCbcDecrypt(keyBytes, ivBytes, dataBytes[, {unpad:true}]) -> Uint8Array
 *   aesCbcEncrypt(keyBytes, ivBytes, dataBytes[, {pad:true}]) -> Uint8Array
 *   b64decode(str) -> Uint8Array ; b64encode(bytes) -> string
 *   utf8Encode(str) -> Uint8Array ; utf8Decode(bytes) -> string
 *
 * Sizes: deliberately compact; AES is S-box based with on-the-fly MixColumns.
 */
(function (global) {
  'use strict';

  var B0 = BigInt(0), B1 = BigInt(1);

  /* ------------------------------------------------------------------ *
   * byte helpers
   * ------------------------------------------------------------------ */

  function toBytes(x) {
    if (x instanceof Uint8Array) return x;
    if (x && typeof x.length === 'number') {
      var o = new Uint8Array(x.length);
      for (var i = 0; i < x.length; i++) o[i] = x[i] & 0xff;
      return o;
    }
    if (typeof x === 'string') return utf8Encode(x);
    throw new Error('expected bytes');
  }

  function concatBytes(a, b) {
    var o = new Uint8Array(a.length + b.length);
    o.set(a, 0); o.set(b, a.length);
    return o;
  }

  var HEXD = '0123456789abcdef';
  var HEXV = (function () {
    var m = [], i;
    for (i = 0; i < 256; i++) m[i] = -1;
    for (i = 0; i < 10; i++) m[48 + i] = i;
    for (i = 0; i < 6; i++) { m[97 + i] = 10 + i; m[65 + i] = 10 + i; }
    return m;
  })();

  function bytesToHex(b) {
    var s = '', i;
    for (i = 0; i < b.length; i++) s += HEXD.charAt(b[i] >> 4) + HEXD.charAt(b[i] & 15);
    return s;
  }

  function hexToBytes(h) {
    var n = h.length >> 1, o = new Uint8Array(n), i;
    for (i = 0; i < n; i++) o[i] = (HEXV[h.charCodeAt(i * 2)] << 4) | HEXV[h.charCodeAt(i * 2 + 1)];
    return o;
  }

  function bytesToBigInt(b) {
    if (!b.length) return B0;
    var h = bytesToHex(b);
    if (HEXV[h.charCodeAt(0)] < 0) throw new Error('bad hex');
    return BigInt('0x' + h);
  }

  /* Big-endian fixed-width integer -> bytes. len may be 0 (natural width). */
  function bigIntToBytes(x, len) {
    var h = x.toString(16);
    if (h.length & 1) h = '0' + h;
    var out = hexToBytes(h);
    if (!len) return out;
    if (out.length > len) return out.subarray(out.length - len);
    if (out.length < len) {
      var p = new Uint8Array(len);
      p.set(out, len - out.length);
      return p;
    }
    return out;
  }

  function bytesEqual(a, b) {
    if (a.length !== b.length) return false;
    for (var i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
    return true;
  }

  /* ------------------------------------------------------------------ *
   * base64
   * ------------------------------------------------------------------ */

  var B64A = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  var B64V = (function () {
    var m = [], i;
    for (i = 0; i < 256; i++) m[i] = -1;
    for (i = 0; i < 64; i++) m[B64A.charCodeAt(i)] = i;
    /* accept URL-safe variants too */
    m[45] = 62; m[95] = 63;
    return m;
  })();

  function b64encode(input) {
    var b = toBytes(input), out = [], i, n = b.length;
    for (i = 0; i + 2 < n; i += 3) {
      var t = (b[i] << 16) | (b[i + 1] << 8) | b[i + 2];
      out.push(B64A.charAt((t >> 18) & 63), B64A.charAt((t >> 12) & 63),
               B64A.charAt((t >> 6) & 63), B64A.charAt(t & 63));
    }
    var r = n - i;
    if (r === 1) {
      var t1 = b[i] << 16;
      out.push(B64A.charAt((t1 >> 18) & 63), B64A.charAt((t1 >> 12) & 63), '==');
    } else if (r === 2) {
      var t2 = (b[i] << 16) | (b[i + 1] << 8);
      out.push(B64A.charAt((t2 >> 18) & 63), B64A.charAt((t2 >> 12) & 63),
               B64A.charAt((t2 >> 6) & 63), '=');
    }
    return out.join('');
  }

  function b64decode(str) {
    var s = String(str), i, c, v;
    /* strip whitespace / padding, accept both alphabets */
    var acc = 0, bits = 0, out = [], n = s.length;
    for (i = 0; i < n; i++) {
      c = s.charCodeAt(i);
      if (c === 61) continue;              /* '=' */
      v = c < 256 ? B64V[c] : -1;
      if (v < 0) continue;                 /* whitespace, newlines, junk */
      acc = (acc << 6) | v; bits += 6;
      if (bits >= 8) {
        bits -= 8;
        out.push((acc >> bits) & 0xff);
      }
    }
    var r = new Uint8Array(out.length);
    for (i = 0; i < out.length; i++) r[i] = out[i];
    return r;
  }

  /* ------------------------------------------------------------------ *
   * utf-8
   * ------------------------------------------------------------------ */

  function utf8Encode(str) {
    var s = String(str), out = [], i, n = s.length;
    for (i = 0; i < n; i++) {
      var c = s.charCodeAt(i);
      if (c < 0x80) {
        out.push(c);
      } else if (c < 0x800) {
        out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
      } else if (c >= 0xd800 && c <= 0xdbff && i + 1 < n) {
        var c2 = s.charCodeAt(i + 1);
        if (c2 >= 0xdc00 && c2 <= 0xdfff) {
          var cp = 0x10000 + ((c - 0xd800) << 10) + (c2 - 0xdc00);
          out.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 63),
                   0x80 | ((cp >> 6) & 63), 0x80 | (cp & 63));
          i++;
        } else {
          out.push(0xef, 0xbf, 0xbd);   /* lone high surrogate -> U+FFFD */
        }
      } else if (c >= 0xd800 && c <= 0xdfff) {
        out.push(0xef, 0xbf, 0xbd);     /* lone surrogate -> U+FFFD */
      } else {
        out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
      }
    }
    var r = new Uint8Array(out.length);
    for (i = 0; i < out.length; i++) r[i] = out[i];
    return r;
  }

  function utf8Decode(input) {
    var b = toBytes(input), out = [], i = 0, n = b.length;
    function rep() { out.push(0xfffd); }
    while (i < n) {
      var c = b[i++];
      if (c < 0x80) {
        out.push(c);
      } else if (c >= 0xc2 && c <= 0xdf) {
        if (i >= n || (b[i] & 0xc0) !== 0x80) { rep(); continue; }
        out.push(((c & 31) << 6) | (b[i++] & 63));
      } else if (c >= 0xe0 && c <= 0xef) {
        if (i + 1 >= n || (b[i] & 0xc0) !== 0x80 || (b[i + 1] & 0xc0) !== 0x80) { rep(); continue; }
        var c3 = ((c & 15) << 12) | ((b[i] & 63) << 6) | (b[i + 1] & 63);
        i += 2;
        if (c3 < 0x800) { rep(); continue; }
        if (c3 >= 0xd800 && c3 <= 0xdfff) { rep(); continue; }
        out.push(c3);
      } else if (c >= 0xf0 && c <= 0xf4) {
        if (i + 2 >= n || (b[i] & 0xc0) !== 0x80 || (b[i + 1] & 0xc0) !== 0x80 || (b[i + 2] & 0xc0) !== 0x80) { rep(); continue; }
        var c4 = ((c & 7) << 18) | ((b[i] & 63) << 12) | ((b[i + 1] & 63) << 6) | (b[i + 2] & 63);
        i += 3;
        if (c4 < 0x10000 || c4 > 0x10ffff) { rep(); continue; }
        c4 -= 0x10000;
        out.push(0xd800 + (c4 >> 10), 0xdc00 + (c4 & 0x3ff));
      } else {
        rep();
      }
    }
    var chunks = [], cur = '', lim = 8192;
    for (i = 0; i < out.length; i++) {
      cur += String.fromCharCode(out[i]);
      if (cur.length >= lim) { chunks.push(cur); cur = ''; }
    }
    if (cur.length) chunks.push(cur);
    return chunks.join('');
  }

  /* ------------------------------------------------------------------ *
   * AES (S-box based, CBC mode, 128/192/256)
   * ------------------------------------------------------------------ */

  var SBOX = new Uint8Array([
    0x63,0x7c,0x77,0x7b,0xf2,0x6b,0x6f,0xc5,0x30,0x01,0x67,0x2b,0xfe,0xd7,0xab,0x76,
    0xca,0x82,0xc9,0x7d,0xfa,0x59,0x47,0xf0,0xad,0xd4,0xa2,0xaf,0x9c,0xa4,0x72,0xc0,
    0xb7,0xfd,0x93,0x26,0x36,0x3f,0xf7,0xcc,0x34,0xa5,0xe5,0xf1,0x71,0xd8,0x31,0x15,
    0x04,0xc7,0x23,0xc3,0x18,0x96,0x05,0x9a,0x07,0x12,0x80,0xe2,0xeb,0x27,0xb2,0x75,
    0x09,0x83,0x2c,0x1a,0x1b,0x6e,0x5a,0xa0,0x52,0x3b,0xd6,0xb3,0x29,0xe3,0x2f,0x84,
    0x53,0xd1,0x00,0xed,0x20,0xfc,0xb1,0x5b,0x6a,0xcb,0xbe,0x39,0x4a,0x4c,0x58,0xcf,
    0xd0,0xef,0xaa,0xfb,0x43,0x4d,0x33,0x85,0x45,0xf9,0x02,0x7f,0x50,0x3c,0x9f,0xa8,
    0x51,0xa3,0x40,0x8f,0x92,0x9d,0x38,0xf5,0xbc,0xb6,0xda,0x21,0x10,0xff,0xf3,0xd2,
    0xcd,0x0c,0x13,0xec,0x5f,0x97,0x44,0x17,0xc4,0xa7,0x7e,0x3d,0x64,0x5d,0x19,0x73,
    0x60,0x81,0x4f,0xdc,0x22,0x2a,0x90,0x88,0x46,0xee,0xb8,0x14,0xde,0x5e,0x0b,0xdb,
    0xe0,0x32,0x3a,0x0a,0x49,0x06,0x24,0x5c,0xc2,0xd3,0xac,0x62,0x91,0x95,0xe4,0x79,
    0xe7,0xc8,0x37,0x6d,0x8d,0xd5,0x4e,0xa9,0x6c,0x56,0xf4,0xea,0x65,0x7a,0xae,0x08,
    0xba,0x78,0x25,0x2e,0x1c,0xa6,0xb4,0xc6,0xe8,0xdd,0x74,0x1f,0x4b,0xbd,0x8b,0x8a,
    0x70,0x3e,0xb5,0x66,0x48,0x03,0xf6,0x0e,0x61,0x35,0x57,0xb9,0x86,0xc1,0x1d,0x9e,
    0xe1,0xf8,0x98,0x11,0x69,0xd9,0x8e,0x94,0x9b,0x1e,0x87,0xe9,0xce,0x55,0x28,0xdf,
    0x8c,0xa1,0x89,0x0d,0xbf,0xe6,0x42,0x68,0x41,0x99,0x2d,0x0f,0xb0,0x54,0xbb,0x16
  ]);

  var INV_SBOX = (function () {
    var m = new Uint8Array(256), i;
    for (i = 0; i < 256; i++) m[SBOX[i]] = i;
    return m;
  })();

  function xtime(a) {
    a <<= 1;
    if (a & 0x100) a ^= 0x11b;
    return a & 0xff;
  }

  function gmul(a, b) {
    var p = 0, i;
    for (i = 0; i < 8; i++) {
      if (b & 1) p ^= a;
      var hi = a & 0x80;
      a = (a << 1) & 0xff;
      if (hi) a ^= 0x1b;
      b >>= 1;
    }
    return p & 0xff;
  }

  function subBytes(s) { for (var i = 0; i < 16; i++) s[i] = SBOX[s[i]]; }
  function invSubBytes(s) { for (var i = 0; i < 16; i++) s[i] = INV_SBOX[s[i]]; }

  /* state index = row + 4*col */
  function shiftRows(s) {
    var t = s.slice(0), r, c;
    for (r = 1; r < 4; r++)
      for (c = 0; c < 4; c++) s[r + 4 * c] = t[r + 4 * ((c + r) & 3)];
  }

  function invShiftRows(s) {
    var t = s.slice(0), r, c;
    for (r = 1; r < 4; r++)
      for (c = 0; c < 4; c++) s[r + 4 * ((c + r) & 3)] = t[r + 4 * c];
  }

  function mixColumns(s) {
    for (var c = 0; c < 4; c++) {
      var i = 4 * c;
      var a0 = s[i], a1 = s[i + 1], a2 = s[i + 2], a3 = s[i + 3];
      var t = a0 ^ a1 ^ a2 ^ a3;
      s[i]     ^= t ^ xtime(a0 ^ a1);
      s[i + 1] ^= t ^ xtime(a1 ^ a2);
      s[i + 2] ^= t ^ xtime(a2 ^ a3);
      s[i + 3] ^= t ^ xtime(a3 ^ a0);
    }
  }

  /* GF(2^8) multiply tables for the inverse MixColumns coefficients */
  var MUL9 = new Uint8Array(256), MUL11 = new Uint8Array(256),
      MUL13 = new Uint8Array(256), MUL14 = new Uint8Array(256);
  (function () {
    for (var i = 0; i < 256; i++) {
      MUL9[i] = gmul(i, 9); MUL11[i] = gmul(i, 11);
      MUL13[i] = gmul(i, 13); MUL14[i] = gmul(i, 14);
    }
  })();

  function invMixColumns(s) {
    for (var c = 0; c < 4; c++) {
      var i = 4 * c;
      var a0 = s[i], a1 = s[i + 1], a2 = s[i + 2], a3 = s[i + 3];
      s[i]     = MUL14[a0] ^ MUL11[a1] ^ MUL13[a2] ^ MUL9[a3];
      s[i + 1] = MUL9[a0]  ^ MUL14[a1] ^ MUL11[a2] ^ MUL13[a3];
      s[i + 2] = MUL13[a0] ^ MUL9[a1]  ^ MUL14[a2] ^ MUL11[a3];
      s[i + 3] = MUL11[a0] ^ MUL13[a1] ^ MUL9[a2]  ^ MUL14[a3];
    }
  }

  function expandKey(key) {
    var Nk = key.length >> 2;
    if (Nk !== 4 && Nk !== 6 && Nk !== 8) throw new Error('bad AES key length');
    var Nr = Nk + 6;
    var w = new Uint8Array(16 * (Nr + 1));
    w.set(key);
    var rcon = 1, t0, t1, t2, t3, i;
    for (i = Nk; i < 4 * (Nr + 1); i++) {
      var p = 4 * (i - 1);
      t0 = w[p]; t1 = w[p + 1]; t2 = w[p + 2]; t3 = w[p + 3];
      if (i % Nk === 0) {
        var tmp = t0;
        t0 = SBOX[t1] ^ rcon; t1 = SBOX[t2]; t2 = SBOX[t3]; t3 = SBOX[tmp];
        rcon = xtime(rcon);
      } else if (Nk > 6 && (i % Nk) === 4) {
        t0 = SBOX[t0]; t1 = SBOX[t1]; t2 = SBOX[t2]; t3 = SBOX[t3];
      }
      var q = 4 * (i - Nk);
      w[4 * i]     = w[q]     ^ t0;
      w[4 * i + 1] = w[q + 1] ^ t1;
      w[4 * i + 2] = w[q + 2] ^ t2;
      w[4 * i + 3] = w[q + 3] ^ t3;
    }
    return { w: w, Nr: Nr };
  }

  function addRoundKey(s, w, round) {
    var o = round * 16;
    for (var i = 0; i < 16; i++) s[i] ^= w[o + i];
  }

  function encryptBlock(s, ks) {
    var r;
    addRoundKey(s, ks.w, 0);
    for (r = 1; r < ks.Nr; r++) {
      subBytes(s); shiftRows(s); mixColumns(s); addRoundKey(s, ks.w, r);
    }
    subBytes(s); shiftRows(s); addRoundKey(s, ks.w, ks.Nr);
  }

  function decryptBlock(s, ks) {
    var r;
    addRoundKey(s, ks.w, ks.Nr);
    for (r = ks.Nr - 1; r >= 1; r--) {
      invShiftRows(s); invSubBytes(s); addRoundKey(s, ks.w, r); invMixColumns(s);
    }
    invShiftRows(s); invSubBytes(s); addRoundKey(s, ks.w, 0);
  }

  function normalizeOpt(v, dflt) {
    if (v === undefined || v === null) return dflt;
    if (typeof v === 'boolean') return v;
    if (typeof v === 'object' && typeof v.pad === 'boolean') return v.pad;
    if (typeof v === 'object' && typeof v.unpad === 'boolean') return v.unpad;
    return dflt;
  }

  function aesCbcEncrypt(keyBytes, ivBytes, dataBytes, opts) {
    var key = toBytes(keyBytes), iv = toBytes(ivBytes), data = toBytes(dataBytes);
    var pad = normalizeOpt(opts, true);
    if (iv.length !== 16) throw new Error('AES-CBC iv must be 16 bytes');
    var ks = expandKey(key);
    var input = data;
    if (pad) {
      var n = 16 - (data.length % 16);
      input = new Uint8Array(data.length + n);
      input.set(data, 0);
      for (var i = 0; i < n; i++) input[data.length + i] = n;
    }
    if (input.length % 16 !== 0) throw new Error('data not block-aligned');
    var out = new Uint8Array(input.length), s = new Uint8Array(16), prev = iv;
    for (var off = 0; off < input.length; off += 16) {
      for (var j = 0; j < 16; j++) s[j] = input[off + j] ^ prev[j];
      encryptBlock(s, ks);
      out.set(s, off);
      prev = out.subarray(off, off + 16);
    }
    return out;
  }

  function aesCbcDecrypt(keyBytes, ivBytes, dataBytes, opts) {
    var key = toBytes(keyBytes), iv = toBytes(ivBytes), data = toBytes(dataBytes);
    var unpad = normalizeOpt(opts, true);
    if (iv.length !== 16) throw new Error('AES-CBC iv must be 16 bytes');
    if (data.length % 16 !== 0) throw new Error('ciphertext not block-aligned');
    var ks = expandKey(key);
    var out = new Uint8Array(data.length), s = new Uint8Array(16);
    var prev = iv, off, j;
    for (off = 0; off < data.length; off += 16) {
      for (j = 0; j < 16; j++) s[j] = data[off + j];
      var cblock = data.subarray(off, off + 16);
      decryptBlock(s, ks);
      for (j = 0; j < 16; j++) out[off + j] = s[j] ^ prev[j];
      prev = cblock;
    }
    if (!unpad) return out;
    if (out.length === 0) throw new Error('empty ciphertext');
    var n = out[out.length - 1];
    if (n < 1 || n > 16 || n > out.length) throw new Error('bad PKCS#7 padding');
    for (off = out.length - n; off < out.length; off++)
      if (out[off] !== n) throw new Error('bad PKCS#7 padding');
    return out.subarray(0, out.length - n);
  }

  /* ------------------------------------------------------------------ *
   * minimal DER
   * ------------------------------------------------------------------ */

  function derRead(buf, pos) {
    if (pos + 2 > buf.length) throw new Error('DER truncated');
    var tag = buf[pos++];
    var l = buf[pos++], len;
    if (l & 0x80) {
      var k = l & 0x7f;
      if (k === 0 || k > 4) throw new Error('DER bad length');
      len = 0;
      for (var i = 0; i < k; i++) len = (len * 256) + buf[pos++];
    } else {
      len = l;
    }
    if (pos + len > buf.length) throw new Error('DER length overrun');
    return { tag: tag, start: pos, end: pos + len, body: buf.subarray(pos, pos + len) };
  }

  function derChildren(body) {
    var out = [], pos = 0;
    while (pos < body.length) {
      var t = derRead(body, pos);
      out.push(t);
      pos = t.end;
    }
    return out;
  }

  function derUint(node) {
    var b = node.body;
    if (!b.length) return B0;
    var i = 0;
    while (i < b.length - 1 && b[i] === 0) i++;
    return bytesToBigInt(b.subarray(i));
  }

  function pemToDer(pem) {
    var s = String(pem);
    var m = /-----BEGIN ([A-Z0-9 ]+)-----([\s\S]*?)-----END \1-----/.exec(s);
    if (!m) throw new Error('PEM armor not found');
    return { label: m[1], der: b64decode(m[2]) };
  }

  /* ASN.1 INTEGER contents are unsigned for RSA params; leading 0x00 is sign. */

  function seqOf(node) {
    if ((node.tag & 0x1f) !== 0x10 || !(node.tag & 0x20)) throw new Error('expected SEQUENCE');
    return derChildren(node.body);
  }

  function rsaPrivFromPkcs1(der) {
    var top = derRead(der, 0);
    var f = seqOf(top);
    if (f.length < 9) throw new Error('RSAPrivateKey too short');
    return {
      n: derUint(f[1]), e: derUint(f[2]), d: derUint(f[3]),
      p: derUint(f[4]), q: derUint(f[5]),
      dP: derUint(f[6]), dQ: derUint(f[7]), qInv: derUint(f[8]),
      bits: derUint(f[1]).toString(2).length
    };
  }

  function parsePrivateKey(pem) {
    var p = pemToDer(pem), der = p.der;
    if (p.label === 'RSA PRIVATE KEY') return rsaPrivFromPkcs1(der);
    var top = derRead(der, 0), f = seqOf(top);
    /* PKCS#8 PrivateKeyInfo: version, algorithm, OCTET STRING(privateKey), [attrs] */
    if (f.length >= 3 && (f[2].tag === 0x04) && f[1].tag === 0x30) {
      return rsaPrivFromPkcs1(f[2].body);
    }
    /* bare PKCS#1 sequence */
    return rsaPrivFromPkcs1(der);
  }

  function parsePublicKey(pem) {
    var der = pemToDer(pem).der;
    var top = derRead(der, 0), f = seqOf(top);
    /* SPKI: SEQUENCE { AlgorithmIdentifier, BIT STRING } */
    var bitstr = null;
    for (var i = 0; i < f.length; i++) if (f[i].tag === 0x03) { bitstr = f[i]; break; }
    if (!bitstr) {
      /* already PKCS#1 RSAPublicKey */
      if (f.length >= 2) return { n: derUint(f[0]), e: derUint(f[1]) };
      throw new Error('no BIT STRING in public key');
    }
    var inner = bitstr.body;
    var skip = inner[0];                 /* unused-bit count */
    var rsa = derRead(inner, 1 + skip);
    var rf = seqOf(rsa);
    if (rf.length < 2) throw new Error('RSAPublicKey too short');
    return { n: derUint(rf[0]), e: derUint(rf[1]) };
  }

  /* ------------------------------------------------------------------ *
   * RSA
   * ------------------------------------------------------------------ */

  function modPow(base, exp, mod) {
    if (mod === B1) return B0;
    var r = B1, b = base % mod;
    if (b < B0) b += mod;
    var bits = exp.toString(2);
    for (var i = 0; i < bits.length; i++) {
      r = (r * r) % mod;
      if (bits.charCodeAt(i) === 49) r = (r * b) % mod;
    }
    return r;
  }

  function modPowFixed(base, exp, mod, width) {
    return bigIntToBytes(modPow(base, exp, mod), width);
  }

  /* PKCS#1 v1.5 decryption with CRT. */
  function rsaDecryptPKCS1v15(privateKeyPem, cipherBytes) {
    var k = parsePrivateKey(privateKeyPem);
    var ct = toBytes(cipherBytes);
    var kBytes = Math.ceil(k.bits / 8);
    if (ct.length > kBytes) throw new Error('ciphertext longer than modulus (' + ct.length + ' > ' + kBytes + ')');
    var c = bytesToBigInt(ct);
    if (c >= k.n) throw new Error('ciphertext out of range');

    var em;
    if (k.p && k.q && k.dP && k.dQ && k.qInv) {
      var m1 = modPow(c % k.p, k.dP, k.p);
      var m2 = modPow(c % k.q, k.dQ, k.q);
      var h = ((m1 - m2) % k.p + k.p) % k.p;
      h = (h * k.qInv) % k.p;
      em = bigIntToBytes(m2 + h * k.q, kBytes);
    } else {
      em = bigIntToBytes(modPow(c, k.d, k.n), kBytes);
    }
    return pkcs1v15Unpad(em);
  }

  function pkcs1v15Unpad(em) {
    if (em.length < 11) throw new Error('EM too short');
    if (em[0] !== 0x00) throw new Error('EM[0] != 0');
    if (em[1] !== 0x02) throw new Error('EM[1] != 2 (not PKCS#1 v1.5 type 2)');
    var sep = -1;
    for (var i = 2; i < em.length; i++) {
      if (em[i] === 0x00) { sep = i; break; }
    }
    if (sep < 0) throw new Error('PKCS#1 v1.5 separator not found');
    if (sep < 10) throw new Error('PKCS#1 v1.5 PS too short (' + (sep - 2) + ')');
    return em.subarray(sep + 1);
  }

  function makeRng(opt) {
    if (typeof opt === 'function') return opt;
    if (opt && typeof opt.rng === 'function') return opt.rng;
    return function (n) {
      var b = new Uint8Array(n);
      for (var i = 0; i < n; i++) b[i] = (Math.random() * 256) & 0xff;
      return b;
    };
  }

  function rsaEncryptPKCS1v15(publicKeyPem, messageBytes, rngOrOpts) {
    var k = parsePublicKey(publicKeyPem);
    var msg = toBytes(messageBytes);
    var kBytes = Math.ceil(k.n.toString(2).length / 8);
    if (msg.length > kBytes - 11) throw new Error('message too long for ' + (kBytes * 8) + '-bit key');
    var rng = makeRng(rngOrOpts);
    var psLen = kBytes - 3 - msg.length;
    var em = new Uint8Array(kBytes);
    em[0] = 0x00; em[1] = 0x02;
    /* PS: psLen nonzero random bytes; pull in batches until filled */
    var got = 0, pool = [], pi = 0, draws = 0;
    while (got < psLen) {
      if (pi >= pool.length) {
        pool = toBytes(rng(psLen - got + 8));
        pi = 0;
        if (!pool.length) throw new Error('rng returned no bytes');
        if (++draws > 4096) throw new Error('rng keeps returning zero bytes');
      }
      var v = pool[pi++];
      if (v === 0) continue;
      em[2 + got] = v;
      got++;
    }
    em[2 + psLen] = 0x00;
    em.set(msg, 2 + psLen + 1);
    var c = modPow(bytesToBigInt(em), k.e, k.n);
    return bigIntToBytes(c, kBytes);
  }

  /* ------------------------------------------------------------------ *
   * export
   * ------------------------------------------------------------------ */

  var HGCrypto = {
    version: '1.0.0',
    rsaDecryptPKCS1v15: rsaDecryptPKCS1v15,
    rsaEncryptPKCS1v15: rsaEncryptPKCS1v15,
    aesCbcDecrypt: aesCbcDecrypt,
    aesCbcEncrypt: aesCbcEncrypt,
    b64decode: b64decode,
    b64encode: b64encode,
    utf8Encode: utf8Encode,
    utf8Decode: utf8Decode,
    /* small helpers, handy from a spider script */
    parsePrivateKey: parsePrivateKey,
    parsePublicKey: parsePublicKey,
    bytesToHex: bytesToHex,
    hexToBytes: hexToBytes,
    _util: { toBytes: toBytes, concatBytes: concatBytes, bytesEqual: bytesEqual, modPow: modPow }
  };

  global.HGCrypto = HGCrypto;
  if (typeof module !== 'undefined' && module && module.exports) module.exports = HGCrypto;
})(typeof globalThis !== 'undefined' ? globalThis : this);


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
var PIE_CLIENT_KEY = '-----BEGIN PRIVATE KEY-----\nMIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDY8WdvLG4ObJyq\n3Gme9HD9uVSGjWtf5c66C/eiwNJe2O0mratg0t2BuGzI6hIYrHMe/B8ffbkYH6J5\n8Ru8IqIar5eVRSTh5A0rNOzSf/S0PvOlgmPVac8vJijmpTBe1UdJm9wRLfonUZqG\njFXcRNhUFr+Fc2vZ1/wjjFz2P4iPe2wim1g5iTNdfJoUbBLg5QAF35JHGq9o3HFK\npNk/9neOKo7RXETI2nklfrPy/EOEUU8LD0dyta/idBZz5QXisptQmNZUA1l2VWyt\n/Q72zdU+KU/yQ2YvAjNmR4nd6NdXPhsKDn6n+JaAzTlWbeYfE0JWh2JcJM8GfD2d\nZ0HdZXaDAgMBAAECggEAfzIi/BrnuEtUHBW5U7psWUcFWRKnpwVdjNWhbupxLa0w\ndqISvmVD/F++YJpk29SYds1KDjOvh4Zcw/Rq14bS1qSnUpzEwDDuy6tvQwhE2D2k\nqyDlGea81B8ikgC+eIvCDVV11RjI966v1D2JxekTkVH5HuyKiqJJMiqFAthDYCPf\n5XC7eaAf6dpW9sDTisrb4woaN+4nA+NBgww7sFLs0/0OM4fSrJK5ObxUkSxc+fj9\nxWbHeaVMiyqR/OmOMl3uIf5ffLWDdiqYlxkH18WFDrqr2dkKQxjFvJ0y4kcTf8P8\nK0xfSXt5SdDiRHpQEe3hfp03zfxoRsRNzRIa3InyuQKBgQDvFv0wPoBxkUupZ8Pi\nHXgPNiqPy5iG77d+Jn9/xJ/gv1vfQoW0J0TYUYL8n1m6c9KwzpUrlJhQuAX+Ad9e\n/XLtgmG0IcAQ7rLARXsRZSMGRRPYHX9X+vuOfoKLgP/2ZIj0B1rrbZH802rjkqUW\n8x8kT71SX/OQuBbOcTYs7VAGNQKBgQDoSWuHmkXwtQ0cgVputlaLRzPiqAKkOBQG\nWL8msqNdYQ9BfFfmPwM+sFsonA4NE4T+5AR/5JysnLpsvZpC2QriIBQlgBQt9Lel\n6hzSzFVRkPkz3zTZFWRwArL2R0Hjj44897tOYW4oK1PgkLokTtgzBdvGWFS5psSm\nA7EqX3dA1wKBgAUSKcCSMss117L6Hm93TIqWDYULPmruPqgQTPf6xaFQrbQohwlx\nI8aqw+pzvsVzm25gk01fU3Oy0321sHUlTwC2mTfm11oElgn570hJKtsBMBCPX5oQ\nGrHFASPQUp15OGZkmYsQwd3o5gV5oMLB2VE917gdjN7u6JzsTcfaKK5ZAoGBAKsx\nURqDTIB2Ls+PVcK6h1XmiArHoAq/eaKjzV0L0vKDEedFaNK8JfwRpV1shb6FYEMq\ncVaAeieM6qgxpHsX0rmMwsxQdI7aCG8y90M0bLzMh5z/J8fFyFv04Ug4hAreRgoQ\n2e6OR807AXpDr6wKn7Qa4Tpw5yBcFBn2Jp//87fJAoGBAOh0SzrlxvzrZauwVjqd\nveTajwPGQlmYjNPW9XIDFcO1YhR3HCnpl47RYrXT0lvuz0U+OcgeWEoDW+okw1f2\nuX2xSdwhiNiiOFtOQiGmrEumH2wgHg2rA8u3xOza+OxPpB7bDSFe4DsqlnRVXzDm\n3DtD0lwzJVe52++V5NhE+/E8\n-----END PRIVATE KEY-----';

// 服务端公钥：封请求体用。原文 assets/flutter_assets/assets/key/service_public.key。
var PIE_SERVICE_KEY = '-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA2WxnqdfalxP45kvAk+pz\nkabw4T9zk5OvbCgPBpb2KEqMV3ZF9xyPeMaxPZnUXzicai1j/1I3vriSSCfvZ9pN\nxrAh/P6N3NidzlvWbmvjL3gBzIeQXeWbBMxgUzVYGAE6vxqewmZatxUF0Venh3k8\n9hjdU/pP0Vsqxp3mXOKpR5T9UrOQb9eFhndOJ4mVYxsHQMEAs+H8mn3IA9w++HdE\n/rZRDdVGsCvUZsgecI0tV4qut1la6oPt6q/27eXUqV/IBdTOaowvU05YtFIRpb66\n078HGkkmt/qJLNmveIH5vHRpeEqA3twE9L7OgIZJMG2uruoxprH2ysM5YsZIYkCr\nWwIDAQAB\n-----END PUBLIC KEY-----';

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
