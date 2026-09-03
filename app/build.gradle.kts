plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.gms.google-services")
}

android {
    namespace = "com.ma.sms.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ma.sms.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 36
        versionName = "1.1.34"
        manifestPlaceholders["appAuthRedirectScheme"] = "com.ma.sms.android"
    }

    signingConfigs {
        create("release") {
            storeFile = file("/home/ubuntu/sms-release.keystore")
            storePassword = "smspass123"
            keyAlias = "sms-key"
            keyPassword = "smspass123"
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "BASE_URL", "\"https://smsproject.duckdns.org\"")
            buildConfigField("String", "KEYCLOAK_URL", "\"https://smsproject.duckdns.org/auth\"")
            buildConfigField("String", "KEYCLOAK_REALM", "\"sms-realm\"")
            buildConfigField("String", "KEYCLOAK_CLIENT_ID", "\"sms-mobile\"")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            buildConfigField("String", "BASE_URL", "\"https://smsproject.duckdns.org\"")
            buildConfigField("String", "KEYCLOAK_URL", "\"https://smsproject.duckdns.org/auth\"")
            buildConfigField("String", "KEYCLOAK_REALM", "\"sms-realm\"")
            buildConfigField("String", "KEYCLOAK_CLIENT_ID", "\"sms-mobile\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)
    implementation(libs.appauth)
    implementation(libs.security.crypto)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.play.services.location)
    debugImplementation(libs.androidx.ui.tooling)
}
