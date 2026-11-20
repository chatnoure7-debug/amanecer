import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.reloj.despertar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.reloj.despertar"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
    }

    // Firma fija: así las versiones nuevas se instalan ENCIMA de la anterior sin perder las alarmas.
    // (La clave está en amanecer.keystore.b64; es la típica de pruebas, solo para instalar a mano.)
    val keystoreB64 = file("amanecer.keystore.b64")
    val keystoreFile = layout.buildDirectory.file("amanecer.keystore").get().asFile
    if (keystoreB64.exists()) {
        keystoreFile.parentFile.mkdirs()
        keystoreFile.writeBytes(Base64.getMimeDecoder().decode(keystoreB64.readText()))
        signingConfigs.getByName("debug") {
            storeFile = keystoreFile
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    testImplementation("junit:junit:4.13.2")
}
