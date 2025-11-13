# LiveTranslate

Android-приложение для перевода речи в реальном времени (ASR → MT → TTS).  
Поддерживает оффлайн ASR через Vosk и перевод через Google ML Kit, вывод перевода через overlay и озвучку через Android TTS.

> Версия: 0.1-alpha1 (из `app/build.gradle.kts`)

---

## Быстрая сборка (локально)

Требования:
- JDK 11/17
- Android SDK с платформой API 34 и build-tools 34.0.0
- adb (platform-tools)
- Рекомендуется: реальное устройство Android (ARM64) для тестирования Vosk

Сборка:
```bash
# из корня репозитория
./gradlew clean assembleDebug
# APK появится:
app/build/outputs/apk/debug/app-debug.apk
