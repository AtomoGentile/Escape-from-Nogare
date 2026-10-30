-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }

# WebRTC calls these Java APIs from its native library.
-keep class org.webrtc.** { *; }
