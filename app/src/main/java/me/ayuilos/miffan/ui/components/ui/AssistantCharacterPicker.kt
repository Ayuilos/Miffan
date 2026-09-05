package me.ayuilos.miffan.ui.components.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.data.model.Avatar
import me.ayuilos.miffan.data.model.characterMotionProfileOrDefault
import me.ayuilos.miffan.data.model.isMiffanAvatar

@Composable
fun AssistantCharacterPicker(
    avatar: Avatar,
    onAvatarChange: (Avatar) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("角色头像", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val characters = listOf(
                Triple("Miffan 饭碗", avatar.isMiffanAvatar(),
                    avatar.takeIf { it.isMiffanAvatar() } ?: Avatar.Miffan(motionProfile = avatar.characterMotionProfileOrDefault())),
                Triple("蓝色大肥鱼", avatar is Avatar.WhaleGirl,
                    avatar as? Avatar.WhaleGirl ?: Avatar.WhaleGirl()),
            )
            characters.forEach { (name, isSelected, character) ->
                Surface(
                    onClick = { if (!isSelected) onAvatarChange(character) },
                    modifier = Modifier.weight(1f).semantics { selected = isSelected },
                    shape = MaterialTheme.shapes.large,
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(
                        if (isSelected) 2.dp else 1.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ),
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AssistantCharacterMascot(
                            avatar = character,
                            state = MiffanMascotState.Idle,
                            presentation = MiffanPresentation.Avatar,
                            modifier = Modifier.size(64.dp),
                        )
                        Text(name, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
