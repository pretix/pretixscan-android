package eu.pretix.pretixscan.droid.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.view.WindowManager
import eu.pretix.libpretixsync.check.TicketCheckProvider
import eu.pretix.libpretixsync.db.MediaPolicy
import eu.pretix.libpretixsync.db.ReusableMediaType
import eu.pretix.libpretixui.android.questions.QuestionsDialogInterface
import eu.pretix.pretixscan.droid.R

class ExchangeUnsupportedDialog(ctx: Activity): AlertDialog(ctx), QuestionsDialogInterface {
    init {
        setTitle("Medium Exchange Needed")
        setMessage("This ticket requires to be exchanged to a reusable medium. Sadly this version of pretixSCAN isn't able to do this yet.")
        setButton(BUTTON_NEUTRAL, ctx.getString(R.string.ok)) { _, _ ->
            cancel()
        }
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        return false
    }
}

fun showExchangeDialog(
        ctx: Activity,
        res: TicketCheckProvider.CheckResult,
        secret: String,
        sourceType: ReusableMediaType,
        ignore_unpaid: Boolean): QuestionsDialogInterface? {

    // first version: only nfc-uid, only existing - reject everything else
    var supported = false

    when (res.requiredMediaType) {
        ReusableMediaType.NFC_UID -> { supported = true }
        else -> {}
    }
    when (res.requiredMediaPolicy) {
        MediaPolicy.REUSE -> { /* supported*/ }
        else -> { supported = false }
    }

    if (!supported) {
        val dialog = ExchangeUnsupportedDialog(ctx)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        return dialog
    }

    return null
}
