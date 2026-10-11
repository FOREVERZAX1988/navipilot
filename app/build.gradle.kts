import java.util.Properties
import java.io.FileOutputStream
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.JarEntry
import org.objectweb.asm.*

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/config/detekt/detekt-config.yml"))
}

android {
    namespace = "com.jixiexiaoge.drivingassist"
    compileSdk = 35

    val navAbiList: List<String> =
        (project.findProperty("navipilot.abis") as String?)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: listOf("arm64-v8a")

    defaultConfig {
        applicationId = "com.jixiexiaoge.drivingassist"
        minSdk = 29
        targetSdk = 35
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 260726
        versionName = (project.findProperty("versionName") as String?) ?: "v260726"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // GitHub OAuth Client ID（从 local.properties 读取，不硬编码）
        val props = Properties()
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) props.load(localPropsFile.inputStream())
        buildConfigField("String", "GITHUB_CLIENT_ID", "\"${props.getProperty("GITHUB_CLIENT_ID", "Ov23ctaOHfiktpd9aTE6")}\"")

    }


    // 签名配置：优先从 Gradle project property (-P) 读取，支持 CI；其次 local.properties
    signingConfigs {
        create("release") {
            val props = Properties()
            val localPropsFile = rootProject.file("local.properties")
            if (localPropsFile.exists()) props.load(localPropsFile.inputStream())

            fun resolve(name: String): String =
                (project.findProperty(name) as String?) ?: props.getProperty(name, "")

            storeFile = file("release.keystore")
            storePassword = resolve("RELEASE_STORE_PASSWORD")
            keyAlias = resolve("RELEASE_KEY_ALIAS")
            keyPassword = resolve("RELEASE_KEY_PASSWORD")
        }
    }

    // 体积优化：按 ABI 拆包 + 构建 AAB；CI 通过 -Pnavipilot.abis 控制输出范围
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    bundle {
        // AAB 原生库不压缩，安装时按设备 ABI 分发且无需解压，降低安装后占用
        storeArchive {
            enable = false
        }
    }

    buildTypes {
        release {
            // 体积优化：R8 代码混淆+裁剪 + 资源裁剪（libVLC 占大头，长期建议替换为 MediaCodec/ExoPlayer）
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            isJniDebuggable = false
            isPseudoLocalesEnabled = false
            isCrunchPngs = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // CI：-Pnavipilot.debugSign=release 时把 debug 变体也用 release keystore 签，
            // 这样 debug APK 与我们已安装的 release 包**同签名**，可直接覆盖安装（不必卸载重装）。
            // 未传该属性（本地开发）时保持内置 debug key 签名。
            if ((project.findProperty("navipilot.debugSign") as String?) == "release") {
                signingConfig = signingConfigs.getByName("release")
            }
            // 体积优化：debug 亦开 R8 + 资源裁剪（本地环测无需热重载）
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = true
            isJniDebuggable = false
            isPseudoLocalesEnabled = false
            isCrunchPngs = false
            // 本地 debug 使用默认 debug 签名；CI release 才用 release keystore
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            packaging {
                jniLibs {
                    useLegacyPackaging = false
                    pickFirsts += listOf("**/libc++_shared.so")
                    keepDebugSymbols += setOf(
                        "*/libc++_shared.so",
                        "*/libnavicore.so",
                        "*/libsynthesizer.so",
                        "*/libtxmapvis.so"
                    )
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    
    // Kotlin JVM 目标版本（必须与 Java compileOptions 一致）
    kotlinOptions {
        jvmTarget = "11"
        // 修复 Kotlin 编译器内部错误
        freeCompilerArgs += listOf(
            "-Xjvm-default=all",  // 启用 JVM 默认方法
            "-Xno-call-assertions",  // 减少编译时断言检查
            "-Xno-param-assertions",
            "-Xno-receiver-assertions"
        )
    }
    
    // Kotlin Compose Compiler配置（Gradle 9.x + Kotlin 2.1）
    composeCompiler {
        // 启用强跳过模式以提升性能
        // enableStrongSkippingMode = true  // 已废弃，使用featureFlags
    }

    // Google Navigation SDK 需要较大的 heap（Gradle 9.x 已移除 dexOptions javaMaxHeapSize）

    buildFeatures {
        compose = true
        buildConfig = true  // 启用BuildConfig生成
    }

    lint {
        disable += "DuplicateNamespace"
        disable += "PackagedPrivateKey"
    }

    // R8优化配置 + native libs 安装时解压（减小 APK 体积并提升运行时性能）
    packaging {
        jniLibs {
            useLegacyPackaging = false
            pickFirsts += listOf(
                "**/libc++_shared.so",
            )
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/*.kotlin_module",
                "META-INF/*.version",
                "META-INF/proguard/*",
                "META-INF/com.android.tools/*",
                "META-INF/gradle-plugins/*",
                "META-INF/versions/*",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                // libVLC 内置的大量资源可按需裁剪；如果保留 libvlc-all，先去掉不常用字幕/字体
                "assets/subtitles/**",
                "assets/lua/meta/**",
                "assets/lua/extensions/**",
                "assets/lua/sd/**",
            )
        }
    }
    
    
    // 启用资源混淆（使用新的 androidResources API）
    androidResources {
        noCompress += setOf("tflite", "lite")
        ignoreAssetsPattern += setOf("!.svn", "!.git", "!.ds_store", "!*.scc", ".*", "<dir>_*", "!CVS", "!thumbs.db", "!picasa.ini", "!*~")
    }
}



dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    // ActivityResult API 需要 Fragment >= 1.3.0；强制覆盖旧版本 transitive
    implementation("androidx.fragment:fragment:1.6.2")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Material View层组件 - 已移除（原为腾讯导航SDK提供主题属性）

    // AppCompat - 已移除（原为腾讯SDK drawable主题解析）

    // HTTP客户端 - 用于导航确认API请求和反馈提交
    // libVLC - 一键全屏投屏（播放设备 screencastd MPEG-TS 流）
    // 长期建议：仅播放 H.264/MPEG-TS over TCP，可替换为 ExoPlayer + FFmpeg extension 或自研 MediaCodec，APK 可减小 30-40MB
    implementation("org.videolan.android:libvlc-all:3.6.5")
    // 若功能满足可切换为精简版 libvlc（体积小很多，但需验证编解码支持）：
    // implementation("org.videolan.android:libvlc:3.6.5")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    
    // ExoPlayer - 用于视频播放
    // 已移除（无代码使用）
    // implementation("androidx.media3:media3-exoplayer:1.2.1")
    // implementation("androidx.media3:media3-ui:1.2.1")
    // implementation("androidx.media3:media3-common:1.2.1")
    

    
    // Koin依赖注入 - 已移除（无使用）
    // implementation("io.insert-koin:koin-android:3.5.3")
    // implementation("io.insert-koin:koin-androidx-compose:3.5.3")
    // implementation("io.insert-koin:koin-androidx-navigation:3.5.3")
    
    // Timber日志库 - P2 代码质量优化
    implementation("com.jakewharton.timber:timber:5.0.1")
    
    // DataStore - P3 功能增强（示例）
    // 已移除（无代码使用）
    // implementation("androidx.datastore:datastore-preferences:1.0.0")
    
    // 🆕 安全存储（EncryptedSharedPreferences）
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    // ZeroMQ - 已移除（无使用）
    // implementation("org.zeromq:jeromq:0.6.0")

    // SSH - 用于远程连接 comma3 设备
    // 已移除（SshConnectionManager 已删除）
    // implementation("com.hierynomus:sshj:0.38.0")
    //
    // SLF4J - SSHJ依赖的日志框架
    // implementation("org.slf4j:slf4j-api:2.0.9")
    // implementation("com.github.tony19:logback-android:3.0.0")
    //
    // BouncyCastle - 用于解析 RSA 私钥（SSHJ需要）
    // implementation("org.bouncycastle:bcprov-jdk18on:1.77")
    // BouncyCastle - 已移除（SSH 已删除）
    // implementation("org.bouncycastle:bcpkix-jdk18on:1.77")

    // 测试框架 - P0 优先级优化
    testImplementation(libs.junit)
    testImplementation("com.google.truth:truth:1.1.5")  // Google Truth 断言库
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")  // 协程测试
    testImplementation("io.mockk:mockk:1.13.8")  // Kotlin Mock 框架
    
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation("io.mockk:mockk-android:1.13.8")  // Android Mock 支持
    
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
