package eu.pretix.pretixscan.droid.ui


import android.R
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.util.Log
import java.io.*


class DataWedgeHelper(private val ctx: Context) {

    val isInstalled: Boolean
        get() {
            try {
                val pm = ctx.packageManager
                pm.getPackageInfo("com.symbol.datawedge", 0)
                return true
            } catch (e: PackageManager.NameNotFoundException) {
                return false
            }

        }

    private val stagingDirectory: File
        get() {
            val externalStorageDirectory = Environment.getExternalStorageDirectory()
            val stagingDirectory = File(externalStorageDirectory.path, "/datawedge_import")
            if (!stagingDirectory.exists()) {
                stagingDirectory.mkdirs()
            }
            return stagingDirectory
        }

    @Throws(IOException::class)
    private fun copyAllStagedFiles() {
        val stagingDirectory = stagingDirectory
        val filesToStage = stagingDirectory.listFiles()
        val outputDirectory = File("/enterprise/device/settings/datawedge/autoimport")
        if (!outputDirectory.exists())
            outputDirectory.mkdirs()
        if (filesToStage!!.size == 0)
            return
        for (i in filesToStage.indices) {
            //  Write the file as .tmp to the autoimport directory
            try {
                val `in` = FileInputStream(filesToStage[i])
                val outputFile = File(outputDirectory, filesToStage[i].name + ".tmp")
                val out = FileOutputStream(outputFile)

                copyFile(`in`, out)

                //  Rename the temp file
                var outputFileName = outputFile.absolutePath
                outputFileName = outputFileName.substring(0, outputFileName.length - 4)
                val fileToImport = File(outputFileName)
                outputFile.renameTo(fileToImport)
                //set permission to the file to read, write and exec.
                fileToImport.setExecutable(true, false)
                fileToImport.setReadable(true, false)
                fileToImport.setWritable(true, false)
                Log.i("DataWedge", "DataWedge profile written successfully.")
            } catch (e: FileNotFoundException) {
                e.printStackTrace()
            } catch (e: IOException) {
                e.printStackTrace()
            }

        }
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
    fun install() {
        val stgfile = File(stagingDirectory, "dwprofile_pretix.db")
        if (stgfile.exists()) {
            return
        }
        val stgout = FileOutputStream(stgfile)

        val rawin = ctx.resources.openRawResource(eu.pretix.pretixscan.droid.R.raw.dwprofile)
        copyFile(rawin, stgout)
        copyAllStagedFiles()
    }
}