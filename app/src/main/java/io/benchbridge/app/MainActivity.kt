package io.benchbridge.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.benchbridge.app.ram.RamViewModel
import io.benchbridge.app.ui.BenchBridgeApp
import io.benchbridge.app.ui.BenchBridgeTheme

class MainActivity : ComponentActivity() {
    private lateinit var ramModel: RamViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ramModel = ViewModelProvider(this)[RamViewModel::class.java]
        setContent {
            val state by ramModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.running) {
                if (state.running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            BenchBridgeTheme { BenchBridgeApp(state, ramModel) }
        }
    }
}
