package me.ayuilos.miffan.ui.im

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.DiscoverCircle
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.User
import me.rerere.hugeicons.stroke.UserGroup
import me.ayuilos.miffan.R
import org.koin.androidx.compose.koinViewModel

enum class ImTab(@StringRes val label: Int, val icon: ImageVector) {
    CHATS(R.string.im_tab_chats, HugeIcons.Message01),
    PARTNERS(R.string.im_tab_partners, HugeIcons.UserGroup),
    DISCOVER(R.string.im_tab_discover, HugeIcons.DiscoverCircle),
    ME(R.string.im_tab_me, HugeIcons.User),
}

@Composable
fun ImHomePage(vm: ImHomeVM = koinViewModel()) {
    var tab by rememberSaveable { mutableStateOf(ImTab.CHATS) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                ImTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(stringResource(item.label)) },
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        when (tab) {
            ImTab.CHATS -> ImChatsTab(vm, innerPadding, onFindPartner = { tab = ImTab.PARTNERS })
            ImTab.PARTNERS -> ImPartnersTab(vm, innerPadding)
            ImTab.DISCOVER -> ImDiscoverTab(innerPadding)
            ImTab.ME -> ImMeTab(vm, innerPadding)
        }
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
