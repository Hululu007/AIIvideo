#!/usr/bin/env bash
#
# AIIvideo —— 打包脚本（壳改造自 橙子短剧 v0.3.15）
#
#   ./build.sh fast          从现有 cz_repack/ 工作树打包（日常迭代：改完 assets/ 或 smali/ 直接跑）
#   ./build.sh clean         从原始样本解包 + 套用 patches/ 后打包（可复现路径，不碰工作树）
#   ./build.sh sync-patches  以「基准解包 vs 工作树」的文件级差异刷新 patches/（改完工作树跑这个）
#   ./build.sh verify        重建 clean 树，与工作树逐文件比对（证明补丁集完整且无多余改动）
#   ./build.sh release       打包 → 证明补丁集 → 发补丁 → 再打包 → 出发布包（发版用这个，别手动 cp）
  ./build.sh doctor        只检查工具链
#
# 环境变量：
#   INSTALL=1     打包成功后 adb install -r（SERIAL=xxx 指定设备）
#   NO_SIGN=1     跳过签名，只出 cz-mod.apk（用于比对 dex / resources）
#
# 为什么 patches/ 不用 mtime 推导：cp -p 会把新文件的 mtime 伪装成解包时间，
# 上一版就是这么漏掉 vendor/hls.min.js 的。文件级 diff 才是唯一可信来源。
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

# ---- 固定路径 ---------------------------------------------------------------
SDK_BT="${ANDROID_BUILD_TOOLS:-$HOME/Library/Android/sdk/build-tools/34.0.0}"
ZIPALIGN="$SDK_BT/zipalign"
APKSIGNER="$SDK_BT/apksigner"

BASE_APK="chengzi-v0.3.15.apk"      # 原始样本，唯一输入
WORK_TREE="cz_repack"               # 日常迭代的工作树
PATCHES="patches"                   # 与工作树同构的补丁集
DELETED_LIST="$PATCHES/.deleted"    # 相对基准树被删掉的文件（一行一个）
KEYSTORE="cz.keystore"
KS_PASS="123456"
KEY_PASS="123456"

BUILD_DIR=".build"
BASELINE="$BUILD_DIR/baseline"      # 基准解包，永不打补丁
CLEAN_TREE="$BUILD_DIR/clean-tree"  # = 基准 + 补丁
OUT_UNSIGNED="cz-mod.apk"
OUT_ALIGNED="cz-aligned.apk"
OUT_SIGNED="cz-final.apk"

IGNORE=(--exclude=build --exclude=classes5.dex --exclude=site-web.js --exclude=.DS_Store \
        --exclude=hxm.wasm.js)   # apktool 产物 + 由 java-src 现编的 dex + 别人的 wasm 转出的 base64

# ---- Java 侧代码 -----------------------------------------------------------
# SiteRepository.fetch 原来那 289 行 HttpURLConnection 逻辑搬到了 Java（要自定义
# DNS + 保留 SNI，HttpURLConnection 两样都做不到）。手写等价的 smali 太容易错，
# 所以走「Java 源码 → d8 → classes5.dex」这条路。
#
# classes5.dex 是**构建产物**，不进补丁集：补丁集里放的是 java-src/ 的源码。
JAVA_SRC="$WORK_TREE/../java-src"
JAVA_STUBS="$WORK_TREE/../java-stubs"
ANDROID_JAR="${ANDROID_JAR:-$HOME/Library/Android/sdk/platforms/android-34/android.jar}"
D8="$SDK_BT/d8"
OKHTTP_VER="4.10.0"              # 必须和 APK 里打包的版本一致（apktool 树里能看到 okhttp/4.10.0）
JAVA_DEX_REL="unknown/classes5.dex"  # apktool 的 unknown/ 原样拷进 APK 根目录，所以这里是 classes5.dex

ASSETS_JS=(
  "$WORK_TREE/assets/app.js"
  "$WORK_TREE/assets/feed-player.js"
  "$WORK_TREE/assets/hot-runtime.js"
  "$WORK_TREE/assets/card-transition.js"
  "$WORK_TREE/assets/runtime.js"
  "$WORK_TREE/assets/cover.js"
  "$WORK_TREE/assets/adapters.js"
  "$WORK_TREE/assets/setup.js"
)

