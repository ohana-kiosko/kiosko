// App Android «Panel Kiosko» (dueño, 21 nov 2026): el panel del dueño (cajas y horas de los camareros) como app del móvil.
// Se compila sola en GitHub (Actions › «App Panel Kiosko») y deja el .apk para descargar.
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
rootProject.name = "PanelKiosko"
include(":app")
