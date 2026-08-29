// Root build file. Plugin versions come from gradle/libs.versions.toml so there
// is exactly one place to change them.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}
