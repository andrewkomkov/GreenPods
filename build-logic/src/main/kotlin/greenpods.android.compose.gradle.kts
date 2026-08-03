import com.android.build.api.dsl.CommonExtension

plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

// Enable the Compose build feature for whichever Android plugin the module applied.
// Compose dependencies stay in each module's build file so the version catalog
// remains the single source of truth for versions.
listOf("com.android.library", "com.android.application").forEach { pluginId ->
    plugins.withId(pluginId) {
        val android = extensions.getByName("android") as CommonExtension
        android.buildFeatures.compose = true
    }
}
