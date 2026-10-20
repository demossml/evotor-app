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
        versionCode = 103
        versionName = "4.9.4-toppings-link-fallback"
        buildConfigField("String", "APP_UUID", "\"151071e8-88a4-44f6-b71a-b17c559f9b7d\"")
        // Единицы PRICE_OUT из ProductTable: KOPECKS (по умолчанию, как делает integration-library) | AUTO | RUBLES.
        buildConfigField("String", "PRICE_UNITS", "\"${project.findProperty("PRICE_UNITS") ?: "KOPECKS"}\"")
        // Public HTTPS URL of the backend as allowed in the Evotor cabinet (proxy mask). No secrets here.
        buildConfigField("String", "API_BASE_URL", "\"${project.findProperty("API_BASE_URL") ?: "https://app.67coffee.ru"}\"")
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
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
