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

@Composable
private fun HealthRationale(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Heart rate and Health Connect", style = MaterialTheme.typography.headlineSmall)

        Text(
            "GreenPods reads heart rate from your earbuds' own sensor and shows it in the " +
                "app. If you turn the Health Connect integration on, it also writes those " +
                "readings into Health Connect so your other apps can use them.",
            style = MaterialTheme.typography.bodyLarge,
        )

        Section(
            title = "What it writes",
            body =
                "Heart-rate readings only, recorded against your earbuds as the measuring " +
                    "device. Nothing else — no workouts, no calories, no derived figures. " +
                    "Readings the earbuds report low confidence in are never written.",
        )

        Section(
            title = "What it reads",
            body =
                "Only the heart-rate records GreenPods itself wrote, and only to check its " +
                    "own work — so it can tell you how many readings actually arrived. It " +
                    "does not read heart rate written by any other app.",
        )

        Section(
            title = "What it does not do",
            body =
                "GreenPods does not interpret your heart rate, alert on it, or send it " +
                    "anywhere. Readings stay on this phone unless you switch on the Health " +
                    "Connect integration yourself, and you can switch it off at any time.",
        )

        Section(
            title = "Deleting",
            body =
                "GreenPods can delete the records it wrote. Anything already in Health " +
                    "Connect is managed in Health Connect, including data other apps put there.",
        )
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
