"""签名的黄金向量。

`ReportTest.java` 里那两条断言注释写着「与 server/track 用同一个向量。服务端
那边的 test_events.py 里是一模一样的这两个值 —— 改任何一边，这条测试都会红」。

那个文件从来没有存在过。这个模块把它坐实：两边用同一个 secret、同一个 body，
算出同一个 hex。改了签名算法，这边的断言先红。
"""
from __future__ import annotations

import hashlib
import hmac
import unittest


def hmac_hex(secret: str, body: bytes) -> str:
    return hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()


class SignatureVectorTest(unittest.TestCase):
    def test_与安卓侧同一个向量(self) -> None:
        # 与 ReportTest.签名与空体都对得上服务端的向量 逐字一致。
        self.assertEqual(
            "179bf20a8b9040a32368814a68b0dc270823b5968498e0a73796c4202708ed8d",
            hmac_hex("test-secret", b'{"a":1}'),
        )
        self.assertEqual(
            "a41bc6d81d6413576ae0994995e0ad89a416ec97389515c3604f47722122eeeb",
            hmac_hex("test-secret", b""),
        )


if __name__ == "__main__":
    unittest.main()
