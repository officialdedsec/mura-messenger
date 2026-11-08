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
        // Guardian Project's Maven repo: Tor for Android (tor-android) and its control library (jtorctl).
        maven { url = uri("https://raw.githubusercontent.com/guardianproject/gpmaven/master") }
    }
}

rootProject.name = "Mura"
include(":app")
