import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localSettings = Properties().apply {
    val source = rootProject.file("local.properties")
    if (source.exists()) source.inputStream().use { load(it) }
}
val publicBuild = providers.gradleProperty("publicBuild").orNull == "true"
fun configValue(name: String, default: String = ""): String {
    val value = if (publicBuild && name.endsWith("_API_KEY")) default
        else localSettings.getProperty(name) ?: System.getenv(name) ?: default
    return "\"" + value.trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

android {
    namespace = "com.alexdiab.stadiatv.core"
    compileSdk = 36
    defaultConfig {
        minSdk = 23
        buildConfigField("String", "ODDS_API_KEY", configValue("ODDS_API_KEY"))
        buildConfigField("String", "ODDS_API_BASE_URL", configValue("ODDS_API_BASE_URL", "https://mlapi.bet/v1"))
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    testFixtures { enable = true }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    api(project(":shared"))
    api(platform("androidx.compose:compose-bom:2026.06.01"))
    api("androidx.compose.runtime:runtime")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.2")
    api("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.squareup.okhttp3:okhttp-brotli:5.3.2")
    api("io.coil-kt.coil3:coil:3.5.0")
    api("io.coil-kt.coil3:coil-network-okhttp:3.5.0")
    implementation("io.coil-kt.coil3:coil-svg:3.5.0")
    api("androidx.media3:media3-exoplayer:1.11.1")
    api("androidx.media3:media3-exoplayer-hls:1.11.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "1g"
    localSettings.getProperty("LINEUP_DATA_DIR")?.takeIf(String::isNotBlank)?.let { systemProperty("lineup.dataDir", it) }
    systemProperty("lineup.reportDir", layout.buildDirectory.dir("reports/lineup").get().asFile.absolutePath)
}
