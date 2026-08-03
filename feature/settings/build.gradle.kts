plugins {
    id("greenpods.android.library")
    id("greenpods.android.compose")
}

android {
    namespace = "io.github.andrewkomkov.greenpods.feature.settings"
}

dependencies {
    implementation(platform(libs.compose.bom))

    api(projects.core.designsystem)
    implementation(projects.core.model)
    implementation(projects.core.data)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.datastore.preferences)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
