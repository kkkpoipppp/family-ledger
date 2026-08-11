package com.familyledger.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        WorkerEntity::class,
        WorkItemEntity::class,
        LedgerEntryEntity::class,
        SettlementEntity::class,
        SettlementLineEntity::class,
        PurgedWorkerEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao

    companion object {
        @Volatile
        private var instance: LedgerDatabase? = null

        fun getInstance(context: Context): LedgerDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LedgerDatabase::class.java,
                    "family-ledger.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settlements ADD COLUMN reversedAt INTEGER")
                db.execSQL("ALTER TABLE settlements ADD COLUMN reversalReason TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE settlements ADD COLUMN reversalEntryId TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS settlement_lines (
                        id TEXT NOT NULL PRIMARY KEY,
                        settlementId TEXT NOT NULL,
                        workItemId TEXT,
                        description TEXT NOT NULL,
                        garmentTypeSnapshot TEXT NOT NULL,
                        lengthTypeSnapshot TEXT NOT NULL,
                        processNameSnapshot TEXT NOT NULL,
                        quantity INTEGER NOT NULL,
                        unitSnapshot TEXT NOT NULL,
                        amountMicros INTEGER NOT NULL,
                        FOREIGN KEY(settlementId) REFERENCES settlements(id)
                            ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_settlement_lines_settlementId " +
                        "ON settlement_lines(settlementId)",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS purged_workers (
                        id TEXT NOT NULL PRIMARY KEY,
                        purgedAt INTEGER NOT NULL,
                        originDeviceId TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
