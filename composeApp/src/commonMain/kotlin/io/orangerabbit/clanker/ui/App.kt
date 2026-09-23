package io.orangerabbit.clanker.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.orangerabbit.clanker.ui.theme.ClankerTheme

@Composable
fun App() {
    ClankerTheme {
        Text("Clanker", style = MaterialTheme.typography.headlineMedium)
    }
}
