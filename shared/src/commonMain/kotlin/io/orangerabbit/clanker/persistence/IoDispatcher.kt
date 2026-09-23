package io.orangerabbit.clanker.persistence

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Dispatcher for blocking database I/O. On JVM/Android this is
 * `Dispatchers.IO`; on native targets `Dispatchers.IO` is internal in
 * kotlinx-coroutines (and its pool is limited there anyway), so the default
 * dispatcher is used — SQLDelight native drivers block the calling worker.
 */
internal expect val ioDispatcher: CoroutineDispatcher
