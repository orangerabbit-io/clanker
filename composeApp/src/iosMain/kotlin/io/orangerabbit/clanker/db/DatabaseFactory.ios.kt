package io.orangerabbit.clanker.db

import app.cash.sqldelight.driver.native.NativeSqliteDriver

/**
 * Production iOS database (Task 8 wiring). Same FK rationale as the Android
 * factory: the native driver uses one connection, so the PRAGMA set once on it
 * enables ON DELETE CASCADE for the app's lifetime.
 */
fun createDatabase(): ClankerDb {
    val driver = NativeSqliteDriver(ClankerDb.Schema, "clanker.db")
    driver.execute(null, "PRAGMA foreign_keys=ON", 0)
    return ClankerDb(driver)
}