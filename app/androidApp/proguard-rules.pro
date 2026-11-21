# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.lework.**$$serializer { *; }
-keepclassmembers class dev.lework.** {
    *** Companion;
}
-keepclasseswithmembers class dev.lework.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor & OkHttp
-dontwarn io.ktor.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.slf4j.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ZXing QR Decoder
-dontwarn com.google.zxing.**
