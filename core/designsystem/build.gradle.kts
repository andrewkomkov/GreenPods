plugins {
    id("greenpods.android.library")
    id("greenpods.android.compose")
}

android {
    namespace = "io.github.andrewkomkov.greenpods.core.designsystem"
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)

    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.material3)
    api(libs.compose.material.icons.extended)
    api(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotest.assertions)
}
