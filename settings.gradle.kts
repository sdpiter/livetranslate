pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven("https://alphacephei.com/maven")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://alphacephei.com/maven")
        // при желании можно добавить JitPack как запасной
        // maven("https://jitpack.io")
    }
}
rootProject.name = "LiveTranslate"
include(":app")
