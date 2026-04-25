# Keep kotlinx.serialization metadata
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.dartrack.**$$serializer { *; }
-keepclassmembers class com.dartrack.** { *** Companion; }
-keepclasseswithmembers class com.dartrack.** { kotlinx.serialization.KSerializer serializer(...); }
