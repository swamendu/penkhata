plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.penkhata"
    compileSdk = 34
    defaultConfig { applicationId = "com.example.penkhata"; minSdk = 26; targetSdk = 34; versionCode = 19; versionName = "2.9" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    kotlinOptions { jvmTarget = "1.8" }
    signingConfigs {
        create("release") {
            val ks = System.getenv("KEYSTORE_PATH")
            if (!ks.isNullOrBlank() && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            // Debug builds skip R8, so Compose runs noticeably slower. This is the
            // build to install on a phone.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with your keystore when the secrets are present, otherwise with the
            // debug key so the APK still installs for testing.
            signingConfig = if (!System.getenv("KEYSTORE_PATH").isNullOrBlank()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        getByName("debug") { isMinifyEnabled = false }
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2023.08.00"))
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling-preview")
}
