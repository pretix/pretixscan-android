package eu.pretix.pretixscan.droid.ui

import android.app.DatePickerDialog
import android.content.Context
import androidx.appcompat.widget.AppCompatEditText
import java.text.DateFormat
import java.util.*


class DatePickerField(context: Context) : AppCompatEditText(context) {
    internal var cal = Calendar.getInstance()
    internal var dateFormat: DateFormat = android.text.format.DateFormat.getDateFormat(context)
    internal var set = false

    internal var dateChangeListener: DatePickerDialog.OnDateSetListener = DatePickerDialog.OnDateSetListener { view, year, monthOfYear, dayOfMonth ->
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, monthOfYear)
        cal.set(Calendar.DAY_OF_MONTH, dayOfMonth)

        setText(dateFormat.format(cal.time))
        set = true
    }

    var value: Calendar?
        get() = if (!set) {
            null
        } else cal.clone() as Calendar
        set(cal) {
            this.cal = cal?.clone() as Calendar
            set = true
            setText(dateFormat.format(cal.time))
        }


    init {
        isFocusable = false

        setOnClickListener {
            DatePickerDialog(
                    context,
                    dateChangeListener,
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH),
                    cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
    }

    fun setValue(date: Date) {
        cal.time = date
        set = true
        setText(dateFormat.format(cal.time))
    }
}
