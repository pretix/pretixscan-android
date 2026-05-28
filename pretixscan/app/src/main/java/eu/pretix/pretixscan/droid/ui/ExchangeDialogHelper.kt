package eu.pretix.pretixscan.droid.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import eu.pretix.libpretixnfc.android.hardware.NfcHandler
import eu.pretix.libpretixnfc.communication.ChipReadError
import eu.pretix.libpretixsync.check.TicketCheckProvider
import eu.pretix.libpretixsync.db.MediaPolicy
import eu.pretix.libpretixsync.db.ReusableMediaType
import eu.pretix.libpretixui.android.questions.QuestionsDialogInterface
import eu.pretix.pretixscan.droid.R
import eu.pretix.pretixscan.droid.databinding.DialogReusableMediumExchangeNfcBinding


interface NfcDialogInterface {
    fun chipReadSuccessfully(identifier: String, mediaType: ReusableMediaType)
    fun chipReadError(error: ChipReadError, identifier: String?)
}

interface ExchangeDialogInterface {
    fun showError(resId: Int)
    fun showError(text: String)
    fun hideError()
    fun dismiss()
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

class ExchangeScanNfcDialog(var ctx: Activity, var requiredMediaType: ReusableMediaType, var onSuccessfulNfcScan: ((ExchangeDialogInterface, String, ReusableMediaType) -> Unit)): AlertDialog(ctx),
    NfcHandler.OnChipReadListener, QuestionsDialogInterface, NfcDialogInterface, ExchangeDialogInterface {
    private lateinit var binding: DialogReusableMediumExchangeNfcBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        binding = DialogReusableMediumExchangeNfcBinding.inflate(layoutInflater)
        setTitle(R.string.reusable_media_exchange_needed)
        setView(binding.root)
        setButton(BUTTON_NEGATIVE, ctx.getString(R.string.cancel)) { _, _ ->
            cancel()
        }

        super.onCreate(savedInstanceState)
    }

    override fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        return false
    }

    override fun chipReadSuccessfully(identifier: String, mediaType: ReusableMediaType) {
        if (mediaType != requiredMediaType) {
            showError(when (requiredMediaType) {
                ReusableMediaType.NFC_UID -> R.string.reusable_media_exchange_nfc_needs_nfc_uid
                ReusableMediaType.NFC_MF0AES -> R.string.reusable_media_exchange_nfc_needs_nfc_mf0aes
                else -> R.string.reusable_media_exchange_nfc_needs_nfc_unknown
            })
            return
        }
        hideError()
        this.onSuccessfulNfcScan(this, identifier, mediaType)
    }

    override fun chipReadError(error: ChipReadError, identifier: String?) {
        showError(when (error) {
            ChipReadError.IO_ERROR -> ctx.getString(R.string.nfc_read_error)
            ChipReadError.UNKNOWN_CHIP_TYPE -> ctx.getString(R.string.nfc_unknown_chip_type)
            ChipReadError.FOREIGN_CHIP -> ctx.getString(R.string.nfc_foreign_chip)
            ChipReadError.EMPTY_CHIP -> ctx.getString(R.string.nfc_empty_chip)
            else -> error.toString()
        })
    }

    override fun showError(resId: Int) {
        showError(ctx.getString(resId))
    }

    override fun showError(text: String) {
        binding.cvWarningMessage.visibility = View.VISIBLE
        binding.tvWarningMessage.text = text
    }

    override fun hideError() {
        binding.cvWarningMessage.visibility = View.GONE
    }
}

fun showExchangeDialog(
        ctx: Activity,
        res: TicketCheckProvider.CheckResult,
        completion: ((ExchangeDialogInterface, String, ReusableMediaType) -> Unit)): QuestionsDialogInterface {

    var supported = when (res.requiredMediaType) {
        ReusableMediaType.NFC_UID -> true
        ReusableMediaType.NFC_MF0AES -> true
        else -> false
    }

    // FIXME: implement support for MediaPolicy.NEW and MediaPolicy.REUSE_OR_NEW
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

    if (res.requiredMediaType?.isNfcBased() == true) {
        // FIXME: check if nfc is supported and enabled
    }

    val dialog = ExchangeScanNfcDialog(ctx, res.requiredMediaType!!, completion)
    dialog.setCanceledOnTouchOutside(false)
    dialog.show()
    return dialog
}
