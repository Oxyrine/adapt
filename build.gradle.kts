plugins {
    id("com.android.application") version "8.6.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20" apply false
    // Kotlin 2.0+ moved Compose codegen out of the (now-removed) kotlinCompilerExtensionVersion
    // path into its own plugin -- required, not optional, once compose = true.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}
