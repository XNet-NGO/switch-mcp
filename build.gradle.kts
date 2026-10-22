// Top-level build file. Plugin versions declared here, applied in :app.
// AGP 9 has built-in Kotlin support, so the Kotlin Android plugin is not
// applied; only the serialization compiler plugin is needed on top.
plugins {
    id("com.android.application") version "9.3.2" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
}
