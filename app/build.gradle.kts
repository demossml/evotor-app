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
        versionCode = 54
        versionName = "3.0.0-cashier-ui"
        buildConfigField("String", "APP_UUID", "\"151071e8-88a4-44f6-b71a-b17c559f9b7d\"")
        buildConfigField("String", "SERVER_PUBLIC_KEY", "\"${project.findProperty("SERVER_PUBLIC_KEY") ?: ""}\"")
        buildConfigField("String", "SERVER_KEY_ID", "\"${project.findProperty("SERVER_KEY_ID") ?: "main"}\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}
dependencies {
    implementation("com.github.evotor:integration-library:v0.6.27")
    implementation("net.i2p.crypto:eddsa:0.3.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
