#!/usr/bin/env bash
# UserSource.validate() 的单元测试。它不依赖 Context，可以在 JVM 上直接跑。
#
# 注意 classpath 顺序：org.json 必须排在 android.jar 前面 ——
# android.jar 里的 org.json 是抛 "Stub!" 的占位实现，放前面会让所有用例
# 都以「解析异常」通过，看着全绿其实什么都没测。这个坑踩过一次。
set -e
cd "$(dirname "$0")/.."
JARS="$PWD/.build/jars"
ANDROID_JAR="${ANDROID_JAR:-$HOME/Library/Android/sdk/platforms/android-34/android.jar}"

for j in json-20231013.jar okhttp-4.10.0.jar okio-jvm-3.0.0.jar kotlin-stdlib-1.6.20.jar; do
  [ -s "$JARS/$j" ] || { echo "缺 $j，先跑一次 ./build.sh fast"; exit 1; }
done

CP="$JARS/json-20231013.jar:$ANDROID_JAR:$JARS/okhttp-4.10.0.jar:$JARS/okio-jvm-3.0.0.jar:$JARS/kotlin-stdlib-1.6.20.jar"
OUT=$(mktemp -d)
# 编**整个 java-src**，不是只有 UserSource —— UserSource 现在会用到同一个包的
# CmsXml / CmsAdapter / AgedmAdapter / AiiHttp，只编它一个文件必然一片「找不到符号」，
# 而那个报错看起来像是这次改坏了（2026-10-16 恢复出厂设置这一轮踩到）。
# 桩照 build.sh 的做法单独编到 stubs/：只进 classpath，不进最终产物。
mkdir -p "$OUT/stubs"
javac -nowarn -cp "$ANDROID_JAR" -d "$OUT/stubs" $(find java-stubs -name '*.java')
javac -nowarn -cp "$CP:$OUT/stubs" -d "$OUT" $(find java-src -name '*.java')
javac -nowarn -cp "$CP:$OUT:$OUT/stubs" -d "$OUT" java-test/UserSourceTest.java
java -cp "$CP:$OUT:$OUT/stubs" UserSourceTest cz_repack/assets/site-default.json
