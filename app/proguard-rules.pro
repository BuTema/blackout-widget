# The app's own code is small and reached through reflection in places (WebView JavaScript bridges,
# Glance action callbacks, WorkManager workers, manifest components), so it is kept as is; R8 shrinks
# the libraries, which are most of the size.
-keep class com.artem.blackoutwidget.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
-dontwarn org.json.**
