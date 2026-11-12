import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// API keys live in keys.properties (gitignored). Missing keys build fine; those features just stay off.
val keys = Properties().apply { rootProject.file("keys.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
fun key(name: String) = "\"" + (keys.getProperty(name) ?: "").trim() + "\""

// Release signing key lives outside the repo (../powerj-signing). Without it, release builds are unsigned.
val signing = Properties().apply { rootProject.file("../powerj-signing/keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }

// A release APK must behave the same on every PC: refuse to build one without the keys or the signing key
// instead of quietly turning features off or leaving it unsigned (an unsigned APK will not install).
gradle.taskGraph.whenReady {
    if (allTasks.none { it.project == project && it.name.contains("Release") }) return@whenReady
    val missing = listOf("data_go_kr", "opinet", "kakao", "firebase").filter { keys.getProperty(it).isNullOrBlank() }.map { "keys.properties: $it" } +
        listOfNotNull("../powerj-signing/keystore.properties".takeIf { signing.getProperty("storeFile") == null })
    if (missing.isNotEmpty()) throw GradleException("Release build needs: " + missing.joinToString(", ") + " (see README, 다른 PC에서 빌드)")
}

android {
    namespace = "com.beat.powerj"
    compileSdk = 35
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.beat.powerj"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "DATA_GO_KR", key("data_go_kr"))
        buildConfigField("String", "OPINET", key("opinet"))
        buildConfigField("String", "KAKAO", key("kakao"))
        buildConfigField("String", "FIREBASE", key("firebase"))
    }
    signingConfigs {
        if (signing.getProperty("storeFile") != null) create("release") {
            storeFile = rootProject.file("../powerj-signing").resolve(signing.getProperty("storeFile")) // relative to powerj-signing/
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
