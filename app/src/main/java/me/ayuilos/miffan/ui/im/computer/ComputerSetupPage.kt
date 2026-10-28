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
import me.ayuilos.miffan.data.ai.computer.CuaUpdateState
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.ArrowLeft01
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/** Screens past the point of no return (a run under way, or finished) have no back arrow. */
private val ComputerSetupStep.canGoBack: Boolean get() = this !in setOf(ComputerSetupStep.DONE, ComputerSetupStep.SPACE_PREPARE,
    ComputerSetupStep.SPACE_NETWORK, ComputerSetupStep.SPACE_DONE)

/** Which of the four dots a screen lights: find, sign in, get ready, share. */
private fun ComputerSetupStep.dot(): Int? = when (this) {
    ComputerSetupStep.FIND, ComputerSetupStep.MANUAL -> 0
    ComputerSetupStep.SIGN_IN, ComputerSetupStep.PASSWORD, ComputerSetupStep.KEY, ComputerSetupStep.WINDOWS, ComputerSetupStep.VERIFY -> 1
    ComputerSetupStep.READY -> 2
    ComputerSetupStep.SHARE, ComputerSetupStep.HOW -> 3
    ComputerSetupStep.SPACE_INSTALL, ComputerSetupStep.SPACE_PREPARE, ComputerSetupStep.SPACE_NETWORK,
    ComputerSetupStep.SPACE_DONE, ComputerSetupStep.DONE -> null
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
    val self = Screen.ComputerSetup(forPartner?.toString(), args.hostId, args.edit, args.screen, args.status)
    val statusPage = args.status && state.step == ComputerSetupStep.READY
    val update by vm.cuaUpdate.collectAsStateWithLifecycle()
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
                    statusPage -> Text(stringResource(R.string.im_computer_status_title))
                    else -> state.step.dot()?.let { StepDots(it) }
                }
            },
            actions = {
                if (statusPage) IconButton(onClick = vm::reprobe,
                    enabled = !state.busy && update !is CuaUpdateState.Running && update !is CuaUpdateState.Waiting) {
                    Icon(HugeIcons.Refresh01, stringResource(R.string.im_computer_recheck))
                }
            },
            navigationIcon = {
                if (state.step.canGoBack) IconButton(onClick = ::back, enabled = !state.busy) {
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
                ComputerSetupStep.PASSWORD -> ComputerPasswordStep(vm, state)
                ComputerSetupStep.KEY -> ComputerKeyStep(vm, state)
                ComputerSetupStep.WINDOWS -> ComputerWindowsStep(vm, state)
                ComputerSetupStep.VERIFY -> ComputerVerifyStep(vm, state)
                ComputerSetupStep.READY -> ComputerReadyStep(vm, state)
                ComputerSetupStep.SHARE -> ComputerShareStep(vm, state, forPartner)
                ComputerSetupStep.HOW -> ComputerHowStep(vm, state)
                ComputerSetupStep.SPACE_INSTALL -> ComputerSpaceInstallStep(vm, state)
                ComputerSetupStep.SPACE_PREPARE -> ComputerSpacePrepareStep(vm, state) {
                    // The run goes on without this page; the partner says so in its chat when it is done.
                    val id = state.sharePartners.firstOrNull()?.toString()
                    if (id == null) nav.popBackStack()
                    else {
                        nav.removeAll(Screen.Home) { it is Screen.ComputerSetup || it == Screen.PartnerScreen(id) || it is Screen.Thread && it.assistantId == id }
                        nav.navigate(Screen.Thread(id))
                    }
                }
                ComputerSetupStep.SPACE_NETWORK -> ComputerSpaceNetworkStep(vm, state)
                ComputerSetupStep.SPACE_DONE -> ComputerSpaceDoneStep(vm, state,
                    onChat = { partner ->
                        val id = partner.id.toString()
                        nav.removeAll(Screen.Home) { it is Screen.ComputerSetup || it == Screen.PartnerScreen(id) || it is Screen.Thread && it.assistantId == id }
                        nav.navigate(Screen.Thread(id))
                    },
                    onChangeMode = { partner ->
                        // Setup can sit on the stack twice (picking a saved computer opens its own entry).
                        nav.removeAll(Screen.Home) { it is Screen.ComputerSetup }
                        nav.navigate(Screen.PartnerComputer(partner.id.toString()))
                    })
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
