// Top-level build file — plugins are declared here with `apply false` and
// applied in module build files. This keeps a single source of truth for
// plugin versions across all modules.
plugins {
    id("com.android.application") version "8.9.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
