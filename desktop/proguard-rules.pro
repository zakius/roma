# The release distribution (createReleaseDistributable): shrinking and renaming, as finimal's desktop build does; no
# optimizing (ProGuard 7.8 fails on Kotlin 2.4's code). Stack traces keep line numbers and read through
# build/proguard-mapping.txt (retrace; each release attaches it). Exception names stay: the UI shows some.
-dontoptimize
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, EnclosingMethod, SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
-printmapping build/proguard-mapping.txt
-keepnames class * extends java.lang.Throwable
-adaptresourcefilenames META-INF/services/*
-adaptresourcefilecontents META-INF/services/*

# Enums looked up by name (Sudachi's settings), which R8 keeps on its own and ProGuard doesn't.
-keepclassmembers enum * { public static **[] values(); public static ** valueOf(java.lang.String); }

# Skia's native code calls back through Function0 by name.
-keep interface kotlin.jvm.functions.Function0 { *; }
-keep class * implements kotlinx.coroutines.internal.MainDispatcherFactory { *; }

# kotlinx.serialization: its own rules (META-INF/proguard in its jar, which ProGuard doesn't read).
-keepclassmembers @kotlinx.serialization.Serializable class ** { static ** Companion; }
-if @kotlinx.serialization.internal.NamedCompanion class *
-keepclassmembers class * { static <1> *; }
-if @kotlinx.serialization.Serializable class ** { static **$* *; }
-keepclassmembers class <2>$<3> { kotlinx.serialization.KSerializer serializer(...); }
-if @kotlinx.serialization.Serializable class ** { public static ** INSTANCE; }
-keepclassmembers class <1> { public static <1> INSTANCE; kotlinx.serialization.KSerializer serializer(...); }
-keepclassmembers public class **$$serializer { private ** descriptor; }

# Sudachi creates its plugins by class name and reads much of itself by reflection (RomaLattice too): kept whole, it
# is small. javax.json finds its provider by ServiceLoader.
-keep class com.worksap.nlp.sudachi.** { *; }
-keep class org.glassfish.json.JsonProviderImpl { <init>(); }

# ICU loads its transliterators and data classes by name.
-keep class com.ibm.icu.** { *; }
# CEF (Browser.kt's fallback): JCEF's native side calls back into Java by name, jcefmaven reads its build
# metadata through Gson, and JOGL draws the off-screen page.
-keep class org.cef.** { *; }
-keep class me.friwi.jcefmaven.** { *; }
-keep class com.google.gson.** { *; }
-keep class com.jogamp.** { *; }
-keep class jogamp.** { *; }
-keepclassmembers,allowobfuscation class * { @com.google.gson.annotations.SerializedName <fields>; }

# Optional parts of the libraries that roma doesn't use.
-dontwarn org.slf4j.**
-dontwarn org.jetbrains.annotations.**
-dontwarn org.tukaani.**
-dontwarn org.brotli.**
-dontwarn com.github.luben.**
-dontwarn org.objectweb.asm.**
-dontwarn org.apache.commons.compress.**
-dontwarn com.jogamp.**
-dontwarn jogamp.**
-dontwarn org.osgi.**
-dontwarn com.ibm.icu.**
-dontwarn javax.annotation.**
-dontnote **
# JCEF's macOS window and Sudachi's MethodHandle calls (signature-polymorphic, which ProGuard can't resolve).
-dontwarn org.cef.browser.mac.**
-dontwarn com.worksap.nlp.sudachi.MMap
-dontwarn com.worksap.nlp.sudachi.SudachiCommandLine
