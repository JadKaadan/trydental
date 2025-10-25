# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ARCore
-keep class com.google.ar.** { *; }
-keep class com.google.ar.core.** { *; }
-keepclassmembers class com.google.ar.core.** { *; }

# Sceneform
-keep class com.google.ar.sceneform.** { *; }
-keep class com.gorisse.thomas.sceneform.** { *; }
-keepclassmembers class com.google.ar.sceneform.** { *; }



# TensorFlow Lite
-keep class org.tensorflow.** { *; }
-keepclassmembers class org.tensorflow.** { *; }


-keep class org.tensorflow.lite.** { *; }
-keep interface org.tensorflow.lite.** { *; }

-keepclassmembers class * {
    @org.tensorflow.lite.annotations.UsedByReflection *;
}
-keep class kotlin.Metadata { *; }

-keep class androidx.** { *; }
-keep interface androidx.** { *; }
