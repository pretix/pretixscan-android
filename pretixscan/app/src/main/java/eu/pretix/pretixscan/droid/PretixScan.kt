package eu.pretix.pretixscan.droid

import android.database.sqlite.SQLiteException
import androidx.multidex.MultiDexApplication
import com.facebook.stetho.Stetho
import eu.pretix.libpretixsync.db.Migrations
import eu.pretix.libpretixsync.db.Models
import eu.pretix.pretixscan.utils.KeystoreHelper
import io.requery.BlockingEntityStore
import io.requery.Persistable
import io.requery.android.sqlcipher.SqlCipherDatabaseSource
import io.requery.android.sqlite.DatabaseSource
import io.requery.sql.EntityDataStore
import io.sentry.Sentry
import io.sentry.android.AndroidSentryClientFactory
import java.util.concurrent.locks.ReentrantLock


class PretixScan : MultiDexApplication() {
    private var dataStore: BlockingEntityStore<Persistable>? = null
    val fileStorage = AndroidFileStorage(this)
    val syncLock = ReentrantLock()

    val data: BlockingEntityStore<Persistable>
        get() {
            if (dataStore == null) {
                if (BuildConfig.DEBUG) {
                    // Do not encrypt on debug, because it breaks Stetho
                    val source = DatabaseSource(this, Models.DEFAULT, Migrations.CURRENT_VERSION)
                    source.setLoggingEnabled(BuildConfig.DEBUG)
                    val configuration = source.configuration
                    dataStore = EntityDataStore<Persistable>(configuration)
                } else {
                    val dbPass = KeystoreHelper.secureValue(KEYSTORE_PASSWORD, true)

                    var source = SqlCipherDatabaseSource(this,
                            Models.DEFAULT, Models.DEFAULT.getName(), dbPass, Migrations.CURRENT_VERSION)

                    try {
                        // check if database has been decrypted
                        source.readableDatabase.rawQuery("select count(*) from sqlite_master;", emptyArray()) //source.getReadableDatabase().getSyncedTables() ???
                    } catch (e: SQLiteException) {
                        // if not, delete it
                        this.deleteDatabase(Models.DEFAULT.getName())
                        // and create a new one
                        source = SqlCipherDatabaseSource(this,
                                Models.DEFAULT, Models.DEFAULT.getName(), dbPass, Migrations.CURRENT_VERSION)
                    }

                    val configuration = source.configuration
                    dataStore = EntityDataStore<Persistable>(configuration)
                }
            }
            return dataStore!!
        }

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Stetho.initializeWithDefaults(this)

        }
        if (BuildConfig.SENTRY_DSN != null) {
            val sentryDsn = BuildConfig.SENTRY_DSN
            Sentry.init(sentryDsn, AndroidSentryClientFactory(this))
        }
    }

    companion object {
        /*
     * It is not a security problem that the keystore password is hardcoded in plain text.
     * It would be only relevant in a case in which the attack would have either root access on the
     * phone or can execute arbitrary code with this application's user. In both cases, we're
     * screwed either way.
     */
        val KEYSTORE_PASSWORD = "ZnDNUkQ01PVZyD7oNP3a8DVXrvltxD"
    }
}
