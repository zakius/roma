# NewPipeExtractor runs YouTube's player JavaScript through Rhino.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn org.slf4j.**
# Sudachi creates its plugins by class name from sudachi.json; javax.json finds its provider by ServiceLoader.
-keep class com.worksap.nlp.sudachi.** { *; }
-keep class org.glassfish.json.** { *; }
# Optional JDK classes referenced by Rhino and other JVM libraries, absent on Android.
-dontwarn jdk.dynalink.**
