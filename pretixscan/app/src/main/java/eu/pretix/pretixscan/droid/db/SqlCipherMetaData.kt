/*
 * Copyright 2018 requery.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.pretix.pretixscan.droid.db

import android.database.Cursor
import android.database.sqlite.SQLiteException
import io.requery.android.sqlite.BaseConnection
import io.requery.android.sqlite.SqliteMetaData
import io.requery.util.function.Function
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.Closeable

import java.sql.SQLException

internal class SqlCipherMetaData(connection: BaseConnection) : SqliteMetaData(connection) {

    @Throws(SQLException::class)
    override fun <R> queryMemory(function: Function<Cursor, R>, query: String): R {
        try {
            val database = SQLiteDatabase.openOrCreateDatabase(":memory:", "", null, null)
            val cursor = database.rawQuery(query, null)
            return function.apply(closeWithCursor(Closeable { database.close() }, cursor))
        } catch (e: SQLiteException) {
            throw SQLException(e)
        }

    }
}