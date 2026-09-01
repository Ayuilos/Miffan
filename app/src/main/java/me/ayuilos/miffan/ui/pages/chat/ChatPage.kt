package me.ayuilos.miffan.ui.pages.chat

import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.dokar.sonner.ToastType
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.material3.Material3
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.FolderOpen
import me.rerere.hugeicons.stroke.FolderUnknown
import me.rerere.hugeicons.stroke.GitFork
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.findProvider
import me.ayuilos.miffan.data.datastore.getCurrentAssistant
import me.ayuilos.miffan.data.datastore.getCurrentChatModel
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.data.files.FilesManager
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.ayuilos.miffan.service.ChatError
import me.ayuilos.miffan.ui.components.ai.ChatInput
import me.ayuilos.miffan.ui.components.ai.ChatInputActivity
import me.ayuilos.miffan.ui.components.ai.FilesPicker
import me.ayuilos.miffan.ui.components.ai.SearchMode
import me.ayuilos.miffan.ui.components.ai.completion.WorkspaceCompletionProvider
import me.ayuilos.miffan.ui.components.ai.useCropLauncher
import me.ayuilos.miffan.ui.components.ui.MiffanMascotInputState
import me.ayuilos.miffan.ui.components.ui.MiffanMascotState
import me.ayuilos.miffan.ui.components.ui.permission.PermissionCamera
import me.ayuilos.miffan.ui.components.ui.permission.PermissionManager
import me.ayuilos.miffan.ui.components.ui.permission.rememberPermissionState
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.LocalToaster
import me.ayuilos.miffan.ui.context.Navigator
import me.ayuilos.miffan.ui.hooks.ChatInputState
import me.ayuilos.miffan.ui.hooks.EditStateContent
import me.ayuilos.miffan.ui.hooks.rememberIsPlayStoreVersion
import me.ayuilos.miffan.ui.hooks.useEditState
import me.ayuilos.miffan.utils.ImageUtils
import me.ayuilos.miffan.utils.base64Decode
import me.ayuilos.miffan.utils.isAllowedFileType
import me.ayuilos.miffan.utils.navigateToChatPage
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

