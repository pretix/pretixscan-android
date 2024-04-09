package eu.pretix.pretixscan.droid

import android.database.sqlite.SQLiteException
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.multidex.MultiDexApplication
import com.facebook.flipper.android.AndroidFlipperClient
import com.facebook.flipper.core.FlipperClient
import com.facebook.soloader.SoLoader
import eu.pretix.libpretixsync.Models
import eu.pretix.libpretixsync.check.AsyncCheckProvider
import eu.pretix.libpretixsync.check.OnlineCheckProvider
import eu.pretix.libpretixsync.check.ProxyCheckProvider
import eu.pretix.libpretixsync.check.TicketCheckProvider
import eu.pretix.libpretixsync.db.Migrations
import eu.pretix.libpretixui.android.covid.DGC
import eu.pretix.pretixscan.droid.connectivity.ConnectivityHelper
import eu.pretix.pretixscan.droid.db.SqlCipherDatabaseSource
import eu.pretix.pretixscan.utils.KeystoreHelper
import io.requery.BlockingEntityStore
import io.requery.Persistable
import io.requery.android.sqlite.DatabaseSource
import io.requery.sql.EntityDataStore
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import java.util.concurrent.locks.ReentrantLock


class PretixScan : MultiDexApplication() {
    private var dataStore: BlockingEntityStore<Persistable>? = null
    val fileStorage = AndroidFileStorage(this)
    val syncLock = ReentrantLock()
    var flipperInit: FlipperInitializer.IntializationResult? = null
    lateinit var connectivityHelper: ConnectivityHelper

    private fun migrateSqlCipher() {
        val dbPass = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) KeystoreHelper.secureValue(KEYSTORE_PASSWORD, true)
        else KEYSTORE_PASSWORD

        System.loadLibrary("sqlcipher")

        val databaseFile = getDatabasePath(Models.DEFAULT.name)
        SQLiteDatabase.openOrCreateDatabase(databaseFile, dbPass, null, null, object: SQLiteDatabaseHook {
            override fun preKey(connection: SQLiteConnection) {
            }

            override fun postKey(connection: SQLiteConnection) {
                connection.execute("PRAGMA cipher_migrate;", emptyArray(), null)
            }
        })
    }

    val data: BlockingEntityStore<Persistable>
        get() {
            if (dataStore == null) {
                if (BuildConfig.DEBUG) {
                    // Do not encrypt on debug, because it breaks Stetho
                    val source = DatabaseSource(this, Models.DEFAULT, Migrations.CURRENT_VERSION)
                    source.setLoggingEnabled(BuildConfig.DEBUG)
                    val configuration = source.configuration
                    dataStore = EntityDataStore(configuration)
                } else {
                    val dbPass = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) KeystoreHelper.secureValue(KEYSTORE_PASSWORD, true)
                    else KEYSTORE_PASSWORD

                    var source = SqlCipherDatabaseSource(
                        this,
                        Models.DEFAULT,
                        Models.DEFAULT.name,
                        dbPass,
                        Migrations.CURRENT_VERSION
                    )
                    source.setLoggingEnabled(false)
                    try {
                        // check if database has been decrypted
                        source.readableDatabase.rawQuery("select count(*) from sqlite_master;", emptyArray())
                    } catch (e: SQLiteException) {
                        try {
                            migrateSqlCipher()
                            source.readableDatabase.rawQuery("select count(*) from sqlite_master;", emptyArray())
                        } catch (e: SQLiteException) {
                            // still not decrypted? then we probably lost the key due to a keystore issue
                            // let's start fresh, there's no reasonable other way to let the user out of this
                            this.deleteDatabase(Models.DEFAULT.getName())
                            source = SqlCipherDatabaseSource(
                                this,
                                Models.DEFAULT,
                                Models.DEFAULT.name,
                                dbPass,
                                Migrations.CURRENT_VERSION
                            )
                        }
                    }

                    val configuration = source.configuration
                    dataStore = EntityDataStore(configuration)
                }
            }
            return dataStore!!
        }

    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT > 26) {
            SoLoader.init(this, false)

            val client: FlipperClient = AndroidFlipperClient.getInstance(this)
            flipperInit = FlipperInitializer.initFlipperPlugins(this, client)
            AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
        }

        connectivityHelper = ConnectivityHelper(AppConfig(this))

        DGC().init(this@PretixScan)
    }

    fun getCheckProvider(conf: AppConfig): TicketCheckProvider {
        if (conf.proxyMode) {
            return ProxyCheckProvider(conf, AndroidHttpClientFactory(this))
        } else if (conf.offlineMode) {
            return AsyncCheckProvider(conf, data)
        } else {
            var fallback: TicketCheckProvider? = null
            var fallbackTimeout = 30000
            if (conf.autoOfflineMode != "off" && conf.autoOfflineMode != "errors") {
                fallbackTimeout = when (conf.autoOfflineMode) {
                    "1s" -> 1000
                    "2s" -> 2000
                    "3s" -> 3000
                    "5s" -> 5000
                    "10s" -> 10000
                    "15s" -> 15000
                    "20s" -> 20000
                    else -> throw Exception("Unknown offline mode ")
                }
                fallback = AsyncCheckProvider(conf, data)
            }
            return OnlineCheckProvider(conf, AndroidHttpClientFactory(this), data, fileStorage, fallback, fallbackTimeout)
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
