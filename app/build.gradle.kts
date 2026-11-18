plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ZenithLiteAura.app.nezo"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ZenithLiteAura.app.nezo"
        minSdk = 26
        targetSdk = 37
        versionCode = 9
        versionName = "1.2.2"
    }

    signingConfigs {
        // 本仓库自带签名文件：debug / release 同签名，便于 adb install -r 覆盖安装
        create("clipnest") {
            storeFile = rootProject.file("clipnest-release.jks")
            storePassword = "clipnest123"
            keyAlias = "clipnest"
            keyPassword = "clipnest123"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("clipnest")
            isMinifyEnabled = false
            isShrinkResources = false
        }
        debug {
            signingConfig = signingConfigs.getByName("clipnest")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // Shizuku UserService 的 AIDL 需要显式打开（AGP 8+ 默认关闭）
        aidl = true
    }

    // 本机 AGP 9.2.1 的 lintVitalAnalyzeRelease 会内部崩溃（AsyncExecutionService 为 null，与项目代码无关），
    // 发布构建不跑 lint vital；需要 lint 时单独 `gradlew :app:lintDebug`。
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
        }
    }

}


dependencies {
    implementation(project(":core"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    // 小米风格组件库（与 NexioSchedule 同源）
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.blur)
    implementation(libs.materialKolor.utilities)

    implementation(libs.gson)

    // Shizuku：真·后台剪贴板监听（UserService 跑在 shell 进程）
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.shizuku.aidl)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // 纯 JVM 单测（下载工具类等）
    testImplementation("junit:junit:4.13.2")
}
