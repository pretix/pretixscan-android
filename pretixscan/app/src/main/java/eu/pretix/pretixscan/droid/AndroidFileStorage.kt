package eu.pretix.pretixscan.droid

import android.content.Context

import java.io.OutputStream

import eu.pretix.libpretixsync.sync.FileStorage
import java.io.File

class AndroidFileStorage(private val context: Context) : FileStorage {

    fun getDir(): File {
        val dir = File(context.filesDir, "dbcache")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    override fun contains(filename: String): Boolean {
        return File(getDir(), filename).exists()
    }

    fun getFile(filename: String): File {
        return File(getDir(), filename)
    }

    override fun writeStream(filename: String): OutputStream? {
        return File(getDir(), filename).outputStream()
    }

    override fun delete(filename: String) {
        File(getDir(), filename).delete()
    }
}
