package me.ayuilos.miffan.ui.im

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.model.Avatar
import me.ayuilos.miffan.data.model.MiffanAppearance
import me.ayuilos.miffan.data.model.MiffanKind
import me.ayuilos.miffan.data.model.MiffanMotionProfile
import me.ayuilos.miffan.data.model.MiffanPalette

// Making a partner by hand, shared by Partners › Add and the first-run conversation so both make the same partner.

internal val PARTNER_TRAITS = listOf(
    R.string.im_trait_gentle, R.string.im_trait_direct, R.string.im_trait_funny, R.string.im_trait_curious,
    R.string.im_trait_rigorous, R.string.im_trait_brief, R.string.im_trait_encouraging, R.string.im_trait_snarky,
)

/** The system prompt of a partner made from a name, some of [PARTNER_TRAITS] and the user's own words. */
@Composable
internal fun partnerPrompt(name: String, traits: List<Int>, extra: String): String {
    val traitNames = traits.map { stringResource(it) }
    val separator = stringResource(R.string.im_list_separator)
    return listOf(
        if (traitNames.isEmpty()) stringResource(R.string.im_add_partner_prompt_plain, name)
        else stringResource(R.string.im_add_partner_prompt, name, traitNames.joinToString(separator)),
        extra.trim(),
    ).filter { it.isNotEmpty() }.joinToString("\n")
}

/** A Miffan unlike [current] in kind and colours, with any motion, for a partner nobody wants to dress up by hand. */
internal fun randomMiffan(current: Avatar): Avatar.Miffan {
    val now = (current as? Avatar.Miffan)?.appearance
    return Avatar.Miffan(
        appearance = MiffanAppearance(
            kind = MiffanKind.entries.filter { it != now?.kind }.random(),
            palette = MiffanPalette.entries.filter { it != now?.palette }.random(),
        ),
        motionProfile = MiffanMotionProfile.entries.random(),
    )
}
