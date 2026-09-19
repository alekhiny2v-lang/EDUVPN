# EDUVPN ProGuard/R8 rules.

# The OpenVPN engine is driven reflectively in places and its service is
# instantiated by the framework. Keep the whole package.
-keep class de.blinkt.openvpn.** { *; }
-dontwarn de.blinkt.openvpn.**

# JNI bridge into libopenvpn / minivpn.
-keepclasseswithmembernames class * {
    native <methods>;
}

# OkHttp optional dependencies it probes for at runtime.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Kotlin coroutines use volatile-field tricks that confuse R8 on some versions.
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
