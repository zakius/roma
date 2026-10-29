// roma for Linux and Windows: the app's screens and playback logic (shared/, compiled into :app too) on Compose for
// Desktop, with libVLC for audio and the system's Chromium-family browser for BotGuard and sign-in (docs/design.md).
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

// One version for both apps, read from the Android build where release.sh and the release workflow look for it.
val appGradle = file("../app/build.gradle.kts").readText()
val versionName = Regex("""versionName = "(.+)"""").find(appGradle)!!.groupValues[1]
val versionCode = Regex("""versionCode = (\d+)""").find(appGradle)!!.groupValues[1]
evaluationDependsOn(":core")
val dictVersion = project(":core").extra["dictRelease"] as String

java { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_21 }
    sourceSets["main"].kotlin.srcDir("../shared/src/main/kotlin")
    sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("generated/buildConfig"))
}
// From the Android app: the player's icons (vector drawables, which Compose for Desktop reads too), BotGuard's page
// and the licence notices.
sourceSets["main"].resources.srcDirs("../app/src/main/res", "../app/src/main/assets")
sourceSets["main"].resources.include("drawable/*.xml", "po_token.html", "NOTICE", "*.txt", "roma.png")

// What Android's BuildConfig holds, for the shared code.
val buildConfig = tasks.register("buildConfig") {
    val out = layout.buildDirectory.file("generated/buildConfig/BuildConfig.kt")
    val refuse = findProperty("roma.refuseVisionOs") == "true"
    val dictBase = (findProperty("roma.dictBase") as String?) ?: "https://github.com/zakius/roma/releases/download/dict-$dictVersion"
    inputs.property("v", "$versionName $versionCode $dictVersion $refuse $dictBase")
    outputs.file(out)
    doLast {
        out.get().asFile.writeText("""
            |package com.zakius.roma
            |
            |object BuildConfig {
            |    const val VERSION_NAME = "$versionName"
            |    const val VERSION_CODE = $versionCode
            |    const val DICT_VERSION = "$dictVersion"
            |    const val DICT_URL = "$dictBase/roma-dict-$dictVersion.apk.gz"
            |    const val REFUSE_VISIONOS = $refuse
            |}
            |""".trimMargin())
    }
}
tasks.named("compileKotlin") { dependsOn(buildConfig) }

// roma-aac, the audio-only ffmpeg for the songs YouTube has only in AAC (tools/build-ffmpeg.sh), built once per version
// into the Gradle cache. Linux builds both (Windows' by cross-compiling); elsewhere -Proma.aacDir=<dir> hands over
// prebuilt ones (<dir>/linux/roma-aac, <dir>/windows/roma-aac.exe), as the Windows release job does.
val ffmpegVersion = Regex("""FFMPEG=(\S+)""").find(rootProject.file("tools/build-ffmpeg.sh").readText())!!.groupValues[1]
val aacDir = (findProperty("roma.aacDir") as String?)?.let(::File) ?: File(gradle.gradleUserHomeDir, "caches/roma/roma-aac-$ffmpegVersion")
val windowsHost = System.getProperty("os.name").startsWith("Windows")
val romaAac = tasks.register("romaAac") {
    val script = rootProject.file("tools/build-ffmpeg.sh")
    inputs.file(script)
    outputs.dir(aacDir)
    onlyIf { !windowsHost }
    doLast {
        for ((os, exe) in listOf("linux" to "roma-aac", "windows" to "roma-aac.exe")) {
            if (File(aacDir, "$os/$exe").exists()) continue
            providers.exec { commandLine(script.path, os, File(aacDir, os).path) }.result.get()
        }
    }
}
// The package's own resources (compose.application.resources.dir): per OS, roma-aac.
val appResources = layout.buildDirectory.dir("appResources")
val copyAppResources = tasks.register<Sync>("copyAppResources") {
    dependsOn(romaAac)
    from(File(aacDir, "linux")) { into("linux") }
    from(File(aacDir, "windows")) { into("windows") }
    into(appResources)
}
tasks.matching { it.name.startsWith("prepareAppResources") }.configureEach { dependsOn(copyAppResources) }

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    // Transliteration (Chinese pinyin, Greek, the Cyrillic safety net): Android has it as android.icu.
    implementation("com.ibm.icu:icu4j:78.1")
    // Opus, what YouTube serves nearly every song in, decoded in pure Java. The rest (AAC) goes to roma-aac.
    implementation("io.github.jaredmdobson:concentus:1.0.2")
    // Chromium for BotGuard and sign-in when the system has no Chromium-family browser: CEF, downloaded on first need.
    // 146, not 152: CEF 152 exits at start on Linux (code 28), off-screen or not (2026-10-29).
    implementation("me.friwi:jcefmaven:146.0.10")
}

