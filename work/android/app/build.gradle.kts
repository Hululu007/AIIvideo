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

    defaultConfig {
        applicationId = "com.huangguo.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 10023
        versionName = "1.0.23"
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
