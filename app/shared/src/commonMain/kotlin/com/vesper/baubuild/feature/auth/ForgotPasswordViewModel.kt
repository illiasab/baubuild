package com.vesper.baubuild.feature.auth

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Microsoft self-service password reset. BAU passwords belong to the BAU Microsoft (Entra ID) account. */
internal const val MICROSOFT_PASSWORD_RESET_URL = "https://passwordreset.microsoftonline.com/"

// TODO: replace with BAU IT's real support address before release.
internal const val BAU_IT_SUPPORT_EMAIL = "it-support@bau.edu"

internal val BAU_IT_SUPPORT_MAILTO: String =
    "mailto:$BAU_IT_SUPPORT_EMAIL?subject=BAU%20Microsoft%20account%20password"

data class ForgotPasswordUiState(
    /** Set when the platform couldn't open a link (no browser / mail app); shown inline with the address to copy. */
    val unopenedLink: String? = null,
)

/**
 * The app never handles passwords, so this screen only points the user at Microsoft's reset page or BAU IT.
 * The only state is whether opening one of those links failed.
 */
class ForgotPasswordViewModel : ViewModel() {

    private val _state = MutableStateFlow(ForgotPasswordUiState())
    val state: StateFlow<ForgotPasswordUiState> = _state.asStateFlow()

    /** Runs [open] (e.g. `LocalUriHandler.openUri`) and records a failure instead of crashing. */
    fun openLink(uri: String, open: (String) -> Unit) {
        val opened = try {
            open(uri)
            true
        } catch (e: Exception) {
            // LocalUriHandler throws IllegalStateException / ActivityNotFoundException when nothing can open it.
            false
        }
        _state.update { it.copy(unopenedLink = if (opened) null else uri) }
    }

    fun dismissLinkError() = _state.update { it.copy(unopenedLink = null) }
}
