plugins {
    id("com.android.application")
}

android {
    namespace = "com.bromano.mperf.fixture"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bromano.mperf.fixture"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation("androidx.tracing:tracing:2.0.0-alpha09")
}
