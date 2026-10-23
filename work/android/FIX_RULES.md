# jadx 反编译产物修复规则

这批 Java 是 `cz-final.apk` 经 jadx 1.5.6 反编译得到的，正在往 Gradle 正面工程迁移。
**你的任务只有一个：让 javac 通过。** 不重构、不改逻辑、不改命名、不动代码风格。

## 绝对边界

- 只改分给你的文件。别的文件就算看到错也别碰——别人在同时改。
- 不改任何 `逻辑分支`、`字段可见性`、`方法签名`（除非编译必须）。
- 不删代码。看不懂的语义问题，用「保守等价」的写法绕过去。
- `R.java` 不在工程里（由 AGP 生成），看到 `R.xxx` 不要改。

## 编译方式

```bash
GR=$(ls -d ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7 | head -1)
export ANDROID_HOME=$HOME/Library/Android/sdk
cd /Users/zhaoruizhe/Desktop/project/T/hg/work/android
"$GR/bin/gradle" -p . :app:compileDebugJavaWithJavac 2>&1 | grep -A3 '你的文件'
```

javac 一次最多报 100 个错误，所以修完一轮要重新跑，看还剩什么。

## 错误模式与修法（都是 jadx 的机械产物）

### 1. `this.f$0` 找不到符号

R8 把 lambda 编成了独立的合成类（`MainActivity$$ExternalSyntheticLambda33`），
jadx 把这个类内联成了同文件里的匿名类，但**没有把 `f$0` 字段一起搬进来**。

原文形如：
```java
runOnUiThread(new Runnable() {
    public final void run() {
        this.f$0.lambda$dispatch$0();     // f$0 不存在
    }
});
```

改成显式的外层引用：
```java
runOnUiThread(new Runnable() {
    public final void run() {
        MainActivity.this.lambda$dispatch$0();
    }
});
```

如果外层类是 `Bridge` 这种内部类、且目标方法在外层 Activity 上，就写
`MainActivity.this.xxx()`；目标方法在内部类自己身上就写 `Bridge.this.xxx()`。

**判断方法**：看报错那行调用的方法名，`grep -n 'void 该方法名' 文件` 找它的声明位置，
在哪个类的花括号里就限定到哪个类。

对其他文件同理：`ShimmerView.this.lambda$new$0(...)`、`AppStore.this.xxx()`。

### 2. `this.f$0.finish()` / `this.f$0.某字段`

同一个原因。改成 `MainActivity.this.finish()`、`MainActivity.this.某字段`。

### 3. 匿名类方法 `run() throws Exception` / `throws Throwable`

jadx 把 try-catch 里的受检异常声明带到了实现方法上，但 `Runnable.run()` 不声明异常。

```java
public final void run() throws Exception {   // 编译错
    accountService.sync();
}
```

改成在方法体里自己 catch：
```java
public final void run() {
    try {
        accountService.sync();
    } catch (Exception e) {
        throw new RuntimeException(e);
    }
}
```

注意：如果原方法体本来就有 try-catch，那把 `throws` 删掉即可。
如果 body 里确实抛受检异常又没 catch，就包 `RuntimeException` 重新抛——
这和字节码里 R8 生成的行为等价（受检异常本来就不会被运行时校验）。

### 4. 变量找不到符号：`th` / `th2` / `th3` / `e` / `e2`

jadx 还原 try-catch 时把 catch 参数命名成了 `th`、`th2`、但声明处丢掉了，或者
`th = th2;` 这种自赋值出现在 catch 块外。

看上下文补回正确的变量声明或替换成真正在作用域里的那个变量：

```java
} catch (Throwable th) {
    th = th2;              // 错：th 是 catch 参数，不能赋值；th2 不存在
```

改成直接用 catch 参数本名：
```java
} catch (Throwable th) {
    // 用 th
```

如果是 `throw th;` 但 th 未声明，说明整个 try 块需要补 `catch (Throwable th) { throw th; }`。

**原则**：这类错误的正确修法是「让异常继续往上抛，语义保持不变」，
不是吞掉异常。不确定时宁可 `throw new RuntimeException(th)`。

### 5. `无法为最终变量 xxx 分配值`

jadx 把 `final` 加在了被重新赋值的局部变量上。删掉那个 `final` 关键字。

```java
final boolean z = false;
...
z = true;       // 错
```
→ 去掉 `final`。

### 6. `缺少返回语句`