@Composable
fun ChatPage(
    id: Uuid,
    text: String?,
    files: List<Uri>,
    nodeId: Uuid? = null,
    messageId: Uuid? = null,
) {
    val vm: ChatVM = koinViewModel(
        parameters = {
            parametersOf(id.toString())
        }
    )
    val filesManager: FilesManager = koinInject()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val recentConversations by vm.recentConversations.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val processingStatus by vm.processingStatus.collectAsStateWithLifecycle()
    val currentChatModel by vm.currentChatModel.collectAsStateWithLifecycle()
    val enableWebSearch by vm.enableWebSearch.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val availableUpdate by vm.availableUpdate.collectAsStateWithLifecycle()
    val isPlayStore = rememberIsPlayStoreVersion()
    val mascotSemanticState = if (!isPlayStore && availableUpdate != null) {
        MiffanMascotState.UpdateAvailable
    } else {
        MiffanMascotState.Idle
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val softwareKeyboardController = LocalSoftwareKeyboardController.current

    // Handle back press when drawer is open
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch {
            drawerState.close()
        }
    }

    // Hide keyboard when drawer is open
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            softwareKeyboardController?.hide()
        }
    }

    val windowAdaptiveInfo = currentWindowDpSize()
    val isBigScreen =
        windowAdaptiveInfo.width > windowAdaptiveInfo.height && windowAdaptiveInfo.width >= 1100.dp

    // 进入大屏（永久抽屉）模式时重置抽屉状态为关闭，
    // 避免从横屏旋转回竖屏后，模态抽屉残留为打开状态且无法关闭（#1304）
    LaunchedEffect(isBigScreen) {
        if (isBigScreen && drawerState.isOpen) {
            drawerState.close()
        }
    }

    val inputState = vm.inputState

    // 初始化输入状态（处理传入的 files 和 text 参数）
    LaunchedEffect(files, text) {
        if (files.isNotEmpty()) {
            val localFiles = filesManager.createChatFilesByContents(files)
            val contentTypes = files.mapNotNull { file ->
                filesManager.getFileMimeType(file)
            }
            val parts = buildList {
                localFiles.forEachIndexed { index, file ->
                    val type = contentTypes.getOrNull(index)
                    if (type?.startsWith("image/") == true) {
                        add(UIMessagePart.Image(url = file.toString()))
                    } else if (type?.startsWith("video/") == true) {
                        add(UIMessagePart.Video(url = file.toString()))
                    } else if (type?.startsWith("audio/") == true) {
                        add(UIMessagePart.Audio(url = file.toString()))
                    }
                }
            }
            inputState.messageContent = parts
        }
        text?.base64Decode()?.let { decodedText ->
            if (decodedText.isNotEmpty()) {
                inputState.setMessageText(decodedText)
            }
        }
    }

    val chatListState = rememberLazyListState()
    var pendingNodeId by remember(id, nodeId, messageId) { mutableStateOf(nodeId) }
    var pendingMessageId by remember(id, nodeId, messageId) { mutableStateOf(messageId) }
    var highlightedNodeId by remember(id) { mutableStateOf<Uuid?>(null) }
    val currentPathNodeIds = conversation.currentMessageNodes.map { it.id }
    val targetMessageMissing = stringResource(R.string.chat_page_target_message_missing)
    LaunchedEffect(pendingNodeId, pendingMessageId, currentPathNodeIds, conversation.messageNodes.size) {
        if (conversation.messageNodes.isEmpty()) return@LaunchedEffect

        val targetNodeId = pendingNodeId
        if (targetNodeId == null) {
            if (!vm.chatListInitialized) {
                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                vm.chatListInitialized = true
            }
            return@LaunchedEffect
        }

        val targetNode = conversation.getMessageNode(targetNodeId)
        val targetMessageId = pendingMessageId
        if (
            targetNode == null ||
            (targetMessageId != null && targetNode.message.id != targetMessageId)
        ) {
            pendingNodeId = null
            pendingMessageId = null
            toaster.show(targetMessageMissing, type = ToastType.Warning)
            if (!vm.chatListInitialized) {
                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                vm.chatListInitialized = true
            }
            return@LaunchedEffect
        }

        val targetIndex = currentPathNodeIds.indexOf(targetNodeId)
        if (targetIndex < 0) {
            vm.selectMessagePath(targetNodeId)
            return@LaunchedEffect
        }

        chatListState.scrollToItem(targetIndex)
        vm.chatListInitialized = true
        pendingNodeId = null
        pendingMessageId = null
        highlightedNodeId = targetNodeId
    }
    LaunchedEffect(highlightedNodeId) {
        val targetNodeId = highlightedNodeId ?: return@LaunchedEffect
        delay(1800)
        if (highlightedNodeId == targetNodeId) highlightedNodeId = null
    }

    when {
        isBigScreen -> {
            PermanentNavigationDrawer(
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting
                    )
                }
            ) {
                ChatPageContent(
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    recentConversations = recentConversations,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    bigScreen = true,
                    errors = errors,
                    highlightedNodeId = highlightedNodeId,
                    mascotSemanticState = mascotSemanticState,
                    onNavigateToNode = {
                        pendingMessageId = null
                        pendingNodeId = it
                    },
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
        }

        else -> {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting
                    )
                }
            ) {
                ChatPageContent(
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    recentConversations = recentConversations,
                    drawerState = drawerState,
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    bigScreen = false,
                    errors = errors,
                    highlightedNodeId = highlightedNodeId,
                    mascotSemanticState = mascotSemanticState,
                    onNavigateToNode = {
                        pendingMessageId = null
                        pendingNodeId = it
                    },
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
            BackHandler(drawerState.isOpen) {
                scope.launch { drawerState.close() }
            }
        }
    }
}

