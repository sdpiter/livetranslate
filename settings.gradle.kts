pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        // не обязательно, но можно оставить и здесь
        maven("https://alphacephei.com/maven")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // ВОТ ЭТО НУЖНО ДЛЯ VOSK
        maven("https://alphacephei.com/maven")
    }
}
rootProject.name = "LiveTranslate"
include(":app")