jadx 还原的控制流在某个分支没 return。看方法返回类型补 `return null;`
（引用类型）、`return 0;`、`return false;`——**放在方法最后面**，
不要改已有分支的返回值。这是不可达路径，补什么都对。

### 7. `不兼容的类型: Object无法转换为String`（泛型擦除）

```java
for (String str12 : (ArrayList) linkedHashMap2.get(str9)) {   // 错
```

改成带泛型的强制转换：
```java
for (Object o : (List) linkedHashMap2.get(str9)) {
    String str12 = (String) o;
```
或者
```java
for (String str12 : (List<String>) linkedHashMap2.get(str9)) {
```
（后者会有 unchecked 警告，无妨。）

### 8. 程序包不存在 / 找不到符号：第三方类

我们**只保留了四个第三方库**：`androidx.core`、`androidx.recyclerview`、
`androidx.webkit`、`okhttp3`、以及 `com.parse`（本地 jar）、`org.videolan.libvlc`（本地 jar）。

`androidx.media3`、`com.google.common`、`org.bouncycastle`、`org.jsoup` 已经全部移除
（它们原本只服务于已删除的 ExoPlayer 播放链）。

如果报错是这些包里的**常量**，值已经从 smali 里提取到本地类了，直接用：

| 原引用 | 换成 |
|---|---|
| `TtmlNode.ATTR_ID` | `Text.ATTR_ID` |
| `TtmlNode.COMBINE_ALL` | `Text.COMBINE_ALL` |
| `TtmlNode.TEXT_EMPHASIS_AUTO` | `Text.TEXT_EMPHASIS_AUTO` |
| `TtmlNode.TAG_BODY` / `TAG_P` / `TAG_DIV` | `Text.TAG_BODY` 等 |
| `TtmlNode.START` | `Text.START` |
| `IcyHeaders.REQUEST_HEADER_ENABLE_METADATA_VALUE` | `Text.REQUEST_HEADER_ENABLE_METADATA_VALUE` |
| `HttpHeaders.XXX` | `Http.XXX` |
| `Ascii.XXX` | `Ctl.XXX`（byte 常量） |
| `MimeTypes.IMAGE_JPEG` / `IMAGE_PNG` / `IMAGE_WEBP` / `VIDEO_MP4` | `Mime.IMAGE_JPEG` 等 |
| `MimeTypes.BASE_TYPE_AUDIO` / `BASE_TYPE_VIDEO` / `BASE_TYPE_IMAGE` | `Mime.BASE_TYPE_AUDIO` 等 |
| `MessageBundle.TITLE_ENTRY` | `Json.TITLE` |
| `AnnotatedPrivateKey.LABEL` | `Json.LABEL` |

`Text` / `Http` / `Ctl` / `Mime` / `Json` / `Constants` 都在同包下，已经写好了，不要改。

**如果需要某个常量但本地类里没有**：原始 APK 和解包树都不在树里了
（`cz_repack/` 和 `chengzi-v0.3.15.apk` 都已删，apktool 链路整体退役）。
现在只有两条路：翻 git 历史里那段补丁集（`git log -- work/shells/patches`），或者从 Releases 下已发布的
APK 反编译那一个类。**不要瞎猜值** —— 常量猜错的表现是运行时 403 或签名不对，
和编译错误完全两种难查。

`androidx.exifinterface` 如果被用到，加一行依赖到 `app/build.gradle.kts`：
`implementation("androidx.exifinterface:exifinterface:1.3.7")`。

### 9. 方法在低 compileSdk 上不存在（`propagateRequestedFrameRate` 之类）

`compileSdk` 是 35。API 36 才有的方法（如 `ViewGroup.propagateRequestedFrameRate`）
编译期不存在。用反射调用，或者直接删掉那个分支——
这类都是「提升刷新率」的优化，不影响功能。

反射写法：
```java
try {
    ViewGroup.class.getMethod("propagateRequestedFrameRate", float.class, boolean.class)
        .invoke(view, refreshRate, true);
} catch (Throwable ignored) {
}
```

## 验收

改完跑：

```bash
"$GR/bin/gradle" -p . :app:compileDebugJavaWithJavac 2>&1 | tail -20
```

看到 `BUILD SUCCESSFUL`，或者**只剩别人文件的错误**，就算完成。

报告给我：改了哪些文件、用了哪几种修法、还剩什么错（如果有）。