internal data class ChatWorkspaceEntry(
    val id: String,
    val name: String?,
    val scopeId: String?,
    val scopeName: String?,
    val warning: Boolean,
)

internal fun resolveChatWorkspaceEntry(
    boundWorkspaceId: String?,
    workspace: WorkspaceEntity?,
    scopeId: String? = null,
    scopeName: String? = null,
): ChatWorkspaceEntry? {
    val id = boundWorkspaceId ?: return null
    val matchingWorkspace = workspace?.takeIf { it.id == id }
    val shellStatus = matchingWorkspace?.shellStatus?.let { status ->
        runCatching { WorkspaceShellStatus.valueOf(status) }.getOrNull()
    }
    return ChatWorkspaceEntry(
        id = id,
        name = matchingWorkspace?.name?.takeIf { it.isNotBlank() },
        scopeId = scopeId,
        scopeName = scopeName,
        warning = matchingWorkspace == null ||
            shellStatus == null ||
            shellStatus == WorkspaceShellStatus.BROKEN,
    )
}

internal fun workspaceCwdToFilesPath(workspaceCwd: String?): String {
    val normalized = workspaceCwd
        ?.replace('\\', '/')
        ?.trim()
        ?.trimEnd('/')
        .orEmpty()
    if (normalized.isBlank()) return ""

    val relativePath = when {
        normalized == "/workspace" || normalized == "workspace" -> ""
        normalized.startsWith("/workspace/") -> normalized.removePrefix("/workspace/")
        normalized.startsWith("workspace/") -> normalized.removePrefix("workspace/")
        normalized.startsWith('/') -> return ""
        else -> normalized
    }
    val segments = relativePath.split('/').filter { it.isNotBlank() && it != "." }
    if (segments.any { it == ".." }) return ""
    return segments.joinToString("/")
}

internal fun workspaceFilesRoute(
    workspaceId: String,
    workspaceCwd: String?,
    scopeId: String? = null,
    scopeName: String? = null,
): Screen.WorkspaceDetail {
    return Screen.WorkspaceDetail(
        id = workspaceId,
        area = WorkspaceStorageArea.FILES.name,
        path = workspaceCwdToFilesPath(workspaceCwd),
        openFiles = true,
        scopeId = scopeId,
        scopeName = scopeName,
    )
}

