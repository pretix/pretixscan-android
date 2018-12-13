package eu.pretix.pretixscan.utils

import androidx.databinding.BindingConversion
import org.joda.time.DateTime
import org.joda.time.LocalDate
import org.joda.time.format.DateTimeFormat


@BindingConversion
fun formatDateTime(dt: DateTime): String = DateTimeFormat.mediumDateTime().print(dt);

@BindingConversion
fun formatLocalDate(dt: LocalDate): String = DateTimeFormat.mediumDate().print(dt);

fun formatDateTimeRange(from: DateTime, to: DateTime?) =
    if (to != null && to != from) {
        if (from.isMidnight && to.isMidnight) {
            formatLocalDate(from.toLocalDate()) + " – " + formatLocalDate(to.toLocalDate())
        } else {
            formatDateTime(from) + " – " + formatDateTime(to)
        }
    } else {
        if (from.isMidnight) formatLocalDate(from.toLocalDate()) else formatDateTime(from)
    }

val DateTime.isMidnight
    get() = this.millisOfDay == 0