plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.pococast"
    compileSdk = 33
    defaultConfig {
        applicationId = "com.pococast"
        minSdk = 29
        targetSdk = 33
        versionCode = 7
        versionName = "0.3.4"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
    lint { abortOnError = false }
}
dependencies {
    implementation(project(":sender"))
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5") {
        exclude(group = "org.mozilla", module = "rhino")
        exclude(group = "org.mozilla", module = "rhino-engine")
    }
    implementation("org.mozilla:rhino:1.7.15")
}
