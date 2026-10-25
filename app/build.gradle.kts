plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// The Japanese dictionary is a separate app, :dict; roma needs its version and where to fetch it.
evaluationDependsOn(":core")
val dictVersion = project(":core").extra["dictRelease"] as String

android {
    namespace = "com.zakius.roma"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.zakius.roma"
        minSdk = 26
        targetSdk = 36
        versionCode = 35
        versionName = "0.1.34"
        // Testing only: `-Proma.refuseVisionOs=true` acts as if YouTube refused the VISIONOS client (Streams.refuseVisionOs),
        // so songs play through InnerTubeX's other clients. Never set for a release.
        buildConfigField("boolean", "REFUSE_VISIONOS", (findProperty("roma.refuseVisionOs") == "true").toString())
        // The dictionary app, gzipped. `-Proma.dictBase=<url>` points a build at another folder holding it.
        val dictBase = (findProperty("roma.dictBase") as String?) ?: "https://github.com/zakius/roma/releases/download/dict-$dictVersion"
        buildConfigField("String", "DICT_URL", "\"$dictBase/roma-dict-$dictVersion.apk.gz\"")
        buildConfigField("int", "DICT_VERSION", (project(":core").extra["dictCode"] as Int).toString())
        buildConfigField("String", "DICT_PACKAGE", "\"com.zakius.roma.dict\"")
        manifestPlaceholders["dictPackage"] = "com.zakius.roma.dict"
    }

    // One APK for both: phones are arm64, the emulator x86_64. The only native code is androidx's tiny path library
    // (about 10 KB per architecture), so per-architecture APKs aren't worth it (user, 2026-10-24).
    defaultConfig { ndk { abiFilters += setOf("arm64-v8a", "x86_64") } }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "DICT_PACKAGE", "\"com.zakius.roma.dict.debug\"")
            manifestPlaceholders["dictPackage"] = "com.zakius.roma.dict.debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        // NewPipeExtractor uses java.nio APIs that need desugaring below API 33.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures { compose = true; buildConfig = true }
    // Only the app's languages: libraries bring strings for about 80 locales.
    androidResources { localeFilters += setOf("en", "pl") }
    packaging {
        resources { excludes += setOf("META-INF/*.md", "META-INF/INDEX.LIST", "META-INF/DEPENDENCIES", "META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin", "META-INF/*.version") }
        // Native libraries compressed in the APK and unpacked at install (extractNativeLibs), as in tennessine.
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation(project(":core"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui:1.12.1")
    implementation("androidx.compose.foundation:foundation:1.12.1")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1") // only for its player icons

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
}
