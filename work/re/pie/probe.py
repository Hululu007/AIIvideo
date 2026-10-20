import json, base64, sys, urllib.request, urllib.error
from cryptography.hazmat.primitives import serialization, hashes, padding as sympad
from cryptography.hazmat.primitives.asymmetric import padding as apad
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
KEY='/Users/zhaoruizhe/Desktop/project/T/hg/work/re/pie/assets/client_private.key'
priv = serialization.load_pem_private_key(open(KEY,'rb').read(), password=None)
def dec(d):
    ek=base64.b64decode(d['encrypt_key']); ed=base64.b64decode(d['encrypt_data'])
    meta=json.loads(priv.decrypt(ek, apad.PKCS1v15()))
    c=Cipher(algorithms.AES(base64.b64decode(meta['key'])), modes.CBC(base64.b64decode(meta['iv']))).decryptor()
    pt=c.update(ed)+c.finalize()
    try:
        u=sympad.PKCS7(128).unpadder(); pt=u.update(pt)+u.finalize()
    except Exception: pass
    return pt.decode('utf-8','replace')
def post(base, path, body, timeout=8):
    req=urllib.request.Request(base+path, data=json.dumps(body).encode(), headers={'Content-Type':'application/json','User-Agent':'okhttp/4.12.0'})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw=r.read().decode('utf-8','replace')
    except urllib.error.HTTPError as e:
        return ('HTTP%d'%e.code, '')
    except Exception as e:
        return ('ERR', str(e)[:60])
    try:
        j=json.loads(raw)
        if isinstance(j,dict) and 'encrypt_data' in j: return ('200', dec(j))
        return ('200', raw[:300])
    except Exception:
        return ('200-nonjson', raw[:200])
if __name__=='__main__':
    base=sys.argv[1]; names=sys.argv[2:]
    for p in names:
        s,b=post(base,p,{})
        print(f'{p:45s} {s:8s} {b[:180]}')
