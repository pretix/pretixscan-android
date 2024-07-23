package eu.pretix.pretixscan.utils

import io.requery.sql.Configuration
import io.requery.sql.ConnectionProvider
import io.requery.sql.TableCreationMode

/**
 * Converted copy of io.requery.android.sqlite.DatabaseProvider.
 *
 * The original is written in Java and causes platform declaration clashes with newer
 * androidx.sqlite versions.
 */
interface DatabaseProvider<T> : ConnectionProvider, AutoCloseable {
    open fun setLoggingEnabled(enable: Boolean)

    /**
     * Sets the [TableCreationMode] to use when the database is created or upgraded.
     *
     * @param mode to use
     */
    open fun setTableCreationMode(mode: TableCreationMode)

    /**
     * @return [Configuration] used by the provider
     */
    val configuration: Configuration

    /**
     * Callback for when the database schema is to be created.
     *
     * @param db instance
     */
    fun onCreate(db: T)

    /**
     * Callback for when the database should be configured.
     *
     * @param db instance
     */
    fun onConfigure(db: T)

    /**
     * Callback for when the database should be upgraded from an previous version to a new version.
     *
     * @param db instance
     */
    fun onUpgrade(db: T, oldVersion: Int, newVersion: Int)

    /**
     * @return read only database instance
     */
    val readableDatabase: T

    /**
     * @return readable and writable database instance
     */
    val writableDatabase: T

    /**
     * closes the database.
     */
    override fun close()
}
