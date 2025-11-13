// Root build.gradle.kts
plugins {
    // Плагин для Android проекта
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

task<Delete>("clean") {
    delete(rootProject.buildDir)
}
