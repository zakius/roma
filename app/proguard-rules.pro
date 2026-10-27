# Sudachi creates its plugins by class name from sudachi.json; javax.json finds its provider by ServiceLoader.
-keep class * extends com.worksap.nlp.sudachi.Plugin { <init>(); }
# The plugins are package-private: the class creating them must stay in their package.
-keep class com.worksap.nlp.sudachi.Config$PluginConf { *; }
-keep class org.glassfish.json.JsonProviderImpl { <init>(); }
# RomaLattice reads LatticeImpl's list of nodes per end by name.
-keepclassmembers class com.worksap.nlp.sudachi.LatticeImpl { java.util.ArrayList endLists; }
# Kotlin's checks that a parameter isn't null: the body would hit the null anyway. Platform-type checks stay, so a
# Java null still fails where it enters Kotlin.
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkParameterIsNotNull(...);
    static void checkNotNullParameter(...);
}
