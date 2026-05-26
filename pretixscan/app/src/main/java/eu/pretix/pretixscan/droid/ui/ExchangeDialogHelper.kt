package eu.pretix.pretixscan.droid.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import eu.pretix.libpretixnfc.android.hardware.NfcDisabled
import eu.pretix.libpretixnfc.android.hardware.NfcHandler
import eu.pretix.libpretixnfc.android.hardware.NfcHandlerMode
import eu.pretix.libpretixnfc.android.hardware.NfcUnsupported
import eu.pretix.libpretixnfc.communication.ChipReadError
import eu.pretix.libpretixsync.check.TicketCheckProvider
import eu.pretix.libpretixsync.db.MediaPolicy
import eu.pretix.libpretixsync.db.ReusableMediaType
import eu.pretix.libpretixui.android.questions.QuestionsDialogInterface
import eu.pretix.pretixscan.droid.R
import eu.pretix.pretixscan.utils.getNfcHandler

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

class ExchangeScanExistingNfcDialog(ctx: Activity, mediaType: ReusableMediaType, var onSuccessfulNfcScan: ((String, ReusableMediaType) -> Unit)): AlertDialog(ctx),
    NfcHandler.OnChipReadListener, QuestionsDialogInterface {
    private var v: View = LayoutInflater.from(context).inflate(R.layout.dialog_exchange_nfc_existing, null)
    private var nfcHandler: NfcHandler?

    init {
        setView(v)

        nfcHandler = getNfcHandler(ctx, mode = NfcHandlerMode.DEFAULT)
        if (nfcHandler == null) {
            cancel()
        } else {
            nfcHandler!!.setOnChipReadListener(this)
            try {
                nfcHandler!!.start(listOf(mediaType))
            } catch (_: NfcUnsupported) {
                cancel()
            } catch (_: NfcDisabled) {
                cancel()
            }
        }
    }

    override fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        return false
    }

    override fun chipReadSuccessfully(identifier: String, mediaType: ReusableMediaType) {
        dismiss()
        this.onSuccessfulNfcScan(identifier, mediaType)
    }

    override fun chipReadError(error: ChipReadError, identifier: String?) {
        TODO("Not yet implemented")
    }

    override fun dismiss() {
        nfcHandler?.stop()
        super.dismiss()
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

    val dialog = ExchangeScanExistingNfcDialog(ctx, res.requiredMediaType!!, completion)
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
