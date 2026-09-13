plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.securewa.architecture"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.securewa.architecture"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-m1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Release signing is configured only when a keystore is supplied through
        // the environment. Without it, release artifacts are produced unsigned:
        // they build, and therefore prove the release variant compiles, but they
        // are explicitly not distributable.
        create("releaseFromEnvironment") {
            val keystorePath = System.getenv("SECUREWA_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank() && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SECUREWA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SECUREWA_KEY_ALIAS")
                keyPassword = System.getenv("SECUREWA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Hardening flags stay off until a validated R8 ruleset exists. See
            // docs/MILESTONES.md, milestone 9.
            isMinifyEnabled = findProperty("securewa.release.minifyEnabled")?.toString()?.toBoolean() ?: false
            isShrinkResources = findProperty("securewa.release.shrinkResources")?.toString()?.toBoolean() ?: false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val keystorePath = System.getenv("SECUREWA_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank() && file(keystorePath).exists()) {
                signingConfig = signingConfigs.getByName("releaseFromEnvironment")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)

    testImplementation(libs.junit)
    testImplementation(project(":core"))
}
