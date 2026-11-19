package com.vesper.baubuild.data.http.auth

import com.vesper.baubuild.core.model.AccountKind
import com.vesper.baubuild.core.model.AccountPolicy
import com.vesper.baubuild.core.model.Role
import com.vesper.baubuild.core.model.User
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** `device` in the register / login / verify-email / password-reset bodies. */
@Serializable
internal data class DeviceDto(
    val platform: String,
    @SerialName("app_version") val appVersion: String,
)

/** `POST /v1/auth/register`. Never log: [toString] hides the password. */
@Serializable
internal data class RegisterRequestDto(
    val name: String,
    val email: String,
    val password: String,
    val device: DeviceDto,
) {
    override fun toString(): String = "RegisterRequestDto(email=$email)"
}

/** `POST /v1/auth/verify-email`. */
@Serializable
internal data class VerifyEmailRequestDto(val email: String, val code: String, val device: DeviceDto) {
    override fun toString(): String = "VerifyEmailRequestDto(email=$email)"
}

/** `POST /v1/auth/resend-code`; `purpose` is `verify` or `reset`. */
@Serializable
internal data class ResendCodeRequestDto(val email: String, val purpose: String)

/** `POST /v1/auth/login`. Never log: [toString] hides the password. */
@Serializable
internal data class LoginRequestDto(val email: String, val password: String, val device: DeviceDto) {
    override fun toString(): String = "LoginRequestDto(email=$email)"
}

/** `POST /v1/auth/password/forgot`. */
@Serializable
internal data class ForgotPasswordRequestDto(val email: String)

/** `POST /v1/auth/password/reset`. Never log: [toString] hides the password and code. */
@Serializable
internal data class ResetPasswordRequestDto(
    val email: String,
    val code: String,
    @SerialName("new_password") val newPassword: String,
    val device: DeviceDto,
) {
    override fun toString(): String = "ResetPasswordRequestDto(email=$email)"
}

/** 202 of `POST /v1/auth/register`, `resend-code` and `password/forgot` (fields optional: lenient decoding). */
@Serializable
internal data class CodeSentDto(
    @SerialName("verification_required") val verificationRequired: Boolean? = null,
    val email: String? = null,
    @SerialName("resend_after_s") val resendAfterSeconds: Long? = null,
)

/** 200 of `POST /v1/auth/login`, `verify-email`, `password/reset` (and `POST /v1/auth/refresh`). */
@Serializable
internal data class TokenResponseDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    /** Seconds. */
    @SerialName("expires_in") val expiresIn: Long? = null,
    val user: AuthUserDto? = null,
    val entitlement: EntitlementDto? = null,
)

/** `{tier, expires_at}`; not modelled in the app yet (billing is off). */
@Serializable
internal data class EntitlementDto(
    val tier: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

/**
 * The backend's user object (`GET /v1/me`, `user` in token responses):
 * `{id, name, initials, avatar, bio, role, department, skills, classes, email}`. Everything but `id` is optional
 * here so new or missing fields never break sign-in.
 */
@Serializable
internal data class AuthUserDto(
    val id: String,
    val name: String? = null,
    val initials: String? = null,
    /** URL string or null; kept as JSON so an object here later doesn't fail decoding. */
    val avatar: JsonElement? = null,
    val bio: String? = null,
    /** `student` | `lead` | `staff` | `admin`; unknown values fall back to the email domain. */
    val role: String? = null,
    val department: String? = null,
    val skills: List<String> = emptyList(),
    val classes: List<String> = emptyList(),
    val email: String? = null,
)

/**
 * Backend user → [User]. `kind` and `role` come from the backend `role` (`staff`/`admin` → staff account,
 * `student`/`lead` → student account); an unknown role falls back to the email domain ([AccountPolicy]).
 * Score / earnings / approved projects are not part of this payload (`GET /v1/me/stats`) and keep defaults.
 */
internal fun AuthUserDto.toUser(policy: AccountPolicy = AccountPolicy()): User {
    val address = email.orEmpty().trim()
    val normalizedRole = role?.trim()?.lowercase()
    val kind = when (normalizedRole) {
        "staff", "admin" -> AccountKind.BauStaff
        "student", "lead" -> AccountKind.BauStudent
        else -> if (address.isNotEmpty()) policy.classify(address) else AccountKind.BauStudent
    }
    val appRole = when (normalizedRole) {
        "lead" -> Role.Lead
        "staff", "admin" -> Role.Staff
        "student" -> Role.Student
        else -> if (kind == AccountKind.BauStaff) Role.Staff else Role.Student
    }
    return User(
        id = id,
        displayName = name?.takeIf { it.isNotBlank() } ?: address.substringBefore('@').ifEmpty { "BAU member" },
        email = address,
        kind = kind,
        role = appRole,
        bio = bio.orEmpty(),
        skills = skills,
        classCodes = classes,
        avatarUrl = (avatar as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() },
    )
}
