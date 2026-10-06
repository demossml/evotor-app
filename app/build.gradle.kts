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
        versionCode = 52
        versionName = "2.3.1-card-manual"
        val serverPublicKey = (project.findProperty("SERVER_PUBLIC_KEY") as String?)?.trim()?.replace("\"", "\\\"") ?: ""
    val serverKeyId = (project.findProperty("SERVER_KEY_ID") as String?)?.trim()?.replace("\"", "\\\"") ?: ""
    val cupsForFree = (project.findProperty("CUPS_FOR_FREE") as String?)?.trim()?.toIntOrNull() ?: 5
    buildConfigField("String", "APP_UUID", "\"151071e8-88a4-44f6-b71a-b17c559f9b7d\"")
    buildConfigField("String", "SERVER_PUBLIC_KEY", "\"$serverPublicKey\"")
    buildConfigField("String", "SERVER_KEY_ID", "\"$serverKeyId\"")
    buildConfigField("int", "CUPS_FOR_FREE", "$cupsForFree")
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("net.i2p.crypto:eddsa:0.3.0")
}
