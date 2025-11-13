pluginManagement {
    repositories {
        // 👇 Google ОБЯЗАТЕЛЕН ПЕРВЫМ!
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

rootProject.name = "livetranslate"
include(":app")
