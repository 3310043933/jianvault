plugins { id("com.android.application") }
android {
    namespace = "cn.local.simpleauth"
    compileSdk = 36
    defaultConfig {
        applicationId = "cn.local.simpleauth"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "1.5"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
dependencies {
    implementation(files("libs/zxing-android-embedded-4.3.0.aar", "libs/zxing-core-3.4.1.jar"))
    implementation("androidx.core:core:1.18.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.20")
}
