package me.ayuilos.miffan.service

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.common.android.Logging
import me.ayuilos.miffan.AppScope
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.GenerationChunk
import me.ayuilos.miffan.data.ai.GenerationHandler
import me.ayuilos.miffan.data.ai.TranslationHandler
import me.ayuilos.miffan.data.ai.mcp.McpManager
import me.ayuilos.miffan.data.ai.tools.createConversationTools
import me.ayuilos.miffan.data.ai.tools.createExtensionManagementTools
import me.ayuilos.miffan.data.ai.tools.local.LocalTools
import me.ayuilos.miffan.data.ai.tools.local.LocalToolOption
import me.ayuilos.miffan.data.ai.tools.createSearchTools
import me.ayuilos.miffan.data.ai.tools.createSkillTools
import me.ayuilos.miffan.data.ai.tools.createWorkspaceTools
import me.ayuilos.miffan.data.ai.tools.extensionManagementBuiltInSkill
import me.ayuilos.miffan.data.ai.tools.WORKSPACE_SHELL_TOOL_NAME
import me.ayuilos.miffan.data.extensions.ExtensionManagementService
import me.ayuilos.miffan.data.files.SkillManager
import me.ayuilos.miffan.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.ayuilos.miffan.data.ai.transformers.DocumentAsPromptTransformer
import me.ayuilos.miffan.data.ai.transformers.OcrTransformer
import me.ayuilos.miffan.data.ai.transformers.PlaceholderTransformer
import me.ayuilos.miffan.data.ai.transformers.PromptInjectionTransformer
import me.ayuilos.miffan.data.ai.transformers.RegexOutputTransformer
import me.ayuilos.miffan.data.ai.transformers.TemplateTransformer
import me.ayuilos.miffan.data.ai.transformers.ThinkTagTransformer
import me.ayuilos.miffan.data.ai.transformers.TimeReminderTransformer
import me.ayuilos.miffan.data.ai.transformers.WorkspaceReminderTransformer
import me.ayuilos.miffan.data.event.AppEvent
import me.ayuilos.miffan.data.event.AppEventBus
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.findModelById
import me.ayuilos.miffan.data.datastore.findProvider
import me.ayuilos.miffan.data.datastore.getAssistantById
import me.ayuilos.miffan.data.datastore.getCurrentAssistant
import me.ayuilos.miffan.data.datastore.getCurrentChatModel
import me.ayuilos.miffan.data.files.FilesManager
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.AssistantAffectScope
import me.ayuilos.miffan.data.model.MessageNode
import me.ayuilos.miffan.data.model.replaceRegexes
import me.ayuilos.miffan.data.model.toLinearMessageNodes
import me.ayuilos.miffan.data.repository.ConversationRepository
import me.ayuilos.miffan.data.repository.FolderRepository
import me.ayuilos.miffan.data.repository.MemoryRepository
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.ayuilos.miffan.web.BadRequestException
import me.ayuilos.miffan.web.NotFoundException
import me.ayuilos.miffan.utils.applyPlaceholders
import me.rerere.workspace.WorkspaceShellStatus
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

internal fun backgroundTextGenerationParams(
    model: Model,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
)

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    return assistant.enableWebSearch && BuiltInTools.Search !in model.tools
}

internal fun shouldEnableExtensionManagement(assistant: Assistant, model: Model): Boolean {
    return LocalToolOption.ExtensionManagement in assistant.localTools &&
        ModelAbility.TOOL in model.abilities
}

internal fun Conversation.approvePendingWorkspaceShellTools(): Conversation {
    val currentNodeIds = currentMessageNodes.mapTo(HashSet()) { it.id }
    return copy(
        messageNodes = messageNodes.map { node ->
            if (node.id !in currentNodeIds) return@map node
            node.withMessage(
                node.message.copy(
                    parts = node.message.parts.map { part ->
                        if (
                            part is UIMessagePart.Tool &&
                            part.toolName == WORKSPACE_SHELL_TOOL_NAME &&
                            part.isPending
                        ) {
                            part.copy(approvalState = ToolApprovalState.Approved)
                        } else {
                            part
                        }
                    }
                )
            )
        }
    )
}

