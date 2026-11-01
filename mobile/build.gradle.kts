import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Empty by design until the owner creates the Google Play products and verification service.
// These are identifiers/URLs, never a service-account credential or private signing key.
fun billingString(name: String) = providers.gradleProperty(name).orElse("").get().trim()
fun quotedBuildString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
val unlockFeatures = providers.gradleProperty("bannerUnlockAllFeatures").orElse("true").get().toBooleanStrict()
val subscriptionIds = billingString("bannerSubscriptionIds")
val lifetimeIds = billingString("bannerLifetimeIds")
val billingVerifyUrl = billingString("bannerBillingVerifyUrl")
if (!unlockFeatures) {
    require((subscriptionIds + lifetimeIds).isNotBlank() && billingVerifyUrl.startsWith("https://")) {
        "Paid mode requires owner-configured Google Play product IDs and an HTTPS verification service."
    }
}

android {
    namespace = "com.alexdiab.stadiatv.mobile"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.alexdiab.stadiatv.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "1.7.0"
        buildConfigField("boolean", "UNLOCK_ALL_FEATURES", unlockFeatures.toString())
        buildConfigField("String", "PLAY_SUBSCRIPTION_IDS", quotedBuildString(subscriptionIds))
        buildConfigField("String", "PLAY_LIFETIME_IDS", quotedBuildString(lifetimeIds))
        buildConfigField("String", "PLAY_VERIFY_URL", quotedBuildString(billingVerifyUrl))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").res.srcDir("../app/src/main/res")
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/{AL2.0,LGPL2.1}") }
    testOptions { unitTests.isReturnDefaultValues = true }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.window:window:1.5.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("com.google.android.gms:play-services-cast-framework:22.3.1")
    implementation("com.android.billingclient:billing:9.1.0")
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
