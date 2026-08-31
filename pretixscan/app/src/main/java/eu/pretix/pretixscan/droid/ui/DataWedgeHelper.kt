package eu.pretix.pretixscan.droid.ui


import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat.getExternalFilesDirs
import androidx.preference.PreferenceManager
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream


class DataWedgeHelper(private val ctx: Context) {
    val dwprofileVersion = 2

    val isInstalled: Boolean
        get() {
            try {
                val pm = ctx.packageManager
                pm.getPackageInfo("com.symbol.datawedge", 0)
                return true
            } catch (_: PackageManager.NameNotFoundException) {
                return false
            }

        }

    private val stagingDirectory: File
        get() {
            val externalStorageDirectory = getExternalFilesDirs(ctx, null)
            val stagingDirectory = File(externalStorageDirectory[0].path, "/datawedge_import")
            if (!stagingDirectory.exists()) {
                stagingDirectory.mkdirs()
            }
            return stagingDirectory
        }

    @Throws(IOException::class)
    private fun copyAllStagedFiles(path: String): Boolean {
        val stagingDirectory = stagingDirectory
        val filesToStage = stagingDirectory.listFiles()
        val outputDirectory = File(path)
        if (!outputDirectory.exists()) {
            try {
                outputDirectory.mkdirs()
            } catch (e: SecurityException) {
                e.printStackTrace()
                return false
            }
        }
        if (filesToStage!!.size == 0)
            return false
        var success = true
        for (i in filesToStage.indices) {
            // Write the file as .tmp to the autoimport directory
            try {
                val `in` = FileInputStream(filesToStage[i])
                val outputFile = File(outputDirectory, filesToStage[i].name + ".tmp")
                val out = FileOutputStream(outputFile)

                copyFile(`in`, out)

                // Rename the temp file
                var outputFileName = outputFile.absolutePath
                outputFileName = outputFileName.substring(0, outputFileName.length - 4)
                val fileToImport = File(outputFileName)
                outputFile.renameTo(fileToImport)
                // set permission to the file to read, write and exec.
                fileToImport.setExecutable(true, false)
                fileToImport.setReadable(true, false)
                fileToImport.setWritable(true, false)
            } catch (e: FileNotFoundException) {
                e.printStackTrace()
                success = false
            } catch (e: IOException) {
                e.printStackTrace()
                success = false
            }
        }
        return success
    }

    @Throws(IOException::class)
    private fun copyFile(`in`: InputStream, out: OutputStream) {
        val buffer = ByteArray(1024)
        var read: Int
        while (true) {
            read = `in`.read(buffer)
            if (read == -1) {
                break
            }
            out.write(buffer, 0, read)
        }
        out.flush()
        `in`.close()
        out.close()
    }


    @Throws(IOException::class)
    fun install(force: Boolean = false) {
        val stgfile = File(stagingDirectory, "dwprofile_pretix.db")
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        if (!force && stgfile.exists() && prefs.getInt("__dwprofile_installed_version", 0) >= dwprofileVersion) {
            return
        }
        val stgout = FileOutputStream(stgfile)

        val rawin = ctx.resources.openRawResource(eu.pretix.pretixscan.droid.R.raw.dwprofile)
        copyFile(rawin, stgout)
        Log.i("DataWedge", "DataWedge profile copied to staging directory: $stagingDirectory")

        // Legacy DataWedge Profile import
        val autoimportPath = "/enterprise/device/settings/datawedge/autoimport"
        if (copyAllStagedFiles(autoimportPath)) {
            Log.i("DataWedge", "DataWedge profile successfully written to legacy autoimport directory: $autoimportPath")
        } else {
            Log.e("DataWedge", "Failed to write DataWedge profile to legacy autoimport directory: $autoimportPath")
        }

        // New DataWedge Profile import (available since DataWedge 6.7)
        var importPath = stagingDirectory.toString()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            importPath = "/data/tmp/public"
            if (copyAllStagedFiles(importPath)) {
                Log.i("DataWedge", "DataWedge profile successfully written to public import directory: $importPath")
            } else {
                Log.i("DataWedge", "Failed to write DataWedge profile to public import directory: $importPath")
                // then try with the app specific path
                importPath = stagingDirectory.toString()
            }
        }

        val importIntent = Intent()
        val importBundle = Bundle()
        importBundle.putString("FOLDER_PATH", importPath)
        importIntent.action = "com.symbol.datawedge.api.ACTION"
        importIntent.putExtra("com.symbol.datawedge.api.IMPORT_CONFIG", importBundle)
        ctx.sendBroadcast(importIntent)
        Log.i("DataWedge", "DataWedge profile import broadcast sent")

        prefs.edit().putInt("__dwprofile_installed_version", dwprofileVersion).apply()
    }
}