plugins {
    id("greenpods.android.application")
    id("greenpods.android.compose")
}

/**
 * Packs semver into a monotonically increasing integer: 1.2.3 -> 10203.
 * Play and the in-app updater both require the code to rise with every release.
 */
fun versionCodeFrom(name: String): Int {
    val (major, minor, patch) =
        (name.substringBefore('-').split('.') + listOf("0", "0"))
            .take(3)
            .map { it.toIntOrNull() ?: 0 }
    return major * 10_000 + minor * 100 + patch
}

android {
    namespace = "io.github.andrewkomkov.greenpods"

    defaultConfig {
        applicationId = "io.github.andrewkomkov.greenpods"

        // release-please rewrites the literal below on every release; the version
        // code is derived from it so there is only one number to maintain.
        versionName = "0.3.0" // x-release-please-version
        versionCode = versionCodeFrom(versionName!!)
    }

    // A skipped CI step still exports its output as an empty string, so "set but
    // blank" has to count as absent here or `file("")` blows up the release build.
    val keystorePath: String? = System.getenv("GREENPODS_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }

    signingConfigs {
        create("release") {
            // Populated from environment variables in CI (see .github/workflows/release.yml).
            // Falls back to the debug key locally so `assembleRelease` always works.
            val storePath = keystorePath
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = System.getenv("GREENPODS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("GREENPODS_KEY_ALIAS")
                keyPassword = System.getenv("GREENPODS_KEY_PASSWORD")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig =
                if (keystorePath != null) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }
        }
    }
}

dependencies {
    // The BOM has to be applied to every configuration that resolves Compose
    // artifacts, not just `implementation` — the test artifacts carry no version
    // of their own and fail to resolve without it.
    implementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))
    debugImplementation(platform(libs.compose.bom))

    implementation(projects.core.model)
    implementation(projects.core.data)
    implementation(projects.core.bluetooth)
    implementation(projects.core.designsystem)
    implementation(projects.feature.pods)
    implementation(projects.feature.controls)
    implementation(projects.feature.settings)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotest.assertions)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