@Composable
private fun ChatPageContent(
    inputState: ChatInputState,
    loadingJob: Job?,
    processingStatus: String? = null,
    setting: Settings,
    bigScreen: Boolean,
    conversation: Conversation,
    recentConversations: List<Conversation>,
    drawerState: DrawerState,
    navController: Navigator,
    vm: ChatVM,
    chatListState: LazyListState,
    enableWebSearch: Boolean,
    currentChatModel: Model?,
    errors: List<ChatError>,
    highlightedNodeId: Uuid?,
    mascotSemanticState: MiffanMascotState,
    onNavigateToNode: (Uuid) -> Unit,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val focusManager = LocalFocusManager.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    var previewMode by rememberSaveable { mutableStateOf(false) }
    val hazeState = rememberHazeState()
    val assistant = setting.getCurrentAssistant()
    val messageQueue by vm.messageQueue.collectAsStateWithLifecycle()
    val workspaceId = assistant.workspaceId?.toString()
    val workspaces by workspaceRepository.listFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    val workspaceEntry = resolveChatWorkspaceEntry(
        boundWorkspaceId = workspaceId,
        workspace = workspaces.find { it.id == workspaceId },
        scopeId = assistant.workspaceScopeId?.toString(),
        scopeName = assistant.name.takeIf { it.isNotBlank() },
    )
    var showFilesSheet by remember { mutableStateOf(false) }
    var showPathOverview by remember { mutableStateOf(false) }
    val messagePathCount = remember(conversation.messageNodes) {
        conversation.getMessagePathLeaves().size
    }
    var mascotInputState by remember(conversation.id) {
        mutableStateOf(MiffanMascotInputState.Inactive)
    }
    var mascotSubmitId by remember(conversation.id) { mutableIntStateOf(0) }
    var observedMascotJob by remember(conversation.id) { mutableStateOf(loadingJob) }
    LaunchedEffect(conversation.id, loadingJob) {
        if (loadingJob != null && loadingJob !== observedMascotJob) mascotSubmitId++
        observedMascotJob = loadingJob
    }
    val completedMascotReply by rememberCompletedMascotReply(
        conversationId = conversation.id,
        completions = vm.assistantReplyCompleted,
        generationJobs = vm.conversationJob,
        holdMillis = 900L,
    )

    val completionProviders = remember(
        assistant.workspaceId,
        assistant.workspaceScopeId,
        conversation.workspaceCwd,
        workspaceRepository,
    ) {
        assistant.workspaceId?.let { workspaceId ->
            listOf(
                WorkspaceCompletionProvider(
                    workspaceId = workspaceId.toString(),
                    workspaceScopeId = assistant.workspaceScopeId?.toString(),
                    repository = workspaceRepository,
                    currentCwd = conversation.workspaceCwd,
                )
            )
        }.orEmpty()
    }

    TTSAutoPlay(vm = vm, setting = setting, conversation = conversation)

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        AssistantBackground(setting = setting, modifier = Modifier.hazeSource(hazeState))
        Scaffold(
            topBar = {
                TopBar(
                    hazeState = hazeState,
                    settings = setting,
                    conversation = conversation,
                    bigScreen = bigScreen,
                    drawerState = drawerState,
                    previewMode = previewMode,
                    workspaceEntry = workspaceEntry,
                    pathCount = messagePathCount,
                    onNewChat = {
                        navigateToChatPage(navController)
                    },
                    onOpenWorkspace = { entry ->
                        navController.navigate(
                            workspaceFilesRoute(
                                workspaceId = entry.id,
                                workspaceCwd = conversation.workspaceCwd,
                                scopeId = entry.scopeId,
                                scopeName = entry.scopeName,
                            )
                        )
                    },
                    onClickMenu = {
                        previewMode = !previewMode
                    },
                    onOpenPaths = {
                        showPathOverview = true
                    },
                    onUpdateTitle = {
                        vm.updateTitle(it)
                    }
                )
            },
            bottomBar = {
                ChatInput(
                    state = inputState,
                    loading = loadingJob != null,
                    messageQueue = messageQueue,
                    onRemoveQueuedMessage = vm::removeQueuedMessage,
                    onSendQueuedMessageImmediately = vm::sendQueuedMessageImmediately,
                    onResumeQueue = vm::resumeMessageQueue,
                    settings = setting,
                    hazeState = hazeState,
                    completionProviders = completionProviders,
                    onCancelClick = {
                        vm.stopGeneration()
                    },
                    enableSearch = enableWebSearch,
                    onUpdateSearchMode = { mode ->
                        val current = setting.getCurrentAssistant()
                        val model = setting.getCurrentChatModel()
                        vm.updateSettings(
                            setting.copy(
                                assistants = setting.assistants.map { assistant ->
                                    if (assistant.id == current.id) {
                                        assistant.copy(enableWebSearch = mode == SearchMode.LOCAL)
                                    } else {
                                        assistant
                                    }
                                },
                                providers = if (model == null) {
                                    setting.providers
                                } else {
                                    setting.providers.map { provider ->
                                        provider.editModel(
                                            model.copy(
                                                tools = if (mode == SearchMode.BUILT_IN) {
                                                    model.tools + BuiltInTools.Search
                                                } else {
                                                    model.tools - BuiltInTools.Search
                                                }
                                            )
                                        )
                                    }
                                },
                            )
                        )
                    },
                    onSendClick = {
                        if (currentChatModel == null) {
                            toaster.show("请先选择模型", type = ToastType.Error)
                            return@ChatInput
                        }
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(inputState.getContents())
                            scope.launch {
                                delay(100.milliseconds)
                                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                            }
                        }
                        inputState.clearInput()
                    },
                    onSendImmediatelyClick = {
                        if (currentChatModel == null) {
                            toaster.show("请先选择模型", type = ToastType.Error)
                            return@ChatInput
                        }
                        vm.handleMessageSend(inputState.getContents(), immediately = true)
                        inputState.clearInput()
                        scope.launch {
                            delay(100.milliseconds)
                            chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                        }
                    },
                    onLongSendClick = {
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(content = inputState.getContents(), answer = false)
                            scope.launch {
                                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                            }
                        }
                        inputState.clearInput()
                    },
                    onActivityChanged = { activity ->
                        mascotInputState = when (activity) {
                            ChatInputActivity.Inactive -> MiffanMascotInputState.Inactive
                            ChatInputActivity.Focused -> MiffanMascotInputState.Focused
                            ChatInputActivity.Typing -> MiffanMascotInputState.Typing
                        }
                    },
                    onUpdateChatModel = {
                        vm.setChatModel(assistant = setting.getCurrentAssistant(), model = it)
                    },
                    onUpdateAssistant = {
                        vm.updateSettings(
                            setting.copy(
                                assistants = setting.assistants.map { assistant ->
                                    if (assistant.id == it.id) {
                                        it
                                    } else {
                                        assistant
                                    }
                                }
                            )
                        )
                    },
                    onUpdateSearchService = { index ->
                        vm.updateSettings(
                            setting.copy(
                                searchServiceSelected = index
                            )
                        )
                    },
                    onMoreClick = {
                        showFilesSheet = true
                    },
                )
            },
            containerColor = Color.Transparent,
        ) { innerPadding ->
            ChatList(
                innerPadding = innerPadding,
                conversation = conversation,
                recentConversations = recentConversations,
                state = chatListState,
                loading = loadingJob != null,
                modifier = Modifier.pointerInput(focusManager) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press) {
                                focusManager.clearFocus(force = true)
                            }
                        }
                    }
                },
                processingStatus = processingStatus,
                previewMode = previewMode,
                settings = setting,
                hazeState = hazeState,
                errors = errors,
                highlightedNodeId = highlightedNodeId,
                mascotSemanticState = mascotSemanticState,
                mascotInputState = mascotInputState,
                mascotSubmitId = mascotSubmitId,
                completedMascotReplyId = completedMascotReply,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRegenerate = {
                    vm.regenerateAtMessage(it)
                },
                onOpenConversation = { recentConversation ->
                    navigateToChatPage(navController, chatId = recentConversation.id)
                },
                onEdit = {
                    inputState.editingMessage = it.id
                    inputState.setContents(it.parts)
                },
                onForkMessage = {
                    scope.launch {
                        val fork = vm.forkMessage(message = it)
                        navigateToChatPage(navController, chatId = fork.id)
                    }
                },
                onDelete = {
                    if (loadingJob != null) {
                        vm.showDeleteBlockedWhileGeneratingError()
                    } else {
                        vm.deleteMessage(it)
                    }
                },
                onSelectMessageBranch = { nodeId, branchIndex ->
                    vm.selectMessageBranch(nodeId, branchIndex)
                },
                onClickSuggestion = { suggestion ->
                    inputState.editingMessage = null
                    inputState.setMessageText(suggestion)
                },
                onTranslate = { message, locale ->
                    vm.translateMessage(message, locale)
                },
                onClearTranslation = { message ->
                    vm.clearTranslationField(message.id)
                },
                onJumpToMessage = { index ->
                    previewMode = false
                    scope.launch {
                        chatListState.requestScrollToItem(index)
                    }
                },
                onToolApproval = { toolCallId, approved, reason ->
                    vm.handleToolApproval(toolCallId, approved, reason)
                },
                onToolAnswer = { toolCallId, answer ->
                    vm.handleToolAnswer(toolCallId, answer)
                },
                onAlwaysAllowWorkspaceShell = vm::alwaysAllowWorkspaceShell,
                onToggleFavorite = { node ->
                    vm.toggleMessageFavorite(node)
                },
                onConversationSystemPromptChange = { newPrompt ->
                    vm.updateConversation(conversation.copy(customSystemPrompt = newPrompt))
                    vm.saveConversationAsync()
                },
            )
        }

        if (showFilesSheet) {
            ChatFilesPickerSheet(
                inputState = inputState,
                setting = setting,
                conversation = conversation,
                assistant = assistant,
                vm = vm,
                onDismiss = { showFilesSheet = false },
            )
        }
        if (showPathOverview) {
            MessagePathOverview(
                conversation = conversation,
                onDismiss = { showPathOverview = false },
                onSelectPath = onNavigateToNode,
            )
        }
    }
}

