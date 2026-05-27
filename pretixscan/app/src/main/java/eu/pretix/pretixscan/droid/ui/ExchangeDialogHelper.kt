package eu.pretix.pretixscan.droid.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import eu.pretix.libpretixnfc.android.hardware.NfcHandler
import eu.pretix.libpretixnfc.communication.ChipReadError
import eu.pretix.libpretixsync.check.TicketCheckProvider
import eu.pretix.libpretixsync.db.MediaPolicy
import eu.pretix.libpretixsync.db.ReusableMediaType
import eu.pretix.libpretixui.android.questions.QuestionsDialogInterface
import eu.pretix.pretixscan.droid.R


interface NfcQuestionsDialogInterface : QuestionsDialogInterface {
    fun chipReadSuccessfully(identifier: String, mediaType: ReusableMediaType)

    fun chipReadError(error: ChipReadError, identifier: String?)
}

class ExchangeUnsupportedDialog(ctx: Activity): AlertDialog(ctx), QuestionsDialogInterface {
    init {
        setTitle(R.string.reusable_media_exchange_needed)
        setMessage(ctx.getString(R.string.reusable_media_exchange_not_implemented))
        setButton(BUTTON_NEUTRAL, ctx.getString(R.string.ok)) { _, _ ->
            cancel()
        }
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        return false
    }
}

class ExchangeScanNfcDialog(ctx: Activity, var requiredMediaType: ReusableMediaType, var onSuccessfulNfcScan: ((String, ReusableMediaType) -> Unit)): AlertDialog(ctx),
    NfcHandler.OnChipReadListener, NfcQuestionsDialogInterface {
    private var v: View = LayoutInflater.from(context).inflate(R.layout.dialog_reusable_medium_exchange_nfc, null)

    init {
        setTitle(R.string.reusable_media_exchange_needed)
        setView(v)
        setButton(BUTTON_NEGATIVE, ctx.getString(R.string.cancel)) { _, _ ->
            cancel()
        }
    }

    override fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        return false
    }

    override fun chipReadSuccessfully(identifier: String, mediaType: ReusableMediaType) {
        if (mediaType != requiredMediaType) {
            // FIXME: show error
            return
        }
        dismiss()
        this.onSuccessfulNfcScan(identifier, mediaType)
    }

    override fun chipReadError(error: ChipReadError, identifier: String?) {
        TODO("Not yet implemented")
    }
}

fun showExchangeDialog(
        ctx: Activity,
        res: TicketCheckProvider.CheckResult,
        completion: ((String, ReusableMediaType) -> Unit)): QuestionsDialogInterface {

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

    // FIXME: check if nfc is supported and enabled

    val dialog = ExchangeScanNfcDialog(ctx, res.requiredMediaType!!, completion)
    dialog.setCanceledOnTouchOutside(false)
    dialog.show()
    return dialog

    // show dialog: please scan an nfc tag
    // onSuccessfulScan -> look up reusable media in api
    // #offline(look up uid in reusable_media)
    // if found: send server request to link reusable media
    // if req successful:
    // - *not* (but remark) link locally in db too (-> note that this is done for if offline happens in the next checkin)
    // - checkin media uid
}
