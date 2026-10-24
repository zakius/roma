# NewPipeExtractor runs YouTube's player JavaScript through Rhino.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn org.slf4j.**
# Kuromoji loads its dictionary by class-relative resource paths.
-keep class com.atilika.kuromoji.** { *; }
