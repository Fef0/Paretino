# ONNX Runtime's native code finds these classes and fields by name.
-keep class ai.onnxruntime.** { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.paretino.** { *** Companion; }
-keepclasseswithmembers class com.paretino.** { kotlinx.serialization.KSerializer serializer(...); }
