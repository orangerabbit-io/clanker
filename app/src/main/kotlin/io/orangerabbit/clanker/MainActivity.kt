package io.orangerabbit.clanker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.orangerabbit.clanker.di.AppGraph
import io.orangerabbit.clanker.ui.ChatViewModel
import io.orangerabbit.clanker.ui.ClankerNav
import io.orangerabbit.ui.components.ScanlineOverlay
import io.orangerabbit.ui.effects.CrtScreen
import io.orangerabbit.ui.theme.AppTheme
import io.orangerabbit.ui.theme.CyberpunkFx
import io.orangerabbit.ui.theme.LocalCyberpunkFx

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as ClankerApp).graph
        setContent {
            val vm: ChatViewModel = viewModel(factory = GraphViewModelFactory(graph))
            val state by vm.state.collectAsStateWithLifecycle()
            // AppTheme = the vendored cyberpunk design system (dark-only, cut-corner, monospace).
            AppTheme {
                // The FX intensity slider in Settings dials the whole effect layer here.
                CompositionLocalProvider(LocalCyberpunkFx provides CyberpunkFx(intensity = state.fxIntensity)) {
                    // CrtScreen post-processes the whole UI through the AGSL phosphor shader on API 33+
                    // (drawn untouched below); ScanlineOverlay rolls on top.
                    CrtScreen(modifier = Modifier.fillMaxSize()) {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                ClankerNav(vm)
                                ScanlineOverlay(modifier = Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Bridges AndroidX ViewModel creation to the Metro graph. */
private class GraphViewModelFactory(private val graph: AppGraph) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(ChatViewModel::class.java) -> graph.chatViewModel as T
        else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
