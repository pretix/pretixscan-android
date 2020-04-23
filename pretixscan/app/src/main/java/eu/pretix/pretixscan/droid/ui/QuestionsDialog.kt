package eu.pretix.pretixscan.droid.ui


import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import eu.pretix.libpretixsync.check.QuestionType
import eu.pretix.libpretixsync.check.TicketCheckProvider
import eu.pretix.libpretixsync.db.AbstractQuestion
import eu.pretix.libpretixsync.db.Question
import eu.pretix.libpretixsync.db.QuestionOption
import eu.pretix.pretixscan.droid.R
import org.joda.time.LocalDate
import org.joda.time.LocalTime.fromDateFields
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*

fun addQuestionsError(ctx: Context, f: Any, label: TextView?, strid: Int) {
    if (f is EditText) {
        f.error = if (strid == 0) null else ctx.getString(strid)
    } else if (f is MutableList<*> && f[0] is EditText) {
        (f as List<EditText>).get(1).error = if (strid == 0) null else ctx.getString(strid)
    } else if (label != null) {
        label.error = if (strid == 0) null else ctx.getString(strid)
    }
}

internal class OptionAdapter(context: Context, objects: MutableList<QuestionOption>) : ArrayAdapter<QuestionOption>(context, R.layout.spinneritem_simple, objects)

