plugins {
    id("com.android.application")
}

// The Japanese dictionary as its own app with no code, so roma's updates stay small: it is installed once and
// changes only with the dictionary version. roma reads the dictionary in place from this APK, so it is stored
// uncompressed. Signed with roma's key; roma checks that before using it.
evaluationDependsOn(":core")

android {
    namespace = "com.zakius.roma.dict"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.zakius.roma.dict"
        minSdk = 26
        targetSdk = 36
        versionCode = (project(":core").extra["sudachiVersion"] as String).toInt()
        versionName = project(":core").extra["sudachiVersion"] as String
    }
    buildTypes { debug { applicationIdSuffix = ".debug" } }
    androidResources { noCompress += "dic" }
    sourceSets["main"].assets.srcDir(project(":core").extra["sudachiAssets"] as File)
}

tasks.named("preBuild") { dependsOn(":core:sudachiDict") }