say()  { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m ok\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31mERROR\033[0m %s\n' "$*" >&2; exit 1; }

doctor() {
  local missing=0
  for t in apktool java node; do
    command -v "$t" >/dev/null 2>&1 || { printf '  缺: %s\n' "$t"; missing=1; }
  done
  [ -x "$ZIPALIGN" ]  || { printf '  缺: %s\n' "$ZIPALIGN"; missing=1; }
  [ -x "$APKSIGNER" ] || { printf '  缺: %s\n' "$APKSIGNER"; missing=1; }
  [ -f "$BASE_APK" ]  || { printf '  缺: %s\n' "$BASE_APK"; missing=1; }
  [ -f "$KEYSTORE" ]  || { printf '  缺: %s\n' "$KEYSTORE"; missing=1; }
  [ "$missing" -eq 0 ] || die "工具链不完整"
  ok "工具链完整：apktool $(apktool --version 2>/dev/null | head -1) / build-tools $(basename "$SDK_BT")"
}

ensure_baseline() {
  if [ ! -d "$BASELINE" ]; then
    # 这个样本不进仓库（它是别人的应用，提交等于二次分发），所以克隆下来
    # 第一件事大概率就是撞这里。apktool 自己的报错是一句 "Can not open input
    # file"，看不出要干什么 —— 直接把该做什么写出来。
    if [ ! -f "$BASE_APK" ]; then
      die "找不到原始样本 ${BASE_APK}

  这个仓库只包含「相对样本的改动」（${PATCHES}/，24 个文件），
  样本本身要你自备一份放到上面那个路径。

  没有样本就只能走 fast（需要已存在的 $WORK_TREE/），
  clean / verify 这两条可复现路径用不了。"
    fi
    say "解包基准树 ${BASE_APK} → ${BASELINE}（只需一次）"
    mkdir -p "$BUILD_DIR"
    apktool d -f -q -o "$BASELINE" "$BASE_APK" >/dev/null
    ok "基准树就绪：$(find "$BASELINE" -type f | wc -l | tr -d ' ') 个文件"
  fi
}

# 配置自检：单一来源之后，剩下的风险全是「配置内部对不上」。
# 这几类错的共同点是**静默** —— 不崩、不报错，只是某个入口点了没反应，
# 或者某些标签凭空消失。所以放在打包时拦，别等真机上肉眼发现。
check_config() {
  local tree="$1"
  say "配置自检"
  python3 - "$tree/assets/site-default.json" "$tree/assets/adapters.js" <<'PYEOF'
import json, re, sys

conf = json.load(open(sys.argv[1], encoding='utf-8'))
adapters = open(sys.argv[2], encoding='utf-8').read()
web = conf.get('web') or {}
ui = web.get('ui') or {}
bad = []

# ① categoryIds 里的 slug 必须在 categories 里有对应 cate_id。
#    只加一边的话，categoryId() 会算出 undefined，apiPathFor 返回 null，
#    请求落到 Java 桥撞上已抛异常的桩 —— 表现是点了没反应。
cats = set((web.get('categories') or {}).keys())
for tab, slugs in (ui.get('categoryIds') or {}).items():
    for slug in slugs:
        if slug not in cats:
            bad.append(f"  ui.categoryIds['{tab}'] 里的 {slug!r} 在 web.categories 里没有对应项")

# ② paths 的占位符必须是 adapters.js 真的会填的那几个。
#    写成 {Id} 这种的话，占位符没人替换，URL 里会原样带上 {Id} —— 服务端 404，
#    而前端只看到一次 FAIL，很容易当成网络抖动。
FILLED = {'id', 'episode', 'keyword', 'slug', 'cateId', 'page', 'size'}
for action, tmpl in (web.get('paths') or {}).items():
    for name in re.findall(r'\{(\w+)\}', tmpl):
        if name not in FILLED:
            bad.append(f"  web.paths['{action}'] 里的 {{{name}}} 不是可替换的占位符（可用：{sorted(FILLED)}）")

# ③ adaptTags 产出的 group（映射表的值）必须都在 tagGroups 里，否则那些标签渲染时被丢掉。
#    分组映射表在 adapters.js 里，这里从源码里读出来对齐。
mapping = re.search(r'TAG_GROUP_BY_CODE\s*=\s*\{(.*?)\}', adapters, re.S)
if mapping:
    # 取冒号右边那个值（分组名），不是左边的来源 code —— 左边是 appearance /
    # chigua-heiliao 这类站点编码，右边才是 renderTags 认的组名。
    produced = set(re.findall(r":\s*['\"]([a-zA-Z-]+)['\"]", mapping.group(1)))
else:
    produced = set()
declared = {g[0] for g in (ui.get('tagGroups') or []) if isinstance(g, list) and g}
for group in sorted(produced - declared):
    bad.append(f"  adapters.js 会产出 group={group!r}，但 ui.tagGroups 里没有这个分组 —— 那些标签会被丢掉")

# ④ popularTags / publicPopularTags 的每一项必须是 [slug, 显示名] 两元组。
for key in ('popularTags', 'publicPopularTags'):
    for i, item in enumerate(ui.get(key) or []):
        if not (isinstance(item, list) and len(item) == 2):
            bad.append(f"  ui.{key}[{i}] 不是 [slug, 显示名] 两元组：{item!r}")

# ④a web.shapes —— 形状映射。可选，但写了就得是认识的动作用名，
#     否则拼错的动作名会静默不生效（配置看着是对的，界面永远是空的）。
#     Java 那边的 UserSource.validate() 对用户配置做同一件事，这里是给内置那份兜底。
ACTION_NAMES = {'home', 'list', 'detail', 'episode', 'search', 'category',
                'recommend', 'newest', 'tags', 'tag-list'}
shapes = web.get('shapes')
if shapes is not None:
    if not isinstance(shapes, dict):
        bad.append('web.shapes 不是对象')
    else:
        for action, spec in shapes.items():
            if action == 'envelope':
                continue
            if action not in ACTION_NAMES:
                bad.append(f'web.shapes 里有个不认识的动作用作键：{action}')
                continue
            if not isinstance(spec, dict):
                bad.append(f'web.shapes.{action} 不是对象')
                continue
            for shape_key, value in spec.items():
                ok = isinstance(value, str) and bool(value)
                if not ok and isinstance(value, dict):
                    div = value.get('div')
                    ok = isinstance(div, list) and len(div) == 2
                if not ok:
                    bad.append(f'web.shapes.{action}.{shape_key} 既不是取值路径也不是 {{div:[a,b]}}')

# ④b 底部导航（ui.tabs）和那几张按 key 索引的表必须对得上。
#     这一组错的共同点是「点了没反应」或者「入口挂着别人的名字」：
#       · tabs 有、categoryIds 没有 → 点进去算出 undefined，请求落到 Java 桥撞桩
#       · categoryIds 有、tabs 没有 → 那份分类永远进不去，等于死配置
#       · key 重复 → 两个入口同一份数据（Apple CMS 那版就是这么来的，这轮才修掉）
tabs = ui.get('tabs') or []
if tabs:
    keys = [t.get('key') for t in tabs if isinstance(t, dict)]
    for i, t in enumerate(tabs):
        if not isinstance(t, dict) or not t.get('key'):
            bad.append(f"  ui.tabs[{i}] 没有 key：{t!r}")
    for k in sorted({k for k in keys if keys.count(k) > 1}):
        bad.append(f"  ui.tabs 里 key={k!r} 出现了不止一次 —— 两个入口会指向同一份数据")
    cat_ids = ui.get('categoryIds') or {}
    for k in keys:
        if k not in cat_ids:
            bad.append(f"  ui.tabs 有 key={k!r}，但 ui.categoryIds 里没有它 —— 点进去是空的")
    for k in cat_ids:
        if k not in keys:
            bad.append(f"  ui.categoryIds 有 key={k!r}，但 ui.tabs 里没有对应的入口 —— 这份分类没人能进去")
    for k, table in (('categoryLabels', ui.get('categoryLabels')),
                     ('categoryTitles', ui.get('categoryTitles')),
                     ('categoryLeads', ui.get('categoryLeads'))):
        if not isinstance(table, dict):
            continue
        for key in keys:
            if key not in table:
                bad.append(f"  ui.{k} 里少了 key={key!r} —— 那个入口会退回内置的黄果文案")

# ⑤ 字段别名与代码对齐，双向查。
#    代码用了配置里没有的角色 → pick() 返回 undefined，那个字段静默变空。
#    配置声明了代码不用的角色 → 多半是改名后忘了删，留着会让人以为它还有用。
FIELDS = {k for k in (web.get('fields') or {}) if not k.startswith('_')}
used = set(re.findall(r"pick\(\s*[A-Za-z_$][\w$]*\s*,\s*['\"]([A-Za-z_$][\w$]*)['\"]", adapters))
for role in sorted(used - FIELDS):
    bad.append(f"  adapters.js 用了 pick(…, {role!r})，但 web.fields 里没有这个角色")
for role in sorted(FIELDS - used):
    bad.append(f"  web.fields 声明了 {role!r}，但 adapters.js 里没人用")
# 别名列表本身不能空
for role in sorted(FIELDS):
    names = (web.get('fields') or {}).get(role)
    if not (isinstance(names, list) and names and all(isinstance(n, str) and n for n in names)):
        bad.append(f"  web.fields[{role!r}] 的别名列表为空或含非法项：{names!r}")

if bad:
    print('\n'.join(bad), file=sys.stderr)
    sys.exit(1)
print(f"  ok  categoryIds {len(cats)} 个 slug 全部有对应 cate_id")
print(f"  ok  paths {len(web.get('paths') or {})} 条，占位符合法")
print(f"  ok  tagGroups {len(declared)} 组，覆盖 adapters 产出的 {len(produced)} 种 group")
print(f"  ok  popularTags/publicPopularTags 结构正确")
print(f"  ok  fields {len(FIELDS)} 个角色，代码用到 {len(used)} 个，双向对齐")
PYEOF
  [ $? -eq 0 ] || die "配置自检不通过"
}

# 从 site-default.json 生成 assets/site-web.js（配置只能有一份，但浏览器读不了 JSON）。
gen_web_config() {
  local tree="$1"
  say "生成前端配置"
  python3 gen-web-config.py "$tree/assets/site-default.json" "$tree/assets/site-web.js" \
    || die "site-web.js 生成失败"
}

# OkHttp 4.10 是 Kotlin 写的，编译期需要它的 jar 和两个依赖。
# 只用于 javac；运行期这些类 APK 里本来就有（红果源在用），不进 dex。
fetch_jars() {
  local dir="$BUILD_DIR/jars" gav name code
  mkdir -p "$dir"
  for gav in \
    "com/squareup/okhttp3/okhttp/$OKHTTP_VER/okhttp-$OKHTTP_VER.jar" \
    "com/squareup/okio/okio-jvm/3.0.0/okio-jvm-3.0.0.jar" \
    "org/jetbrains/kotlin/kotlin-stdlib/1.6.20/kotlin-stdlib-1.6.20.jar" ; do
    name=$(basename "$gav")
    [ -s "$dir/$name" ] && continue
    code=$(curl -sL -o "$dir/$name" -w '%{http_code}' --max-time 60 "https://repo1.maven.org/maven2/$gav" 2>/dev/null)
    [ "$code" = "200" ] || die "拉不到 $name（HTTP $code）"
  done
  JAVA_CP="$dir/okhttp-$OKHTTP_VER.jar:$dir/okio-jvm-3.0.0.jar:$dir/kotlin-stdlib-1.6.20.jar"
}

# Java 源码 → classes5.dex。目标树由参数给出（fast 用工作树，clean 用 clean-tree）。
build_java() {
  local tree="$1"
  [ -d "$JAVA_SRC" ] || die "找不到 Java 源码目录：$JAVA_SRC"
  [ -f "$ANDROID_JAR" ] || die "找不到 android.jar：$ANDROID_JAR"
  [ -x "$D8" ] || die "找不到 d8：$D8"

  say "编译 Java 层"
  fetch_jars
  local work="$BUILD_DIR/java"
  rm -rf "$work"; mkdir -p "$work/stubs" "$work/classes" "$work/dex"

  # 桩单独编到 stubs/，只进 classpath 不进 dex —— 否则会覆盖 APK 里真实的
  # SiteProfile，那是个到处都在用的类，直接崩。
  javac -nowarn -source 8 -target 8 -classpath "$ANDROID_JAR" \
        -d "$work/stubs" $(find "$JAVA_STUBS" -name '*.java') 2>/dev/null \
    || die "桩编译失败"

  javac -nowarn -source 8 -target 8 -classpath "$ANDROID_JAR:$JAVA_CP:$work/stubs" \
        -d "$work/classes" $(find "$JAVA_SRC" -name '*.java') 2>/dev/null \
    || die "Java 编译失败"

  "$D8" --min-api 26 --output "$work/dex" \
        $(find "$work/classes" -name '*.class') >/dev/null 2>&1 \
    || die "d8 转换失败"

  # 方法数闸门：单个 dex 上限 65536。这份是独立 dex，不和现有的抢额度，
  # 但写个检查免得以后越加越多悄悄越界。
  local methods
  methods=$(python3 -c "
import struct
d=open('$work/dex/classes.dex','rb').read()
print(struct.unpack_from('<I', d, 0x58)[0])")
  [ "$methods" -lt 65536 ] || die "classes5.dex 方法数 $methods 超限"

  mkdir -p "$tree/$(dirname "$JAVA_DEX_REL")"
  cp "$work/dex/classes.dex" "$tree/$JAVA_DEX_REL"
  ok "classes5.dex  $methods 个方法  $(stat -f '%z' "$tree/$JAVA_DEX_REL" 2>/dev/null || stat -c '%s' "$tree/$JAVA_DEX_REL") 字节"
}

# 语法闸门：assets 下的 JS 语法错会让整个界面白屏，且只有装机才看得见
check_js() {
  say "语法检查"
  for f in "${ASSETS_JS[@]}"; do
    [ -f "$f" ] || continue
    node --check "$f" || die "语法错误：$f"
    printf '  ok  %s\n' "$f"
  done
}

# clean 树 = 基准树 + 补丁集（含删除清单）
build_clean_tree() {
  ensure_baseline
  say "重建 $CLEAN_TREE = 基准 + $PATCHES/"
  rm -rf "$CLEAN_TREE"
  cp -a "$BASELINE" "$CLEAN_TREE"

  local n=0 d=0 rel
  if [ -f "$DELETED_LIST" ]; then
    while IFS= read -r rel; do
      [ -n "$rel" ] || continue
      # 用 rm -rf 而不是 rm -f：整目录被删时 diff -rq 报的是目录名
      # （比如 smali/com/hongguotv），rm -f 对目录会失败，留下半个树。
      # 路径来自我们自己的 sync-patches，但仍挡一下绝对路径和 ..，
      # 免得 .deleted 被改坏时删到树外面去。
      case "$rel" in
        /*|*..*) die "删除清单里有不安全的路径：$rel" ;;
      esac
      rm -rf "${CLEAN_TREE:?}/$rel"; d=$((d + 1))
    done < "$DELETED_LIST"
    [ "$d" -gt 0 ] && ok "删除 $d 个文件"
  fi

  while IFS= read -r -d '' f; do
    rel="${f#"$PATCHES"/}"
    mkdir -p "$CLEAN_TREE/$(dirname "$rel")"
    cp "$f" "$CLEAN_TREE/$rel"
    n=$((n + 1))
  done < <(find "$PATCHES" -type f ! -name '.deleted' -print0)
  [ "$n" -gt 0 ] || die "$PATCHES/ 里没有补丁文件"
  ok "套用 $n 个文件"
  gen_web_config "$CLEAN_TREE"
  check_config "$CLEAN_TREE"
  build_java "$CLEAN_TREE"
}

# 用文件级 diff 刷新补丁集
sync_patches() {
  ensure_baseline
  say "比对 $BASELINE 与 $WORK_TREE"
  rm -rf "$PATCHES"; mkdir -p "$PATCHES"

  local changed=() added=() removed=() line rel dir name
  while IFS= read -r line; do
    case "$line" in
      "Files $BASELINE/"*" and $WORK_TREE/"*" differ")
        rel="${line#Files $BASELINE/}"; rel="${rel%% and *}"
        changed+=("$rel") ;;
      "Only in $WORK_TREE/"*)
        dir="${line#Only in $WORK_TREE/}"; dir="${dir%%: *}"
        name="${line##*: }"
        added+=("${dir:+$dir/}$name") ;;
      "Only in $BASELINE/"*)
        dir="${line#Only in $BASELINE/}"; dir="${dir%%: *}"
        name="${line##*: }"
        removed+=("${dir:+$dir/}$name") ;;
    esac
  done < <(diff -rq "${IGNORE[@]}" "$BASELINE" "$WORK_TREE" 2>/dev/null || true)

  for rel in "${changed[@]+"${changed[@]}"}" "${added[@]+"${added[@]}"}"; do
    mkdir -p "$PATCHES/$(dirname "$rel")"
    # 新目录（比如 assets/hxm/）会被 diff 整个报出来，得用 -R
    if [ -d "$WORK_TREE/$rel" ]; then
      cp -R "$WORK_TREE/$rel" "$PATCHES/$rel"
    else
      cp "$WORK_TREE/$rel" "$PATCHES/$rel"
    fi
  done

  # diff 的 --exclude 只管比对、不管拷贝 —— 目录被整个端过来时，
  # 里面那份排除项也跟着进来了（hxm.wasm.js 是别人的 wasm，不进仓库）。
  for name in classes5.dex site-web.js hxm.wasm.js; do
    find "$PATCHES" -name "$name" -type f -delete 2>/dev/null || true
  done
  if [ "${#removed[@]}" -gt 0 ]; then
    printf '%s\n' "${removed[@]}" > "$DELETED_LIST"
  fi

  ok "改动 ${#changed[@]} / 新增 ${#added[@]} / 删除 ${#removed[@]}"
  [ "${#changed[@]}" -gt 0 ] && printf '  改  %s\n' "${changed[@]}"
  [ "${#added[@]}"   -gt 0 ] && printf '  新  %s\n' "${added[@]}"
  [ "${#removed[@]}" -gt 0 ] && printf '  删  %s\n' "${removed[@]}"
  du -sh "$PATCHES" | awk '{print "  补丁集体积: " $1}'
  return 0
}

verify() {
  build_clean_tree
  say "逐文件比对 $CLEAN_TREE 与 $WORK_TREE"
  local out
  out=$(diff -rq "${IGNORE[@]}" "$CLEAN_TREE" "$WORK_TREE" 2>&1 || true)
  if [ -z "$out" ]; then
    ok "完全一致：patches/ 就是工作树的全部改动"
  else
    printf '%s\n' "$out"
    die "不一致，上面列出的就是遗漏或多余的改动"
  fi
}

pack() {
  local tree="$1"
  gen_web_config "$tree"
  check_config "$tree"
  check_js
  build_java "$tree"
  say "apktool b $tree"
  apktool b "$tree" -o "$OUT_UNSIGNED"

  if [ "${NO_SIGN:-0}" = "1" ]; then
    ok "已产出 $OUT_UNSIGNED（跳过签名）"
    return
  fi

  say "zipalign"
  "$ZIPALIGN" -f -p 4 "$OUT_UNSIGNED" "$OUT_ALIGNED"

  say "apksigner"
  "$APKSIGNER" sign \
    --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
    --out "$OUT_SIGNED" "$OUT_ALIGNED"
  "$APKSIGNER" verify --print-certs "$OUT_SIGNED" >/dev/null || die "签名校验失败"

  local size sha
  size=$(stat -f '%z' "$OUT_SIGNED" | awk '{printf "%.1f MB", $1/1048576}')
  sha=$(shasum -a 256 "$OUT_SIGNED" | awk '{print substr($1,1,16)}')
  ok "${OUT_SIGNED}  ${size}  sha256:${sha}…"

  if [ "${INSTALL:-0}" = "1" ]; then
    say "install"
    local args=()
    [ -n "${SERIAL:-}" ] && args=(-s "$SERIAL")
    adb "${args[@]}" install -r "$OUT_SIGNED"
  fi
}

# 发布：打包 → 证明补丁集 → 同步 patches → 再打包 → 再说一遍 → 才拷贝出去。
#
# 为什么要有这一步，而不是让人自己 cp：site-web.js 是**生成**的（每次 pack 从
# site-default.json 现生成），而且它不参与补丁集比对。所以「改了 site-default.json
# 跑一次 fast、又改回去、再 cp 上次的 cz-final.apk」会发出一份**带着临时改动**的包，
# 而 verify 照样说「完全一致」。2026-10-16 真踩了一次：验证 shapes 自检时往
# site-default.json 塞的测试形状，被发进了 APK，表现是所有源的 envelope 都变成空串、
# 首页全空。顺序钉死在这里，比记住这件事可靠。
release() {
  doctor
  [ -d "$WORK_TREE" ] || die "$WORK_TREE/ 不存在"
  # 顺序不能反：verify 是「patches/ 和工作树逐文件一致」，改动还没刷进 patches/
  # 的时候跑它**必然**报不一致。所以先把差异刷进去，再证，最后才出包。
  # （第一版把 verify 放在了最前面，于是每次发版都先红一次。）
  sync_patches                            # 把工作树差异刷进 patches/
  verify                                  # 证明 patches/ 就是工作树的全部改动
  pack "$WORK_TREE"                       # 从工作树出包；site-web.js 在这里重新生成
  shasum -a 256 "$OUT_SIGNED"
  ls -l "$OUT_SIGNED"
  say "发布包：$OUT_SIGNED"
}

case "${1:-fast}" in
  doctor)       doctor ;;
  fast)         doctor; [ -d "$WORK_TREE" ] || die "$WORK_TREE/ 不存在"; pack "$WORK_TREE" ;;
  clean)        doctor; build_clean_tree; pack "$CLEAN_TREE" ;;
  sync-patches) doctor; sync_patches ;;
  verify)       doctor; verify ;;
  release)      release ;;
  *)            sed -n '3,14p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
