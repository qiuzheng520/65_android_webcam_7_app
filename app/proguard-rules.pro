# Keep the JS bridge so WebView can call it after obfuscation.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
