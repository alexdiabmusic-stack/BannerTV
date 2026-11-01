import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.multiplatform") version "2.4.21" apply false
    id("com.android.application") version "9.2.0" apply false
    id("com.android.library") version "9.2.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
}

// Optional per-machine build output location (local.properties: build.root=/some/dir).
// Keeps generated files out of iCloud/Dropbox-synced folders, which inject "name 2.ext"
// conflict copies into build/ and break resource validation.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
localProperties.getProperty("build.root")?.takeIf { it.isNotBlank() }?.let { root ->
    allprojects {
        layout.buildDirectory.set(file("$root/${if (path == ":") "root" else path.removePrefix(":").replace(':', '-')}"))
    }
}