fun showQuestionsDialog(ctx: Activity, res: TicketCheckProvider.CheckResult,
                        secret: String, ignore_unpaid: Boolean,
                        retryHandler: ((String, MutableList<TicketCheckProvider.Answer>, Boolean) -> Unit)): Dialog {
    val inflater = ctx.layoutInflater
    val fviews = HashMap<Question, Any>()
    val labels = HashMap<Question, TextView>()
    val hf = SimpleDateFormat("HH:mm", Locale.US)
    val wf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
    val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    val view = inflater.inflate(R.layout.dialog_questions, null)
    val llFormFields = view.findViewById<LinearLayout>(R.id.llFormFields)

    for (ra in res.requiredAnswers!!) {
        val tv = TextView(ctx)
        tv.text = ra.question.question
        llFormFields.addView(tv)
        labels.put(ra.question, tv)

        when (ra.question.getType()) {
            QuestionType.S -> {
                val fieldS = EditText(ctx)
                fieldS.setText(ra.currentValue)
                fieldS.setLines(1)
                fieldS.setSingleLine(true)
                fviews[ra.question] = fieldS
                llFormFields.addView(fieldS)
            }
            QuestionType.T -> {
                val fieldT = EditText(ctx)
                fieldT.setText(ra.currentValue)
                fieldT.setLines(2)
                fviews[ra.question] = fieldT
                llFormFields.addView(fieldT)
            }
            QuestionType.N -> {
                val fieldN = EditText(ctx)
                fieldN.setText(ra.currentValue)
                fieldN.inputType = InputType.TYPE_CLASS_NUMBER.or(InputType.TYPE_NUMBER_FLAG_DECIMAL).or(InputType.TYPE_NUMBER_FLAG_SIGNED)
                fieldN.setSingleLine(true)
                fieldN.setLines(1)
                fviews[ra.question] = fieldN
                llFormFields.addView(fieldN)
            }

            QuestionType.B -> {
                val fieldB = CheckBox(ctx)
                fieldB.setText(R.string.yes)
                fieldB.isChecked = "True" == ra.currentValue
                fviews[ra.question] = fieldB
                llFormFields.addView(fieldB)
            }
            QuestionType.F -> {
            }
            QuestionType.M -> {
                val fields = ArrayList<CheckBox>()
                val selected = ra.currentValue!!.split(",")
                for (opt in ra.question.options) {
                    val field = CheckBox(ctx)
                    field.text = opt.value
                    field.tag = opt.server_id
                    if (selected.contains(opt.server_id.toString())) {
                        field.isChecked = true
                    }
                    fields.add(field)
                    llFormFields.addView(field)
                }
                fviews[ra.question] = fields
            }
            QuestionType.C -> {
                val fieldC = Spinner(ctx)
                val opts = ra.question.options
                val emptyOpt = QuestionOption(0L, 0, "", "")
                opts.add(0, emptyOpt)
                fieldC.adapter = OptionAdapter(ctx, opts)
                var i = 0
                for (opt in ra.question.options) {
                    if (opt.server_id.toString() == ra.currentValue) {
                        fieldC.setSelection(i)
                        break
                    }
                    i++
                }
                fviews[ra.question] = fieldC
                llFormFields.addView(fieldC)
            }
            QuestionType.D -> {
                val fieldD = DatePickerField(ctx)
                if (ra.currentValue!!.isNotEmpty()) {
                    try {
                        fieldD.setValue(df.parse(ra.currentValue))
                    } catch (e: ParseException) {
                        e.printStackTrace()
                    }
                }
                fviews[ra.question] = fieldD
                llFormFields.addView(fieldD)
            }
            QuestionType.H -> {
                val fieldH = TimePickerField(ctx)
                fviews[ra.question] = fieldH
                try {
                    fieldH.value = fromDateFields(hf.parse(ra.currentValue))
                } catch (e: ParseException) {
                    e.printStackTrace()
                }
                llFormFields.addView(fieldH)
            }
            QuestionType.W -> {
                val fieldsW = ArrayList<EditText>()
                val llInner = LinearLayout(ctx)
                llInner.orientation = LinearLayout.HORIZONTAL

                val fieldWD = DatePickerField(ctx)
                fieldWD.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, 1f)
                fieldWD.gravity = Gravity.CENTER
                fieldsW.add(fieldWD)
                llInner.addView(fieldWD)

                val fieldWH = TimePickerField(ctx)
                fieldWH.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, 1f)
                fieldWH.gravity = Gravity.CENTER
                fieldsW.add(fieldWH)
                llInner.addView(fieldWH)

                if (ra.currentValue!!.isNotEmpty()) {
                    try {
                        fieldWD.setValue(wf.parse(ra.currentValue))
                        fieldWH.value = fromDateFields(wf.parse(ra.currentValue))
                    } catch (e: ParseException) {
                        e.printStackTrace()
                    }
                }

                fviews[ra.question] = fieldsW
                llFormFields.addView(llInner)
            }
        }
    }

    val dialog = AlertDialog.Builder(ctx)
            .setView(view)
            .setNegativeButton(R.string.cancel) { dialogInterface, i ->
                dialogInterface.cancel()
            }
            .setPositiveButton(R.string.cont) { dialogInterface, i ->
            }.create()

    dialog.setOnShowListener {
        val button = (dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE)
        button.setOnClickListener {

            val answers = ArrayList<TicketCheckProvider.Answer>()
            var has_errors = false

            for (ra in res.requiredAnswers!!) {
                var answer = ""
                var empty = false
                var field = fviews.get(ra.question)
                when (ra.question.type) {
                    QuestionType.S, QuestionType.T -> {
                        answer = (field as EditText).text.toString()
                        empty = answer.trim() == ""
                    }
                    QuestionType.N -> {
                        answer = (field as EditText).text.toString()
                        empty = answer.trim() == ""
                    }
                    QuestionType.B -> {
                        answer = if ((field as CheckBox).isChecked) "True" else "False"
                        empty = answer == "False"
                    }
                    QuestionType.F -> {
                        empty = true
                    }
                    QuestionType.M -> {
                        empty = true
                        val aw = StringBuilder()
                        for (f in (field as List<CheckBox>)) {
                            if (f.isChecked) {
                                if (!empty) {
                                    aw.append(",")
                                }
                                aw.append(f.tag)
                                empty = false
                            }
                        }
                        answer = aw.toString()
                    }
                    QuestionType.C -> {
                        val opt = ((field as Spinner).selectedItem as QuestionOption)
                        if (opt.server_id == 0L) {
                            empty = true
                        } else {
                            answer = opt.server_id.toString()
                        }
                    }
                    QuestionType.D -> {
                        empty = ((field as DatePickerField).value == null)
                        if (!empty) {
                            answer = df.format(field.value!!.time)
                        }
                    }
                    QuestionType.H -> {
                        empty = ((field as TimePickerField).value == null)
                        if (!empty) {
                            answer = hf.format(field.value!!.toDateTimeToday().toDate())
                        }
                    }
                    QuestionType.W -> {
                        val fieldset = field as List<View>
                        empty = (
                                (fieldset[0] as DatePickerField).value == null
                                        || (fieldset[1] as TimePickerField).value == null
                                )
                        if (!empty) {
                            answer = wf.format(
                                    LocalDate.fromCalendarFields((fieldset[0] as DatePickerField).value).toDateTime(
                                            (fieldset[1] as TimePickerField).value
                                    ).toDate()
                            )
                        }
                    }
                }

                if (empty && ra.question.isRequired) {
                    has_errors = true
                    addQuestionsError(ctx, field!!, labels[ra.question], R.string.question_input_required)
                } else if (empty) {
                    answers.add(TicketCheckProvider.Answer(ra.question, ""))
                } else {
                    try {
                        ra.question.clean_answer(answer, ra.question.options)
                        addQuestionsError(ctx, field!!, labels[ra.question], 0)
                    } catch (e: AbstractQuestion.ValidationException) {
                        has_errors = true
                        addQuestionsError(ctx, field!!, labels[ra.question], R.string.question_input_invalid)
                    }
                    answers.add(TicketCheckProvider.Answer(ra.question, answer))
                }
            }
            if (!has_errors) {
                dialog.dismiss()
                retryHandler(secret, answers, ignore_unpaid)
            } else {
                Toast.makeText(ctx, R.string.question_validation_error, Toast.LENGTH_SHORT).show()
            }
        }
    }
    dialog.show()
    return dialog
}

