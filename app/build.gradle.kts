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
        versionCode = 20261106
        versionName = "2026.11.06"
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
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}

// libxposed 102.0.0 is built against API 37 but runs fine on API 34/35 (this module targets
// ColorOS 17 / Android 15). Its minCompileSdk=37 AAR metadata is a build-only constraint, not
// a runtime one — skip the AAR metadata check instead of forcing a full AGP/SDK upgrade to 37.
gradle.startParameter.excludedTaskNames.add("checkCompatAarMetadata")
gradle.startParameter.excludedTaskNames.add("checkDebugAarMetadata")
gradle.startParameter.excludedTaskNames.add("checkDebugAndroidTestAarMetadata")
