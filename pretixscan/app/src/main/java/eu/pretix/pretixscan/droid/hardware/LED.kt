package eu.pretix.pretixscan.droid.hardware

import android.content.Context
import android.content.Intent

class LED(val ctx: Context) {
    fun success(blink: Boolean = false) {
        send("eu.pretix.led.SUCCESS", blink)
    }

    fun error(blink: Boolean = false) {
        send("eu.pretix.led.ERROR", blink)
    }

    fun attention(blink: Boolean = false) {
        send("eu.pretix.led.ATTENTION", blink)
    }

    fun progress(blink: Boolean = false) {
        send("eu.pretix.led.PROGRESS", blink)
    }

    fun off() {
        send("eu.pretix.led.OFF", false)
    }

    private fun send(action: String, blink: Boolean) {
        val intent = Intent()
        intent.action = action
        intent.putExtra("blink", blink)
        ctx.sendBroadcast(intent)
    }
}