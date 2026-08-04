plugins {
    id("greenpods.android.library")
}

android {
    namespace = "io.github.andrewkomkov.greenpods.core.data"
}

dependencies {
    api(projects.core.model)
    api(projects.core.bluetooth)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    // Deliberately `implementation`, never `api`: no Health Connect type may cross a
    // module boundary. `feature/settings` launches the permission request through an
    // ActivityResultContract parameterised on plain strings instead — see AD-11.
    implementation(libs.health.connect)

    testImplementation(libs.junit)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
