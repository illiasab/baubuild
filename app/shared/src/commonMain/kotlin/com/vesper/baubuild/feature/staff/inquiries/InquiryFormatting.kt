package com.vesper.baubuild.feature.staff.inquiries

import com.vesper.baubuild.core.model.InquiryStatus
import com.vesper.baubuild.core.model.ProjectType
import com.vesper.baubuild.core.model.StaffInquiry
import com.vesper.baubuild.core.model.StaffProfile
import com.vesper.baubuild.core.model.User

// Pure text helpers shared by the staff mobile screens (and reusable by the desktop portal).

/** "$4,820". */
fun formatUsd(amount: Int): String {
    val digits = kotlin.math.abs(amount).toString()
    val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
    return (if (amount < 0) "-$" else "$") + grouped
}

/** Short, human summary of the platforms: "iOS + Android", "App + Website", "Website", "Web App + Backend / API". */
fun typesSummary(types: Set<ProjectType>): String {
    if (types.isEmpty()) return ""
    val mobile = types.filter { it == ProjectType.IosApp || it == ProjectType.AndroidApp }
    val web = types.filter { it == ProjectType.Website || it == ProjectType.WebApp }
    val others = types - mobile.toSet() - web.toSet()
    if (mobile.isNotEmpty() && web.isNotEmpty() && others.isEmpty()) return "App + Website"
    if (mobile.size == 2 && others.isEmpty() && web.isEmpty()) return "iOS + Android"
    val labels = ProjectType.entries.filter { it in types }.map { it.label }
    return if (labels.size <= 2) labels.joinToString(" + ") else "${labels[0]} + ${labels.size - 1} more"
}

/** Second line of an inquiry row on Overview ("BAU Project · iOS + Android"); closed ones show their outcome. */
val StaffInquiry.overviewSubtitle: String
    get() = if (!status.isOpen && latest.isNotBlank()) latest
    else listOf(draft.projectFor.label, typesSummary(draft.types)).filter { it.isNotBlank() }.joinToString(" · ")

/** Second line of an inquiry row on Inquiries: "BAU Project · Urgent"; closed ones show their outcome. */
val StaffInquiry.listSubtitle: String
    get() = if (!status.isOpen && latest.isNotBlank()) latest else subtitle

/** Extra status line for open rows ("Day 4 of 7 · 1 blocker"); null when nothing to add. */
val StaffInquiry.listDetail: String?
    get() = latest.takeIf { status.isOpen && it.isNotBlank() }

/** "BAU Project · Urgent · $35". */
val StaffInquiry.detailEyebrow: String
    get() = listOf(subtitle, rewardLabel.takeIf { it.isNotBlank() && it != "—" }).filterNotNull().joinToString(" · ")

/** "Due Nov 22 · 3 students · dev lead reviews". */
val StaffInquiry.detailMeta: String
    get() = buildList {
        draft.deadline?.let(::formatShortDate)?.let { add("Due $it") }
        val students = progress?.studentNames?.size ?: 0
        if (students > 0) add(if (students == 1) "1 student" else "$students students")
        reviewedBy?.takeIf { it.isNotBlank() }?.let { add("$it reviews") }
    }.joinToString(" · ")

/** "Hello, Dr. Hale" for professors, "Hello, Nora" otherwise. Null when nobody is signed in. */
fun staffGreeting(user: User?, profile: StaffProfile?): String? {
    if (user == null) return null
    val words = user.displayName.split(' ').map { it.trim() }.filter { it.isNotEmpty() }
        .filterNot { it.lowercase().trimEnd('.') in HONORIFICS }
    val fromEmail = user.email.substringBefore('@').split('.', '_', '-').filter { it.isNotBlank() }
        .map { part -> part.replaceFirstChar { it.uppercase() } }
    val first = words.firstOrNull() ?: fromEmail.firstOrNull()
    val last = (if (words.size >= 2) words.last() else null) ?: fromEmail.takeIf { it.size >= 2 }?.last()
    val isProfessor = profile?.title?.contains("professor", ignoreCase = true) == true
    return when {
        isProfessor && last != null -> "Hello, Dr. $last"
        first != null -> "Hello, $first"
        else -> null
    }
}

private val HONORIFICS = setOf("dr", "prof", "mr", "mrs", "ms")

// ---------------------------------------------------------------------------------------------------------------
// Dates (ISO yyyy-MM-dd, no kotlinx-datetime in this project)
// ---------------------------------------------------------------------------------------------------------------

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** Parsed ISO date, or null if [iso] isn't a valid yyyy-MM-dd. */
data class IsoDate(val year: Int, val month: Int, val day: Int) {
    val iso: String get() = "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"

    /** Days since 1970-01-01 (H. Hinnant's days_from_civil). */
    val epochDay: Long
        get() {
            val y = if (month <= 2) year - 1 else year
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era.toLong() * 146097 + doe - 719468
        }

    companion object {
        fun parse(iso: String?): IsoDate? {
            val parts = iso?.trim()?.split('-') ?: return null
            if (parts.size != 3 || parts[0].length != 4 || parts[1].length != 2 || parts[2].length != 2) return null
            val y = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
            val d = parts[2].toIntOrNull()?.takeIf { it in 1..daysInMonth(y, m) } ?: return null
            return IsoDate(y, m, d)
        }

        /** Inverse of [epochDay] (civil_from_days). */
        fun fromEpochDay(days: Long): IsoDate {
            val z = days + 719468
            val era = (if (z >= 0) z else z - 146096) / 146097
            val doe = z - era * 146097
            val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
            val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
            return IsoDate(y, m, d)
        }

        private fun daysInMonth(y: Int, m: Int) = when (m) {
            2 -> if ((y % 4 == 0 && y % 100 != 0) || y % 400 == 0) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
    }
}

/** "2026-11-22" → "Nov 22". */
fun formatShortDate(iso: String): String? = IsoDate.parse(iso)?.let { "${MONTHS[it.month - 1]} ${it.day}" }

/** "2026-11-22" → "Nov 22, 2026". */
fun formatLongDate(iso: String): String? = IsoDate.parse(iso)?.let { "${MONTHS[it.month - 1]} ${it.day}, ${it.year}" }

/** Statuses grouped the way the Inquiries list shows them. */
enum class InquiryGroup(val title: String) {
    Drafts("Drafts"), Refining("AI Refining"), Posted("Posted"), Closed("Closed");

    companion object {
        fun of(status: InquiryStatus): InquiryGroup = when (status) {
            InquiryStatus.Draft -> Drafts
            InquiryStatus.AiRefining, InquiryStatus.ReadyToPost -> Refining
            InquiryStatus.Posted, InquiryStatus.InProgress -> Posted
            InquiryStatus.Done, InquiryStatus.Cancelled -> Closed
        }
    }
}
