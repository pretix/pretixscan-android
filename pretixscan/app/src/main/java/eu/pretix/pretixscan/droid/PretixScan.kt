package eu.pretix.pretixscan.droid

import android.database.sqlite.SQLiteException
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.multidex.MultiDexApplication
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.facebook.flipper.android.AndroidFlipperClient
import com.facebook.flipper.core.FlipperClient
import com.facebook.soloader.SoLoader
import eu.pretix.libpretixsync.Models
import eu.pretix.libpretixsync.check.AsyncCheckProvider
import eu.pretix.libpretixsync.check.OnlineCheckProvider
import eu.pretix.libpretixsync.check.ProxyCheckProvider
import eu.pretix.libpretixsync.check.TicketCheckProvider
import eu.pretix.libpretixsync.db.Migrations
import eu.pretix.libpretixsync.sqldelight.AndroidUtilDateAdapter
import eu.pretix.libpretixsync.sqldelight.BigDecimalAdapter
import eu.pretix.pretixscan.droid.connectivity.ConnectivityHelper
import eu.pretix.pretixscan.sqldelight.SyncDatabase
import eu.pretix.pretixscan.utils.KeystoreHelper
import eu.pretix.pretixscan.utils.createSyncDatabase
import eu.pretix.pretixscan.utils.readVersionPragma
import io.requery.BlockingEntityStore
import io.requery.Persistable
import io.requery.sql.EntityDataStore
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.util.concurrent.locks.ReentrantLock


class PretixScan : MultiDexApplication() {
    private var dataStore: BlockingEntityStore<Persistable>? = null
    val fileStorage = AndroidFileStorage(this)
    val syncLock = ReentrantLock()
    var flipperInit: FlipperInitializer.IntializationResult? = null
    lateinit var connectivityHelper: ConnectivityHelper

    private fun migrateSqlCipher(name: String, dbPass: String) {
        System.loadLibrary("sqlcipher")

        val databaseFile = getDatabasePath(name)
        SQLiteDatabase.openOrCreateDatabase(databaseFile, dbPass, null, null, object: SQLiteDatabaseHook {
            override fun preKey(connection: SQLiteConnection) {
            }

            override fun postKey(connection: SQLiteConnection) {
                val result = connection.executeForLong("PRAGMA cipher_migrate;", emptyArray(), null)
                if (result != 0L) {
                    throw SQLiteException("cipher_migrate failed")
                }
            }
        }).close()
    }

    val data: BlockingEntityStore<Persistable>
        get() {
            if (dataStore == null) {
                if (BuildConfig.DEBUG) {
                    // Do not encrypt on debug, because it breaks Stetho
                    val source = AndroidDatabaseSource(this, Models.DEFAULT, Migrations.CURRENT_VERSION)
                    source.setLoggingEnabled(BuildConfig.DEBUG)
                    val configuration = source.configuration
                    dataStore = EntityDataStore(configuration)
                } else {
                    val dbPass = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) KeystoreHelper.secureValue(KEYSTORE_PASSWORD, true)
                    else KEYSTORE_PASSWORD

                    var source = AndroidSqlCipherDatabaseSource(
                        this,
                        Models.DEFAULT,
                        Models.DEFAULT.name,
                        dbPass,
                        Migrations.CURRENT_VERSION
                    )
                    try {
                        // check if database has been decrypted
                        source.readableDatabase.rawQuery("select count(*) from sqlite_master;", emptyArray())
                    } catch (e: SQLiteException) {
                        try {
                            source.close()
                            migrateSqlCipher(Models.DEFAULT.name, dbPass)
                            source = AndroidSqlCipherDatabaseSource(
                                this,
                                Models.DEFAULT,
                                Models.DEFAULT.name,
                                dbPass,
                                Migrations.CURRENT_VERSION
                            )
                            source.readableDatabase.rawQuery("select count(*) from sqlite_master;", emptyArray())
                        } catch (e: SQLiteException) {
                            // still not decrypted? then we probably lost the key due to a keystore issue
                            // let's start fresh, there's no reasonable other way to let the user out of this
                            this.deleteDatabase(Models.DEFAULT.getName())
                            source = AndroidSqlCipherDatabaseSource(
                                this,
                                Models.DEFAULT,
                                Models.DEFAULT.name,
                                dbPass,
                                Migrations.CURRENT_VERSION
                            )
                        }
                    }
                    source.setLoggingEnabled(false)

                    val configuration = source.configuration
                    dataStore = EntityDataStore(configuration)
                }
            }
            return dataStore!!
        }

    val db: SyncDatabase by lazy {
        // Access data to init schema through requery if it hasn't been created already
        data.raw("PRAGMA user_version;").first()

        val dbPass = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) KeystoreHelper.secureValue(KEYSTORE_PASSWORD, true)
        else KEYSTORE_PASSWORD

        val androidDriver = if (BuildConfig.DEBUG) {
            AndroidSqliteDriver(
                schema = SyncDatabase.Schema,
                context = this.applicationContext,
                name = "default",
                callback = object : AndroidSqliteDriver.Callback(SyncDatabase.Schema) {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.setForeignKeyConstraintsEnabled(true)
                    }
                },
            )
        } else {
            System.loadLibrary("sqlcipher")
            AndroidSqliteDriver(
                schema = SyncDatabase.Schema,
                context = this.applicationContext,
                name = "default",
                callback = object : AndroidSqliteDriver.Callback(SyncDatabase.Schema) {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.setForeignKeyConstraintsEnabled(true)
                    }
                },
                factory = SupportOpenHelperFactory(dbPass.toByteArray())
            )
        }

        // Uncomment LogSqliteDriver for verbose logging
        val driver = if(BuildConfig.DEBUG) {
//            LogSqliteDriver(androidDriver) {
//                Log.d("SQLDelight", it)
//            }
            androidDriver
        } else {
            androidDriver
        }

        createSyncDatabase(
            driver = driver,
            version = readVersionPragma(driver),
            dateAdapter = AndroidUtilDateAdapter(),
            bigDecimalAdapter = BigDecimalAdapter(),
        )
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
    }

    fun getCheckProvider(conf: AppConfig): TicketCheckProvider {
        if (conf.proxyMode) {
            return ProxyCheckProvider(conf, AndroidHttpClientFactory(this))
        } else if (conf.offlineMode) {
            return AsyncCheckProvider(conf, db)
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
                fallback = AsyncCheckProvider(conf, db)
            }
            return OnlineCheckProvider(conf, AndroidHttpClientFactory(this), db, fileStorage, fallback, fallbackTimeout)
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
