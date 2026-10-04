plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.sixthcup.evotor"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.sixthcup.evotor"
        minSdk = 23
        targetSdk = 30
        versionCode = 31
        versionName = "2.1.0-stage1"
        buildConfigField("String", "APP_UUID", "\"${project.findProperty("APP_UUID") ?: "00000000-0000-0000-0000-000000000000"}\"")
        buildConfigField("String", "API_BASE_URL", "\"https://app.67coffee.ru\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // Evotor SDK — required on real terminal; app still builds UI without device APIs mocked
    implementation("com.github.evotor:integration-library:v0.6.27")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // QR display for loyalty receipt
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.78.1")
}
