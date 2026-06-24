package io.orangerabbit.clanker

import android.app.Application
import dev.zacsweers.metro.createGraph
import io.orangerabbit.clanker.di.AppGraph

class ClankerApp : Application() {
    val graph: AppGraph by lazy { createGraph<AppGraph>() }
}
