# kotlinx.serialization: keep generated serializers for @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class ** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.cocakova.pygmalion.**$$serializer { *; }
-keepclassmembers class com.cocakova.pygmalion.** { *** Companion; }

# Ktor / OkHttp optional platform classes
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn java.lang.management.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
