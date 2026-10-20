import java.util.Properties

plugins {
    id("com.android.application")
}

// 签名：从 keystore.properties 读（不进 git）。没有这个文件时只出 debug 包。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.huangguo.mobile"
    compileSdk = 36

    // spider 宿主的跨进程通道（ISpiderChannel.aidl）。AGP 8 起 aidl 默认关。
    buildFeatures {
        aidl = true
    }

    defaultConfig {
        applicationId = "com.huangguo.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 20005
        versionName = "2.0.5"
        vectorDrawables { useSupportLibrary = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    sourceSets["main"].apply {
        assets.srcDirs("src/main/assets")
    }

    // 单测要读 assets/site-default.json —— 那是内置模板，UserSourceTest 里有一条
    // 用例专门断言「模板不该能当用户配置用」。把它并进 test 的 resources。
    sourceSets["test"].apply {
        resources.srcDirs("src/main/assets", "src/test/resources")
    }

    // libvlc-all 的 AAR 带四个 ABI，全打进去 200MB 起步。
    // 2019 年之后的机器清一色 arm64；32 位机器上 libvlc.so 不出现，
    // VlcPlayerActivity.available() 返回 false，页面自动退回网页播放器。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")

        // 和 manifest 的 android:extractNativeLibs="true" 必须成对出现，否则 Gradle
        // 会警告行为未定义。两条一起决定 so 是「安装时解压到 lib/<abi>/」还是
        // 「不压缩留在 APK 里由链接器 mmap」：
        //
        //   false（旧） 56.0 MB 下载 / 58.7 MB 磁盘
        //   true （现在）26.2 MB 下载 / 81.5 MB 磁盘
        //
        // 换 true 的唯一动机是下载体积省 53% —— libvlc.so 45MB 能 deflate 到 20.7MB。
        // 代价是安装时多解压 54MB、多占 23MB 磁盘。真机实测（23113RKC6C / Android 16）：
        // 冷启动 5 次 311~400ms vs 旧配置 341~414ms，量不出差异；播放路径完全等价。
        // 别看到「解压到磁盘」就以为会拖慢启动 —— 两种配置运行时都是 mmap，没人整份读。
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            // android.jar 里没实现的方法是抛 Stub! 的占位。UserSource.validate()
            // 这类纯逻辑测试用不到它们，设成 true 后没实现的方法返回默认值（null/0/false）
            // 而不是抛异常。注意这不会让「真的依赖 Android 的代码」跑通 —— 那种要
            // Robolectric 或仪器测试，见 test/java 下各文件的注释。
            isReturnDefaultValues = true
            // 联网用例默认不跑（CI 不能依赖外网）。手动验真站：
            //   ./gradlew :app:testDebugUnitTest --tests '*Mrds66LiveTest*' -Dhg.live=1
            all {
                it.systemProperty("hg.live", System.getProperty("hg.live", ""))
                it.systemProperty("hg.site", System.getProperty("hg.site", ""))
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.customview:customview:1.1.0")
    implementation("androidx.webkit:webkit:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.10.0")

    // Parse：官方 artifact 只在 jitpack 上有，mavenCentral 是 404
    implementation("com.github.parse-community.Parse-SDK-Android:parse:4.4.0")

    // libVLC：自带 FFmpeg 软解，so 只留 arm64-v8a
    implementation("org.videolan.android:libvlc-all:3.7.7")

    // libv2ray（2dust/AndroidLibV2rayLite 打出来的 AAR）：给 app 起一个本地 SOCKS5，
    // 让 OkHttp 与 libVLC 自己走代理，不碰 VpnService、不改系统路由。
    //
    // 仓库里这份是精简过的：原包 68MB 带四个 ABI，这里 20.4MB —— 只留
    // arm64-v8a/libgojni.so（42MB，压缩后约 20MB）+ classes.jar + geoip/geosite。
    // 上面 splits.abi 已经 include 了 arm64-v8a，两边口径一致：32 位机器上
    // libgojni.so 不会出现，ProxyCore.available() 返回 false，功能整体退成直连。
    //
    // 放进 app/libs 并进 git，比「去某个地址下载」多换一份可复现的构建。
    implementation(files("libs/libv2ray.aar"))

    testImplementation("junit:junit:4.13.2")
    // 单测的 classpath 里 android.jar 排在前面，它的 org.json 是空壳
    // （put() 不存值、size() 恒 0）。换成真的实现，否则所有断言都失真 ——
    // 这个坑和旧链路 run.sh 里那条注释是同一个：org.json 必须在 android.jar 之前。
    testImplementation("org.json:json:20231013")
}

// site-web.js 是 site-default.json 的派生产物（浏览器读不了 JSON，页面 CSP 是 connect-src 'none'）。
// 以前由 build.sh 在打包前现生成；现在收进 Gradle，preBuild 自动跑，改完 json 直接出包。
tasks.register<Exec>("genWebConfig") {
    val assetDir = file("src/main/assets")
    val json = File(assetDir, "site-default.json")
    val out = File(assetDir, "site-web.js")
    val gen = rootProject.file("scripts/gen-web-config.py")
    inputs.file(json)
    inputs.file(gen)
    outputs.file(out)
    commandLine("python3", gen.absolutePath, json.absolutePath, out.absolutePath)
    onlyIf { json.exists() }
}

tasks.named("preBuild") { dependsOn("genWebConfig") }
