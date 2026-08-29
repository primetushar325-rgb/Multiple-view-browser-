# minifyEnabled is false for both build types, so nothing is stripped today.
# These rules exist so that enabling R8 later stays safe.
-keepattributes *Annotation*
-keep class com.example.multiview.panes.LayoutResolver { *; }
-keep class com.example.multiview.data.** { *; }
-dontwarn org.jetbrains.annotations.**
