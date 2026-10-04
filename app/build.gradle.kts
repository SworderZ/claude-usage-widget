import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val releaseSigningNames = listOf("ANDROID_KEYSTORE_PATH", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD")
val releaseSigningValues = releaseSigningNames.associateWith { System.getenv(it)?.takeIf(String::isNotBlank) }
val hasReleaseSigning = releaseSigningValues.values.any { it != null }
require(!hasReleaseSigning || releaseSigningValues.values.all { it != null }) {
    "Release signing requires all four ANDROID_KEYSTORE_PATH/PASSWORD and ANDROID_KEY_ALIAS/PASSWORD variables."
}

android {
    namespace = "space.megaworld.claudeusage"
    compileSdk = 35

    defaultConfig {
        applicationId = "space.megaworld.claudeusage"
        minSdk = 26
        targetSdk = 35
        versionCode = 28
        versionName = "0.16.2"
    }

    signingConfigs {
        if (hasReleaseSigning) create("release") {
            storeFile = file(releaseSigningValues.getValue("ANDROID_KEYSTORE_PATH")!!)
            storePassword = releaseSigningValues.getValue("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = releaseSigningValues.getValue("ANDROID_KEY_ALIAS")
            keyPassword = releaseSigningValues.getValue("ANDROID_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.tink.android)

    // Glyph Developer Kit от Nothing: на Maven его нет, AAR лежит рядом.
    // Источник: https://github.com/Nothing-Developer-Programme/Glyph-Developer-Kit
    implementation(files("libs/glyph-matrix-sdk-2.0.aar"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
