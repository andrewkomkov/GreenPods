import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

// AGP 9 ships built-in Kotlin support, so the standalone `kotlin.android` plugin
// must not be applied — it fails the build if it is. AGP 9 also defaults to
// `android.newDsl=true`, under which the extension is registered by name with an
// implementation type, so it is looked up by name and cast to the public interface.
plugins {
    id("com.android.library")
}

(extensions.getByName("android") as LibraryExtension).apply {
    compileSdk = GreenPodsConfig.COMPILE_SDK

    defaultConfig {
        minSdk = GreenPodsConfig.MIN_SDK
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

extensions.configure<KotlinAndroidProjectExtension> {
    jvmToolchain(GreenPodsConfig.JVM_TOOLCHAIN)
}
