package io.orangerabbit.clanker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay

/** Navigation 3 routes. Plain data objects are fine as back-stack keys (see NavDisplay basics). */
private data object ChatRoute
private data object SettingsRoute

/**
 * App navigation host: a Navigation 3 [NavDisplay] over a developer-owned back stack. Chat is the
 * root; the gear opens Settings, system back pops it. One shared [ChatViewModel] backs both screens
 * so model defaults, the loaded character, and the live transcript stay in sync across them.
 */
@Composable
fun ClankerNav(viewModel: ChatViewModel) {
    val backStack = remember { mutableStateListOf<Any>(ChatRoute) }
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<ChatRoute> {
                ChatScreen(viewModel, onOpenSettings = { backStack.add(SettingsRoute) })
            }
            entry<SettingsRoute> {
                SettingsScreen(viewModel, onBack = { backStack.removeLastOrNull() })
            }
        },
    )
}
