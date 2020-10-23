package eu.pretix.pretixscan

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log


interface ScanReceiver {
    fun scanResult(result: String)
}

class HardwareScanner(val receiver: ScanReceiver) {

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.hasExtra("com.symbol.datawedge.data_string")) {
                // Zebra DataWedge
                receiver.scanResult(intent.getStringExtra("com.symbol.datawedge.data_string"))
            } else if (intent.hasExtra("SCAN_BARCODE1")) {
                // NewLand
                val barcode = intent.getStringExtra("SCAN_BARCODE1").trim()
                receiver.scanResult(barcode)
            } else if (intent.hasExtra("EXTRA_BARCODE_DECODING_DATA")) {
                // Bluebird
                val barcode = String(intent.getByteArrayExtra("EXTRA_BARCODE_DECODING_DATA")).trim()
                receiver.scanResult(barcode)
            } else if (intent.hasExtra("barocode")) {
                // Intent receiver for LECOM-manufactured hardware scanners
                val barcode = intent?.getByteArrayExtra("barocode") // sic!
                val barocodelen = intent?.getIntExtra("length", 0)
                val barcodeStr = String(barcode, 0, barocodelen)
                receiver.scanResult(barcodeStr)
            }
        }
    }

    fun start(ctx: Context) {
        val filter = IntentFilter()
        filter.addAction("scan.rcv.message")  // LECOM
        filter.addAction("eu.pretix.SCAN")  // Zebra DataWedge
        filter.addAction("kr.co.bluebird.android.bbapi.action.BARCODE_CALLBACK_DECODING_DATA")  // Bluebird
        filter.addAction("nlscan.action.SCANNER_RESULT")  // NewLand
        ctx.registerReceiver(scanReceiver, filter)
    }

    fun stop(ctx: Context) {
        ctx.unregisterReceiver(scanReceiver)
    }
}

fun defaultToScanner(): Boolean {
    Log.i("HardwareScanner", "Detecting brand='${Build.BRAND}' model='${Build.MODEL}'")
    return when (Build.BRAND) {
        "Zebra" -> Build.MODEL.startsWith("TC") || Build.MODEL.startsWith("M") || Build.MODEL.startsWith("CC6")
        "Bluebird" -> Build.MODEL.startsWith("EF")
        "NewLand" -> Build.MODEL.startsWith("NQ")
        else -> false
    }
}