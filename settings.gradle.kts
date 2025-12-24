pluginManagement {
    repositories {
        google()            // 🔴 REQUIRED for Android plugins
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()            // 🔴 REQUIRED
        mavenCentral()
    }
}

rootProject.name = "TmmRelay"
include(":app")
