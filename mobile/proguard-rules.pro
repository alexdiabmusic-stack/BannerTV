# Kotlin serialization supplies its own consumer rules; shared playback has no reflection entry points.
-keepattributes *Annotation*, InnerClasses, Signature, SourceFile, LineNumberTable
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
