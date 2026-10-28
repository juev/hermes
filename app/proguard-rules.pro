# kotlinx.serialization keeps generated serializers through its own consumer rules.
-dontwarn org.slf4j.**
# The tailnet node is called by reflection (Tailnet.Engine); gomobile glue calls back into Java by JNI.
-keep class org.evsyukov.hermes.tailnet.go.** { *; }
-keep class go.** { *; }
