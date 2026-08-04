package io.github.andrewkomkov.greenpods.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.andrewkomkov.greenpods.core.designsystem.theme.GreenPodsTheme

/**
 * Why GreenPods wants health-data access, reachable from the *system's* health settings.
 *
 * FR-022, and it is not a formality. Users grant and revoke these permissions from
 * outside the app entirely — from Health Connect's own screens, or from the permission
 * usage page — and an app that cannot explain itself where the decision is actually made
 * is one they should decline. The intent filters that make this reachable are in the
 * manifest; this is what they land on.
 *
 * The copy answers three questions and stops: what is written, what is read, and what is
 * not done at all. It interprets nothing (FR-010).
 */
class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GreenPodsTheme {
                Surface(modifier = Modifier.fillMaxSize()) { HealthRationale() }
            }
        }
    }
}

/**
 * Every sentence this screen shows.
 *
 * Hoisted out of the composable so a unit test can walk it, the same way the pods card's
 * copy is. This screen is read at the moment a user decides whether to trust the app with
 * health data, which makes it the worst possible place for a sentence that interprets a
 * reading — and the easiest place for one to be added in good faith later (FR-010).
 */
object HealthRationaleCopy {
    const val TITLE = "Heart rate and Health Connect"

    const val INTRO =
        "GreenPods reads heart rate from your earbuds' own sensor and shows it in the " +
            "app. If you turn the Health Connect integration on, it also writes those " +
            "readings into Health Connect so your other apps can use them."

    const val WRITES_TITLE = "What it writes"

    const val WRITES_BODY =
        "Heart-rate readings only, recorded against your earbuds as the measuring " +
            "device. Nothing else — no workouts, no calories, no derived figures. " +
            "Readings the earbuds report low confidence in are never written."

    const val READS_TITLE = "What it reads"

    const val READS_BODY =
        "Only the heart-rate records GreenPods itself wrote, and only to check its " +
            "own work — so it can tell you how many readings actually arrived. It " +
            "does not read heart rate written by any other app."

    const val NOT_DONE_TITLE = "What it does not do"

    const val NOT_DONE_BODY =
        "GreenPods does not interpret your heart rate, alert on it, or send it " +
            "anywhere. Readings stay on this phone unless you switch on the Health " +
            "Connect integration yourself, and you can switch it off at any time."

    const val DELETING_TITLE = "Deleting"

    const val DELETING_BODY =
        "GreenPods can delete the records it wrote. Anything already in Health " +
            "Connect is managed in Health Connect, including data other apps put there."

    fun everySentence(): List<String> =
        listOf(
            TITLE,
            INTRO,
            WRITES_TITLE,
            WRITES_BODY,
            READS_TITLE,
            READS_BODY,
            NOT_DONE_TITLE,
            NOT_DONE_BODY,
            DELETING_TITLE,
            DELETING_BODY,
        )
}

@Composable
private fun HealthRationale(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(HealthRationaleCopy.TITLE, style = MaterialTheme.typography.headlineSmall)

        Text(HealthRationaleCopy.INTRO, style = MaterialTheme.typography.bodyLarge)

        Section(title = HealthRationaleCopy.WRITES_TITLE, body = HealthRationaleCopy.WRITES_BODY)
        Section(title = HealthRationaleCopy.READS_TITLE, body = HealthRationaleCopy.READS_BODY)
        Section(title = HealthRationaleCopy.NOT_DONE_TITLE, body = HealthRationaleCopy.NOT_DONE_BODY)
        Section(title = HealthRationaleCopy.DELETING_TITLE, body = HealthRationaleCopy.DELETING_BODY)
    }
}

@Composable
private fun Section(
    title: String,
    body: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}
