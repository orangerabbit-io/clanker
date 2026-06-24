package io.orangerabbit.clanker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import io.orangerabbit.clanker.di.AppGraph
import io.orangerabbit.clanker.ui.ChatScreen
import io.orangerabbit.clanker.ui.ChatViewModel
import io.orangerabbit.clanker.ui.theme.ClankerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as ClankerApp).graph
        setContent {
            ClankerTheme {
                val vm: ChatViewModel = viewModel(factory = GraphViewModelFactory(graph))
                ChatScreen(vm)
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