@Composable
private fun ChatFilesPickerSheet(
    inputState: ChatInputState,
    setting: Settings,
    conversation: Conversation,
    assistant: Assistant,
    vm: ChatVM,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toaster = LocalToaster.current
    val filesManager: FilesManager = koinInject()
    var showInjectionSheet by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }

    fun dismissAll() {
        showInjectionSheet = false
        showCompressDialog = false
        onDismiss()
    }

    val cameraPermission = rememberPermissionState(PermissionCamera)
    PermissionManager(permissionState = cameraPermission)

    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }
    var cameraOutputFile by remember { mutableStateOf<File?>(null) }
    val (_, launchCameraCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            inputState.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
            dismissAll()
        },
        onCleanup = {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    )
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captureSuccessful ->
        if (captureSuccessful && cameraOutputUri != null) {
            if (setting.displaySetting.skipCropImage) {
                inputState.addImages(filesManager.createChatFilesByContents(listOf(cameraOutputUri!!)))
                cameraOutputFile?.delete()
                cameraOutputFile = null
                cameraOutputUri = null
                dismissAll()
            } else {
                launchCameraCrop(cameraOutputUri!!)
            }
        } else {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    }
    val onLaunchCamera: () -> Unit = {
        if (cameraPermission.allRequiredPermissionsGranted) {
            cameraOutputFile = context.cacheDir.resolve("camera_${Uuid.random()}.jpg")
            cameraOutputUri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", cameraOutputFile!!
            )
            cameraLauncher.launch(cameraOutputUri!!)
        } else {
            cameraPermission.requestPermissions()
        }
    }

    var preCropTempFile by remember { mutableStateOf<File?>(null) }
    val (_, launchImageCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            inputState.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
            dismissAll()
        },
        onCleanup = {
            preCropTempFile?.delete()
            preCropTempFile = null
        }
    )
    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                Log.d("ImagePickButton", "Selected URIs: $selectedUris")
                if (setting.displaySetting.skipCropImage) {
                    inputState.addImages(filesManager.createChatFilesByContents(selectedUris))
                    dismissAll()
                } else if (selectedUris.size == 1) {
                    val tempFile = File(context.appTempFolder, "pick_temp_${System.currentTimeMillis()}.jpg")
                    runCatching {
                        val source = selectedUris.first()
                        // HEIF/HEIC（尤其 HDR HEIF）交给 UCrop 前先解码转为 JPEG，规避裁剪解码失败
                        val converted = ImageUtils.isHeifImage(context, source) &&
                            ImageUtils.convertHeifToJpeg(context, source, tempFile)
                        if (!converted) {
                            context.contentResolver.openInputStream(source)?.use { input ->
                                tempFile.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                        preCropTempFile = tempFile
                        launchImageCrop(tempFile.toUri())
                    }.onFailure {
                        Log.e("ImagePickButton", "Failed to copy image to temp, falling back", it)
                        launchImageCrop(selectedUris.first())
                    }
                } else {
                    inputState.addImages(filesManager.createChatFilesByContents(selectedUris))
                    dismissAll()
                }
            } else {
                Log.d("ImagePickButton", "No images selected")
            }
        }

    val videoPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                inputState.addVideos(filesManager.createChatFilesByContents(selectedUris))
                dismissAll()
            }
        }

    val audioPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                inputState.addAudios(filesManager.createChatFilesByContents(selectedUris))
                dismissAll()
            }
        }

    val filePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                val documents = uris.mapNotNull { uri ->
                    val fileName = filesManager.getFileNameFromUri(uri) ?: "file"
                    val mime = filesManager.getFileMimeType(uri) ?: "text/plain"
                    if (isAllowedFileType(fileName, mime)) {
                        val localUri = filesManager.createChatFilesByContents(listOf(uri)).firstOrNull()
                            ?: run {
                                toaster.show(
                                    resources.getString(R.string.chat_input_file_read_failed, fileName),
                                    type = ToastType.Error
                                )
                                return@mapNotNull null
                            }
                        UIMessagePart.Document(url = localUri.toString(), fileName = fileName, mime = mime)
                    } else {
                        toaster.show(
                            resources.getString(R.string.chat_input_unsupported_file_type, fileName),
                            type = ToastType.Error
                        )
                        null
                    }
                }
                if (documents.isNotEmpty()) {
                    inputState.addFiles(documents)
                    dismissAll()
                }
            }
        }

    val filesSheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    ModalBottomSheet(
        sheetState = filesSheetState,
        onDismissRequest = { dismissAll() },
    ) {
        FilesPicker(
            conversation = conversation,
            state = inputState,
            assistant = assistant,
            mcpManager = vm.mcpManager,
            onCompressContext = { additionalPrompt, targetTokens, keepRecentMessages ->
                vm.handleCompressContext(additionalPrompt, targetTokens, keepRecentMessages)
            },
            onUpdateAssistant = {
                vm.updateSettings(
                    setting.copy(
                        assistants = setting.assistants.map { assistant ->
                            if (assistant.id == it.id) {
                                it
                            } else {
                                assistant
                            }
                        }
                    )
                )
            },
            onUpdateConversation = {
                vm.updateConversation(it)
                vm.saveConversationAsync()
            },
            showInjectionSheet = showInjectionSheet,
            onShowInjectionSheetChange = { showInjectionSheet = it },
            showCompressDialog = showCompressDialog,
            onShowCompressDialogChange = { showCompressDialog = it },
            onDismiss = { dismissAll() },
            onTakePic = onLaunchCamera,
            onPickImage = { imagePickerLauncher.launch("image/*") },
            onPickVideo = { videoPickerLauncher.launch("video/*") },
            onPickAudio = { audioPickerLauncher.launch("audio/*") },
            onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
        )
    }
}

