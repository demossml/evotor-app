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
        versionCode = 116
        versionName = "5.1.1"
        buildConfigField("String", "APP_UUID", "\"151071e8-88a4-44f6-b71a-b17c559f9b7d\"")
        // Единицы PRICE_OUT из ProductTable: KOPECKS (по умолчанию, как делает integration-library) | AUTO | RUBLES.
        buildConfigField("String", "PRICE_UNITS", "\"${project.findProperty("PRICE_UNITS") ?: "KOPECKS"}\"")
        // Public HTTPS URL of the backend as allowed in the Evotor cabinet (proxy mask). No secrets here.
        buildConfigField("String", "API_BASE_URL", "\"${project.findProperty("API_BASE_URL") ?: "https://app.67coffee.ru"}\"")
        // UUID магазина для /api/devices/catalog?storeUuid=… (можно зафиксировать при сборке: -PSTORE_UUID=…).
        buildConfigField("String", "STORE_UUID", "\"${project.findProperty("STORE_UUID") ?: ""}\"")
    }
    // Release-подпись: ключ задаётся снаружи (KEYSTORE_FILE/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD
    // или -P…). Ключа в репозитории нет — без него release собирается debug-ключом, чтобы APK
    // можно было установить на терминал (как в предыдущих сборках 4.9.x).
    signingConfigs {
        val ksPath = (project.findProperty("KEYSTORE_FILE") ?: System.getenv("KEYSTORE_FILE")) as String?
        if (!ksPath.isNullOrBlank()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = (project.findProperty("KEYSTORE_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD")) as String?
                keyAlias = (project.findProperty("KEY_ALIAS") ?: System.getenv("KEY_ALIAS")) as String?
                keyPassword = (project.findProperty("KEY_PASSWORD") ?: System.getenv("KEY_PASSWORD")) as String?
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    lint {
        // APK ставится на терминал Эвотор (targetSdk 30 — требование платформы кассы),
        // поэтому проверка Google Play «targetSdk 33+» к этой сборке неприменима.
        disable += "ExpiredTargetSdkVersion"
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
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
