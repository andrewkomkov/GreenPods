package io.github.andrewkomkov.greenpods.core.model

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * The rule that keeps a stranger's earbuds out of this phone's bond.
 *
 * A resolvable private address cannot be linked to a bond without the pairing key, so
 * nothing here identifies an accessory. What it does is rule one out, and the tests below
 * are about exactly that asymmetry: a `false` is a fact, a `true` is only the absence of a
 * contradiction.
 */
class AppleAccessoryFamilyTest {
    @Test
    fun `the longest matching family wins`() {
        // "AirPods Pro 3" is prefixed by both "AirPods" and "AirPods Pro". Taking the
        // shorter one would put every model in one family and quietly undo the fix.
        AppleAccessoryFamily.of("AirPods Pro 3") shouldBe "AirPods Pro"
        AppleAccessoryFamily.of("AirPods Pro") shouldBe "AirPods Pro"
        AppleAccessoryFamily.of("AirPods (3rd gen)") shouldBe "AirPods"
        AppleAccessoryFamily.of("AirPods Max (USB-C)") shouldBe "AirPods Max"
        AppleAccessoryFamily.of("Powerbeats Pro 2") shouldBe "Powerbeats Pro"
        AppleAccessoryFamily.of("Powerbeats 4") shouldBe "Powerbeats"
    }

    @Test
    fun `a name from no known family resolves to nothing`() {
        AppleAccessoryFamily.of("DualSense Wireless Controller").shouldBeNull()
        AppleAccessoryFamily.of("Мои уши").shouldBeNull()
        AppleAccessoryFamily.of("").shouldBeNull()
        AppleAccessoryFamily.of(null).shouldBeNull()
    }

    /**
     * The case that was actually observed, on 2026-08-05: a stranger's AirPods 3 in range
     * of a phone paired to AirPods Pro. It took the bonded key, pushed the owner's own
     * earbuds onto a rotating address, and collected the open channel.
     */
    @Test
    fun `a stranger's AirPods 3 cannot be this phone's AirPods Pro`() {
        AppleAccessoryFamily.couldBeTheSame("AirPods Pro", PodModel.AIRPODS_3.displayName) shouldBe false
        AppleAccessoryFamily.couldBeTheSame("AirPods Pro", PodModel.AIRPODS_PRO_3.displayName) shouldBe true
    }

    @Test
    fun `the check is not a match, and says so where it cannot tell`() {
        // Two people with the same model are indistinguishable here — and to anything
        // that does not hold the pairing key. This asserts the limit, so that nobody
        // later reads a `true` as identification.
        AppleAccessoryFamily.couldBeTheSame(
            PodModel.AIRPODS_PRO_2.displayName,
            PodModel.AIRPODS_PRO_3.displayName,
        ) shouldBe true
    }

    @Test
    fun `an unknown name on either side is never assumed to match`() {
        AppleAccessoryFamily.couldBeTheSame(null, PodModel.AIRPODS_PRO_3.displayName) shouldBe false
        AppleAccessoryFamily.couldBeTheSame("AirPods Pro", null) shouldBe false
        AppleAccessoryFamily.couldBeTheSame("Мои уши", PodModel.AIRPODS_PRO_3.displayName) shouldBe false
    }

    @Test
    fun `every known model belongs to a family`() {
        // A model with no family can never be corroborated, so it would silently lose
        // rotation-resolution. Adding one to PodModel should fail here, not in the field.
        PodModel.entries
            .filter { it != PodModel.UNKNOWN }
            .forEach { model -> AppleAccessoryFamily.of(model.displayName) shouldBe model.family }
    }
}
