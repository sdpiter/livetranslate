plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // ‼️ УДАЛЕН "kotlin-kapt", он не используется в вашем коде
    id("kotlin-parcelize") // <-- ✅ ВОЗВРАЩЕН (на всякий случай)
}

android {
    namespace = "com.sdpiter.livetranslate" // <-- Я поменял на тот, что был в коде
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sdpiter.livetranslate"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Включим MultiDex, он был в старом файле
        multiDexEnabled = true 
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false // Временно выключаем, чтобы не мешало
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = "17"
    }
    
    buildFeatures {
        // ✅ ВКЛЮЧАЕМ COMPOSE
        compose = true 
        // ❌ ВЫКЛЮЧАЕМ ОСТАЛЬНОЕ
        viewBinding = false
        dataBinding = false
    }

    // ✅ ДОБАВЛЯЕМ ОПЦИИ COMPOSE
    composeOptions {
        // Версия из вашего старого проекта
        kotlinCompilerExtensionVersion = "1.5.14" 
    }

    // ✅ ДОБАВЛЯЕМ УПАКОВКУ (была в старом проекте)
    packaging {
        resources {
            excludes.addAll(setOf(
                "META-INF/**",
                "lib/x86/**",
                "lib/x86_64/**",
                "*.so"
            ))
        }
    }
}

dependencies {
    // --- AndroidX Core & Lifecycle (из вашего нового файла) ---
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // --- Material (из нового файла) ---
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // --- Coroutines (из нового файла) ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    
    // --- Networking & JSON (из нового файла) ---
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    
    // --- Preferences (из нового файла) ---
    implementation("androidx.preference:preference-ktx:1.2.1")

    // --- MultiDex (из старого файла) ---
    implementation("androidx.multidex:multidex:2.0.1")

    // --- ✅ ML Kit (из старого файла) ---
    implementation("com.google.mlkit:translate:17.0.2")

    // --- ✅ Vosk (из старого файла) ---
    implementation("com.alphacephei:vosk-android:0.3.45") {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    // --- ✅ TTS (из старого файла) ---
    implementation("com.google.android.tts:tts:3.0.0")

    // --- ✅ JETPACK COMPOSE (из старого файла) ---
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    
    // --- Testing ---
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

// ‼️ БЛОК KAPT УДАЛЕН, он не нужен
