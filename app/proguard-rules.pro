# sshj / bouncycastle
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
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
