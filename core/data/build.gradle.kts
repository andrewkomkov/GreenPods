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

    testImplementation(libs.junit)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
