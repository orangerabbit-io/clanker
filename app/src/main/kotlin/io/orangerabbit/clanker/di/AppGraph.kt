package io.orangerabbit.clanker.di

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.orangerabbit.clanker.ui.ChatViewModel

/**
 * Application-scoped Metro dependency graph. Metro is a compiler plugin (no KSP) — bindings are
 * resolved at compile time. `@Inject` classes are constructed automatically; only the things
 * Metro can't construct itself (the Ktor engine) need an explicit `@Provides`.
 */
@SingleIn(AppScope::class)
@DependencyGraph(scope = AppScope::class)
interface AppGraph {

    /** Fresh ViewModel per access; the AndroidX ViewModelStore caches the instance per Activity. */
    val chatViewModel: ChatViewModel

    @Provides
    @SingleIn(AppScope::class)
    fun httpEngine(): HttpClientEngine = OkHttp.create()

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides context: Context): AppGraph
    }
}
