// Build-time tool, not part of the app: turns SudachiDict core into the slimmer dictionary roma ships (Slim.kt).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_21 } }

dependencies {
    implementation("com.worksap.nlp:sudachi:0.8.2")
}
