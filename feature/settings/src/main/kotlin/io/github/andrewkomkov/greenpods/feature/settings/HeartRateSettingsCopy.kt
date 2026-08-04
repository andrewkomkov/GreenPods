package io.github.andrewkomkov.greenpods.feature.settings

/**
 * Every word the heart-rate and Health Connect sections can say.
 *
 * In one object for the same reason `HeartRateCopy` is: FR-010 is a boundary that decays
 * one well-meant sentence at a time, and the only version of that rule which survives the
 * next person adding a row is a list a test can walk.
 *
 * The settings copy carries two things the card does not, and both are requirements
 * rather than politeness:
 *
 * - **The cost is stated before the switch can be moved** (FR-012). [SECTION_SUBTITLE] is
 *   the section's subtitle, above the switch — a cost discovered afterwards was not
 *   disclosed.
 * - **Enabling implies background monitoring** (AD-8, FR-013). The same subtitle says so,
 *   because overriding a default that exists to protect the user has to be consented to.
 */
object HeartRateSettingsCopy {
    const val SECTION_TITLE = "Heart rate"

    const val SECTION_SUBTITLE =
        "Reads the earbuds' optical sensor over Apple's protocol — no workout needed. " +
            "It runs continuously while you wear them, which uses the earbuds' battery, " +
            "and keeps GreenPods monitoring in the background while it is on."

    const val ENABLE_TITLE = "Measure heart rate"
    const val ENABLE_DESCRIPTION = "Off until you ask for it."

    /**
     * Says what the confidence gate does, and stops there.
     *
     * "It does not interpret the number" is the sentence that keeps the gate from being
     * read as a quality judgement about the reader rather than about the sensor.
     */
    const val CONFIDENCE_NOTE =
        "GreenPods shows a reading only once the earbuds report they are confident in " +
            "it. It does not interpret the number."

    /** Where `POST_NOTIFICATIONS` is denied the service still runs, and FR-013 says so. */
    const val NOTIFICATION_CAVEAT =
        "If notifications are turned off for GreenPods, sensing still runs — you just " +
            "will not see the ongoing notification that says so."

    const val HEALTH_TITLE = "Health Connect"
    const val HEALTH_CHECKING = "Checking Health Connect…"
    const val HEALTH_SWITCH_TITLE = "Write readings to Health Connect"
    const val HEALTH_SWITCH_DESCRIPTION = "So your other apps can read heart rate from your earbuds."

    const val PERMISSION_GRANTED = "Permission granted. Only readings the earbuds are confident in are written."

    const val PERMISSION_REFUSED =
        "Permission was declined. You can grant it later in Health Connect's own " +
            "settings; GreenPods will not ask again."

    const val PERMISSION_BUTTON = "Allow writing heart rate"

    const val DELETE_TITLE = "Delete what GreenPods holds"

    const val DELETE_DESCRIPTION =
        "Removes the heart-rate records GreenPods wrote. Anything already in Health " +
            "Connect is managed there, including data from other apps."

    const val DELETE_BUTTON = "Delete GreenPods' records"

    /** Every sentence above, for the test that checks none of it is clinical. */
    fun everySentence(): List<String> =
        listOf(
            SECTION_TITLE,
            SECTION_SUBTITLE,
            ENABLE_TITLE,
            ENABLE_DESCRIPTION,
            CONFIDENCE_NOTE,
            NOTIFICATION_CAVEAT,
            HEALTH_TITLE,
            HEALTH_CHECKING,
            HEALTH_SWITCH_TITLE,
            HEALTH_SWITCH_DESCRIPTION,
            PERMISSION_GRANTED,
            PERMISSION_REFUSED,
            PERMISSION_BUTTON,
            DELETE_TITLE,
            DELETE_DESCRIPTION,
            DELETE_BUTTON,
        )
}
