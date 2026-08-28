package me.ayuilos.miffan.ui.pages.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.common.android.Logging
import me.ayuilos.miffan.BuildConfig
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.model.Avatar
import me.ayuilos.miffan.data.model.MiffanAppearance
import me.ayuilos.miffan.data.model.MiffanColorSource
import me.ayuilos.miffan.data.model.MiffanKind
import me.ayuilos.miffan.data.model.MiffanMotionProfile
import me.ayuilos.miffan.data.model.MiffanPalette
import me.ayuilos.miffan.ui.components.ui.UIAvatar
import me.ayuilos.miffan.ui.components.ui.MiffanDayPhase
import me.ayuilos.miffan.ui.components.ui.MiffanDayPhaseDebugOverride
import me.ayuilos.miffan.ui.components.ui.MiffanMascot
import me.ayuilos.miffan.ui.components.ui.MiffanMascotInputState
import me.ayuilos.miffan.ui.components.ui.MiffanMascotState
import me.ayuilos.miffan.ui.components.ui.displayName
import me.ayuilos.miffan.ui.components.ui.rememberMiffanDayPhase
import me.ayuilos.miffan.ui.components.nav.BackButton
import me.ayuilos.miffan.ui.components.richtext.MarkdownBlock
import me.ayuilos.miffan.ui.components.richtext.MathBlock
import me.ayuilos.miffan.ui.components.richtext.Mermaid
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.LocalToaster
import me.ayuilos.miffan.ui.theme.JetbrainsMono
import org.koin.androidx.compose.koinViewModel
import kotlin.random.Random
import kotlin.random.nextInt
import kotlin.uuid.Uuid

@Composable
fun DebugPage(vm: DebugVM = koinViewModel()) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Debug Mode")
                },
                navigationIcon = {
                    BackButton()
                }
            )
        }
    ) { contentPadding ->
        val state = rememberPagerState { 4 }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            SecondaryTabRow(
                selectedTabIndex = state.currentPage,
            ) {
                Tab(
                    selected = state.currentPage == 0,
                    onClick = {
                        scope.launch {
                            state.animateScrollToPage(0)
                        }
                    },
                    text = {
                        Text("Main")
                    }
                )
                Tab(
                    selected = state.currentPage == 1,
                    onClick = {
                        scope.launch {
                            state.animateScrollToPage(1)
                        }
                    },
                    text = {
                        Text("Colors")
                    }
                )
                Tab(
                    selected = state.currentPage == 2,
                    onClick = {
                        scope.launch {
                            state.animateScrollToPage(2)
                        }
                    },
                    text = {
                        Text("Miffan")
                    }
                )
                Tab(
                    selected = state.currentPage == 3,
                    onClick = {
                        scope.launch {
                            state.animateScrollToPage(3)
                        }
                    },
                    text = {
                        Text("Logging")
                    }
                )
            }
            HorizontalPager(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                when (page) {
                    0 -> MainPage(vm)
                    1 -> ColorsPage()
                    2 -> MiffanLabPage()
                    3 -> Box {}
                }
            }
        }
    }
}

private enum class MiffanLabMode(
    val label: String,
    val mascotState: MiffanMascotState = MiffanMascotState.Idle,
    val inputState: MiffanMascotInputState = MiffanMascotInputState.Inactive,
    val signaturePreview: Boolean = false,
) {
    Idle("Idle"),
    Signature("Signature", signaturePreview = true),
    Thinking("Thinking", mascotState = MiffanMascotState.Thinking),
    Happy("Happy", mascotState = MiffanMascotState.Happy),
    Error("Error", mascotState = MiffanMascotState.Error),
    UpdateAvailable("Update available", mascotState = MiffanMascotState.UpdateAvailable),
    Focused("Focused", inputState = MiffanMascotInputState.Focused),
    Typing("Typing", inputState = MiffanMascotInputState.Typing),
    Transitions("Transitions"),
}

