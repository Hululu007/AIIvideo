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
javac -nowarn -cp "$CP" -d "$OUT" java-src/com/huangguo/mobile/UserSource.java
javac -nowarn -cp "$CP:$OUT" -d "$OUT" java-test/UserSourceTest.java
java -cp "$CP:$OUT" UserSourceTest cz_repack/assets/site-default.json
