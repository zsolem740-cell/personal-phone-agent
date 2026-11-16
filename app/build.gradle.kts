plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePath: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "hu.personal.phoneagent"
    compileSdk = 35

    defaultConfig {
        applicationId = "hu.personal.phoneagent"
        minSdk = 26
        targetSdk = 35
        versionCode = 23
        versionName = "0.9.12"
    }

    if (keystorePath != null) {
        signingConfigs {
            create("stable") {
                storeFile = file(keystorePath)
                storeType = "PKCS12"
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        debug {
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
        release {
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("stable")
            }
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
