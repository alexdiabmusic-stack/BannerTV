import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { id("org.jetbrains.kotlin.multiplatform"); id("org.jetbrains.kotlin.plugin.serialization") }

val webConfig = rootProject.file("apple/Config/ProviderDefaults.xcconfig")
val generateProviderDefaults by tasks.registering {
    inputs.files(webConfig)
    val output = layout.buildDirectory.dir("generated/providerDefaults")
    outputs.dir(output)
    doLast {
        require(webConfig.isFile) { "Run python3 scripts/bootstrap-public-sports.py before building." }
        val properties = Properties().apply { webConfig.inputStream().use { load(it) } }
        val names = listOf("NFL_WEB_CLIENT_KEY", "NFL_WEB_CLIENT_SECRET", "PGA_WEB_API_KEY")
        val constants = names.map { name ->
            val value = properties.getProperty(name).orEmpty().trim()
            require(value.matches(Regex("[a-zA-Z0-9_./+=-]+"))) { "Invalid public provider setting: $name" }
            "    const val $name = \"$value\""
        }
        output.get().file("ProviderDefaults.kt").asFile.apply {
            parentFile.mkdirs()
            writeText("package com.alexdiab.stadiatv.foundation\n\ninternal object ProviderDefaults {\n" +
                constants.joinToString("\n") + "\n}\n")
        }
    }
}

kotlin {
    // The library contains no Android SDK code. Both Android apps consume its JVM artifact.
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    listOf(iosArm64(), iosSimulatorArm64(), tvosArm64(), tvosSimulatorArm64(), macosArm64()).forEach { target ->
        target.binaries.framework { baseName = "BannerFoundation"; isStatic = true }
    }
    sourceSets {
        commonMain { kotlin.srcDir(generateProviderDefaults) }
        commonMain.dependencies {
            // Match the Android hosts' Compose BOM: annotation constraints also align runtime/saveable.
            implementation("androidx.compose.runtime:runtime-annotation:1.11.4")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            implementation("com.squareup.okio:okio:3.18.1")
            implementation("io.ktor:ktor-client-core:3.6.1")
            implementation("io.ktor:ktor-client-websockets:3.6.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
        jvmTest {
            resources.srcDir("../core/src/test/resources")
            resources.srcDir("../contracts")
            dependencies {
                implementation("junit:junit:4.13.2")
                implementation("io.ktor:ktor-client-mock:3.6.1")
            }
        }
        appleMain.dependencies { implementation("io.ktor:ktor-client-darwin:3.6.1") }
    }
}

// Enable the public historical-score probe explicitly. Regular CI uses checked-in fixtures.
tasks.named<Test>("jvmTest") {
    providers.gradleProperty("nhlLiveOutput").orNull?.let { path ->
        systemProperty("banner.nhl.liveOutput", path)
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
}
