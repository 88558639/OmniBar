plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.omnibar.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.omnibar.app"
        minSdk = 34
        targetSdk = 36
        versionCode = 11
        versionName = "0.1.10"
    }
    buildTypes {
        create("compat") {
            initWith(getByName("release"))
            isDebuggable = false
            isMinifyEnabled = false
            // Preserve update compatibility with the existing test installation.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
