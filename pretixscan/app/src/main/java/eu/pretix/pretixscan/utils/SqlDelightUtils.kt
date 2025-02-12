package eu.pretix.pretixscan.utils

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import eu.pretix.libpretixsync.sqldelight.CheckIn
import eu.pretix.libpretixsync.sqldelight.Closing
import eu.pretix.libpretixsync.sqldelight.Event
import eu.pretix.libpretixsync.sqldelight.QueuedCheckIn
import eu.pretix.libpretixsync.sqldelight.Receipt
import eu.pretix.libpretixsync.sqldelight.ReceiptLine
import eu.pretix.libpretixsync.sqldelight.ReceiptPayment
import eu.pretix.libpretixsync.sqldelight.SubEvent
import eu.pretix.pretixscan.sqldelight.SyncDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.math.BigDecimal
import java.util.Date

fun createSyncDatabase(
    driver: SqlDriver,
    version: Long?,
    dateAdapter: ColumnAdapter<Date, String>,
    bigDecimalAdapter: ColumnAdapter<BigDecimal, Double>,
): SyncDatabase {
    // TODO: Check DB migrations
    if (version == null || version == 0L) {
        try {
            val t = object : TransacterImpl(driver) {}
            t.transaction {
                SyncDatabase.Schema.create(driver)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            throw e
        }
    }

    return SyncDatabase(
        driver = driver,
        CheckInAdapter =
        CheckIn.Adapter(
            datetimeAdapter = dateAdapter,
        ),
        ClosingAdapter =
        Closing.Adapter(
            cash_countedAdapter = bigDecimalAdapter,
            datetimeAdapter = dateAdapter,
            payment_sumAdapter = bigDecimalAdapter,
            payment_sum_cashAdapter = bigDecimalAdapter,
        ),
        EventAdapter =
        Event.Adapter(
            date_fromAdapter = dateAdapter,
            date_toAdapter = dateAdapter,
        ),
        QueuedCheckInAdapter = QueuedCheckIn.Adapter(
            datetimeAdapter = dateAdapter,
        ),
        ReceiptLineAdapter =
        ReceiptLine.Adapter(
            cart_expiresAdapter = dateAdapter,
            createdAdapter = dateAdapter,
            custom_price_inputAdapter = bigDecimalAdapter,
            listed_priceAdapter = bigDecimalAdapter,
            priceAdapter = bigDecimalAdapter,
            price_after_voucherAdapter = bigDecimalAdapter,
            tax_rateAdapter = bigDecimalAdapter,
            tax_valueAdapter = bigDecimalAdapter,
        ),
        ReceiptAdapter =
        Receipt.Adapter(
            datetime_closedAdapter = dateAdapter,
            datetime_openedAdapter = dateAdapter,
        ),
        ReceiptPaymentAdapter =
        ReceiptPayment.Adapter(
            amountAdapter = bigDecimalAdapter,
        ),
        SubEventAdapter =
        SubEvent.Adapter(
            date_fromAdapter = dateAdapter,
            date_toAdapter = dateAdapter,
        ),
    )
}

fun readVersionPragma(driver: SqlDriver): Long? {
    return driver.executeQuery(
        identifier = null,
        sql = "PRAGMA user_version;",
        mapper = { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0))
        },
        parameters = 0,
    ).value
}

fun createDriver(context: Context, dbName: String, dbPass: String?) =
    if (dbPass != null) {
        AndroidSqliteDriver(
            schema = SyncDatabase.Schema,
            context = context,
            name = dbName,
            callback = object : AndroidSqliteDriver.Callback(SyncDatabase.Schema) {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.setForeignKeyConstraintsEnabled(true)
                }
            },
            factory = SupportOpenHelperFactory(dbPass.toByteArray())
        )
    } else {
        AndroidSqliteDriver(
            schema = SyncDatabase.Schema,
            context = context,
            name = dbName,
            callback = object : AndroidSqliteDriver.Callback(SyncDatabase.Schema) {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    db.setForeignKeyConstraintsEnabled(true)
                }
            },
        )
    }
