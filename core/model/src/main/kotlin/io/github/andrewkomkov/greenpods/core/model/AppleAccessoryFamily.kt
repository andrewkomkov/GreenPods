package io.github.andrewkomkov.greenpods.core.model

/**
 * The product family a name belongs to — "AirPods Pro", "AirPods", "Powerbeats Pro".
 *
 * This exists to answer one question: *could* this advertisement be the accessory that is
 * paired to this phone? A resolvable private address cannot be linked to a bond without
 * the pairing key, so nothing here identifies an accessory. What it can do is **rule one
 * out**: a phone paired to "AirPods Pro" is not the phone that stranger's "AirPods
 * (3rd gen)" belongs to, and saying so costs nothing and needs no permission.
 *
 * Families are matched longest-first, so "AirPods Pro 3" resolves to `AirPods Pro` and not
 * to the shorter `AirPods` that also prefixes it. Getting that order wrong would collapse
 * every model into one family and quietly restore the bug this is here to prevent.
 */
object AppleAccessoryFamily {
    /**
     * Ordered longest-first within each brand. `Powerbeats` precedes `Beats` because
     * "Powerbeats Pro 2" must not be read as a Beats device.
     */
    private val FAMILIES =
        listOf(
            "Powerbeats Pro",
            "Powerbeats",
            "AirPods Pro",
            "AirPods Max",
            "AirPods",
            "Beats",
        )

    /** The family [name] belongs to, or null when it belongs to none that is known. */
    fun of(name: String?): String? {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return FAMILIES.firstOrNull { trimmed.startsWith(it, ignoreCase = true) }
    }

    /**
     * Whether two names could belong to the same accessory.
     *
     * False is a fact — different families are different products. True is only the
     * absence of a contradiction, never a match: two people in a room with the same model
     * of AirPods Pro are indistinguishable to this function, and to any function that does
     * not hold the pairing key.
     */
    fun couldBeTheSame(
        one: String?,
        other: String?,
    ): Boolean {
        val a = of(one) ?: return false
        val b = of(other) ?: return false
        return a.equals(b, ignoreCase = true)
    }
}

/** The family this model's name belongs to — see [AppleAccessoryFamily]. */
val PodModel.family: String?
    get() = AppleAccessoryFamily.of(displayName)
