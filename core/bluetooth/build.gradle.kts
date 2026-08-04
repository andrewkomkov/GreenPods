plugins {
    id("greenpods.android.library")
}

android {
    namespace = "io.github.andrewkomkov.greenpods.core.bluetooth"
}

dependencies {
    api(projects.core.model)

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hidden.api.bypass)

    testImplementation(libs.junit)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
}