@Composable
private fun WorkspaceTopBarAction(
    entry: ChatWorkspaceEntry,
    showName: Boolean,
    onClick: () -> Unit,
) {
    val workspaceLabel = stringResource(R.string.extensions_page_workspace)
    val filesLabel = stringResource(R.string.workspace_detail_tab_files)
    val errorLabel = stringResource(R.string.workspace_detail_shell_broken)
    val scopeLabel = if (entry.scopeId == null) {
        stringResource(R.string.workspace_scope_legacy)
    } else {
        stringResource(
            R.string.workspace_scope_private,
            entry.scopeName ?: entry.scopeId.take(8),
        )
    }
    val displayName = "${entry.name ?: workspaceLabel} · $scopeLabel"
    val actionLabel = buildString {
        append(workspaceLabel)
        append(' ')
        append(filesLabel)
        if (!showName) {
            append(": ")
            append(displayName)
        }
        if (entry.warning) {
            append(", ")
            append(errorLabel)
        }
    }
    val contentColor = if (entry.warning) {
        MaterialTheme.colorScheme.error
    } else {
        LocalContentColor.current
    }
    val icon = if (entry.warning) HugeIcons.FolderUnknown else HugeIcons.FolderOpen

    if (showName) {
        TextButton(
            onClick = onClick,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .widthIn(max = 200.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = actionLabel,
                modifier = Modifier.size(20.dp),
                tint = contentColor,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = displayName,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = icon,
                contentDescription = actionLabel,
                tint = contentColor,
            )
        }
    }
}

