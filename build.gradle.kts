// R8 newer than AGP's own (9.4): its release APK is 16 KB smaller. Drop this when AGP bundles 9.5 or later.
buildscript {
    repositories { google() }
    dependencies { classpath("com.android.tools:r8:9.5.28") }
}
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
    id("org.jetbrains.compose") version "1.12.2" apply false
}
