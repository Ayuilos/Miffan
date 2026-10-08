package me.ayuilos.miffan.ui.im

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.material3.Material3
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import me.ayuilos.miffan.R
import me.ayuilos.miffan.ui.components.ui.GlassShadow
import me.ayuilos.miffan.ui.hooks.rememberIsPlayStoreVersion
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.DiscoverCircle
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.User
import me.rerere.hugeicons.stroke.UserGroup
import org.koin.androidx.compose.koinViewModel

enum class ImTab(@StringRes val label: Int, val icon: ImageVector) {
    CHATS(R.string.im_tab_chats, HugeIcons.Message01),
    PARTNERS(R.string.im_tab_partners, HugeIcons.UserGroup),
    DISCOVER(R.string.im_tab_discover, HugeIcons.DiscoverCircle),
    ME(R.string.im_tab_me, HugeIcons.User),
}

private val TabItemWidth = 72.dp
private val TabItemHeight = 52.dp
private val TabBarInnerPadding = 5.dp
private val TabBarBottomMargin = 12.dp

@Composable
fun ImHomePage(vm: ImHomeVM = koinViewModel()) {
    var tab by rememberSaveable { mutableStateOf(ImTab.CHATS) }
    val hazeState = rememberHazeState()
    val layoutDirection = LocalLayoutDirection.current
    val isPlayStore = rememberIsPlayStoreVersion()
    val availableUpdate = if (isPlayStore) null else vm.availableUpdate.collectAsStateWithLifecycle().value
    val updateNotice = availableUpdate?.let { stringResource(R.string.update_card_new_version_found, it.version) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        // Tab content scrolls underneath the floating bar, so reserve its height at the end of lists.
        val contentPadding = PaddingValues(
            start = innerPadding.calculateStartPadding(layoutDirection),
            top = innerPadding.calculateTopPadding(),
            end = innerPadding.calculateEndPadding(layoutDirection),
            bottom = innerPadding.calculateBottomPadding() + TabBarBottomMargin +
                TabItemHeight + TabBarInnerPadding * 2 + 8.dp,
        )
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .hazeSource(hazeState)
            ) {
                when (tab) {
                    ImTab.CHATS -> ImChatsTab(vm, contentPadding, onFindPartner = { tab = ImTab.PARTNERS })
                    ImTab.PARTNERS -> ImPartnersTab(vm, contentPadding)
                    ImTab.DISCOVER -> ImDiscoverTab(contentPadding)
                    ImTab.ME -> ImMeTab(vm, contentPadding, availableUpdate)
                }
            }
            ImFloatingTabBar(
                selected = tab,
                onSelect = { tab = it },
                hazeState = hazeState,
                notices = mapOf(ImTab.ME to updateNotice),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = innerPadding.calculateBottomPadding() + TabBarBottomMargin),
            )
        }
    }
}

/** Compact frosted-glass capsule that floats above the tab content. */
@Composable
private fun ImFloatingTabBar(
    selected: ImTab,
    onSelect: (ImTab) -> Unit,
    hazeState: HazeState,
    notices: Map<ImTab, String?>,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    // Same recipe as the chat top bar capsule: theme hue with a tonal step from the page.
    val glassColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val glassStyle = HazeBlurStyle.Material3 {
        blurRadius(28.dp)
        noiseFactor(0.05f)
        colorEffects(listOf(HazeColorEffect.tint(glassColor.copy(alpha = 0.55f))))
        fallbackColorEffect(HazeColorEffect.tint(glassColor.copy(alpha = 0.92f)))
    }
    Box(modifier) {
        GlassShadow(shape, Modifier.matchParentSize())
        ImFloatingTabBarGlass(shape, glassStyle, hazeState, selected, onSelect, notices)
    }
}

@Composable
private fun ImFloatingTabBarGlass(
    shape: Shape,
    glassStyle: HazeBlurStyle,
    hazeState: HazeState,
    selected: ImTab,
    onSelect: (ImTab) -> Unit,
    notices: Map<ImTab, String?>,
) {
    val indicatorOffset by animateDpAsState(
        targetValue = TabItemWidth * selected.ordinal,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
        label = "imTabIndicator",
    )
    Surface(
        modifier = Modifier
            .clip(shape)
            .hazeBlur(input = HazeInput.Sources(hazeState), style = glassStyle),
        shape = shape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
    ) {
        Box(Modifier.padding(TabBarInnerPadding)) {
            Box(
                Modifier
                    .offset(x = indicatorOffset)
                    .size(TabItemWidth, TabItemHeight)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f))
            )
            Row(Modifier.selectableGroup()) {
                ImTab.entries.forEach { item ->
                    ImFloatingTabItem(
                        tab = item,
                        selected = item == selected,
                        notice = notices[item],
                        onClick = { onSelect(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ImFloatingTabItem(tab: ImTab, selected: Boolean, notice: String?, onClick: () -> Unit) {
    val color by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "imTabItemColor",
    )
    Column(
        modifier = Modifier
            .size(TabItemWidth, TabItemHeight)
            .clip(RoundedCornerShape(50))
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
            )
            .semantics { if (notice != null) stateDescription = notice },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Icon(tab.icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            // A dot for something waiting in this tab, such as a new version.
            if (notice != null) Badge(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 3.dp, y = (-2).dp)
                    .size(8.dp),
                containerColor = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = stringResource(tab.label),
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

/** Large page title shared by the four tabs. */
@Composable
internal fun ImTabTitle(@StringRes title: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
    )
}
