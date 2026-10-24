// Plain JVM module: no Android imports, so a desktop client could reuse it.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.security.MessageDigest

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
    api("com.github.MetrolistGroup.innertubex:innertubex:v0.11.2")
    api("io.ktor:ktor-client-core:3.6.0")
    implementation("io.ktor:ktor-client-okhttp:3.6.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.6.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.0")
    implementation("io.ktor:ktor-client-encoding:3.6.0")
    // Stream URLs.
    api("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    // Japanese words and readings. The dictionary isn't in the jar: see sudachiDict below.
    implementation("com.worksap.nlp:sudachi:0.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation(kotlin("test"))
    // Android supplies android.icu; on the JVM the tests use ICU4J.
    testImplementation("com.ibm.icu:icu4j:78.1")
}

// SudachiDict "core", downloaded once into the Gradle cache (202 MB unpacked, too big for git). The app ships it as
// an asset; the JVM tests read it from here. To update: change the version and the zip's sha256 (docs/design.md).
val sudachiVersion = "20261015"
val sudachiSha256 = "5cf5cb7f1ea943a988e2fb5d285a9a8b3538861faefa7a2604c2cd18c387f503"
val sudachiDir = File(gradle.gradleUserHomeDir, "caches/roma/sudachi-core-$sudachiVersion")
extra["sudachiAssets"] = File(sudachiDir, "assets")

tasks.register("sudachiDict") {
    val assets = File(sudachiDir, "assets")
    outputs.dir(assets)
    doLast {
        if (File(assets, "system_core.dic").exists()) return@doLast
        val zip = File(sudachiDir, "core.zip")
        sudachiDir.mkdirs()
        URI("https://github.com/WorksApplications/SudachiDict/releases/download/v$sudachiVersion/sudachi-dictionary-$sudachiVersion-core.zip")
            .toURL().openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
        val digest = MessageDigest.getInstance("SHA-256").digest(zip.readBytes()).joinToString("") { "%02x".format(it) }
        check(digest == sudachiSha256) { "SudachiDict zip: sha256 $digest, expected $sudachiSha256" }
        project.copy {
            from(zipTree(zip)) { include("*/system_core.dic", "*/LEGAL"); eachFile { path = if (name == "LEGAL") "sudachi-LEGAL.txt" else name } }
            into(assets)
            includeEmptyDirs = false
        }
        zip.delete()
    }
}

tasks.test {
    useJUnitPlatform()
    dependsOn("sudachiDict")
    systemProperty("roma.sudachi", File(sudachiDir, "assets/system_core.dic").path)
}

tasks.register<JavaExec>("networkCheck") {
    classpath = sourceSets["test"].runtimeClasspath
    dependsOn("sudachiDict")
    systemProperty("roma.sudachi", File(sudachiDir, "assets/system_core.dic").path)
    mainClass = (project.findProperty("main") as String?) ?: "roma.core.NetworkCheckKt"
}
