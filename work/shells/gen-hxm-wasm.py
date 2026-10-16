#!/usr/bin/env python3
"""把 AGE动漫 播放层的 wasm 转成 assets/hxm/hxm.wasm.js。

    python3 gen-hxm-wasm.py <jx.wuzhoupai.com.wasm>

为什么是 base64 内联而不是一个 .wasm 文件：
  · 资源处理器（HotUpdateManager.resource）按**扩展名**分派 MIME，.wasm 不在它的表里
  · 走 <script> 就顺带绕开了页面 CSP 的 connect-src —— 那条我们保持 'none' 不动

为什么这个文件不进 patches/：
  那是**别人的代码**（TinyGo 编的，二进制里还留着 1.16 的 goroot 路径），
  仓库是公开的，不适合把它搬进去。build.sh 的 sync_patches 已排除它。
  拿不到它的时候构建仍然能过，只是 AGE动漫 那条线播不了 —— 见 README 的说明。

取法：站点播放页会 fetch
    https://xcdn.khfed.com:8443/jx/20230723ver/Play/jx.wuzhoupai.com.wasm?v=20260512
sha256 记在 docs/agedm-播放层逆向/harness/wasm.sha256。
"""
import base64
import hashlib
import pathlib
import sys

HEAD = """/* 生成文件，不要手改 —— 用 work/shells/gen-hxm-wasm.py <wasm> 生成。
 *
 * AGE动漫 播放层那个 TinyGo wasm 的 base64 形态。原始文件与 sha256 见
 * docs/agedm-播放层逆向/harness/wasm.sha256。
 *
 * 为什么内联成 .js：资源处理器按扩展名分派 MIME，.wasm 不在它的表里；走 <script>
 * 也就顺带绕开了 CSP 的 connect-src（那条保持 'none' 不动）。
 * 为什么不在仓库里：那是别人的代码，仓库是公开的。
 */
"""


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2

    src = pathlib.Path(sys.argv[1])
    raw = src.read_bytes()
    dest = pathlib.Path(__file__).resolve().parent / "cz_repack/assets/hxm/hxm.wasm.js"
    dest.write_text(HEAD + "window.HXM_WASM_B64 = '" + base64.b64encode(raw).decode() + "';\n")

    print(f"wasm {len(raw)} 字节  sha256 {hashlib.sha256(raw).hexdigest()[:16]}…")
    print(f"→ {dest}  {dest.stat().st_size} 字节")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
