package me.ayuilos.miffan.ui.im

import androidx.annotation.StringRes
import me.ayuilos.miffan.R
import java.util.Locale

/**
 * What went wrong with a request to an AI service, in the terms easy mode tells the owner about.
 * Raw provider messages stay in the log; the owner sees a sentence and, where it helps, where to fix it.
 */
enum class ImProblem(@StringRes val text: Int?) {
    Connection(R.string.im_thread_error_connection),
    Balance(R.string.im_thread_error_balance),
    Busy(R.string.im_thread_error_busy),
    Network(R.string.im_thread_error_network),
    ContentRule(R.string.im_error_content_rule),
    /** Nothing more specific is known; the caller words it for its own task. */
    Other(null);

    /** Only the service's settings can fix these. */
    val opensService: Boolean get() = this == Connection || this == Balance

    @StringRes
    fun textOr(@StringRes other: Int): Int = text ?: other
}

fun imProblemOf(error: Throwable): ImProblem =
    imProblemOf(error.message, network = error is java.io.IOException)

fun imProblemOf(detail: String?, network: Boolean = false): ImProblem {
    val text = detail.orEmpty().lowercase(Locale.ROOT)
    return when {
        listOf("401", "403", "api key", "unauthorized", "authentication").any { it in text } -> ImProblem.Connection
        // Checked before "busy": an empty balance also answers 429 ("insufficient_quota").
        listOf("402", "insufficient", "balance", "余额", "欠费").any { it in text } -> ImProblem.Balance
        listOf("429", "rate limit", "quota").any { it in text } -> ImProblem.Busy
        listOf("safety", "moderation", "content policy", "content_policy", "policy violation").any { it in text } ->
            ImProblem.ContentRule
        network -> ImProblem.Network
        else -> ImProblem.Other
    }
}
