package io.github.andrewkomkov.greenpods.core.data.transport

import io.github.andrewkomkov.greenpods.core.model.AppleAccessoryFamily
import io.github.andrewkomkov.greenpods.core.model.PodModel

/**
 * Decides which paired accessory an advertisement could have come from.
 *
 * Pure and index-based so the decision can be tested without a Bluetooth stack —
 * [BondedPodResolver] does the Android part and hands the names here.
 *
 * The distinction the whole thing turns on is between **"not yours"** and **"cannot
 * tell"**. They look the same from a failed comparison and they must not be treated the
 * same: the first is grounds to discard an advertisement, and the second is grounds to
 * keep it and claim nothing. Collapsing them in either direction has already cost this
 * app a bug in each direction — treating cannot-tell as yours let a stranger's earbuds
 * take the bond, and treating it as not-yours made a renamed accessory disappear.
 */
internal object BondedPodMatch {
    sealed interface Verdict {
        /** Exactly one paired accessory is of the advertised product family. */
        data class Resolved(
            val index: Int,
        ) : Verdict

        /** Every paired accessory could be classified, and none is this product family. */
        data object NotThisAccessory : Verdict

        /**
         * A paired accessory cannot be classified at all — the usual cause being that its
         * owner renamed it — so this advertisement can be neither confirmed nor excluded.
         */
        data object Uncorroborated : Verdict

        /** Several paired accessories are of this family, and an address cannot choose. */
        data class Ambiguous(
            val indices: List<Int>,
        ) : Verdict
    }

    /**
     * @param candidateNames the paired Apple accessories' names, in order; a null or
     *   unrecognisable name is the renamed case and is what produces [Verdict.Uncorroborated].
     */
    fun of(
        candidateNames: List<String?>,
        advertisedModel: PodModel,
    ): Verdict {
        val matching =
            candidateNames.indices.filter {
                AppleAccessoryFamily.couldBeTheSame(candidateNames[it], advertisedModel.displayName)
            }

        return when {
            matching.size == 1 -> Verdict.Resolved(matching.single())

            matching.size > 1 -> Verdict.Ambiguous(matching)

            // Nothing matched, and whether that means "somebody else's" depends entirely
            // on whether the paired accessories could be classified in the first place. An
            // accessory named "Мои уши" belongs to no known family, so it cannot be ruled
            // out as the source of this advertisement — and ruling it out anyway is what
            // made renamed earbuds vanish from the app.
            candidateNames.any { AppleAccessoryFamily.of(it) == null } -> Verdict.Uncorroborated

            else -> Verdict.NotThisAccessory
        }
    }
}