internal fun Conversation.hasPendingToolApprovals(): Boolean = currentMessageNodes.any { node ->
    node.currentMessage.parts.any { part ->
        part is UIMessagePart.Tool && part.isPending
    }
}

internal fun Conversation.completedAssistantReplyId(previous: Conversation? = null): Uuid? = currentMessages.lastOrNull()
    ?.takeIf {
        it.role == MessageRole.ASSISTANT && !it.parts.isEmptyUIMessage() && !hasPendingToolApprovals() &&
            it != previous?.currentMessages?.lastOrNull()
    }
    ?.id

/** Transient UI feedback; unlike generationDoneFlow this only describes a successful reply. */
data class AssistantReplyCompleted(val conversationId: Uuid, val messageId: Uuid, val job: Job)

internal fun Conversation.hasPendingWorkspaceShellTools(): Boolean = currentMessageNodes.any { node ->
    node.currentMessage.parts.any { part ->
        part is UIMessagePart.Tool &&
            part.toolName == WORKSPACE_SHELL_TOOL_NAME &&
            part.isPending
    }
}

internal fun createForkConversation(
    source: Conversation,
    messageNodes: List<MessageNode>,
): Conversation = Conversation(
    id = Uuid.random(),
    assistantId = source.assistantId,
    messageNodes = messageNodes,
    selectedRootId = messageNodes.firstOrNull { it.parentId == null }?.id,
    customSystemPrompt = source.customSystemPrompt,
    modeInjectionIds = source.modeInjectionIds,
    lorebookIds = source.lorebookIds,
    workspaceCwd = source.workspaceCwd,
    folderId = source.folderId,
)

