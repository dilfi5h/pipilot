package dev.pipilot.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.pipilot.app.ui.PiScreen
import dev.pipilot.app.ui.PiViewModel
import dev.pipilot.app.ui.theme.PiPilotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dev.pipilot.app.PipilotApp.consumeLastCrash(filesDir)
        enableEdgeToEdge()
        setContent {
            PiPilotTheme {
                PiScreen(viewModel = androidx.lifecycle.viewmodel.compose.viewModel<PiViewModel>())
            }
        }
    }
}
