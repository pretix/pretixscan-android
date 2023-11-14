package eu.pretix.pretixscan.droid.ui

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.core.os.bundleOf
import androidx.databinding.ObservableField
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eu.pretix.pretixscan.droid.databinding.DialogPinBinding
import java.util.Collections


class PINInputDataHolder() {
    val input = ObservableField("")
    val text = ObservableField("")
}

class PinDialog : DialogFragment() {
    companion object {
        const val TAG = "PinDialogFragment"
        const val RESULT_PIN = "pin"
        const val RESULT_DISMISS = "dismiss"
    }

    val data = PINInputDataHolder()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        data.input.set("")

        val binding = DialogPinBinding.inflate(layoutInflater)
        binding.data = data

        binding.keyboardButtonView0.setOnClickListener { pushDigit("0") }
        binding.keyboardButtonView00.visibility = View.INVISIBLE
        binding.keyboardButtonView1.setOnClickListener { pushDigit("1") }
        binding.keyboardButtonView2.setOnClickListener { pushDigit("2") }
        binding.keyboardButtonView3.setOnClickListener { pushDigit("3") }
        binding.keyboardButtonView4.setOnClickListener { pushDigit("4") }
        binding.keyboardButtonView5.setOnClickListener { pushDigit("5") }
        binding.keyboardButtonView6.setOnClickListener { pushDigit("6") }
        binding.keyboardButtonView7.setOnClickListener { pushDigit("7") }
        binding.keyboardButtonView8.setOnClickListener { pushDigit("8") }
        binding.keyboardButtonView9.setOnClickListener { pushDigit("9") }
        binding.keyboardButtonViewBackspace.setOnClickListener { pushBackspace() }

        return MaterialAlertDialogBuilder(context!!)
            .setView(binding.root)
            .setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false

                if (event.displayLabel.toString().matches(Regex("^[0-9]$"))) {
                    pushDigit(event.displayLabel.toString())
                    return@setOnKeyListener true
                }
                if (keyCode == KeyEvent.KEYCODE_DEL) {
                    pushBackspace()
                    return@setOnKeyListener true
                }
                if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    dismiss()
                    return@setOnKeyListener true
                }

                return@setOnKeyListener false
            }
            .create()
    }

    protected fun pushBackspace() {
        val current = data.input.get()!!
        if (current.isNotBlank()) {
            data.input.set(current.substring(0, current.length - 1))
        }
        data.text.set(Collections.nCopies(data.input.get()!!.length, "*").joinToString(""))
    }

    protected fun pushDigit(digit: String) {
        val current = data.input.get()!!
        data.input.set(current + digit)
        data.text.set(Collections.nCopies(data.input.get()!!.length, "*").joinToString(""))
        setFragmentResult(RESULT_PIN, bundleOf(RESULT_PIN to data.input.get()!!))
    }

    override fun onDismiss(dialog: DialogInterface) {
        setFragmentResult(RESULT_DISMISS, bundleOf())
        super.onDismiss(dialog)
    }

    fun show(manager: FragmentManager) {
        super.show(manager, TAG)
    }
}