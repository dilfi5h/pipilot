plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.pipilot.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.pipilot.app"
        minSdk = 26
        targetSdk = 35
        // 手工版本号:实机测 OK 之前每次发验证包 +1,App 内标题栏可见,防止旧包覆盖装不上的糊涂账
        versionCode = 4
        versionName = "0.0.4"
    }

    buildTypes {
        release {
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
