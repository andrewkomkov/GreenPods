package io.github.andrewkomkov.greenpods

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.andrewkomkov.greenpods.core.designsystem.theme.GreenPodsTheme
import io.github.andrewkomkov.greenpods.feature.pods.PodsScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { /* Scanning simply yields nothing until granted; no special handling needed. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        permissionLauncher.launch(requiredPermissions())

        val pods =
            GreenPodsApplication.instance.podRepository
                .observePods()
                .stateIn(lifecycleScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        setContent {
            GreenPodsTheme {
                val state by pods.collectAsStateWithLifecycle()
                Scaffold(
                    topBar = { TopAppBar(title = { Text("GreenPods") }) },
                ) { padding ->
                    PodsScreen(pods = state, modifier = Modifier.padding(padding))
                }
            }
        }
    }

    /**
     * From API 31 scanning needs the dedicated Bluetooth permissions; before that
     * it is gated behind location instead.
     */
    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
}
