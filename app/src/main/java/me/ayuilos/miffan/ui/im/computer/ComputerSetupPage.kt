package me.ayuilos.miffan.ui.im.computer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.LocalSettings
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/** Which of the four dots a screen lights: find, sign in, get ready, share. */
private fun ComputerSetupStep.dot(): Int? = when (this) {
    ComputerSetupStep.FIND, ComputerSetupStep.MANUAL -> 0
    ComputerSetupStep.SIGN_IN, ComputerSetupStep.KEY, ComputerSetupStep.WINDOWS, ComputerSetupStep.VERIFY -> 1
    ComputerSetupStep.READY -> 2
    ComputerSetupStep.SHARE -> 3
    ComputerSetupStep.DONE -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComputerSetupPage(args: ComputerSetupArgs) {
    val vm: ComputerSetupVM = koinViewModel(key = "setup:$args", parameters = { parametersOf(args) })
    // Null when opened from "my computers": the setup ends by picking any partners, or none.
    val forPartner = args.assistantId
    val state by vm.state.collectAsStateWithLifecycle()
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val host = hosts.find { it.id == state.hostId }
    val nav = LocalNavController.current
    val assistants = LocalSettings.current.assistants
    // This page's own entry on the back stack, to replace it on the way out.
    val self = Screen.ComputerSetup(forPartner?.toString(), args.hostId, args.edit, args.screen)
    fun back() {
        if (state.busy) return
        if (!vm.back()) nav.popBackStack()
    }
    BackHandler { back() }
    Scaffold(topBar = {
        CenterAlignedTopAppBar(
            title = {
                when {
                    state.editing && state.step == ComputerSetupStep.MANUAL -> Text(host?.name.orEmpty())
                    else -> state.step.dot()?.let { StepDots(it) }
                }
            },
            navigationIcon = {
                if (state.step != ComputerSetupStep.DONE) IconButton(onClick = ::back, enabled = !state.busy) {
                    Icon(HugeIcons.ArrowLeft01, stringResource(R.string.back))
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            when (state.step) {
                ComputerSetupStep.FIND -> ComputerFindStep(vm, state, hosts) { hostId ->
                    nav.navigate(Screen.ComputerSetup(forPartner?.toString(), hostId, edit = true))
                }
                ComputerSetupStep.MANUAL -> if (state.editing) ComputerSettingsStep(vm, state, host) else ComputerManualStep(vm, state)
                ComputerSetupStep.SIGN_IN -> ComputerSignInStep(vm, state)
                ComputerSetupStep.KEY -> ComputerKeyStep(vm, state)
                ComputerSetupStep.WINDOWS -> ComputerWindowsStep(vm, state)
                ComputerSetupStep.VERIFY -> ComputerVerifyStep(vm, state)
                ComputerSetupStep.READY -> ComputerReadyStep(vm, state)
                ComputerSetupStep.SHARE -> ComputerShareStep(vm, state, forPartner)
                ComputerSetupStep.DONE -> {
                    val bound by vm.boundPartnerIds.collectAsStateWithLifecycle()
                    val partner = assistants.find { it.id == forPartner && it.id in bound } ?: assistants.firstOrNull { it.id in bound }
                    val hostId = state.hostId
                    ComputerDoneStep(vm, state, partner,
                        onChat = { text ->
                            val id = partner?.id?.toString() ?: return@ComputerDoneStep
                            nav.removeAll(Screen.Home) { it is Screen.ComputerSetup || it == Screen.PartnerScreen(id) || it is Screen.Thread && it.assistantId == id }
                            nav.navigate(Screen.Thread(id, text = text))
                        },
                        onScreen = if (state.usesScreen && hostId != null) ({
                            if (partner != null) nav.replace(self, Screen.PartnerScreen(partner.id.toString()), Screen.Home)
                            else nav.navigate(Screen.ComputerScreen(hostId))
                        }) else null,
                        onFinish = {
                            // A new computer continues to its own page; checking or editing returns where it started.
                            if (args.hostId == null && hostId != null) nav.replace(self, Screen.ComputerDetail(hostId), Screen.Home)
                            else nav.popBackStack()
                        },
                    )
                }
            }
        }
    }
}
