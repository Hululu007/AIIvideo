"""限流：单 IP 每分钟 RATE_LIMIT 次，滑动窗口。

放内存里是有意的：只有一个进程、数据规模只有几百个设备，Redis 是给更大的系统
准备的。代价是「重启清零」和「多副本不共享」—— 这个服务两条都不需要，写在这里
免得以后有人以为是漏了。

窗口用单调时钟，机器时间被 NTP 往前拨也不会放出超额请求。
"""
from __future__ import annotations

import threading
import time
from collections import deque
from typing import Deque

from .config import RATE_LIMIT, RATE_WINDOW

_lock = threading.Lock()
_hits: dict[str, Deque[float]] = {}
_last_sweep = 0.0
_SWEEP_EVERY = 300.0


def hit(ip: str) -> tuple[bool, int, float]:
    """记一次。返回 (是否放行, 本窗口已用次数, 还要等几秒)。

    被人拿几十万个源 IP 刷时，字典会长大 —— 每 5 分钟扫一遍，把过期的键删掉。
    """
    global _last_sweep
    now = time.monotonic()
    with _lock:
        window = _hits.setdefault(ip, deque())
        cutoff = now - RATE_WINDOW
        while window and window[0] <= cutoff:
            window.popleft()

        if now - _last_sweep >= _SWEEP_EVERY:
            _last_sweep = now
            for key in [key for key, hits in _hits.items()
                        if not hits or hits[-1] <= cutoff]:
                _hits.pop(key, None)

        used = len(window)
        if used >= RATE_LIMIT:
            retry_after = max(window[0] + RATE_WINDOW - now, 0.0)
            return False, used, retry_after
        window.append(now)
        return True, used + 1, 0.0