@Composable
private fun ChatTopBarCapsule(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(50)
    // Keep the theme's hue, with a tonal step from the page (including AMOLED black).
    val glassColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val glassStyle = HazeBlurStyle.Material3 {
        blurRadius(20.dp)
        noiseFactor(0.04f)
        colorEffects(listOf(HazeColorEffect.tint(glassColor.copy(alpha = 0.8f))))
        fallbackColorEffect(HazeColorEffect.tint(glassColor.copy(alpha = 0.9f)))
    }
    Surface(
        modifier = modifier
            .clip(shape)
            .hazeBlur(input = HazeInput.Sources(hazeState), style = glassStyle),
        shape = shape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun TopBar(
    hazeState: HazeState,
    settings: Settings,
    conversation: Conversation,
    drawerState: DrawerState,
    bigScreen: Boolean,
    previewMode: Boolean,
    workspaceEntry: ChatWorkspaceEntry?,
    pathCount: Int,
    onClickMenu: () -> Unit,
    onOpenPaths: () -> Unit,
    onNewChat: () -> Unit,
    onOpenWorkspace: (ChatWorkspaceEntry) -> Unit,
    onUpdateTitle: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val titleState = useEditState<String> {
        onUpdateTitle(it)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("chat_floating_topbar")
            .windowInsetsPadding(TopAppBarDefaults.windowInsets)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ChatTopBarCapsule(
            hazeState = hazeState,
            modifier = Modifier.weight(1f, fill = false).testTag("chat_title_capsule"),
        ) {
            if (!bigScreen) {
                IconButton(
                    onClick = {
                        scope.launch { drawerState.open() }
                    }
                ) {
                    Icon(HugeIcons.Menu03, "Messages")
                }
            }
            val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)
            Surface(
                modifier = Modifier.weight(1f, fill = false),
                onClick = {
                    if (conversation.messageNodes.isNotEmpty()) {
                        titleState.open(conversation.title)
                    } else {
                        toaster.show(editTitleWarning, type = ToastType.Warning)
                    }
                },
                color = Color.Transparent,
            ) {
                Column(
                    modifier = Modifier.padding(
                        start = if (bigScreen) 12.dp else 2.dp,
                        end = 12.dp,
                        top = 6.dp,
                        bottom = 6.dp,
                    ),
                ) {
                    val assistant = settings.getCurrentAssistant()
                    val model = settings.getCurrentChatModel()
                    val provider = model?.findProvider(providers = settings.providers, checkOverwrite = false)
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (model != null && provider != null) {
                        Text(
                            text = "${assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }} / ${model.displayName} (${provider.name})",
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            color = LocalContentColor.current.copy(0.65f),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                            )
                        )
                    }
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        ChatTopBarCapsule(
            hazeState = hazeState,
            modifier = Modifier.testTag("chat_actions_capsule"),
        ) {
            workspaceEntry?.let { entry ->
                WorkspaceTopBarAction(
                    entry = entry,
                    showName = bigScreen,
                    onClick = { onOpenWorkspace(entry) },
                )
            }

            if (pathCount > 1) {
                IconButton(onClick = onOpenPaths) {
                    Icon(
                        imageVector = HugeIcons.GitFork,
                        contentDescription = stringResource(R.string.chat_page_message_paths),
                    )
                }
            }

            IconButton(
                onClick = {
                    onClickMenu()
                }
            ) {
                Icon(if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet, "Chat Options")
            }

            IconButton(
                onClick = {
                    onNewChat()
                }
            ) {
                Icon(HugeIcons.MessageAdd01, "New Message")
            }
        }
    }
    titleState.EditStateContent { title, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                titleState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_title))
            },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        titleState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        titleState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }
}
