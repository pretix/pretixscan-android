package eu.pretix.pretixscan.droid

import android.app.Activity
import android.app.Application
import android.database.sqlite.SQLiteException
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.multidex.MultiDexApplication
import androidx.preference.PreferenceManager
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.pretix.libpretixsync.check.AsyncCheckProvider
import eu.pretix.libpretixsync.check.OnlineCheckProvider
import eu.pretix.libpretixsync.check.ProxyCheckProvider
import eu.pretix.libpretixsync.check.TicketCheckProvider
import eu.pretix.libpretixsync.sqldelight.AndroidUtilDateAdapter
import eu.pretix.libpretixsync.sqldelight.BigDecimalAdapter
import eu.pretix.libpretixsync.sqldelight.Migrations
import eu.pretix.pretixscan.droid.connectivity.ConnectivityHelper
import eu.pretix.pretixscan.sqldelight.SyncDatabase
import eu.pretix.pretixscan.utils.KeystoreHelper
import eu.pretix.pretixscan.utils.createDriver
import eu.pretix.pretixscan.utils.createSyncDatabase
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import java.util.concurrent.locks.ReentrantLock


class PretixScan : MultiDexApplication(), Application.ActivityLifecycleCallbacks {
    val fileStorage = AndroidFileStorage(this)
    val syncLock = ReentrantLock()
    lateinit var connectivityHelper: ConnectivityHelper

    private fun migrateSqlCipher(name: String, dbPass: String, driver: AndroidSqliteDriver): Boolean {
        System.loadLibrary("sqlcipher")

        try {
            driver.executeQuery(
                identifier = null,
                sql = "select count(*) from sqlite_master;",
                mapper = { cursor ->
                    cursor.next()
                    QueryResult.Value(cursor.getLong(0))
                },
                parameters = 0,
            )
            return false
        } catch (e: SQLiteException) {
            try {
                val databaseFile = getDatabasePath(name)
                SQLiteDatabase.openOrCreateDatabase(
                    databaseFile,
                    dbPass,
                    null,
                    null,
                    object : SQLiteDatabaseHook {
                        override fun preKey(connection: SQLiteConnection) {
                        }

                        override fun postKey(connection: SQLiteConnection) {
                            val result = connection.executeForLong(
                                "PRAGMA cipher_migrate;",
                                emptyArray(),
                                null
                            )
                            if (result != 0L) {
                                throw SQLiteException("cipher_migrate failed")
                            }
                        }
                    }).close()
                return true
            } catch (e: SQLiteException) {
                // still not decrypted? then we probably lost the key due to a keystore issue
                // let's start fresh, there's no reasonable other way to let the user out of this
                this.deleteDatabase(name)
                return true
            }
        }
    }

    val db: SyncDatabase by lazy {
        val dbName = Migrations.DEFAULT_DATABASE_NAME

        val dbPass = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) KeystoreHelper.secureValue(KEYSTORE_PASSWORD, true)
        else KEYSTORE_PASSWORD

        val androidDriver = if (BuildConfig.DEBUG) {
            createDriver(
                context = this.applicationContext,
                dbName = dbName,
                dbPass = null,
            )
        } else {
            System.loadLibrary("sqlcipher")
            val driver = createDriver(
                context = this.applicationContext,
                dbName = dbName,
                dbPass = dbPass,
            )

            val reopen = migrateSqlCipher(dbName, dbPass, driver)
            if (reopen) {
                // Re-open database if we had to delete it during migration or the cipher had to be migrated
                driver.close()
                createDriver(
                    context = this.applicationContext,
                    dbName = dbName,
                    dbPass = dbPass,
                )
            } else {
                driver
            }
        }

        // Uncomment LogSqliteDriver for verbose logging
        val driver = if (BuildConfig.DEBUG) {
//            LogSqliteDriver(androidDriver) {
//                Log.d("SQLDelight", it)
//            }
            androidDriver
        } else {
            androidDriver
        }

        createSyncDatabase(
            driver = driver,
            dateAdapter = AndroidUtilDateAdapter(),
            bigDecimalAdapter = BigDecimalAdapter(),
        )
    }

    override fun onCreate() {
        super.onCreate()

        if (Build.VERSION.SDK_INT > 26) {
            AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
        }

        connectivityHelper = ConnectivityHelper(AppConfig(this))


        registerActivityLifecycleCallbacks(this)
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

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?
    ) {
    }

    override fun onActivityDestroyed(activity: Activity) {}

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivityResumed(activity: Activity) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val shouldKeepScreenOn = prefs.getBoolean("pref_screen_always_on", true)
        if (shouldKeepScreenOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle
    ) {
    }

    override fun onActivityStarted(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {}

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
