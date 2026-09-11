import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 签名密钥固定在仓库根的 pipilot-release.keystore(凭据在 keystore.properties,均不入 git)。
// 固定密钥是为了让新旧 APK 能互相覆盖安装:Android 拒绝安装与已装版本签名不同的包。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.pipilot.app"
    compileSdk = 35

    signingConfigs {
        create("pipilot") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    defaultConfig {
        applicationId = "dev.pipilot.app"
        minSdk = 26
        targetSdk = 35
        // 手工版本号:实机测 OK 之前每次发验证包 +1,App 内标题栏可见,防止旧包覆盖装不上的糊涂账
        versionCode = 10
        versionName = "0.0.10"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("pipilot")
        }
        release {
            signingConfig = signingConfigs.getByName("pipilot")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    implementation(libs.sshj) {
        // sshj 传递依赖里同时带了 bcprov-jdk15on 和 bcprov-jdk18on,排除旧版避免重复类
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
    }

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
