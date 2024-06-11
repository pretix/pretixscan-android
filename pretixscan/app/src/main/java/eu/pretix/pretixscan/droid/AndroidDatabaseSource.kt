package eu.pretix.pretixscan.droid

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import eu.pretix.libpretixsync.db.Migrations
import io.requery.android.sqlite.DatabaseSource
import io.requery.meta.EntityModel
import io.sentry.Sentry
import java.sql.SQLException

class AndroidDatabaseSource(context: Context, model: EntityModel, version: Int) : DatabaseSource(context, model, version) {
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Only upgrades which are not automated by requery
        super.onUpgrade(db, oldVersion, newVersion)

        try {
            Migrations.android_manual_migrations(connection, oldVersion, newVersion)
        } catch (e: SQLException) {
            Sentry.captureException(e)
            e.printStackTrace()
        }

    }
}