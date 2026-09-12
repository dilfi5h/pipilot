# sshj / bouncycastle
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
# sshj 的 AuthGssApiWithMic 引用 JGSS(Android 无此类,该认证方式在移动端也不会走到)
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.login.**
-keep class net.schmizz.sshj.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keepclassmembers class * extends net.schmizz.sshj.userauth.keyprovider.KeyProvider { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.pipilot.app.rpc.** {
    *** Companion;
}
-keepclasseswithmembers class dev.pipilot.app.rpc.** {
    kotlinx.serialization.KSerializer serializer(...);
}
