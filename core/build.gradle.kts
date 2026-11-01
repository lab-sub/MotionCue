import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 순수 Kotlin(JVM) 모듈 — Android·Pleos SDK에 의존하지 않는다.
// CueEngine 등 도메인 로직을 Android 없이 단위 테스트하기 위해 분리.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
