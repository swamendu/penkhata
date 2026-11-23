# Keep line numbers for readable crash reports, nothing else is needed here:
# the app uses no reflection, and Compose ships its own consumer rules.
-keepattributes SourceFile,LineNumberTable

# Play Services code scanner: the delegate activity is looked up by name.
-keep class com.google.mlkit.vision.codescanner.** { *; }
-keep class com.google.mlkit.vision.barcode.** { *; }
-dontwarn com.google.mlkit.**
