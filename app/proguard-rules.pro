# AndroidX Media3 and Compose supply consumer rules with their libraries.
# JNI entry points and worker callback names are looked up from native code.
-keep class com.zhuanformat.app.NativeAudioBridge { *; }
-keep interface com.zhuanformat.app.NativeProgressCallback { *; }
-keepclassmembers class * implements com.zhuanformat.app.NativeProgressCallback { public void onProgress(float); }