private data class MiffanLabFrame(
    val label: String,
    val durationMillis: Long,
    val state: MiffanMascotState = MiffanMascotState.Idle,
    val input: MiffanMascotInputState = MiffanMascotInputState.Inactive,
    val attention: Offset? = null,
    val submitted: Boolean = false,
)

private val miffanTransitionFrames = listOf(
    MiffanLabFrame("Idle", 1_000),
    MiffanLabFrame("Focus input", 600, input = MiffanMascotInputState.Focused),
    MiffanLabFrame("Typing", 900, input = MiffanMascotInputState.Typing),
    MiffanLabFrame("Repeated taps: left", 120, attention = Offset(-0.9f, -0.4f)),
    MiffanLabFrame("Repeated taps: right", 120, attention = Offset(0.9f, 0.3f)),
    MiffanLabFrame("Repeated taps: left", 600, attention = Offset(-0.7f, 0.2f)),
    MiffanLabFrame("Submit", 220, submitted = true),
    MiffanLabFrame("Thinking", 2_600, state = MiffanMascotState.Thinking),
    MiffanLabFrame("Interrupted by error", 1_000, state = MiffanMascotState.Error),
    MiffanLabFrame("Retry", 900, state = MiffanMascotState.Thinking),
    MiffanLabFrame("Completed", 1_000, state = MiffanMascotState.Happy),
)

