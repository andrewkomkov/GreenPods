package io.github.andrewkomkov.greenpods.core.data

import io.kotest.matchers.shouldBe
import org.junit.Test
import java.io.File

/**
 * FR-024, proved from the source rather than from a packet capture.
 *
 * The requirement is that a heart rate leaves the phone only through the health store the
 * user switched on. `quickstart.md` §6 checks this by watching network counters during a
 * session, which proves nothing about the paths that session happened not to take. This
 * proves the stronger thing: **there is exactly one outbound network client in the
 * repository, it is the update check, and it has nowhere to put a reading.**
 *
 * A source-scanning test is usually a smell. Here it is the only shape that matches the
 * claim: "no code path sends a reading" is a statement about all code, and no runtime
 * observation can establish it.
 */
class NoReadingLeavesTheDeviceTest {
    private val repoRoot: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() }

    /** Production Kotlin across every module — tests and generated code excluded. */
    private val productionSources: List<File> =
        listOf("app", "core", "feature")
            .map { File(repoRoot, it) }
            .filter { it.exists() }
            .flatMap { module -> module.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filterNot { file ->
                val path = file.invariantSeparatorsPath
                "/build/" in path || "/src/test/" in path || "/src/androidTest/" in path
            }

    /**
     * Anything that could open a connection to somewhere that is not this phone.
     *
     * `BluetoothSocket` is deliberately absent: a channel to the earbuds in the user's
     * ears is not a network boundary, and treating it as one would make this test assert
     * that the feature does not work.
     *
     * Each token is written so it can only appear in **code**, because this test reads whole
     * files including their prose. `retrofit` was originally listed bare and matched the word
     * "retrofitting" in an unrelated KDoc, which failed the build and pointed an
     * architectural alarm at a file containing no network call at all. A guard that cries
     * wolf at ordinary English gets narrowed by whoever hits it next, and the narrowing is
     * what would eventually let a real client through — so the package name is the token.
     */
    private val networkApis =
        listOf(
            "okhttp3",
            "java.net.URL",
            "java.net.Socket",
            "HttpURLConnection",
            "URLConnection",
            "openConnection",
            "retrofit2",
            "io.ktor",
        )

    @Test
    fun `the repository has exactly one outbound network client`() {
        val networkFiles =
            productionSources
                .filter { file -> networkApis.any { api -> api in file.readText() } }
                .map { it.name }
                .distinct()
                .sorted()

        // If this fails, a second way out of the phone was added. That is not necessarily
        // wrong — but FR-024 says a heart rate must not take it, and this test is the
        // place that decision gets made deliberately instead of by accident.
        networkFiles shouldBe listOf("UpdateChecker.kt")
    }

    @Test
    fun `the update check cannot carry a reading, because it has nothing to carry it in`() {
        val source = productionSources.single { it.name == "UpdateChecker.kt" }.readText()

        // A GET with a fixed URL and a fixed header. No request body exists, so there is
        // no field a reading could be written into even by mistake.
        ("""".post("""" in source) shouldBe false
        ("post(" in source) shouldBe false
        ("put(" in source) shouldBe false
        ("patch(" in source) shouldBe false
        ("RequestBody" in source) shouldBe false
        ("FormBody" in source) shouldBe false
    }

    @Test
    fun `the update check has never heard of a heart rate`() {
        val source = productionSources.single { it.name == "UpdateChecker.kt" }.readText()

        // Enforced at the type level upstream — its constructor takes a version string and
        // a URL — but stated here too, because the cheapest way to break FR-024 would be
        // to add "helpful" diagnostics to an update ping.
        listOf("HeartRate", "heartRate", "beatsPerMinute", "PodState", "bpm").forEach { term ->
            (term in source) shouldBe false
        }
    }

    @Test
    fun `the scan actually found the sources it claims to have checked`() {
        // A guard against the failure mode where a path change makes every assertion above
        // pass by scanning nothing at all.
        (productionSources.size > 40) shouldBe true
        productionSources.any { it.name == "HeartRateController.kt" } shouldBe true
        productionSources.any { it.name == "UpdateChecker.kt" } shouldBe true
    }
}