internal fun Conversation.deleteNodeSubtree(nodeId: Uuid): Conversation {
    val target = getMessageNode(nodeId) ?: return this
    val removedIds = HashSet<Uuid>()
    val pending = ArrayDeque<Uuid>()
    pending += nodeId
    while (pending.isNotEmpty()) {
        val currentId = pending.removeLast()
        if (!removedIds.add(currentId)) continue
        getChildren(currentId).forEach { pending += it.id }
    }

    val siblings = getSiblings(nodeId)
    val targetIndex = siblings.indexOfFirst { it.id == nodeId }
    val replacement = siblings.getOrNull(targetIndex - 1)
        ?: siblings.getOrNull(targetIndex + 1)
    val targetWasSelected = if (target.parentId == null) {
        (selectedRootId ?: getChildren(null).firstOrNull()?.id) == target.id
    } else {
        getMessageNode(target.parentId)?.selectedChildId == target.id
    }

    return copy(
        selectedRootId = if (target.parentId == null && targetWasSelected) replacement?.id else selectedRootId,
        messageNodes = messageNodes
            .filterNot { it.id in removedIds }
            .map { node ->
                if (targetWasSelected && node.id == target.parentId) {
                    node.withSelectedChild(replacement?.id)
                } else {
                    node
                }
            },
    )
}

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckTitleModelSettings,
}

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val translationHandler: TranslationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val extensionManagementService: ExtensionManagementService,
    private val workspaceRepository: WorkspaceRepository,
    private val folderRepository: FolderRepository,
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update {
            it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    private val _assistantReplyCompleted = MutableSharedFlow<AssistantReplyCompleted>(extraBufferCapacity = 1)
    val assistantReplyCompleted: SharedFlow<AssistantReplyCompleted> = _assistantReplyCompleted.asSharedFlow()

    fun cleanup() = runCatching {
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = settings.getCurrentAssistant().id
                ),
                scope = appScope,
                onIdle = { removeSession(it) },
                onQueueReady = { session ->
                    session.takeNextQueuedMessage()?.let { sendMessageNow(session, it) }
                },
            ).also {
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            session.cleanup()
            _sessionsVersion.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        return getOrCreateSession(conversationId).processingStatus
    }

    fun getMessageQueueFlow(conversationId: Uuid): StateFlow<MessageQueueState> {
        return getOrCreateSession(conversationId).messageQueue
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    private fun launchGenerationJob(
        conversationId: Uuid,
        keepAliveInBackground: Boolean = true,
        queuedMessage: QueuedMessage? = null,
        block: suspend () -> Unit,
    ): Job {
        return getOrCreateSession(conversationId).launchGeneration(message = queuedMessage) {
            val generationId = Uuid.random()
            val foregroundStarted = keepAliveInBackground && ChatGenerationForegroundService.acquire(
                context = context,
                generationId = generationId,
                conversationId = conversationId,
            )
            try {
                block()
            } finally {
                if (foregroundStarted) {
                    ChatGenerationForegroundService.release(context, generationId)
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        getOrCreateSession(conversationId) // 确保 session 存在
        val conversation = conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            updateConversation(conversationId, conversation)
            settingsStore.updateAssistant(conversation.assistantId)
        } else {
            // 新建对话, 并添加预设消息
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            val newConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true
            ).updateCurrentMessages(assistant.presetMessages)
            updateConversation(conversationId, newConversation)
        }
    }

    // ---- 发送消息 ----

    fun sendMessage(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        // Keep the existing REST send behavior; the native composer explicitly opts into queuing.
        immediately: Boolean = true,
    ) {
        if (content.isEmptyInputMessage()) return
        val session = getOrCreateSession(conversationId)
        val message = QueuedMessage(content = content.toList(), answer = answer)
        if (immediately) {
            sendMessageNow(session, message)
            session.resumeQueue()
        } else {
            session.enqueueMessage(message)
        }
    }

    fun sendQueuedMessageImmediately(conversationId: Uuid, messageId: Uuid) {
        val session = getOrCreateSession(conversationId)
        val message = session.removeQueuedMessage(messageId) ?: return
        sendMessageNow(session, message)
        session.resumeQueue()
    }

    fun removeQueuedMessage(conversationId: Uuid, messageId: Uuid) {
        sessions[conversationId]?.removeQueuedMessage(messageId)
    }

    fun resumeMessageQueue(conversationId: Uuid) {
        sessions[conversationId]?.resumeQueue()
    }

    private fun sendMessageNow(session: ConversationSession, message: QueuedMessage) {
        val conversationId = session.id
        launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = message.answer,
            queuedMessage = message,
        ) {
            try {
                finishInterruptedPendingTools(conversationId)

                val currentConversation = session.state.value
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(currentConversation.assistantId)
                    ?: settings.getCurrentAssistant()
                val processedContent = preprocessUserInputParts(message.content, assistant)

                // 添加消息到列表
                val newConversation = currentConversation.appendMessage(
                    UIMessage(
                        id = message.id,
                        role = MessageRole.USER,
                        parts = processedContent,
                    )
                )
                saveConversation(conversationId, newConversation)

                // 开始补全
                if (message.answer) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                session.pauseQueue()
                e.printStackTrace()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
                }

                else -> part
            }
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        val session = getOrCreateSession(conversationId)

        launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = message.role == MessageRole.USER || regenerateAssistantMsg,
        ) {
            try {
                val conversation = session.state.value

                if (message.role == MessageRole.USER) {
                    val node = conversation.getMessageNodeByMessageId(message.id)
                        ?: throw NotFoundException("Message not found")
                    val newConversation = conversation.selectNode(node.id)
                    val indexAt = newConversation.currentMessageNodes.indexOfFirst { it.id == node.id }
                    if (indexAt < 0) throw NotFoundException("Message branch not found")
                    saveConversation(conversationId, newConversation)
                    handleMessageComplete(conversationId, messageRange = 0..indexAt)
                } else {
                    if (regenerateAssistantMsg) {
                        val node = conversation.getMessageNodeByMessage(message)
                            ?: throw NotFoundException("Message not found")
                        val selectedConversation = conversation.selectNode(node.id)
                        val nodeIndex = selectedConversation.currentMessageNodes.indexOfFirst { it.id == node.id }
                        if (nodeIndex < 0) throw NotFoundException("Message branch not found")
                        saveConversation(conversationId, selectedConversation)
                        handleMessageComplete(conversationId, messageRange = 0..<nodeIndex)
                    } else {
                        saveConversation(conversationId, conversation)
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                session.pauseQueue()
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        val session = getOrCreateSession(conversationId)

        val hasOtherPendingTools = session.state.value.currentMessageNodes.any { node ->
            node.currentMessage.parts.any { part ->
                part is UIMessagePart.Tool && part.isPending && part.toolCallId != toolCallId
            }
        }

        launchGenerationJob(
            conversationId = conversationId,
            keepAliveInBackground = !hasOtherPendingTools,
        ) {
            try {
                val conversation = session.state.value
                val newApprovalState = when {
                    answer != null -> ToolApprovalState.Answered(answer)
                    approved -> ToolApprovalState.Approved
                    else -> ToolApprovalState.Denied(reason)
                }

                // Update the tool approval state
                val updatedNodes = conversation.messageNodes.map { node ->
                    node.withMessage(
                        node.message.copy(
                            parts = node.message.parts.map { part ->
                                when {
                                    part is UIMessagePart.Tool && part.toolCallId == toolCallId -> {
                                        part.copy(approvalState = newApprovalState)
                                    }

                                    else -> part
                                }
                            }
                        )
                    )
                }
                val updatedConversation = conversation.copy(messageNodes = updatedNodes)
                saveConversation(conversationId, updatedConversation)

                // Check if there are still pending tools
                val hasPendingTools = updatedConversation.hasPendingToolApprovals()

                // Only continue generation when all pending tools are handled
                if (!hasPendingTools) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                session.pauseQueue()
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

    }

    /** Approve this batch and persist the choice only for the current Assistant scope. */
    fun alwaysAllowWorkspaceShell(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)

        launchGenerationJob(conversationId) {
            try {
                val conversation = session.state.value
                if (!conversation.hasPendingWorkspaceShellTools()) return@launchGenerationJob

                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(conversation.assistantId)
                    ?: error("Assistant not found")
                assistant.workspaceId ?: error("Assistant has no bound workspace")
                settingsStore.update { current ->
                    current.withWorkspaceShellAllowedFor(assistant)
                }

                val updatedConversation = conversation.approvePendingWorkspaceShellTools()
                saveConversation(conversationId, updatedConversation)

                if (!updatedConversation.hasPendingToolApprovals()) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                session.pauseQueue()
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null
    ) {
        val settings = settingsStore.settingsFlow.first()
        val initialConversation = getConversationFlow(conversationId).value
        val assistant = settings.getAssistantById(initialConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
            ?: error("请先选择模型")

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }
        val useExternalWebSearch = shouldUseExternalWebSearch(assistant, model)
        val extensionManagementEnabled = shouldEnableExtensionManagement(assistant, model)

        runCatching {

            // reset suggestions
            updateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (
                    useExternalWebSearch ||
                    mcpManager.getAllAvailableTools().isNotEmpty() ||
                    LocalToolOption.ExtensionManagement in assistant.localTools
                ) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value
            val boundWorkspace = assistant.workspaceId
                ?.toString()
                ?.let { workspaceRepository.getById(it) }
            val workspaceReady = boundWorkspace?.shellStatus == WorkspaceShellStatus.READY.name
            val availableSkills = buildList {
                if (boundWorkspace != null) {
                    if (assistant.enabledSkills.isNotEmpty()) {
                        skillManager.migrateLegacySkillsToWorkspace(
                            assistant = assistant,
                            workspace = boundWorkspace,
                        )
                    }
                    addAll(
                        skillManager.listWorkspaceSkills(
                            workspaceId = boundWorkspace.id,
                            workspaceRoot = boundWorkspace.root,
                            scopeId = assistant.workspaceScopeId?.toString(),
                        )
                    )
                }
            }

            // start generating
            val session = getOrCreateSession(conversationId)
            generationHandler.generateText(
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                messages = conversation.currentMessages.let {
                    if (messageRange != null) {
                        it.subList(messageRange.start, messageRange.endInclusive + 1)
                    } else {
                        it
                    }
                },
                assistant = assistant,
                conversationId = conversationId,
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                workspaceCwd = conversation.workspaceCwd,
                memories = if (assistant.useGlobalMemory) {
                    memoryRepository.getGlobalMemories()
                } else {
                    memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                },
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                    add(workspaceReminderTransformer)
                },
                outputTransformers = outputTransformers,
                tools = buildList {
                    if (useExternalWebSearch) {
                        addAll(createSearchTools(settings))
                    }
                    addAll(localTools.getTools(assistant.localTools))
                    if (extensionManagementEnabled) {
                        addAll(createExtensionManagementTools(extensionManagementService))
                    }
                    if (assistant.enableRecentChatsReference) {
                        addAll(createConversationTools(conversationRepo, assistant.id))
                    }
                    addAll(createWorkspaceToolsIfReady(assistant, conversation.workspaceCwd))
                    if (
                        extensionManagementEnabled ||
                        availableSkills.isNotEmpty()
                    ) {
                        addAll(
                            createSkillTools(
                                allSkills = availableSkills,
                                builtInSkills = if (extensionManagementEnabled) {
                                    listOf(extensionManagementBuiltInSkill)
                                } else {
                                    emptyList()
                                },
                                workspaceReady = workspaceReady,
                            )
                        )
                    }
                    mcpManager.getAllAvailableTools().also { allTools ->
                        val invalidNames = allTools
                            .map { it.second }
                            .distinct()
                            .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
                        if (invalidNames.isNotEmpty()) {
                            addError(
                                error = IllegalStateException(
                                    context.getString(
                                        R.string.error_mcp_invalid_server_name,
                                        invalidNames.joinToString(", ")
                                    )
                                ),
                                conversationId = conversationId,
                            )
                            return
                        }
                    }.forEach { (serverId, serverName, tool) ->
                        add(
                            Tool(
                                name = "mcp__${serverName}__${tool.name}",
                                description = tool.description ?: "",
                                parameters = { tool.inputSchema },
                                needsApproval = { tool.needsApproval },
                                execute = {
                                    mcpManager.callTool(serverId, tool.name, it.jsonObject)
                                },
                            )
                        )
                    }
                },
            ).onCompletion {
                // 可能被取消了，或者意外结束，兜底更新
                val updatedConversation = getConversationFlow(conversationId).value.copy(
                    messageNodes = getConversationFlow(conversationId).value.messageNodes.map { node ->
                        node.withMessage(node.message.finishReasoning())
                    },
                    updateAt = Instant.now()
                )
                updateConversation(conversationId, updatedConversation)

                // 生成结束：取消 Live Update 通知，后台时发送完成通知
                appEventBus.emit(
                    AppEvent.ChatGenerationEnded(
                        conversationId = conversationId,
                        senderName = senderName,
                        contentPreview = updatedConversation.currentMessages.lastOrNull()
                            ?.toText()?.take(50)?.trim() ?: "",
                    )
                )
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        val updatedConversation = getConversationFlow(conversationId).value
                            .updateCurrentMessages(chunk.messages)
                        updateConversation(conversationId, updatedConversation)

                        // 通知等边缘副作用由 ChatNotificationManager 消费；
                        // tryEmit 不挂起，事件丢失只影响单次通知更新，不能反压生成链
                        chunk.messages.lastOrNull()?.let { lastMessage ->
                            appEventBus.tryEmit(
                                AppEvent.ChatGenerationUpdate(conversationId, lastMessage, senderName)
                            )
                        }
                    }
                }
            }
        }.onFailure {
            // 兜底取消 Live Update 通知（生成开始前失败时 onCompletion 不会执行）
            appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))

            if (it is CancellationException) throw it
            currentCoroutineContext().ensureActive()
            getOrCreateSession(conversationId).pauseQueue()
            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            val finalConversation = getConversationFlow(conversationId).value
            saveConversation(conversationId, finalConversation)

            currentCoroutineContext().ensureActive()
            finalConversation.completedAssistantReplyId(initialConversation)?.let { messageId ->
                _assistantReplyCompleted.tryEmit(
                    AssistantReplyCompleted(conversationId, messageId, currentCoroutineContext().job),
                )
            }

            launchWithConversationReference(conversationId) {
                generateTitle(conversationId, finalConversation)
            }
            launchWithConversationReference(conversationId) {
                generateSuggestion(conversationId, finalConversation)
            }
        }
    }

    private suspend fun createWorkspaceToolsIfReady(
        assistant: Assistant,
        cwd: String? = null,
    ): List<Tool> {
        val workspaceId = assistant.workspaceId?.toString()
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(
            workspaceId = workspaceId,
            scopeId = assistant.workspaceScopeId?.toString(),
            shellApprovalRequired = assistant.workspaceShellApprovalRequired,
            workspaceRepository = workspaceRepository,
            cwd = cwd,
        )
    }

    // ---- 检查无效消息 ----

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        val invalidNode = conversation.currentMessageNodes.firstOrNull { node ->
            val pendingTools = node.message.getTools().filterNot { it.isExecuted }
            pendingTools.isNotEmpty() && pendingTools.none { it.approvalState.canResumeToolExecution() }
        } ?: return

        updateConversation(conversationId, conversation.deleteNodeSubtree(invalidNode.id))
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                )
            ),
            approvalState = ToolApprovalState.Denied("Generation cancelled by user")
        )
    }

    private suspend fun finishInterruptedPendingTools(conversationId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.currentMessageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val updatedMessage = lastMessage.finishPendingTools(::cancelToolByUser)
        if (updatedMessage == lastMessage) {
            return
        }

        val updatedConversation = currentConversation.updateMessage(lastMessage.id) { updatedMessage }
        saveConversation(conversationId, updatedConversation)
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return@withContext

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.titleModelId, fallback = settings.fastModelId)
                ?: return@runCatching
            val provider = model.findProvider(settings.providers) ?: return@runCatching

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) })
                    ),
                ),
                params = backgroundTextGenerationParams(model),
            )

            // 生成完，conversation可能不是最新了，因此需要重新获取
            conversationRepo.getConversationById(conversation.id)?.let {
                saveConversation(
                    conversationId,
                    it.copy(title = result.message.toText().trim())
                )
            }
        }.onFailure {
            it.printStackTrace()
            addError(
                error = it,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_title),
                solution = ChatErrorSolution.CheckTitleModelSettings,
            )
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(
        conversationId: Uuid,
        conversation: Conversation,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return@runCatching
            val model = settings.findModelById(settings.suggestionModelId, fallback = settings.fastModelId)
                ?: return@runCatching
            val provider = model.findProvider(settings.providers) ?: return@runCatching

            sessions[conversationId]?.let { session ->
                updateConversation(
                    conversationId,
                    session.state.value.copy(chatSuggestions = emptyList())
                )
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }),
                    )
                ),
                params = backgroundTextGenerationParams(model),
            )
            val suggestions =
                result.message.toText().split("\n").map { it.trim() }
                    .filter { it.isNotBlank() }

            val latestConversation = conversationRepo.getConversationById(conversationId)
                ?: sessions[conversationId]?.state?.value
                ?: conversation
            saveConversation(
                conversationId,
                latestConversation.copy(
                    chatSuggestions = suggestions.take(
                        10
                    )
                )
            )
        }.onFailure {
            it.printStackTrace()
        }
    }

    // ---- 压缩对话历史 ----

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32
    ): Result<Unit> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getCurrentChatModel()
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        val maxMessagesPerChunk = 256
        val allMessages = conversation.currentMessages

        // Split messages into those to compress and those to keep
        val messagesToCompress: List<UIMessage>
        val messagesToKeep: List<UIMessage>

        if (keepRecentMessages > 0 && allMessages.size > keepRecentMessages) {
            messagesToCompress = allMessages.dropLast(keepRecentMessages)
            messagesToKeep = allMessages.takeLast(keepRecentMessages)
        } else if (keepRecentMessages > 0) {
            // Not enough messages to compress while keeping recent ones
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        } else {
            messagesToCompress = allMessages
            messagesToKeep = emptyList()
        }

        fun splitMessages(messages: List<UIMessage>): List<List<UIMessage>> {
            if (messages.size <= maxMessagesPerChunk) return listOf(messages)
            val mid = messages.size / 2
            val left = splitMessages(messages.subList(0, mid))
            val right = splitMessages(messages.subList(mid, messages.size))
            return left + right
        }

        suspend fun compressMessages(messages: List<UIMessage>): String {
            val contentToCompress = messages.joinToString("\n\n") { it.summaryAsText(maxLength = 2000) }
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to contentToCompress,
                "target_tokens" to targetTokens.toString(),
                "additional_context" to if (additionalPrompt.isNotBlank()) {
                    "Additional instructions from user: $additionalPrompt"
                } else "",
                "locale" to Locale.getDefault().displayName
            )

            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(model),
            )

            return result.message.toText().trim().takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Failed to generate compressed summary")
        }

        val compressedSummaries = coroutineScope {
            splitMessages(messagesToCompress)
                .map { chunk -> async { compressMessages(chunk) } }
                .awaitAll()
        }

        // Create new conversation with compressed history as multiple user messages + kept messages
        val newMessageNodes = (
            compressedSummaries.map(UIMessage::user) + messagesToKeep
            ).toLinearMessageNodes()
        val newConversation = conversation.copy(
            messageNodes = newMessageNodes,
            selectedRootId = newMessageNodes.firstOrNull()?.id,
            chatSuggestions = emptyList(),
        )

        saveConversation(conversationId, newConversation)
    }

    // ---- 对话状态更新 ----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        checkFilesDelete(conversation, session.state.value)
        session.state.value = conversation
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val current = getConversationFlow(conversationId).value
        updateConversation(conversationId, update(current))
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 若该会话当前有活跃 session（正在查看或后台生成），先同步内存态再落库：
     * 否则仅改数据库 folder_id，而内存里那份 Conversation 仍是旧 folderId，
     * 后续任意 saveConversation(id, state.value) 会用整对象把 folder_id 覆盖回旧值，导致移动丢失。
     * 先改内存可确保这段窗口内的整对象保存也带上新 folderId。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        if (sessions.containsKey(conversationId)) {
            updateConversationState(conversationId) { it.copy(folderId = folderId) }
        }
        conversationRepo.updateConversationFolderId(conversationId, folderId)
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessions.values.any { it.isGenerating && it.state.value.folderId == folderId }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 先把内存中归属该文件夹的活跃 session folderId 置空，再删库：
     * 否则 clearFolder 只改了数据库，而活跃 session 内存态仍指向该文件夹，
     * 后续整对象保存会写回一个已被删除的 folder_id，导致会话在列表中悬空。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        sessions.values
            .filter { it.state.value.folderId == folderId }
            .forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
        folderRepository.deleteFolder(folderId)
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val newFiles = newConversation.files
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }

        val updatedConversation = conversation.copy()
        updateConversation(conversationId, updatedConversation)

        if (!exists) {
            conversationRepo.insertConversation(updatedConversation)
        } else {
            conversationRepo.updateConversation(updatedConversation)
        }
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                translationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the conversation after translation is complete
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        updateConversation(
            conversationId,
            currentConversation.updateMessage(messageId) { it.copy(translation = translationText) }
        )
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return

        val currentConversation = getConversationFlow(conversationId).value
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(currentConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInputParts(parts, assistant)
        val sourceNode = currentConversation.getMessageNodeByMessageId(messageId) ?: return
        val editedMessage = UIMessage(
            role = sourceNode.role,
            parts = processedParts,
        )
        val editedNode = MessageNode(
            message = editedMessage,
            parentId = sourceNode.parentId,
        )
        val updatedConversation = currentConversation
            .selectNode(sourceNode.id)
            .addNodeAndSelect(editedNode)
        saveConversation(conversationId, updatedConversation)

        // Editing a user message starts a new response branch immediately.
        if (editedMessage.role == MessageRole.USER) {
            regenerateAtMessage(conversationId, editedMessage)
        }
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNode = currentConversation.getMessageNodeByMessageId(messageId)
            ?: throw NotFoundException("Message not found")
        val sourcePath = currentConversation.getPathToNode(targetNode.id)
        val copiedNodes = ArrayList<MessageNode>(sourcePath.size)
        sourcePath.forEach { source ->
            val copied = MessageNode(
                message = source.message.copy(
                    parts = source.message.parts.map { it.copyWithForkedFileUrl() }
                ),
                parentId = copiedNodes.lastOrNull()?.id,
            )
            if (copiedNodes.isNotEmpty()) {
                copiedNodes[copiedNodes.lastIndex] = copiedNodes.last().withSelectedChild(copied.id)
            }
            copiedNodes += copied
        }

        val forkConversation = createForkConversation(currentConversation, copiedNodes)

        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")
        val siblings = currentConversation.getSiblings(targetNode.id)

        if (selectIndex !in siblings.indices) {
            throw BadRequestException("Invalid selectIndex")
        }

        val selectedNode = siblings[selectIndex]
        if (selectedNode.id == targetNode.id) {
            return
        }
        saveConversation(conversationId, currentConversation.selectNode(selectedNode.id))
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNode = conversation.getMessageNodeByMessageId(messageId) ?: return null
        return conversation.deleteNodeSubtree(targetNode.id)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        updateConversation(
            conversationId,
            currentConversation.updateMessage(messageId) { it.copy(translation = null) }
        )
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        val generationVersion = session.generationVersion
        session.stopGeneration().joinAll()
        // A subsequent immediate send owns the conversation once it has replaced this job.
        if (session.generationVersion != generationVersion) return
        finishInterruptedPendingTools(conversationId)
        saveConversation(conversationId, session.state.value)
    }

    /** Deleting a conversation also discards its unsent messages and stops its turn chain. */
    suspend fun deleteConversation(conversation: Conversation) {
        discardSession(conversation.id)
        conversationRepo.deleteConversation(conversation)
    }

    suspend fun deleteConversationsOfAssistant(assistantId: Uuid) {
        sessions.values.filter { it.state.value.assistantId == assistantId }.forEach {
            discardSession(it.id)
        }
        conversationRepo.deleteConversationOfAssistant(assistantId)
    }

    private suspend fun discardSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        val jobs = session.stopGeneration()
        session.cleanup()
        jobs.joinAll()
        if (sessions.remove(conversationId, session)) _sessionsVersion.value++
    }
}

/** Applies a persistent Shell approval only when the Assistant's binding is still unchanged. */
internal fun Settings.withWorkspaceShellAllowedFor(binding: Assistant): Settings = copy(
    assistants = assistants.map { candidate ->
        if (candidate.id == binding.id &&
            candidate.workspaceId == binding.workspaceId &&
            candidate.workspaceScopeId == binding.workspaceScopeId
        ) {
            candidate.copy(workspaceShellApprovalRequired = false)
        } else {
            candidate
        }
    },
)
