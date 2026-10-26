#!/usr/bin/env bash
#
# AIIvideo —— 构建入口
#
#   ./scripts/build.sh            出 release APK（默认）
#   ./scripts/build.sh debug      出 debug APK
#   ./scripts/build.sh check      只验编译 + 资源 + assets，秒级
#   ./scripts/build.sh test       跑单元测试 + 前端降级自检 + JS 钩子，秒级
#   ./scripts/build.sh jscheck    前端钩子的离线回归（js-errors-check.js）
#   ./scripts/build.sh install    构建 → 装真机 → 冷启动 → 截图
#   ./scripts/build.sh verify     清空后从零构建，并断言 APK 内容
#
# 环境变量：
#   SERIAL=xxx    指定设备（默认取唯一在线设备）
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
cd "$ROOT"

# 日志一律走 stderr —— stdout 只留给 APK 路径，免得 $(...) 抓到构建日志。
say() { printf '\033[1;36m==>\033[0m %s\n' "$*" >&2; }
ok()  { printf '\033[1;32m ok\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31mERROR\033[0m %s\n' "$*" >&2; exit 1; }

PW="./gradlew"
LAST_APK=".build-last-apk"

doctor() {
  command -v python3 >/dev/null || die "缺 python3（gen-web-config.py 与 check-apk.py 要用）"
  # node 现在是必需的，不再是「只有跑 jscheck 才要」：前端降级（transpileAssets）、
  # ES2017 解析断言、js-errors 回归，三条都在构建链上。缺了就直接停。
  command -v node >/dev/null || die "缺 node（transpile-assets.js / js-target-check.js 要用）"
  [ -f local.properties ] || die "缺 local.properties（写 sdk.dir=$HOME/Library/Android/sdk）"
  [ -x "$PW" ] || die "缺 gradlew"
  ok "工具链就绪"
}

# 前端产物目录。sourceSets 里配的就是这个，见 app/build.gradle.kts 的注释。
WEB_DIR="app/build/generated/assets/webview"

cmd_webassets() {
  say "前端降级 + ES2017 断言"
  "$PW" :app:transpileAssets -q
  [ -f "$WEB_DIR/app.js" ] || die "降级没产出：$WEB_DIR/app.js 不在"
  ok "产物 $WEB_DIR"
}

cmd_build() {
  local variant="${1:-release}" task
  case "$variant" in
    release) task=":app:assembleRelease" ;;
    debug)   task=":app:assembleDebug" ;;
    *)       die "未知变体：$variant（release / debug）" ;;
  esac
  local t0=$SECONDS
  say "构建 $variant"
  "$PW" "$task"
  local apk
  apk=$(find "app/build/outputs/apk/$variant" -name '*.apk' 2>/dev/null | head -1)
  [ -n "$apk" ] || die "构建成功但找不到 APK"
  printf '%s\n' "$apk" > "$LAST_APK"
  local size
  size=$(stat -f%z "$apk" 2>/dev/null || stat -c%s "$apk")
  ok "$apk  $(awk -v s="$size" 'BEGIN{printf "%.1f MB", s/1048576}')  用时 $((SECONDS - t0))s"
}

cmd_check() {
  say "编译检查"
  "$PW" :app:compileDebugJavaWithJavac :app:processDebugResources :app:mergeDebugAssets
  ok "编译 / 资源 / assets 全过"
}

cmd_test() {
  say "单元测试"
  "$PW" :app:testDebugUnitTest --rerun-tasks
  python3 - <<'PYEOF'
import glob, xml.etree.ElementTree as ET
total = failed = skipped = 0
for path in sorted(glob.glob("app/build/test-results/testDebugUnitTest/*.xml")):
    root = ET.parse(path).getroot()
    total += int(root.get("tests"))
    failed += int(root.get("failures")) + int(root.get("errors"))
    skipped += int(root.get("skipped"))
    print(f"  {root.get('name').split('.')[-1]:26} {root.get('tests'):>3} 用例")
print(f"  合计 {total} 用例，失败 {failed}，跳过 {skipped}")
raise SystemExit(1 if failed else 0)
PYEOF
  ok "测试通过"
}

# 前端钩子的离线回归。和 JUnit 分开是因为它测的是 assets 里的 JS：
# 那三条约束（trace 键名、不走桥、不带 source）违反后都是静默失效，
# Java 测试碰不到，只有真机出故障才看得出来。见 js-errors-check.js 文件头。
#
# **必须对着生成目录跑。** 包里那份是 Babel 降级后的输出，不是 src/main/assets
# 里这份；验源码等于没验。
cmd_jscheck() {
  say "JS 钩子离线回归"
  command -v node >/dev/null || die "缺 node（js-errors-check.js 要用）"
  [ -f "$WEB_DIR/js-errors.js" ] || cmd_webassets
  node scripts/js-errors-check.js --file "$WEB_DIR/js-errors.js" --html "$WEB_DIR/app.html"
  ok "JS 钩子通过"
}

cmd_install() {
  cmd_build release
  local apk; apk=$(cat "$LAST_APK")
  local serial="${SERIAL:-}"
  if [ -z "$serial" ]; then
    serial=$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')
  fi
  [ -n "$serial" ] || die "没有在线设备"
  say "安装到 $serial"
  adb -s "$serial" install -r "$apk"
  adb -s "$serial" shell am force-stop com.huangguo.mobile
  adb -s "$serial" logcat -c 2>/dev/null || true
  adb -s "$serial" shell am start -n com.huangguo.mobile/.MainActivity >/dev/null
  sleep 8
  local pid
  pid=$(adb -s "$serial" shell pidof com.huangguo.mobile | tr -d '\r')
  [ -n "$pid" ] || die "启动后进程不在（看 adb logcat -b crash）"
  ok "进程存活 pid=$pid"
  local crashes
  crashes=$(adb -s "$serial" logcat -b crash -d 2>/dev/null | grep -c 'huangguo' || true)
  [ "$crashes" = "0" ] || die "crash buffer 里有 $crashes 条本包记录"
  ok "无崩溃"
  mkdir -p .build
  local shot=".build/verify-$(date +%H%M%S).png"
  adb -s "$serial" exec-out screencap -p > "$shot"
  ok "截图 $shot"
}

cmd_verify() {
  doctor
  say "清理（证明不是增量缓存的假象）"
  rm -rf app/build .gradle
  cmd_test
  cmd_webassets
  cmd_jscheck
  cmd_build release
  local apk; apk=$(cat "$LAST_APK")
  say "断言产物"
  python3 scripts/check-apk.py "$apk"
  ok "verify 通过"
}

case "${1:-build}" in
  check)   doctor; cmd_check ;;
  test)    doctor; cmd_test; cmd_webassets; cmd_jscheck ;;
  jscheck) doctor; cmd_webassets; cmd_jscheck ;;
  build)   doctor; cmd_build release ;;
  debug)   doctor; cmd_build debug ;;
  install) doctor; cmd_install ;;
  verify)  cmd_verify ;;
  *)       sed -n '3,12p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
