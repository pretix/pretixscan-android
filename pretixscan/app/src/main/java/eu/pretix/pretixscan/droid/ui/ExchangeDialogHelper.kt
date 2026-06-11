package eu.pretix.pretixscan.droid.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import eu.pretix.libpretixnfc.android.hardware.NfcHandler
import eu.pretix.libpretixnfc.android.hardware.NfcHandler.NfcState
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

class ExchangeUnsupportedDialog(ctx: Activity, resId: Int): AlertDialog(ctx), QuestionsDialogInterface {
    init {
        setTitle(R.string.reusable_media_exchange_needed)
        setMessage(ctx.getString(resId))
        setButton(BUTTON_NEUTRAL, ctx.getString(R.string.ok)) { _, _ ->
            cancel()
        }
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    override fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        return false
    }
}

class ExchangeScanNfcDialog(var ctx: Activity, var requiredMediaType: ReusableMediaType, var onSuccessfulNfcScan: ((String, ReusableMediaType) -> Unit)): AlertDialog(ctx),
    NfcHandler.OnChipReadListener, QuestionsDialogInterface, NfcDialogInterface {
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
        if (mediaType == ReusableMediaType.NONE || mediaType == ReusableMediaType.UNSUPPORTED || mediaType.serverName == null) {
            showError(R.string.reusable_media_exchange_unknown_type)
            return
        }
        if (mediaType != requiredMediaType) {
            showError(when (requiredMediaType) {
                ReusableMediaType.NFC_UID -> R.string.reusable_media_exchange_nfc_needs_nfc_uid
                ReusableMediaType.NFC_MF0AES -> R.string.reusable_media_exchange_nfc_needs_nfc_mf0aes
                else -> R.string.reusable_media_exchange_nfc_needs_nfc_unknown
            })
            return
        }

        hideError()
        dismiss()
        this.onSuccessfulNfcScan(identifier, mediaType)
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

    fun showError(resId: Int) {
        showError(ctx.getString(resId))
    }

    fun showError(text: String) {
        binding.cvWarningMessage.visibility = View.VISIBLE
        binding.tvWarningMessage.text = text
    }

    fun hideError() {
        binding.cvWarningMessage.visibility = View.GONE
    }
}

fun showExchangeDialog(
        ctx: Activity,
        res: TicketCheckProvider.CheckResult,
        nfcState: NfcState?,
        completion: ((String, ReusableMediaType) -> Unit)): QuestionsDialogInterface {

    var supported = when (res.requiredMediaType) {
        ReusableMediaType.NFC_UID -> true
        ReusableMediaType.NFC_MF0AES -> true
        else -> false
    }

    when (res.requiredMediaPolicy) {
        MediaPolicy.NEW,
        MediaPolicy.REUSE_OR_NEW,
        MediaPolicy.APPEND_OR_NEW -> {
            supported = (res.requiredMediaType != ReusableMediaType.NFC_MF0AES)
        }
        MediaPolicy.REUSE -> { /* supported*/ }
        MediaPolicy.APPEND -> { /* supported*/ }
        else -> { supported = false }
    }

    if (!supported) {
        val dialog = ExchangeUnsupportedDialog(ctx, R.string.reusable_media_exchange_not_implemented)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        return dialog
    }

    if (res.requiredMediaType?.isNfcBased() == true && (nfcState == null || nfcState == NfcState.UNSUPPORTED)) {
        val dialog = ExchangeUnsupportedDialog(ctx, R.string.reusable_media_exchange_no_nfc_support)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        return dialog
    }

    val dialog = ExchangeScanNfcDialog(ctx, res.requiredMediaType!!, completion)
    dialog.setCanceledOnTouchOutside(false)
    dialog.show()

    if (res.requiredMediaType?.isNfcBased() == true && nfcState == NfcState.DISABLED) {
        // FIXME: offer `startActivity(new Intent(Settings.ACTION_NFC_SETTINGS))`
        dialog.showError(R.string.nfc_disabled)
    }

    return dialog
}
