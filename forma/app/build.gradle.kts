plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "by.zaberezh.forma"
    compileSdk = 35

    defaultConfig {
        applicationId = "by.zaberezh.forma"
        minSdk = 28
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.$versionCode"
    }

    // Постоянный ключ: обновления ставятся поверх без потери данных.
    signingConfigs {
        create("forma") {
            storeFile = file("forma.keystore")
            storePassword = "formaforma"
            keyAlias = "forma"
            keyPassword = "formaforma"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("forma") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("forma")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
            "META-INF/INDEX.LIST", "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/FastDoubleParser-*",
        )
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
