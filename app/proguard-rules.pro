# WenQu proguard rules
-dontwarn org.jsoup.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keep class org.jsoup.** { *; }
-keepattributes JavascriptInterface
-keepclassmembers class * extends android.webkit.WebViewClient { public *; }
-keepclassmembers class * extends android.webkit.WebChromeClient { public *; }

# 本地大模型 JNI：类名/方法名必须与 llama-android.cpp 中注册名一致
-keep class com.wenqu.app.LlmJni { *; }
