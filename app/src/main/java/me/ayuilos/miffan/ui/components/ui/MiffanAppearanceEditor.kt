package me.ayuilos.miffan.ui.components.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.data.model.MiffanAppearance
import me.ayuilos.miffan.data.model.MiffanKind
import me.ayuilos.miffan.data.model.MiffanPalette

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
                text = "Miffan 角色",
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
                text = "Miffan 配色",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
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

val MiffanKind.displayName: String
    get() = when (this) {
        MiffanKind.RICE -> "米团"
        MiffanKind.SPROUT -> "芽团"
        MiffanKind.DUMPLING -> "丸团"
        MiffanKind.STARGAZER -> "星团"
    }

val MiffanKind.description: String
    get() = when (this) {
        MiffanKind.RICE -> "软糯米丘\n光滑陶碗"
        MiffanKind.SPROUT -> "一株新芽\n刻纹陶碗"
        MiffanKind.DUMPLING -> "三颗糯丸\n随身小勺"
        MiffanKind.STARGAZER -> "收藏星米\n星光挂饰"
    }

val MiffanPalette.displayName: String
    get() = when (this) {
        MiffanPalette.CLASSIC -> "Classic"
        MiffanPalette.MATCHA -> "Matcha"
        MiffanPalette.SAKURA -> "Sakura"
        MiffanPalette.MOONLIGHT -> "Moonlight"
        MiffanPalette.SEA_SALT -> "Sea Salt"
        MiffanPalette.INK_JADE -> "Ink Jade"
    }
