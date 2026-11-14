package com.ezfiles.reader.presentation.pdftools

/** Shorter than this gets a note, not a refusal: the strength of a password is the user's call. */
private const val SHORT_PASSWORD = 6

/**
 * What's typed into the new-password form. Lives only in a ViewModel, never in
 * saved state, and isn't a data class so that it can't print itself.
 */
internal class PasswordForm(val password: String = "", val confirmation: String = "") {
    val mismatch: Boolean get() = confirmation.isNotEmpty() && confirmation != password

    val isShort: Boolean get() = password.isNotEmpty() && password.length < SHORT_PASSWORD

    val canSubmit: Boolean get() = password.isNotEmpty() && password == confirmation

    override fun toString() = "PasswordForm(****)"
}
