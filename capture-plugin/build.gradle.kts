plugins {
    id("com.android.application")
    kotlin("android")
}

// 必须和主应用 app/build.gradle.kts 里的 CAPTURE_INGEST_TOKEN 一致。
val captureIngestToken = System.getenv("CAPTURE_INGEST_TOKEN")
    ?.takeIf { it.isNotBlank() }
    ?: (project.findProperty("CAPTURE_INGEST_TOKEN") as? String)?.takeIf { it.isNotBlank() }
    ?: "dev-token"

android {
    namespace = "com.taostudio.tapaccounting.capture"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.taostudio.tapaccounting.capture"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"

        // hook -> 插件/主应用 ingestion 的 token；不是强安全边界，个人使用足够。
        buildConfigField("String", "CAPTURE_INGEST_TOKEN", "\"$captureIngestToken\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        getByName("debug") {
            // 和主应用默认值保持一致，hook 侧调试包可直接使用。
            buildConfigField("String", "CAPTURE_INGEST_TOKEN", "\"$captureIngestToken\"")
        }
        getByName("release") {
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

dependencies {
    implementation(project(":capture-contract"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // LSPosed / Xposed API：只编译期需要，运行时由框架提供。
    compileOnly("de.robv.android.xposed:api:82")

    testImplementation("junit:junit:4.13.2")
}
