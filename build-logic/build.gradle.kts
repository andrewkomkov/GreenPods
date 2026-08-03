plugins {
    `kotlin-dsl`
}

group = "io.github.andrewkomkov.greenpods.buildlogic"

kotlin {
    jvmToolchain(21)
}

dependencies {
    compileOnly(libs.plugin.android.gradle)
    compileOnly(libs.plugin.kotlin.gradle)
    compileOnly(libs.plugin.compose.compiler)
}
