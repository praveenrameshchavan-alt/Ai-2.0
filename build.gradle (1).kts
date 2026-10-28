plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val trinetraApiUrl = providers.gradleProperty("TRINETRA_API_URL")
    .orElse(providers.environmentVariable("TRINETRA_API_URL"))
    .orElse("https://YOUR-HTTPS-BACKEND.example.com/chat")
    .get()

android {
    namespace = "com.example.hindiaassistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.hindiaassistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // Keep the backend URL configurable; never put provider API keys in the app.
        buildConfigField("String", "TRINETRA_API_URL", "\"${trinetraApiUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
