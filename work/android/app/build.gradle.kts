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
