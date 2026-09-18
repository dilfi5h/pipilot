import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Signing key is pinned at repo-root pipilot-release.keystore (credentials in keystore.properties; neither is in git).
// A fixed key lets new APKs overwrite old installs: Android refuses a package signed differently from the installed one.
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
        versionCode = 17
        versionName = "0.0.17"
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
        // sshj transitively pulls both bcprov-jdk15on and bcprov-jdk18on; exclude the old module to avoid duplicate classes
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
    }

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
