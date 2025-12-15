pluginManagement {
    repositories {
        google()            // 🔴 REQUIRED for Android plugins
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()            // 🔴 REQUIRED
        mavenCentral()
    }
}

rootProject.name = "TmmRelay"
include(":app")
