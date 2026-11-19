package com.vesper.baubuild.feature.mywork.task

import com.vesper.baubuild.core.model.ProjectTask
import com.vesper.baubuild.core.model.TaskPriority
import com.vesper.baubuild.core.model.TaskStatus

/**
 * What the deadline block shows.
 * [dateLabel] "Sun, 22 Nov" (null when the task has no ISO due date), [relativeLabel] "Due in 3 days",
 * [isOverdue] true only for unfinished tasks past their due date.
 */
data class TaskDeadline(val dateLabel: String?, val relativeLabel: String, val isOverdue: Boolean, val hasDeadline: Boolean)

fun deadlineOf(task: ProjectTask): TaskDeadline {
    val date = task.dueDate?.let(::formatDueDate)
    val days = task.dueInDays
    if (date == null && days == null) return TaskDeadline(null, "No deadline", isOverdue = false, hasDeadline = false)
    val relative = when {
        days == null -> ""
        task.status == TaskStatus.Done -> "Completed"
        days == 0 -> "Due today"
        days == 1 -> "Due tomorrow"
        days > 1 -> "Due in $days days"
        days == -1 -> "Overdue by 1 day"
        else -> "Overdue by ${-days} days"
    }
    val overdue = days != null && days < 0 && task.status != TaskStatus.Done
    return TaskDeadline(date, relative, overdue, hasDeadline = true)
}

private val weekdays = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "2026-11-22" → "Sun, 22 Nov". Returns null for anything that isn't a valid ISO yyyy-MM-dd date. */
fun formatDueDate(iso: String): String? {
    val parts = iso.trim().take(10).split('-')
    if (parts.size != 3) return null
    val y = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
    val d = parts[2].toIntOrNull()?.takeIf { it in 1..daysInMonth(y, m) } ?: return null
    return "${weekdays[dayOfWeek(y, m, d)]}, $d ${months[m - 1]}"
}

private fun isLeap(y: Int) = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0

private fun daysInMonth(y: Int, m: Int) = when (m) {
    2 -> if (isLeap(y)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

/** 0 = Sunday. Days-from-civil (H. Hinnant); 1970-01-01 was a Thursday. */
private fun dayOfWeek(y0: Int, m: Int, d: Int): Int {
    val y = if (m <= 2) y0 - 1 else y0
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val days = era.toLong() * 146097 + doe - 719468
    return (((days + 4) % 7 + 7) % 7).toInt()
}

val TaskPriority.label: String get() = when (this) {
    TaskPriority.Low -> "Low priority"
    TaskPriority.Medium -> "Medium priority"
    TaskPriority.High -> "High priority"
}
