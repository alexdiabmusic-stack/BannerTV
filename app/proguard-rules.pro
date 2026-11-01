# kotlinx.serialization keeps its generated serializers via the plugin; nothing extra is needed
# for @Serializable classes. Keep model names readable in crash reports.
-keepattributes *Annotation*, InnerClasses, Signature, SourceFile, LineNumberTable
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
