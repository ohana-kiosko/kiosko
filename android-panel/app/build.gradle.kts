import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application") version "8.12.0"
    id("org.jetbrains.kotlin.android") version "2.3.21"
}

val numero = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.ohanasurf.panelkiosko"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ohanasurf.panelkiosko"
        minSdk = 26
        targetSdk = 35
        versionCode = numero
        versionName = "1.$numero"
        // Dirección del panel (Vercel). Cambiar aquí cuando el kiosko tenga dominio propio.
        buildConfigField("String", "PANEL_URL", "\"https://kiosko-topaz.vercel.app/panel/\"")
    }
    buildFeatures { buildConfig = true }

    signingConfigs {
        create("panel") {
            storeFile = file("firma.jks")
            storePassword = "ohana-kiosko"
            keyAlias = "kiosko"
            keyPassword = "ohana-kiosko"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("panel")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
