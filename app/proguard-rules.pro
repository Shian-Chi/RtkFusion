# Keep GNSS and sensor classes
-keep class com.example.rtkgnss.** { *; }

# Apache Commons Math
-keep class org.apache.commons.math3.** { *; }
-dontwarn org.apache.commons.math3.**

# osmdroid
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**
