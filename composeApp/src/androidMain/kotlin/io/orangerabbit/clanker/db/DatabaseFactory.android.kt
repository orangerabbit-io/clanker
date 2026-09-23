package io.orangerabbit.clanker.db

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/**
 * Production Android database (Task 8 wiring): single-file SQLite via
 * AndroidSqliteDriver.
 *
 * Task 3 contract: FK cascade + `PRAGMA foreign_keys=ON` is per-connection.
 * AndroidSqliteDriver holds ONE underlying connection for the driver lifetime,
 * so issuing the PRAGMA once on it right after creation enables foreign keys
 * for every later statement — ON DELETE CASCADE works in production, not just
 * tests.
 */
fun createDatabase(context: Context): ClankerDb {
    val driver = AndroidSqliteDriver(ClankerDb.Schema, context, "clanker.db")
    driver.execute(null, "PRAGMA foreign_keys=ON", 0)
    return ClankerDb(driver)
}