@Composable
private fun MiffanLabPage() {
    var mode by remember { mutableStateOf(MiffanLabMode.Signature) }
    var phase by remember { mutableStateOf(MiffanDayPhase.Noon) }
    var kind by remember { mutableStateOf(MiffanKind.RICE) }
    var colorSource by remember { mutableStateOf(MiffanColorSource.PALETTE) }
    var motionProfile by remember { mutableStateOf(MiffanMotionProfile.CURIOUS) }
    var reducedMotion by remember { mutableStateOf(false) }
    var submitId by remember { mutableIntStateOf(0) }
    var frameIndex by remember { mutableIntStateOf(0) }
    var attentionId by remember { mutableIntStateOf(0) }
    var attentionTarget by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(mode) {
        if (mode != MiffanLabMode.Transitions) return@LaunchedEffect
        while (true) {
            miffanTransitionFrames.forEachIndexed { index, frame ->
                frameIndex = index
                frame.attention?.let {
                    attentionTarget = it
                    attentionId++
                }
                if (frame.submitted) submitId++
                delay(frame.durationMillis)
            }
        }
    }
    val frame = miffanTransitionFrames[frameIndex]
    val previewState = if (mode == MiffanLabMode.Transitions) frame.state else mode.mascotState
    val previewInput = if (mode == MiffanLabMode.Transitions) frame.input else mode.inputState
    val sizes = listOf(28.dp, 32.dp, 40.dp, 80.dp, 168.dp)
    val previewAppearances = if (colorSource == MiffanColorSource.APP_THEME) {
        listOf(
            "App theme" to MiffanAppearance(
                kind = kind,
                colorSource = MiffanColorSource.APP_THEME,
            ),
        )
    } else {
        MiffanPalette.entries.map { palette ->
            palette.displayName to MiffanAppearance(
                palette = palette,
                kind = kind,
            )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Semantic state", style = MaterialTheme.typography.titleSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(MiffanLabMode.entries, key = { it.name }) { option ->
                        FilterChip(
                            selected = mode == option,
                            onClick = { mode = option },
                            label = { Text(option.label) },
                        )
                    }
                }
                if (mode == MiffanLabMode.Transitions) {
                    Text(
                        "Loop: ${frame.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Reduced motion preview",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = reducedMotion,
                        onCheckedChange = { reducedMotion = it },
                    )
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Color source", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    MiffanColorSource.entries.forEachIndexed { index, source ->
                        SegmentedButton(
                            selected = colorSource == source,
                            onClick = { colorSource = source },
                            shape = SegmentedButtonDefaults.itemShape(
                                index,
                                MiffanColorSource.entries.size,
                            ),
                        ) {
                            Text(
                                when (source) {
                                    MiffanColorSource.PALETTE -> "Miffan palette"
                                    MiffanColorSource.APP_THEME -> "App theme"
                                },
                            )
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Character kind", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    MiffanKind.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = kind == option,
                            onClick = { kind = option },
                            shape = SegmentedButtonDefaults.itemShape(
                                index,
                                MiffanKind.entries.size,
                            ),
                        ) {
                            Text(option.displayName)
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Motion profile", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    MiffanMotionProfile.entries.forEachIndexed { index, profile ->
                        SegmentedButton(
                            selected = motionProfile == profile,
                            onClick = { motionProfile = profile },
                            shape = SegmentedButtonDefaults.itemShape(
                                index,
                                MiffanMotionProfile.entries.size,
                            ),
                        ) {
                            Text(profile.displayName)
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Time of day", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    MiffanDayPhase.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = phase == option,
                            onClick = { phase = option },
                            shape = SegmentedButtonDefaults.itemShape(
                                index,
                                MiffanDayPhase.entries.size,
                            ),
                        ) {
                            Text(option.name)
                        }
                    }
                }
                Button(onClick = { submitId++ }) {
                    Text("Preview submit")
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Character behavior comparison", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (mode.signaturePreview) {
                        "Isolated at full strength: hop, listen, step, and hover."
                    } else {
                        "Same semantic state and motion profile; only the inhabitant changes."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(MiffanKind.entries, key = { it.name }) { option ->
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            MiffanMascot(
                                state = previewState,
                                appearance = MiffanAppearance(
                                    kind = option,
                                    colorSource = colorSource,
                                ),
                                motionProfile = motionProfile,
                                reducedMotion = reducedMotion,
                                inputState = previewInput,
                                interactive = true,
                                attentionId = attentionId,
                                attentionTarget = attentionTarget,
                                submitId = submitId,
                                dayPhase = phase,
                                previewIdleGestures = mode == MiffanLabMode.Idle,
                                previewSignatureBehavior = mode.signaturePreview,
                                modifier = Modifier.size(96.dp),
                            )
                            Text(option.displayName, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        items(previewAppearances, key = { it.first }) { (label, appearance) ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sizes, key = { it.value }) { previewSize ->
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Box(
                                modifier = Modifier.size(176.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                MiffanMascot(
                                    state = previewState,
                                    appearance = appearance,
                                    motionProfile = motionProfile,
                                    reducedMotion = reducedMotion,
                                    inputState = previewInput,
                                    interactive = true,
                                    attentionId = attentionId,
                                    attentionTarget = attentionTarget,
                                    submitId = submitId,
                                    dayPhase = phase,
                                    previewIdleGestures = mode == MiffanLabMode.Idle,
                                    previewSignatureBehavior = mode.signaturePreview,
                                    modifier = Modifier.size(previewSize),
                                )
                            }
                            Text(
                                text = "${previewSize.value.toInt()} dp",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainPage(vm: DebugVM) {
    val settings = LocalSettings.current
    val navController = LocalNavController.current
    val conversationCount by vm.conversationCount.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .padding(8.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (BuildConfig.DEBUG) {
            val debugUpdateOverrideEnabled by
                vm.debugUpdateOverrideEnabled.collectAsStateWithLifecycle()
            Text("Update reminder preview", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Simulate an available update")
                    Text(
                        "Affects the drawer badge, current Miffan avatar, and settings banner.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = debugUpdateOverrideEnabled,
                    onCheckedChange = vm::setDebugUpdateOverrideEnabled,
                )
            }
            Button(
                enabled = debugUpdateOverrideEnabled,
                onClick = { navController.navigate(Screen.Setting) },
            ) {
                Text("Open Settings preview")
            }
            HorizontalDivider()
        }

        val phaseOverride by MiffanDayPhaseDebugOverride.phase.collectAsState()
        val effectivePhase = rememberMiffanDayPhase()
        val phaseOptions = listOf<MiffanDayPhase?>(
            null,
            MiffanDayPhase.Morning,
            MiffanDayPhase.Noon,
            MiffanDayPhase.Night,
        )
        Text("Mascot time: ${effectivePhase.name}", style = MaterialTheme.typography.labelMedium)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            phaseOptions.forEachIndexed { index, phase ->
                SegmentedButton(
                    selected = phaseOverride == phase,
                    onClick = { MiffanDayPhaseDebugOverride.set(phase) },
                    shape = SegmentedButtonDefaults.itemShape(index, phaseOptions.size),
                ) {
                    Text(phase?.name ?: "Auto")
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            contentAlignment = Alignment.Center,
        ) {
            MiffanMascot(
                state = MiffanMascotState.Idle,
                dayPhase = effectivePhase,
                interactive = true,
                previewIdleGestures = true,
                modifier = Modifier.size(160.dp),
            )
        }
        HorizontalDivider()

        var avatar: Avatar by remember { mutableStateOf(Avatar.Emoji("😎")) }
        UIAvatar(
            value = avatar,
            onUpdate = {
                println("Avatar updated: $it")
                avatar = it
            },
            name = "A"
        )
        Mermaid(
            code = """
                mindmap
                  root((mindmap))
                    Origins
                      Long history
                      ::icon(fa fa-book)
                      Popularisation
                        British popular psychology author Tony Buzan
                    Research
                      On effectiveness<br/>and features
                      On Automatic creation
                        Uses
                            Creative techniques
                            Strategic planning
                            Argument mapping
                    Tools
                      Pen and paper
                      Mermaid
                """.trimIndent(),
            modifier = Modifier.fillMaxWidth(),
        )

        var counter by remember {
            mutableIntStateOf(0)
        }
        val toaster = LocalToaster.current
        Button(
            onClick = {
                toaster.show("测试 ${counter++}")
                toaster.show("测试 ${counter++}", type = ToastType.Info)
                toaster.show("测试 ${counter++}", type = ToastType.Error)
            }
        ) {
            Text("toast")
        }
        Button(
            onClick = {
                vm.updateSettings(
                    settings.copy(
                        chatModelId = Uuid.random()
                    )
                )
            }
        ) {
            Text("重置Chat模型")
        }

        Button(
            onClick = {
                error("测试崩溃 ${Random.nextInt(0..1000)}")
            }
        ) {
            Text("崩溃")
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Conversation 数量: ${conversationCount?.toString() ?: "..."}",
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { vm.refreshConversationCount() }) {
                Text("刷新")
            }
        }

        Button(
            onClick = {
                vm.createOversizedConversation(30)
                toaster.show("正在创建 30MB 超大对话...")
            }
        ) {
            Text("创建超大对话 (30MB)")
        }

        Button(
            onClick = {
                vm.createConversationWithMessages(1024)
                toaster.show("正在创建 1024 条消息对话...")
            }
        ) {
            Text("创建 1024 个消息的聊天")
        }

        HorizontalDivider()

        Text("Launch Stats", style = MaterialTheme.typography.labelMedium)

        var launchCountInput by remember(settings.launchCount) {
            mutableStateOf(settings.launchCount.toString())
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = launchCountInput,
                onValueChange = { launchCountInput = it },
                label = { Text("launchCount (current: ${settings.launchCount})") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(onClick = {
                launchCountInput.toIntOrNull()?.let {
                    vm.updateSettings(settings.copy(launchCount = it))
                }
            }) {
                Text("Set")
            }
        }

        var dismissedAtInput by remember(settings.sponsorAlertDismissedAt) {
            mutableStateOf(settings.sponsorAlertDismissedAt.toString())
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = dismissedAtInput,
                onValueChange = { dismissedAtInput = it },
                label = { Text("sponsorAlertDismissedAt (current: ${settings.sponsorAlertDismissedAt})") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(onClick = {
                dismissedAtInput.toIntOrNull()?.let {
                    vm.updateSettings(settings.copy(sponsorAlertDismissedAt = it))
                }
            }) {
                Text("Set")
            }
        }

        var markdown by remember { mutableStateOf("") }
        MarkdownBlock(markdown, modifier = Modifier.fillMaxWidth())
        MathBlock(markdown)
        OutlinedTextField(
            value = markdown,
            onValueChange = { markdown = it },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ColorsPage() {
    val colorScheme = MaterialTheme.colorScheme
    val colorTokens = remember(colorScheme) {
        listOf(
            "primary" to colorScheme.primary,
            "onPrimary" to colorScheme.onPrimary,
            "primaryContainer" to colorScheme.primaryContainer,
            "onPrimaryContainer" to colorScheme.onPrimaryContainer,
            "inversePrimary" to colorScheme.inversePrimary,
            "secondary" to colorScheme.secondary,
            "onSecondary" to colorScheme.onSecondary,
            "secondaryContainer" to colorScheme.secondaryContainer,
            "onSecondaryContainer" to colorScheme.onSecondaryContainer,
            "tertiary" to colorScheme.tertiary,
            "onTertiary" to colorScheme.onTertiary,
            "tertiaryContainer" to colorScheme.tertiaryContainer,
            "onTertiaryContainer" to colorScheme.onTertiaryContainer,
            "background" to colorScheme.background,
            "onBackground" to colorScheme.onBackground,
            "surface" to colorScheme.surface,
            "onSurface" to colorScheme.onSurface,
            "surfaceVariant" to colorScheme.surfaceVariant,
            "onSurfaceVariant" to colorScheme.onSurfaceVariant,
            "surfaceTint" to colorScheme.surfaceTint,
            "inverseSurface" to colorScheme.inverseSurface,
            "inverseOnSurface" to colorScheme.inverseOnSurface,
            "surfaceBright" to colorScheme.surfaceBright,
            "surfaceDim" to colorScheme.surfaceDim,
            "surfaceContainer" to colorScheme.surfaceContainer,
            "surfaceContainerHigh" to colorScheme.surfaceContainerHigh,
            "surfaceContainerHighest" to colorScheme.surfaceContainerHighest,
            "surfaceContainerLow" to colorScheme.surfaceContainerLow,
            "surfaceContainerLowest" to colorScheme.surfaceContainerLowest,
            "error" to colorScheme.error,
            "onError" to colorScheme.onError,
            "errorContainer" to colorScheme.errorContainer,
            "onErrorContainer" to colorScheme.onErrorContainer,
            "outline" to colorScheme.outline,
            "outlineVariant" to colorScheme.outlineVariant,
            "scrim" to colorScheme.scrim,
            "primaryFixed" to colorScheme.primaryFixed,
            "primaryFixedDim" to colorScheme.primaryFixedDim,
            "onPrimaryFixed" to colorScheme.onPrimaryFixed,
            "onPrimaryFixedVariant" to colorScheme.onPrimaryFixedVariant,
            "secondaryFixed" to colorScheme.secondaryFixed,
            "secondaryFixedDim" to colorScheme.secondaryFixedDim,
            "onSecondaryFixed" to colorScheme.onSecondaryFixed,
            "onSecondaryFixedVariant" to colorScheme.onSecondaryFixedVariant,
            "tertiaryFixed" to colorScheme.tertiaryFixed,
            "tertiaryFixedDim" to colorScheme.tertiaryFixedDim,
            "onTertiaryFixed" to colorScheme.onTertiaryFixed,
            "onTertiaryFixedVariant" to colorScheme.onTertiaryFixedVariant,
        )
    }
    LazyColumn(
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(colorTokens, key = { it.first }) { (name, color) ->
            ColorTokenItem(name, color)
        }
    }
}

@Composable
private fun ColorTokenItem(name: String, color: Color) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clip(shape)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .height(40.dp)
                .weight(1f)
                .clip(RoundedCornerShape(4.dp))
                .background(color)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
        )
        Column(modifier = Modifier.weight(2f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            Text(
                color.toHexString(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = JetbrainsMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun Color.toHexString(): String {
    val argb = toArgb()
    return "#%08X".format(argb)
}