compose.desktop {
    application {
        mainClass = "com.zakius.roma.MainKt"
        // The release distribution (createReleaseDistributable) drops what roma never calls, as finimal's does.
        buildTypes.release.proguard {
            configurationFiles.from(project.file("proguard-rules.pro"))
            obfuscate = true
        }
        nativeDistributions {
            appResourcesRootDir.set(appResources)
            // Linux ships as a .deb and .rpm on the distribution's Java (tools/linux-packages.sh); Windows as this
            // .msi with its own Java ("full") and a small one that finds or downloads Java (tools/windows-small.sh).
            targetFormats(TargetFormat.Msi)
            packageName = "roma"
            packageVersion = versionName.split('.').let { (it + listOf("0", "0")).take(3) }.joinToString(".")
            description = "YouTube Music, every title and lyric romanized"
            vendor = "zakius"
            licenseFile.set(rootProject.file("LICENSE"))
            // The full build's Java: the JDK's modules roma uses, from a JDK 22 or later, whose java.base carries the
            // TLS curves: java.net.http for the browser's DevTools socket, jdk.httpserver for Last.fm's sign-in,
            // java.management for CEF's process. suggestRuntimeModules also lists jdk.javadoc (the whole compiler,
            // 25 MB) and java.instrument, which only optional code refers to.
            modules("java.management", "java.net.http", "java.sql", "jdk.httpserver", "jdk.unsupported")
            windows {
                iconFile.set(rootProject.file("packaging/roma.ico"))
                menuGroup = "roma"
                perUserInstall = true
                shortcut = true
                // Fixed, so a new MSI (full or small) replaces the installed version instead of installing beside it.
                upgradeUuid = "6d0b7c3e-1f5a-4d7e-9a43-6f8f0b2c9e41"
            }
        }
    }
}

dependencies { testImplementation(kotlin("test")) }
tasks.register<JavaExec>("decodeCheck") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.zakius.roma.DecodeCheckKt"
    dependsOn(copyAppResources)
    systemProperty("roma.aac", (findProperty("aac") as String?) ?: File(aacDir, if (windowsHost) "windows/roma-aac.exe" else "linux/roma-aac").path)
    args((findProperty("files") as String? ?: "").split(',').filter { it.isNotBlank() })
}
tasks.register<JavaExec>("botGuardCheck") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.zakius.roma.BotGuardCheckKt"
    if (project.hasProperty("cef")) systemProperty("cef", "")
}

// Linux: Skia's symbol table (3 MB). Its exported symbols stay; the Java runtime keeps its own for crash logs.
tasks.matching { it.name == "createDistributable" || it.name == "createReleaseDistributable" }.configureEach {
    doLast {
        if (System.getProperty("os.name") != "Linux") return@doLast
        val libs = outputs.files.asFileTree.matching { include("**/lib/app/*.so") }.files.map { it.path }
        val strip = ProcessBuilder(listOf("strip", "--strip-unneeded") + libs).inheritIO().start()
        check(strip.waitFor() == 0) { "strip failed" }
    }
}

// Compose's default ProGuard rules keep all of Kotlin's standard library, which roma mostly doesn't call (finimal's
// measurement: 5.6 MB of jars). Skia's native code needs only Function0 (proguard-rules.pro).
afterEvaluate {
    val proguard = tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractProguardTask>()
    val stock = proguard.first().defaultComposeRulesFile.get().asFile
    val trimmed = layout.buildDirectory.file("compose-proguard-rules.pro")
    val trimRules = tasks.register("trimComposeProguardRules") {
        dependsOn("unpackDefaultComposeDesktopJvmApplicationResources")
        inputs.file(stock)
        outputs.file(trimmed)
        doLast {
            val rules = stock.readLines()
            val kept = rules.filterNot { it == "-keep class kotlin.** { *; }" }
            check(kept.size < rules.size) { "Compose's default rules no longer keep kotlin.**; this filter can go" }
            trimmed.get().asFile.writeText(kept.joinToString("\n", postfix = "\n"))
        }
    }
    proguard.configureEach {
        dependsOn(trimRules)
        defaultComposeRulesFile.set(trimmed)
    }
}
