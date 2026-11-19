// App Android «Kiosko Ohana»: abre el programa del kiosko a pantalla completa y cobra con el NFC de la tablet (Stripe Tap to Pay).
// Se compila sola en GitHub (Actions › «App Android») y deja el .apk para descargar.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "KioskoOhana"
include(":app")
