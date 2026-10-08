package me.ayuilos.miffan.ui.components.message

import android.content.Context
import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import me.ayuilos.miffan.R
import me.rerere.ai.ui.ToolDecision
import me.rerere.ai.ui.ToolDecisionVia

/** How a permission request was settled, e.g. "You declined at 13:19". The same wording everywhere. */
@StringRes
internal fun approvalDecisionLabel(decision: ToolDecision, via: ToolDecisionVia?): Int = when (decision) {
    ToolDecision.ALLOWED -> when (via) {
        ToolDecisionVia.ALWAYS_ALLOW -> R.string.audit_always_allowed
        ToolDecisionVia.PARTNER_SCREEN -> R.string.audit_allowed_screen
        else -> R.string.audit_allowed
    }
    ToolDecision.DECLINED -> if (via == ToolDecisionVia.PARTNER_SCREEN) R.string.audit_declined_screen else R.string.audit_declined
    ToolDecision.ANSWERED -> R.string.audit_answered
    ToolDecision.REPLIED_IN_CHAT -> R.string.audit_replied
    ToolDecision.CANCELLED -> R.string.audit_cancelled
    ToolDecision.AUTO_ALLOWED ->
        if (via == ToolDecisionVia.STANDING_ALWAYS_ALLOW) R.string.audit_auto_standing else R.string.audit_auto_setting
}

/** Today's events show the time only; older ones add the date. */
internal fun auditTime(context: Context, at: Long): String = DateUtils.formatDateTime(context, at,
    DateUtils.FORMAT_SHOW_TIME or if (DateUtils.isToday(at)) 0 else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)

@Composable
internal fun approvalDecisionText(decision: ToolDecision, via: ToolDecisionVia?, at: Long): String =
    stringResource(approvalDecisionLabel(decision, via), auditTime(LocalContext.current, at))
