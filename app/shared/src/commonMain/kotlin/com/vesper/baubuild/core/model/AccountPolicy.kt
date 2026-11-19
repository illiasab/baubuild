package com.vesper.baubuild.core.model

/**
 * Who may use the app, decided from the Microsoft account email.
 *
 * - `@stu.bau.edu` → BAU student, free.
 * - `@bau.edu`     → BAU staff, free, may submit project inquiries.
 * - anything else  → external; blocked until [externalAccessEnabled], then allowed behind payment.
 *
 * The backend is the source of truth; this mirrors it so the UI can explain *why* before a round trip.
 */
data class AccountPolicy(
    val studentDomains: Set<String> = setOf("stu.bau.edu"),
    val staffDomains: Set<String> = setOf("bau.edu"),
    val externalAccessEnabled: Boolean = false,
) {
    fun classify(email: String): AccountKind {
        val domain = email.trim().substringAfterLast('@', "").lowercase()
        return when {
            domain in studentDomains -> AccountKind.BauStudent
            domain in staffDomains -> AccountKind.BauStaff
            else -> AccountKind.External
        }
    }

    fun access(email: String): Access = when (classify(email)) {
        AccountKind.BauStudent, AccountKind.BauStaff -> Access.Free
        AccountKind.External -> if (externalAccessEnabled) Access.RequiresPayment else Access.NotYetAvailable
    }

    enum class Access { Free, RequiresPayment, NotYetAvailable }
}

fun AccountKind.canCreateInquiry(): Boolean = this == AccountKind.BauStaff
