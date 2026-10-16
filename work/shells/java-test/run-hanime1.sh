#!/usr/bin/env bash
# Hanime1Adapter 的离线回归。
#
# 不发任何网络请求 —— fixture 是预先抓好的真实 HTML（java-test/fixtures/）。
#
# ★ fixture 不在仓库里。这是有意的：抓下来的是成人内容站的真实页面，
#   1.3 MB、单个文件 98 处露骨描述。测试逻辑值得留，内容不值得进公开仓库。
#   第一次跑先执行 fixtures/refresh.py 现场抓一份（需要 pip install curl_cffi；
#   它模拟 Chrome 的 TLS 指纹，因为直接 curl 会被 Cloudflare 挡在 403）。
set -e
cd "$(dirname "$0")/.."
JARS="$PWD/.build/jars"
ANDROID_JAR="${ANDROID_JAR:-$HOME/Library/Android/sdk/platforms/android-34/android.jar}"

for j in json-20231013.jar okhttp-4.10.0.jar okio-jvm-3.0.0.jar kotlin-stdlib-1.6.20.jar; do
  [ -s "$JARS/$j" ] || { echo "缺 $j，先跑一次 ./build.sh fast"; exit 1; }
done

# 没有 fixture 就直接说清楚怎么办，别让 javac 抛一堆 FileNotFound。
if [ ! -d java-test/fixtures ] || [ -z "$(ls -A java-test/fixtures/*.html 2>/dev/null)" ]; then
  cat <<'EOF'
跳过：java-test/fixtures/ 里没有 HTML 快照。

  这批快照不进仓库（它们是被抓站点的真实页面）。要跑这个回归：

      cd java-test/fixtures && ./refresh.py

  依赖 curl_cffi（pip install curl_cffi）。抓不到就是不跑 —— 不影响主回归，
  ./java-test/run.sh 那条不依赖 fixture。
EOF
  exit 0
fi

CP="$JARS/json-20231013.jar:$ANDROID_JAR:$JARS/okhttp-4.10.0.jar:$JARS/okio-jvm-3.0.0.jar:$JARS/kotlin-stdlib-1.6.20.jar"
OUT=$(mktemp -d)
# 和 UserSourceTest 一样：编**整个 java-src**，不是只编适配器 ——
# 适配器依赖 AiiHttp / SiteProfile，单编会一片「找不到符号」。
mkdir -p "$OUT/stubs"
javac -nowarn -cp "$ANDROID_JAR" -d "$OUT/stubs" $(find java-stubs -name '*.java')
javac -nowarn -cp "$CP:$OUT/stubs" -d "$OUT" $(find java-src -name '*.java')
javac -nowarn -cp "$CP:$OUT:$OUT/stubs" -d "$OUT" java-test/Hanime1Test.java
java -cp "$CP:$OUT:$OUT/stubs" Hanime1Test java-test/fixtures
