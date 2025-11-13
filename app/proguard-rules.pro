# Vosk + JNA rules
-keep class com.alphacep.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keep class org.robolectric.** { *; }

# ML Kit rules
-keep class com.google.mlkit.** { *; }

# Coroutines
-keep class kotlinx.coroutines.** { *; }

# General rules
-keepattributes *Annotation*
-keepclassmembers class * {
    public <init>(android.content.Context);
}
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
