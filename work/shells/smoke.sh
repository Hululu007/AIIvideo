#!/usr/bin/env bash
#
# AIIvideo —— 冒烟测试
#
#   ./smoke.sh                 装当前 cz-final.apk、冷启动、断言、截图存档
#   ./smoke.sh --no-install    跳过安装，直接测设备上已有的包
#   ./smoke.sh --serial XXXX   指定设备（默认取唯一在线设备）
#   ./smoke.sh --wait 45       数据层等待上限秒数（默认 30）
#
# 断言四条：
#   1. 应用进程在 N 秒后仍存活（没崩在启动）
#   2. 数据层被走到（logcat 里出现 [api] 行，ok 或 FAIL 都算 —— 断网时 31ms 快速失败也是通过）
#   3. logcat -b crash 里没有本包的 FATAL EXCEPTION
#   4. 截图不是纯色（白屏 / 黑屏在本项目的主要故障模式里排第一）
#
# 退出码：0 全过；1 有断言失败；2 环境问题（无设备等）
#
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

PKG="com.huangguo.mobile"
APK="cz-final.apk"
SHOT_DIR="$HERE/.build/smoke"
SERIAL=""
DO_INSTALL=1
WAIT=30

while [ $# -gt 0 ]; do
  case "$1" in
    --serial)     SERIAL="$2"; shift 2 ;;
    --wait)       WAIT="$2"; shift 2 ;;
    --no-install) DO_INSTALL=0; shift ;;
    -h|--help)    sed -n '3,16p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)            echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

say()  { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m ok\033[0m %s\n' "$*"; }
bad()  { printf '\033[1;31mFAIL\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31mERROR\033[0m %s\n' "$*" >&2; exit 2; }

# ---- 设备 -------------------------------------------------------------------
if [ -z "$SERIAL" ]; then
  SERIAL=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  SERIAL=$(printf '%s\n' "$SERIAL" | head -1)
fi
[ -n "$SERIAL" ] || die "没有在线设备。插上手机开 USB 调试，或先启动模拟器。"
ADB=(adb -s "$SERIAL")
"${ADB[@]}" get-state >/dev/null 2>&1 || die "设备 $SERIAL 不可用"
say "设备 ${SERIAL}  $("${ADB[@]}" shell getprop ro.product.model | tr -d '\r')  Android $("${ADB[@]}" shell getprop ro.build.version.release | tr -d '\r')"

FAILED=0

# ---- 安装 -------------------------------------------------------------------
if [ "$DO_INSTALL" = "1" ]; then
  [ -f "$APK" ] || die "$APK 不存在，先跑 ./build.sh fast"
  say "安装 ${APK}"
  "${ADB[@]}" install -r "$APK" 2>&1 | tail -2
fi

"${ADB[@]}" shell pm path "$PKG" >/dev/null 2>&1 || die "$PKG 未安装"

# ---- 冷启动 -----------------------------------------------------------------
say "清日志并冷启动"
"${ADB[@]}" logcat -c 2>/dev/null || true
"${ADB[@]}" shell am force-stop "$PKG"
sleep 1
"${ADB[@]}" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

# ---- 断言 1+2：等数据层 -----------------------------------------------------
say "等待数据层（上限 ${WAIT}s）"
hit=0
for i in $(seq 1 "$WAIT"); do
  if "${ADB[@]}" logcat -d 2>/dev/null | grep -q '\[api\] '; then
    hit=1; break
  fi
  sleep 1
done
if [ "$hit" = "1" ]; then
  line=$("${ADB[@]}" logcat -d 2>/dev/null | grep '\[api\] ' | tail -1 | sed 's/.*\[api\]/[api]/' | cut -c1-90)
  ok "数据层已走到：${line}"
elif "${ADB[@]}" logcat -d 2>/dev/null | grep -q "\[source\] 未配源"; then
  # 出厂状态：用户还没配源，设置页挡在启动链前面，一个请求都不会发。
  # 没有 [api] 行不是失败，是设计如此 —— 把它跟「前端没起来」分开报。
  ok "未配源 —— 停在设置页（出厂状态的正常表现）"
else
  bad "等了 ${WAIT}s 没看到任何 [api] 行 —— 前端可能没起来"
  FAILED=1
fi

# ---- 断言 1：进程存活 -------------------------------------------------------
if "${ADB[@]}" shell pidof "$PKG" >/dev/null 2>&1; then
  ok "进程存活 pid=$("${ADB[@]}" shell pidof "$PKG" | tr -d '\r')"
else
  bad "进程不在了 —— 启动阶段崩了"
  FAILED=1
fi

# ---- 断言 4：截图不是纯色 ---------------------------------------------------
mkdir -p "$SHOT_DIR"
SHOT="$SHOT_DIR/$(date +%Y%m%d-%H%M%S).png"
"${ADB[@]}" exec-out screencap -p > "$SHOT" 2>/dev/null
if [ -s "$SHOT" ]; then
  verdict=$(python3 - "$SHOT" <<'PY'
import sys
try:
    from PIL import Image, ImageStat
except Exception:
    print("skip"); raise SystemExit
im = Image.open(sys.argv[1]).convert("RGB").resize((160, 356))
st = ImageStat.Stat(im)
sd = sum(st.stddev) / 3
colors = len(im.getcolors(maxcolors=1 << 24) or [])
print(f"{sd:.1f} {colors}")
PY
)
  if [ "${verdict%% *}" = "skip" ]; then
    ok "截图已存 $SHOT（无 PIL，跳过纯色判定）"
  else
    sd="${verdict%% *}"; colors="${verdict##* }"
    if python3 -c "import sys; sys.exit(0 if float('$sd') > 8 else 1)"; then
      ok "截图非纯色 stddev=${sd} 色彩数=${colors}  → $SHOT"
    else
      bad "截图近似纯色 stddev=${sd} 色彩数=${colors} —— 疑似白屏/黑屏  → $SHOT"
      FAILED=1
    fi
  fi
else
  bad "截图失败"
  FAILED=1
fi

# ---- 断言 3：无崩溃 ---------------------------------------------------------
CRASH=$("${ADB[@]}" logcat -b crash -d 2>/dev/null | grep -c "$PKG" || true)
if [ "${CRASH:-0}" = "0" ]; then
  ok "crash buffer 里本包 0 条"
else
  bad "crash buffer 里本包 $CRASH 条："
  "${ADB[@]}" logcat -b crash -d 2>/dev/null | grep -A12 "$PKG" | head -30
  FAILED=1
fi

# ---- 结论 -------------------------------------------------------------------
echo
if [ "$FAILED" = "0" ]; then
  printf '\033[1;32m冒烟通过\033[0m  %s\n' "$SHOT"
  exit 0
else
  printf '\033[1;31m冒烟失败\033[0m  完整日志：\n'
  printf '  adb -s %s logcat -d | grep -E "AIIvideo|\\[api\\]"\n' "$SERIAL"
  exit 1
fi
