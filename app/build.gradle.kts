import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
    alias(libs.plugins.ksp)
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("key.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.taostudio.tapaccounting"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.taostudio.tapaccounting"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            keyAlias = keystoreProperties["keyAlias"] as String
            keyPassword = keystoreProperties["keyPassword"] as String
            storeFile = file(keystoreProperties["storeFile"] as String)
            storePassword = keystoreProperties["storePassword"] as String
        }
    }

    buildTypes {
        getByName("debug") {
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // sherpa-onnx 自带的 C / C++ API 动态库只给“不走 JNI”的使用者准备
            // （见 app/src/main/jniLibs/arm64-v8a/README.md）。本项目通过
            // OfflineRecognizer 走 JNI，而 libsherpa-onnx-jni.so 的 DT_NEEDED 只依赖
            // libonnxruntime.so，因此排除它们：APK 里的 native 数据少约 2.2MB（压缩后）。
            excludes += setOf(
                "**/libsherpa-onnx-c-api.so",
                "**/libsherpa-onnx-cxx-api.so",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    configurations.configureEach {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }
}

dependencies {
    val sherpaAar = file("libs/sherpa-onnx.aar")
    if (!sherpaAar.exists()) {
        throw GradleException("Missing required local dependency: ${sherpaAar.path}")
    }

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.8.0")

    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // 本地语音识别：显式引入 AAR，避免 fileTree 对 AAR 的处理不稳定
    // 必须是 16KB 对齐的版本（>= v1.12.x，见 k2-fsa/sherpa-onnx#2520）；
    // 旧版 libonnxruntime.so / libsherpa-onnx-*.so 只有 4KB LOAD 对齐，
    // 在 16KB 页设备上会触发系统“Android 应用兼容性”回退模式警告。
    // 当前捆绑版本：sherpa-onnx v1.13.8（arm64-v8a 段 p_align = 0x4000）。
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation(files(sherpaAar))
    implementation("org.apache.commons:commons-compress:1.24.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.github.bumptech.glide:glide:4.16.0")
    // 图片记账走多模态 AI（ReceiptOcrHelper），不再需要本地 ML Kit OCR，
    // 故移除 com.google.mlkit:text-recognition-chinese 依赖（省 ~4.3MB .so + 2MB 模型 assets）。

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    // 首启引导页的分页容器
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
    implementation("com.github.yalantis:ucrop:2.2.8")
    implementation("com.kizitonwose.calendar:view:2.4.0")

    // 点击检测用的 LiteRT（原 TensorFlow Lite）。
    // 关键：org.tensorflow:tensorflow-lite（含 2.16.1/2.17.0）预编译的
    // libtensorflowlite_jni.so 只有 4KB LOAD 对齐，在 16KB 页设备上会触发
    // 系统“Android 应用兼容性”警告，且 Google Play 已要求 16KB 支持。
    // LiteRT 1.4.x 的 .so 为 16KB 对齐（p_align = 0x4000），并保留
    // org.tensorflow.lite.Interpreter / .nnapi.NnApiDelegate 包名，
    // 因此 TfClassifier / TapTfClassifier 不需要改代码。
    implementation("com.google.ai.edge.litert:litert:1.4.2")

    // Onboarding spotlight / coach mark
    implementation("com.github.takusemba:spotlight:2.0.5")

    // Markdown rendering for chat messages
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-tasklist:4.6.2")
    implementation("io.noties.markwon:linkify:4.6.2")
    implementation("io.noties.markwon:html:4.6.2")
    implementation("io.noties.markwon:image-glide:4.6.2")
    implementation("io.noties.markwon:syntax-highlight:4.6.2")
    kapt("io.noties:prism4j-bundler:2.0.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
