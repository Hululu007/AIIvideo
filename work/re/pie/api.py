import json, base64, os, urllib.request, urllib.error, sys
from cryptography.hazmat.primitives import serialization, padding as sympad
from cryptography.hazmat.primitives.asymmetric import padding as apad
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

import os
HERE = os.path.dirname(os.path.abspath(__file__))
CLIENT_KEY = os.path.join(HERE, 'assets/client_private.key')
SERVER_PUB = os.path.join(HERE, 'assets/service_public.key')
priv = serialization.load_pem_private_key(open(CLIENT_KEY,'rb').read(), password=None)
pub = serialization.load_pem_public_key(open(SERVER_PUB,'rb').read())
BASE='https://api.3hvkdg.com'

def dec_resp(d):
    ek=base64.b64decode(d['encrypt_key']); ed=base64.b64decode(d['encrypt_data'])
    meta=json.loads(priv.decrypt(ek, apad.PKCS1v15()))
    c=Cipher(algorithms.AES(base64.b64decode(meta['key'])), modes.CBC(base64.b64decode(meta['iv']))).decryptor()
    pt=c.update(ed)+c.finalize()
    try:
        u=sympad.PKCS7(128).unpadder(); pt=u.update(pt)+u.finalize()
    except Exception: pass
    return json.loads(pt.decode('utf-8','replace'))

def enc_req(obj, key=None):
    key = key or os.urandom(16); iv = os.urandom(16)
    p = sympad.PKCS7(128).padder(); data = p.update(json.dumps(obj).encode())+p.finalize()
    c=Cipher(algorithms.AES(key), modes.CBC(iv)).encryptor(); ct=c.update(data)+c.finalize()
    meta=json.dumps({"key":base64.b64encode(key).decode(),"iv":base64.b64encode(iv).decode()}).encode()
    ek=pub.encrypt(meta, apad.PKCS1v15())
    return {"encrypt_key":base64.b64encode(ek).decode(),"encrypt_data":base64.b64encode(ct).decode()}

def call(path, body, headers=None, base=None, raw=False):
    h={'Content-Type':'application/json','User-Agent':'okhttp/4.12.0'}
    if headers: h.update(headers)
    payload = json.dumps(body).encode() if raw else json.dumps(enc_req(body)).encode()
    req=urllib.request.Request((base or BASE)+path, data=payload, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=15) as r: txt=r.read().decode('utf-8','replace')
    except urllib.error.HTTPError as e: return {'_http':e.code}
    except Exception as e: return {'_err':str(e)[:80]}
    try:
        j=json.loads(txt)
        if isinstance(j,dict) and 'encrypt_data' in j: return dec_resp(j)
        return j
    except Exception: return {'_raw':txt[:300]}

if __name__=='__main__':
    p=sys.argv[1]; b=json.loads(sys.argv[2]) if len(sys.argv)>2 else {}
    print(json.dumps(call(p,b), ensure_ascii=False)[:1200])
