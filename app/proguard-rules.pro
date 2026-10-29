# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.gascanner.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
