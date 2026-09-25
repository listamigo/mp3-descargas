# Koin
-keepclassmembers class * {
    @org.koin.core.annotation.Single *;
    @org.koin.core.annotation.Factory *;
}
-dontwarn org.koin.**

# Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# SLF4J (binding opcional, no presente en Android)
-dontwarn org.slf4j.**
-dontwarn org.slf4j.impl.**

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
