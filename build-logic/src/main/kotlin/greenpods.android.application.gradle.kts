import com.android.build.api.dsl.ApplicationExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

plugins {
    id("com.android.application")
}

(extensions.getByName("android") as ApplicationExtension).apply {
    compileSdk = GreenPodsConfig.COMPILE_SDK

    defaultConfig {
        minSdk = GreenPodsConfig.MIN_SDK
        targetSdk = GreenPodsConfig.TARGET_SDK
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources.excludes +=
            setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
    }
}

extensions.configure<KotlinAndroidProjectExtension> {
    jvmToolchain(GreenPodsConfig.JVM_TOOLCHAIN)
}
