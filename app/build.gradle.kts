import java.util.Properties

plugins { id("com.android.application") }

val releaseVersion = Properties().apply {
    rootProject.file("release/version.properties").inputStream().use { load(it) }
}
val privateSigning = Properties()
val privateSigningPath = providers.gradleProperty("jianvaultSigningProperties")
    .orElse(providers.environmentVariable("JIANVAULT_SIGNING_PROPERTIES")).orNull
if (!privateSigningPath.isNullOrBlank()) {
    val privateFile = rootProject.file(privateSigningPath)
    require(privateFile.isFile) { "Signing properties file not found. Keep it outside Git." }
    privateFile.inputStream().use { privateSigning.load(it) }
}
val signingValues = mapOf(
    "storeFile" to "JIANVAULT_STORE_FILE",
    "storePassword" to "JIANVAULT_STORE_PASSWORD",
    "keyAlias" to "JIANVAULT_KEY_ALIAS",
    "keyPassword" to "JIANVAULT_KEY_PASSWORD"
).mapValues { (property, environment) ->
    providers.environmentVariable(environment).orNull?.takeIf { it.isNotBlank() }
        ?: privateSigning.getProperty(property)?.takeIf { it.isNotBlank() }
}
val releaseSigningReady = signingValues.values.all { !it.isNullOrBlank() }
require(!signingValues.values.any { !it.isNullOrBlank() } || releaseSigningReady) {
    "Incomplete release signing configuration. Supply all four signing values."
}
val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    doLast {
        check(releaseSigningReady) {
            "Release signing is required. Set JIANVAULT_SIGNING_PROPERTIES or the four JIANVAULT signing environment variables. Debug-key fallback is disabled."
        }
    }
}

android {
    namespace = "cn.local.simpleauth"
    compileSdk = 36
    defaultConfig {
        applicationId = releaseVersion.getProperty("applicationId")
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersion.getProperty("versionCode").toInt()
        versionName = releaseVersion.getProperty("versionName")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        if (releaseSigningReady) create("publicRelease") {
            storeFile = rootProject.file(signingValues.getValue("storeFile")!!)
            storePassword = signingValues.getValue("storePassword")
            keyAlias = signingValues.getValue("keyAlias")
            keyPassword = signingValues.getValue("keyPassword")
            storeType = "JKS"
        }
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
        }
        getByName("release") {
            isDebuggable = false
            signingConfig = if (releaseSigningReady) signingConfigs.getByName("publicRelease") else null
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyReleaseSigning) }

dependencies {
    implementation(files("libs/zxing-android-embedded-4.3.0.aar", "libs/zxing-core-3.4.1.jar"))
    implementation("androidx.core:core:1.18.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.20")
}
