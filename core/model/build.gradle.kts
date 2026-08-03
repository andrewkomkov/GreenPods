plugins {
    id("greenpods.android.library")
}

android {
    namespace = "io.github.andrewkomkov.greenpods.core.model"
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotest.assertions)
}
