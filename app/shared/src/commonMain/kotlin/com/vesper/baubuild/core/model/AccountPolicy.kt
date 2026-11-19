package com.vesper.baubuild.core.model

/**
 * Who may register and use the app, decided from the account email.
 *
 * - `@stu.bau.edu` → BAU student, free.
 * - `@bau.edu`     → BAU staff, free, may submit project inquiries.
 * - anything else  → external; blocked until [externalAccessEnabled], then allowed behind payment.
 *
 * The backend is the source of truth (it enforces the domains on register, login and every token refresh); this
 * mirrors it with no network so the UI can explain *why* before a round trip, and so register / log in are never
 * sent for an address the server would refuse.
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

/**
 * Client-side mirror of the server's password rule (the server is authoritative and answers `422 weak_password`):
 * at least [MIN_LENGTH] characters, at least one letter and at least one digit.
 */
data class PasswordCheck(val longEnough: Boolean, val hasLetter: Boolean, val hasDigit: Boolean) {
    val isValid: Boolean get() = longEnough && hasLetter && hasDigit

    companion object {
        const val MIN_LENGTH = 8

        fun of(password: String): PasswordCheck = PasswordCheck(
            longEnough = password.length >= MIN_LENGTH,
            hasLetter = password.any { it.isLetter() },
            hasDigit = password.any { it.isDigit() },
        )
    }
}
