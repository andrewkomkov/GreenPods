package io.github.andrewkomkov.greenpods.core.data.transport

import io.github.andrewkomkov.greenpods.core.model.PodModel
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * The three-way distinction this app kept collapsing: yours, not yours, cannot tell.
 *
 * Both directions have already shipped as bugs. Treating cannot-tell as *yours* let a
 * stranger's AirPods take the bonded key. Treating it as *not yours* made an accessory
 * disappear the moment its owner renamed it.
 */
class BondedPodMatchTest {
    @Test
    fun `one paired accessory of the advertised family resolves to it`() {
        BondedPodMatch.of(listOf("AirPods Pro"), PodModel.AIRPODS_PRO_3) shouldBe
            BondedPodMatch.Verdict.Resolved(0)
    }

    @Test
    fun `a different product family is positively somebody else's`() {
        // The case observed on hardware: paired to AirPods Pro, a stranger's AirPods 3
        // in range.
        BondedPodMatch.of(listOf("AirPods Pro"), PodModel.AIRPODS_3) shouldBe
            BondedPodMatch.Verdict.NotThisAccessory
    }

    @Test
    fun `a renamed accessory is cannot-tell, not somebody else's`() {
        // The regression this class exists to prevent. "Мои уши" belongs to no known
        // family, so nothing about this advertisement can be ruled out — and ruling it
        // out anyway removed the owner's own earbuds from the app.
        BondedPodMatch.of(listOf("Мои уши"), PodModel.AIRPODS_PRO_3) shouldBe
            BondedPodMatch.Verdict.Uncorroborated

        BondedPodMatch.of(listOf(null), PodModel.AIRPODS_PRO_3) shouldBe
            BondedPodMatch.Verdict.Uncorroborated
    }

    @Test
    fun `a renamed accessory alongside a matching one does not block the match`() {
        // Positive evidence wins: one paired accessory is of this family, so the
        // unnameable one does not turn a match into a shrug.
        BondedPodMatch.of(listOf("Мои уши", "AirPods Pro"), PodModel.AIRPODS_PRO_3) shouldBe
            BondedPodMatch.Verdict.Resolved(1)
    }

    @Test
    fun `a renamed accessory alongside a non-matching one is still cannot-tell`() {
        // Nothing matched, but one candidate could not be classified, so "none of these"
        // is not a conclusion that can be drawn.
        BondedPodMatch.of(listOf("Мои уши", "AirPods Pro"), PodModel.AIRPODS_4) shouldBe
            BondedPodMatch.Verdict.Uncorroborated
    }

    @Test
    fun `two accessories of the same family cannot be told apart`() {
        BondedPodMatch.of(listOf("AirPods Pro", "AirPods Pro 3"), PodModel.AIRPODS_PRO_3) shouldBe
            BondedPodMatch.Verdict.Ambiguous(listOf(0, 1))
    }
}
