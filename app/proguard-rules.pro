# OpenCV Java bindings call into native code by name.
-keep class org.opencv.** { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.paretino.** { *** Companion; }
-keepclasseswithmembers class com.paretino.** { kotlinx.serialization.KSerializer serializer(...); }
