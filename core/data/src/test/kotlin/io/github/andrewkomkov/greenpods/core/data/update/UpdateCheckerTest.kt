package io.github.andrewkomkov.greenpods.core.data.update

import io.kotest.matchers.shouldBe
import org.junit.Test

class UpdateCheckerTest {
    private fun newer(
        candidate: String,
        current: String,
    ) = UpdateChecker(currentVersionName = current).isNewerThanCurrent(candidate)

    @Test
    fun `version comparison is numeric, not lexicographic`() {
        // The case a string comparison gets wrong: "0.10.0" < "0.9.0" as text.
        newer("0.10.0", "0.9.0") shouldBe true
        newer("0.9.0", "0.10.0") shouldBe false
    }

    @Test
    fun `equal versions are not an update`() {
        newer("1.2.3", "1.2.3") shouldBe false
    }

    @Test
    fun `missing components are treated as zero`() {
        newer("1.2", "1.2.0") shouldBe false
        newer("1.2.1", "1.2") shouldBe true
    }

    @Test
    fun `suffixes are ignored so a debug build does not look outdated`() {
        newer("1.2.3", "1.2.3-debug") shouldBe false
        newer("1.2.3-rc1", "1.2.3") shouldBe false
    }

    @Test
    fun `non-numeric components do not throw`() {
        newer("nightly", "1.0.0") shouldBe false
    }
}
