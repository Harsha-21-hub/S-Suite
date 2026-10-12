# Keep media3 session service & wearable listener (also referenced from manifest)
-keep class com.sdrive.shared.** extends android.app.Service { *; }
-keep class com.sdrive.shared.InitProvider { *; }
