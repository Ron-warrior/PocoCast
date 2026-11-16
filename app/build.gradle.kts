plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.pococast"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.pococast"
        minSdk = 29
        targetSdk = 33
        versionCode = 16
        versionName = "0.5.0"
    }
    // Release builds are signed with the same key previous builds used, so the
    // installed app can still be replaced instead of uninstalled first.
    signingConfigs {
        create("shared") {
            storeFile = File(System.getProperty("user.home"), ".android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        release {
            // A debuggable APK is the main thing Play Protect flags as unsafe, so
            // the shipped build must never be one.
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
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
    // Silently installs its own updates through the user's Shizuku (ADB shell).
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // Speaks the phone's own Wireless debugging protocol, so updates can install
    // themselves with nothing but this app on the phone.
    implementation("com.github.MuntashirAkon:libadb-android:2.2.2") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
    }
    implementation("org.bouncycastle:bcprov-jdk15to18:1.76")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.76")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5") {
        exclude(group = "org.mozilla", module = "rhino")
        exclude(group = "org.mozilla", module = "rhino-engine")
    }
    implementation("org.mozilla:rhino:1.7.15")
}
