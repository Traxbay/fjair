import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// Release signing comes from signing.properties (git-ignored); without it the release APK is unsigned.
val signing = Properties().apply { rootProject.file("signing.properties").takeIf { it.exists() }?.reader()?.use(::load) }
android {
    namespace = "si.abdullahf.fjair"
    compileSdk = 34
    defaultConfig {
        applicationId = "si.abdullahf.fjair"
        minSdk = 21
        targetSdk = 34
        versionCode = 4
        versionName = "0.4"
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_shared" } }
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    ndkVersion = "26.1.10909125"
    buildFeatures { prefab = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    signingConfigs {
        if (signing.isNotEmpty()) create("release") {
            storeFile = file(signing.getProperty("storeFile")); storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias"); keyPassword = signing.getProperty("keyPassword")
        }
    }
    // R8 off: JNI looks up si.abdullahf.fjair.Native callbacks by name.
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.findByName("release") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies { implementation("com.android.ndk.thirdparty:openssl:1.1.1q-beta-1") }
