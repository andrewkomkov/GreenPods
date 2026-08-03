plugins {
    id("greenpods.android.library")
    id("greenpods.android.compose")
}

android {
    namespace = "io.github.andrewkomkov.greenpods.feature.controls"
}

dependencies {
    implementation(platform(libs.compose.bom))

    api(projects.core.designsystem)
    implementation(projects.core.model)
    implementation(projects.core.data)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
}
