# WenQu proguard rules
-dontwarn org.jsoup.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keep class org.jsoup.** { *; }
-keepattributes JavascriptInterface
-keepclassmembers class * extends android.webkit.WebViewClient { public *; }
-keepclassmembers class * extends android.webkit.WebChromeClient { public *; }
