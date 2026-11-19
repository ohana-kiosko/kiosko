import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application") version "8.12.0"
    id("org.jetbrains.kotlin.android") version "2.3.21"
}

val numero = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.ohanasurf.kiosko"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ohanasurf.kiosko"
        minSdk = 26
        targetSdk = 35
        versionCode = numero
        versionName = "1.$numero"
        // Dirección del programa del kiosko (Vercel). Cambiar aquí cuando tenga dominio propio.
        buildConfigField("String", "KIOSKO_URL", "\"https://kiosko-topaz.vercel.app/\"")
    }
    buildFeatures { buildConfig = true }

    // Firma de instalación (no es una clave de pagos): la misma en cada versión para poder actualizar
    // la app sin desinstalarla (desinstalar borraría las ventas y ajustes guardados en la tablet).
    signingConfigs {
        create("kiosko") {
            storeFile = file("firma.jks")
            storePassword = "ohana-kiosko"
            keyAlias = "kiosko"
            keyPassword = "ohana-kiosko"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("kiosko")
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

dependencies {
    implementation("com.stripe:stripeterminal-taptopay:6.0.0")
    implementation("com.stripe:stripeterminal-core:6.0.0")
}
