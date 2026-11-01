import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Secrets live in local.properties (git-ignored) or the environment, never in source.
// Mirrors Config/Secrets.xcconfig on iOS: an empty key disables the feature that needs it.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// `-PpublicBuild=true` builds without any of your API keys, so an APK that will be shared publicly cannot leak them.
// The features that need a key (moneyline odds) are simply off in that build.
val publicBuild = providers.gradleProperty("publicBuild").orNull == "true"

fun secret(name: String, fallback: String = ""): String =
    if (publicBuild && name.endsWith("_API_KEY")) fallback
    else (localProperties.getProperty(name) ?: System.getenv(name) ?: fallback).trim()

// Optional release signing: keystore.properties (git-ignored) with storeFile, storePassword, keyAlias, keyPassword.
// Without it the release build is signed with the machine's debug key, which is fine for sideloading to your own devices
// but means a build from another machine cannot update an installed copy.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun buildConfigString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.alexdiab.stadiatv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.alexdiab.stadiatv"
        minSdk = 23
        targetSdk = 36
        versionCode = 3
        versionName = "2.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // API configuration shared with the iOS/tvOS app (see MyApp/AppConfiguration.swift).
        buildConfigField("String", "ODDS_API_KEY", buildConfigString(secret("ODDS_API_KEY")))
        buildConfigField("String", "ODDS_API_BASE_URL", buildConfigString(secret("ODDS_API_BASE_URL", "https://mlapi.bet/v1")))
        buildConfigField("String", "YOUTUBE_API_KEY", buildConfigString(secret("YOUTUBE_API_KEY")))
        // Public default from SportsDataverse fox.ts, not a private credential (same fallback as iOS).
        buildConfigField("String", "FOX_SPORTS_API_KEY", buildConfigString(secret("FOX_SPORTS_API_KEY", "jE7yBJVRNAwdDesMgTzTXUUSx1It41Fq")))    }

    signingConfigs {
        if (keystoreProperties.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/{AL2.0,LGPL2.1}")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

}

tasks.withType<Test>().configureEach {
    maxHeapSize = "1g"
    // Optional: lets the lineup report test read a saved provider lineup. Absent, that test is skipped.
    secret("LINEUP_DATA_DIR").takeIf { it.isNotEmpty() }?.let { systemProperty("lineup.dataDir", it) }
    systemProperty("lineup.reportDir", layout.buildDirectory.dir("reports/lineup").get().asFile.absolutePath)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api")
    }
}

dependencies {
    implementation(project(":core"))
    testImplementation(testFixtures(project(":core")))
    // Pinned to releases that still compile against SDK 36 (newer ones require compileSdk 37).
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.2")

    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.squareup.okhttp3:okhttp-brotli:5.3.2")
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-ui-compose:1.11.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // Pure-Java XmlPullParser for JVM unit tests (on a device the platform provides it).
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
}
