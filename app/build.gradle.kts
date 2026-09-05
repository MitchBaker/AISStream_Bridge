plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "net.mitch.aisbridge"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "net.mitch.aisbridge"
        minSdk = 26
        targetSdk = 37
        versionCode = 12
        versionName = "94262223_TypeExpansion"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    //implementation("androidx.core:core-ktx:1.13.1")
    //implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}