plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.anilili.benchmark"
    compileSdk = 36
    buildToolsVersion = "35.0.1"

    defaultConfig {
        // Macrobenchmark requires API 23, but this test-only module does not change the app's
        // API-22 minimum for first/second-generation Fire TV Sticks.
        minSdk = 23
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    // The app's flavors, mirrored. Without them Gradle cannot pick which of the app's variants a
    // profile is for, and generation has failed with a variant-ambiguity error since the tv/mobile
    // split — which is how the shipped profile came to predate the `com.anilili` package entirely.
    flavorDimensions += "form"
    productFlavors {
        create("tv") { dimension = "form" }
        create("mobile") { dimension = "form" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

baselineProfile {
    // API 33+ connected emulators can generate profiles without root. Keeping the device external
    // lets this same module run against the TV emulator, a Firestick, or another Android TV box.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
