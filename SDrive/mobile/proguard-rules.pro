-dontwarn org.slf4j.**
-dontwarn javax.annotation.**

# ---- ADB terminal (LibADB + Conscrypt + BouncyCastle + Spake2) ----
# Conscrypt is looked up by reflection and uses JNI; Spake2 uses JNI.
-keep class org.conscrypt.** { *; }
-keep class io.github.muntashirakon.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.conscrypt.**
-dontwarn com.android.org.conscrypt.**
-dontwarn org.apache.harmony.xnet.provider.jsse.**
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn sun.security.**
-dontwarn java.lang.invoke.StringConcatFactory
-ignorewarnings
