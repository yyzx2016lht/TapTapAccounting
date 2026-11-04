import java.util.UUID

plugins {
    id("com.android.application")
    kotlin("android")
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.taostudio.tapaccounting.capture"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.taostudio.tapaccounting.capture"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        // hook -> 插件 ingestion 的 token；不是强安全边界，个人使用足够。
        buildConfigField("String", "CAPTURE_INGEST_TOKEN", "\"dev-token\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        getByName("debug") {
            // 让 hook 侧的调试版本可以直接使用固定 token。
            buildConfigField("String", "CAPTURE_INGEST_TOKEN", "\"dev-token\"")
        }
        getByName("release") {
            // 可用 CAPTURE_INGEST_TOKEN 环境变量固定；否则每次构建随机生成。
            val token = System.getenv("CAPTURE_INGEST_TOKEN")
                ?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString()
            buildConfigField("String", "CAPTURE_INGEST_TOKEN", "\"$token\"")
            // P0 骨架先不混淆，避免 Xposed 入口/反射锚点被 R8 改名。
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }
}

ksp {
    arg("room.schemaLocation", "${layout.buildDirectory.get().asFile}/roomSchemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(project(":capture-contract"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // 规则执行：在插件进程（Ingest Provider）里跑 AutoAccounting 云端规则（QuickJS）。
    // 只在插件进程加载；不要放进 hook 进程。
    implementation("com.github.AutoAccountingOrg:quickjs-android:35726a9d28")

    // LSPosed / Xposed API：只编译期需要，运行时由框架提供。
    compileOnly("de.robv.android.xposed:api:82")

    testImplementation("junit:junit:4.13.2")
}
