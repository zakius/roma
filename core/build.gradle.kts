// Plain JVM module: no Android imports, so a desktop client could reuse it.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_21 } }

dependencies {
    // YouTube Music API: the vendored com.metrolist.innertube sources sit on top of InnerTubeX.
    api("com.github.MetrolistGroup.innertubex:innertubex:v0.11.0")
    api("io.ktor:ktor-client-core:3.6.0")
    implementation("io.ktor:ktor-client-okhttp:3.6.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.6.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.0")
    implementation("io.ktor:ktor-client-encoding:3.6.0")
    // Stream URLs.
    api("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

tasks.register<JavaExec>("networkCheck") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "roma.core.NetworkCheckKt"
}
