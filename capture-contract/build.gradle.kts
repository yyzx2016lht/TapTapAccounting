plugins {
    alias(libs.plugins.kotlin.jvm)
}

// 纯 Kotlin JVM：不依赖 Android，hook 侧和插件侧可以共用同一份契约。
kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}
