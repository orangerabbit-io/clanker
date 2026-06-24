package io.orangerabbit.clanker

import android.app.Application
import dev.zacsweers.metro.createGraphFactory
import io.orangerabbit.clanker.di.AppGraph

class ClankerApp : Application() {
    val graph: AppGraph by lazy {
        createGraphFactory<AppGraph.Factory>().create(applicationContext)
    }
}
