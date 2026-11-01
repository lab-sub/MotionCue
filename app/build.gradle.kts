import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 비밀값(서명 키 비밀번호 등)은 커밋하지 않는 local.properties에서만 읽는다.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val releaseKeystorePath: String? = localProperties.getProperty("motioncue.keystore.path")

// 대회 제출 규칙: apk/[팀명]_MotionCue_release.apk
val teamName: String = localProperties.getProperty("motioncue.teamName") ?: "TEAM"

android {
    namespace = "app.motioncue"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.motioncue"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "app.motioncue.probe.LiveSessionProbe"

        buildConfigField("String", "VEHICLE_SDK_VERSION", "\"${libs.versions.pleosVehicle.get()}\"")
        buildConfigField("String", "NAVI_HELPER_SDK_VERSION", "\"${libs.versions.pleosNaviHelper.get()}\"")
        buildConfigField("String", "FUSED_LOCATION_SDK_VERSION", "\"${libs.versions.pleosFusedLocation.get()}\"")
    }

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = localProperties.getProperty("motioncue.keystore.password")
                keyAlias = localProperties.getProperty("motioncue.key.alias")
                keyPassword = localProperties.getProperty("motioncue.key.password")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 키스토어를 지정하지 않았으면 debug 키로 서명해 설치 가능한 데모 APK를 만든다.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    implementation(libs.pleos.fusedlocation)
    implementation(libs.pleos.tts)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}

// release APK를 대회 제출 이름으로 복사: ./gradlew :app:exportReleaseApk
tasks.register<Copy>("exportReleaseApk") {
    description = "Copies the release APK to apk/<teamName>_MotionCue_release.apk"
    dependsOn("assembleRelease")
    // rename 람다의 외부 값도 입력으로 추적해 팀명 변경 시 새 파일을 내보낸다.
    inputs.property("teamName", teamName)
    from(layout.buildDirectory.dir("outputs/apk/release"))
    include("*.apk")
    into(rootProject.layout.projectDirectory.dir("apk"))
    rename { "${teamName}_MotionCue_release.apk" }
}
