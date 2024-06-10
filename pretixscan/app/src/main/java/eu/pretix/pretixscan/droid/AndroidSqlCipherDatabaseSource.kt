package eu.pretix.pretixscan.droid

import android.content.Context
import eu.pretix.libpretixsync.db.Migrations
import eu.pretix.pretixscan.droid.db.SqlCipherDatabaseSource
import io.requery.meta.EntityModel
import io.sentry.Sentry
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.sql.SQLException

class AndroidSqlCipherDatabaseSource(context: Context, model: EntityModel, name: String, password: String?, version: Int) : SqlCipherDatabaseSource(context, model, name, password, version) {
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