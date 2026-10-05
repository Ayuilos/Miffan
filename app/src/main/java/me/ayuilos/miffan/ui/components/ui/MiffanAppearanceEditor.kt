package me.ayuilos.miffan.ui.components.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.data.model.MiffanAppearance
import me.ayuilos.miffan.data.model.MiffanColorSource
import me.ayuilos.miffan.data.model.MiffanKind
import me.ayuilos.miffan.data.model.MiffanPalette
import androidx.compose.ui.res.stringResource
import me.ayuilos.miffan.R

@Composable
fun MiffanAppearanceEditor(
    appearance: MiffanAppearance,
    onAppearanceChange: (MiffanAppearance) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.miffan_character_kind_title),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = MiffanKind.entries,
                    key = { it.name },
                ) { kind ->
                    val selected = appearance.kind == kind
                    Surface(
                        onClick = { onAppearanceChange(appearance.copy(kind = kind)) },
                        shape = MaterialTheme.shapes.large,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                        border = BorderStroke(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .width(108.dp)
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            MiffanMascot(
                                state = MiffanMascotState.Idle,
                                appearance = appearance.copy(kind = kind),
                                modifier = Modifier.size(56.dp),
                            )
                            Text(
                                text = kind.displayName,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                text = kind.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                minLines = 2,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.miffan_character_palette_title),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.miffan_character_follow_app_theme),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.miffan_character_follow_app_theme_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = appearance.colorSource == MiffanColorSource.APP_THEME,
                        onCheckedChange = { followTheme ->
                            onAppearanceChange(
                                appearance.copy(
                                    colorSource = if (followTheme) {
                                        MiffanColorSource.APP_THEME
                                    } else {
                                        MiffanColorSource.PALETTE
                                    },
                                ),
                            )
                        },
                    )
                }
            }
            if (appearance.colorSource == MiffanColorSource.PALETTE) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = MiffanPalette.entries,
                        key = { it.name },
                    ) { palette ->
                        val selected = appearance.palette == palette
                        Surface(
                            onClick = { onAppearanceChange(appearance.copy(palette = palette)) },
                            shape = MaterialTheme.shapes.large,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                            border = BorderStroke(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                            ),
                        ) {
                            Column(
                                modifier = Modifier
                                    .width(76.dp)
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                MiffanMascot(
                                    state = MiffanMascotState.Idle,
                                    appearance = appearance.copy(palette = palette),
                                    modifier = Modifier.size(48.dp),
                                )
                                Text(
                                    text = palette.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

val MiffanKind.displayName: String
    @Composable get() = stringResource(
        when (this) {
            MiffanKind.RICE -> R.string.miffan_character_kind_rice
            MiffanKind.SPROUT -> R.string.miffan_character_kind_sprout
            MiffanKind.DUMPLING -> R.string.miffan_character_kind_dumpling
            MiffanKind.STARGAZER -> R.string.miffan_character_kind_stargazer
        }
    )

val MiffanKind.description: String
    @Composable get() = stringResource(
        when (this) {
            MiffanKind.RICE -> R.string.miffan_character_kind_rice_desc
            MiffanKind.SPROUT -> R.string.miffan_character_kind_sprout_desc
            MiffanKind.DUMPLING -> R.string.miffan_character_kind_dumpling_desc
            MiffanKind.STARGAZER -> R.string.miffan_character_kind_stargazer_desc
        }
    )

val MiffanPalette.displayName: String
    get() = when (this) {
        MiffanPalette.CLASSIC -> "Classic"
        MiffanPalette.MATCHA -> "Matcha"
        MiffanPalette.SAKURA -> "Sakura"
        MiffanPalette.MOONLIGHT -> "Moonlight"
        MiffanPalette.SEA_SALT -> "Sea Salt"
        MiffanPalette.INK_JADE -> "Ink Jade"
    }
