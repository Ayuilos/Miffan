package me.ayuilos.miffan.service

import me.ayuilos.miffan.data.ai.tools.buildSelfConfigTools
import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import kotlinx.datetime.LocalDateTime
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
import kotlinx.coroutines.sync.withLock
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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.ayuilos.miffan.AppScope
import me.ayuilos.miffan.BuildConfig
import me.ayuilos.miffan.R
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
import me.ayuilos.miffan.data.ai.GenerationChunk
import me.ayuilos.miffan.data.ai.GenerationHandler
import me.ayuilos.miffan.data.ai.TranslationHandler
import me.ayuilos.miffan.data.ai.mcp.McpManager
import org.koin.java.KoinJavaComponent.getKoin
import me.ayuilos.miffan.data.ai.tools.createComputerTools
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import me.ayuilos.miffan.data.ai.tools.MiffanHelpClient
import me.ayuilos.miffan.data.ai.tools.WORKSPACE_SHELL_TOOL_NAME
import me.ayuilos.miffan.data.ai.tools.WORKSPACE_TERMINAL_TOOL_NAME
import me.ayuilos.miffan.data.ai.tools.workspaceToolTargetError
import me.ayuilos.miffan.data.ai.tools.createConversationTools
import me.ayuilos.miffan.data.ai.tools.createExtensionManagementTools
import me.ayuilos.miffan.data.ai.tools.createMiffanHelpTool
import me.ayuilos.miffan.data.ai.tools.createSearchTools
import me.ayuilos.miffan.data.ai.tools.createSkillTools
import me.ayuilos.miffan.data.ai.tools.createWorkspaceTools
import me.ayuilos.miffan.data.ai.tools.extensionManagementBuiltInSkill
import me.ayuilos.miffan.data.ai.tools.miffanHelpBuiltInSkill
import me.ayuilos.miffan.data.model.withWorkspaceShellApproval
import me.rerere.ai.ui.WorkspaceToolTargetSnapshot
import me.ayuilos.miffan.data.ai.tools.local.LocalToolOption
import me.ayuilos.miffan.data.ai.tools.local.LocalTools
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
import me.ayuilos.miffan.data.model.MessageRef
import me.ayuilos.miffan.data.model.isImMode
import me.ayuilos.miffan.data.model.memoryExtractionEnabled
import me.ayuilos.miffan.data.ai.MemoryOperation
import me.ayuilos.miffan.data.ai.buildMemoryExtractionPrompt
import me.ayuilos.miffan.data.ai.memoryExtractionWindow
import me.ayuilos.miffan.data.ai.parseMemoryOperations
import me.ayuilos.miffan.data.revision.RevisionAuthor
import me.ayuilos.miffan.data.revision.RevisionOrigin
import me.ayuilos.miffan.utils.toLocalString
import java.time.LocalDate
import me.ayuilos.miffan.data.model.recentChatsReferenceEnabled
import me.ayuilos.miffan.data.thread.ThreadContext
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.AssistantAffectScope
import me.ayuilos.miffan.data.model.MessageNode
import me.ayuilos.miffan.data.model.isMiffanHelpEnabled
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
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid
import okhttp3.OkHttpClient

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

internal fun shouldEnableMiffanHelp(
    assistant: Assistant,
    model: Model,
    globalEnabled: Boolean,
): Boolean = ModelAbility.TOOL in model.abilities && assistant.isMiffanHelpEnabled(globalEnabled)

internal fun Conversation.approvePendingWorkspaceShellTools(
    currentTarget: WorkspaceToolTargetSnapshot?,
    shellEnabled: Boolean,
): Conversation {
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
                            val error = if (!shellEnabled) {
                                "Workspace Shell was disabled after this call was created. Please request it again."
                            } else workspaceToolTargetError(part, currentTarget?.copy(conversationId = id.toString()))
                            part.copy(approvalState = if (error == null) {
                                ToolApprovalState.Approved
                            } else ToolApprovalState.Denied(error))
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

internal fun Conversation.copyMessagePathForFork(
    messageId: Uuid,
    transformMessage: (UIMessage) -> UIMessage = { it },
): List<MessageNode>? {
    val targetNode = getMessageNodeByMessageId(messageId) ?: return null
    val sourcePath = getPathToNode(targetNode.id)
    val copiedNodes = ArrayList<MessageNode>(sourcePath.size)
    sourcePath.forEach { source ->
        val copied = MessageNode(
            message = transformMessage(source.message),
            parentId = copiedNodes.lastOrNull()?.id,
        )
        if (copiedNodes.isNotEmpty()) {
            copiedNodes[copiedNodes.lastIndex] = copiedNodes.last().withSelectedChild(copied.id)
        }
        copiedNodes += copied
    }
    return copiedNodes
}

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

    /** The model lacks tool calling, so search/MCP were skipped; the reply itself still runs. */
    EnableModelTools,
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
    httpClient: OkHttpClient,
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)
    private val miffanHelpClient = MiffanHelpClient(
        httpClient = httpClient,
        cacheFile = File(context.cacheDir, "miffan_help/resources.json"),
    )

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

    /**
     * Loads or creates an IM thread segment for [assistantId] without changing the selected
     * assistant. Preset messages keep their ids, so the timeline shows them only once.
     */
    suspend fun openThreadSegment(conversationId: Uuid, assistantId: Uuid) {
        // A loaded session is the source of truth; reloading could drop an in-flight generation.
        if (getOrCreateSession(conversationId).state.value.messageNodes.isNotEmpty()) return
        val conversation = conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            updateConversation(conversationId, conversation)
        } else {
            val assistant = settingsStore.settingsFlow.value.getAssistantById(assistantId) ?: return
            updateConversation(
                conversationId,
                Conversation.ofId(id = conversationId, assistantId = assistant.id, newConversation = true)
                    .updateCurrentMessages(assistant.presetMessages),
            )
        }
    }

    /**
     * Updates thread metadata (summary, closing time) of a segment. A loaded session is updated
     * and saved so a later save from the session cannot overwrite the change.
     */
    suspend fun updateThreadSegment(conversationId: Uuid, transform: (Conversation) -> Conversation) {
        val loaded = sessions[conversationId]?.state?.value?.takeIf { it.messageNodes.isNotEmpty() }
        if (loaded != null) {
            saveConversation(conversationId, transform(loaded))
        } else {
            val stored = conversationRepo.getConversationById(conversationId) ?: return
            conversationRepo.updateConversation(transform(stored))
        }
    }

    // ---- 发送消息 ----

    fun sendMessage(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        // Keep the existing REST send behavior; the native composer explicitly opts into queuing.
        immediately: Boolean = true,
        replyTo: MessageRef? = null,
        messageId: Uuid = Uuid.random(),
        createdAt: LocalDateTime? = null,
    ) {
        if (content.isEmptyInputMessage()) return
        val session = getOrCreateSession(conversationId)
        val message = QueuedMessage(id = messageId, content = content.toList(), answer = answer, replyTo = replyTo, createdAt = createdAt)
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
                    ).let { if (message.createdAt != null) it.copy(createdAt = message.createdAt) else it },
                    replyTo = message.replyTo,
                ).copy(updateAt = Instant.now())
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

    private val terminalResultLocks = java.util.concurrent.ConcurrentHashMap<Uuid, kotlinx.coroutines.sync.Mutex>()

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        val receiptId = terminalResultReceiptId(answer)
        if (receiptId != null) {
            launchWithConversationReference(conversationId) {
                val session = getOrCreateSession(conversationId)
                val lock = terminalResultLocks.getOrPut(conversationId) { kotlinx.coroutines.sync.Mutex() }
                lock.lock()
                try {
                    // Multiple terminals can finish together. Do not cancel another result's save
                    // or a generation the user started while the terminal was open.
                    session.getJob()?.join()
                    // Navigation may have released the in-memory chat while the PTY kept running.
                    // Restore its saved state without changing the user's currently selected assistant.
                    if (session.state.value.messageNodes.isEmpty()) {
                        val saved = conversationRepo.getConversationById(conversationId)
                            ?: return@launchWithConversationReference
                        updateConversation(conversationId, saved)
                    }
                    val call = session.state.value.currentMessageNodes.lastOrNull()?.currentMessage?.getTools()
                        ?.find { it.toolName == WORKSPACE_TERMINAL_TOOL_NAME && it.toolCallId == toolCallId && it.terminalRequestId == receiptId }
                    if (call?.isPending != true || call.isExecuted) return@launchWithConversationReference
                    applyToolApproval(conversationId, toolCallId, approved, reason, answer)?.join()
                } finally {
                    lock.unlock()
                }
            }
        } else {
            applyToolApproval(conversationId, toolCallId, approved, reason, answer)
        }
    }

    private fun terminalResultReceiptId(answer: String?): String? = answer?.let {
        runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(it)
                .jsonObject["terminalRequestId"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    private fun applyToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String,
        answer: String?,
    ): Job? {
        val session = getOrCreateSession(conversationId)
        val receiptId = terminalResultReceiptId(answer)

        // A terminal may finish after navigation, branching, cancellation, or result delivery.
        // Only resume the still-pending call at the current conversation tip.
        val terminalCall = session.state.value.messageNodes.asSequence()
            .flatMap { it.message.getTools().asSequence() }
            .find { it.toolCallId == toolCallId && it.toolName == WORKSPACE_TERMINAL_TOOL_NAME &&
                (receiptId == null || it.terminalRequestId == receiptId) }
        if (terminalCall != null) {
            val currentCall = session.state.value.currentMessageNodes.lastOrNull()?.currentMessage
                ?.getTools()?.find { it.terminalRequestId == terminalCall.terminalRequestId && it.toolCallId == toolCallId }
            if (currentCall?.isPending != true || currentCall.isExecuted) return null
            // Generic approval must never dispatch this interactive command without a terminal.
            if (approved && answer == null) return null
        }

        val hasOtherPendingTools = session.state.value.currentMessageNodes.any { node ->
            node.currentMessage.parts.any { part ->
                part is UIMessagePart.Tool && part.isPending && part.toolCallId != toolCallId
            }
        }

        return launchGenerationJob(
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
                val currentAssistant = settingsStore.settingsFlow.first()
                    .getAssistantById(conversation.assistantId)
                val currentTarget = if (approved || answer != null) {
                    currentAssistant?.let { currentWorkspaceToolTargetFor(it)?.copy(conversationId = conversation.id.toString()) }
                } else null

                // Update the tool approval state
                val updatedNodes = conversation.messageNodes.map { node ->
                    node.withMessage(
                        node.message.copy(
                            parts = node.message.parts.map { part ->
                                when {
                                    part is UIMessagePart.Tool && part.toolCallId == toolCallId &&
                                        (receiptId == null || part.terminalRequestId == receiptId) -> {
                                        // Terminal results describe an operation already dispatched
                                        // against its saved target; a later rebind must not erase it.
                                        val completedTerminal = part.toolName == WORKSPACE_TERMINAL_TOOL_NAME && answer != null
                                        val staleReason = if ((approved || answer != null) && !completedTerminal) {
                                            if (part.toolName in setOf(WORKSPACE_SHELL_TOOL_NAME, WORKSPACE_TERMINAL_TOOL_NAME) &&
                                                currentAssistant?.workspaceShellEnabled != true
                                            ) {
                                                "Workspace Shell was disabled after this call was created. Please request it again."
                                            } else workspaceToolTargetError(part, currentTarget)
                                        } else null
                                        part.copy(approvalState = staleReason?.let { ToolApprovalState.Denied(it) }
                                            ?: newApprovalState)
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
                val target = currentWorkspaceToolTargetFor(assistant)?.copy(conversationId = conversation.id.toString())
                val pendingShells = conversation.currentMessageNodes.flatMap { node ->
                    node.currentMessage.parts.filterIsInstance<UIMessagePart.Tool>()
                        .filter { it.toolName == WORKSPACE_SHELL_TOOL_NAME && it.isPending }
                }
                val allCurrent = assistant.workspaceShellEnabled && target != null &&
                    pendingShells.all { workspaceToolTargetError(it, target) == null }
                if (allCurrent && target != null) settingsStore.update { current ->
                    current.withWorkspaceShellAllowedFor(assistant, target)
                }

                val updatedConversation = conversation.approvePendingWorkspaceShellTools(
                    currentTarget = target,
                    shellEnabled = assistant.workspaceShellEnabled,
                )
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
            ?: error(context.getString(R.string.chat_page_select_model_first))

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
                        title = context.getString(R.string.error_title_tool_unavailable),
                        solution = ChatErrorSolution.EnableModelTools,
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value
            val boundWorkspace = assistant.workspaceId
                ?.toString()
                ?.let { workspaceRepository.getById(it) }
            val workspaceReady = if (boundWorkspace?.isRemote == true) {
                boundWorkspace.shellStatus == WorkspaceShellStatus.READY.name &&
                    boundWorkspace.remoteHostId?.let { workspaceRepository.getHostById(it) }
                        ?.trustedHostKeySha256 != null
            } else {
                boundWorkspace?.shellStatus == WorkspaceShellStatus.READY.name
            }
            val availableSkills = buildList {
                if (boundWorkspace != null && !boundWorkspace.isRemote) {
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
                localToolOutputRoot = boundWorkspace?.takeUnless { it.isRemote }?.root,
                threadContext = if (settings.isImMode) {
                    ThreadContext.build(conversationRepo, assistant.id, conversation.id)
                } else {
                    null
                },
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
                    val miffanHelpEnabled = shouldEnableMiffanHelp(
                        assistant = assistant,
                        model = model,
                        globalEnabled = settings.miffanHelpEnabled,
                    )
                    if (miffanHelpEnabled) {
                        add(createMiffanHelpTool(miffanHelpClient, BuildConfig.VERSION_NAME))
                    }
                    if (useExternalWebSearch) {
                        addAll(createSearchTools(settings))
                    }
                    addAll(localTools.getTools(assistant.localTools))
                    if (extensionManagementEnabled) {
                        addAll(createExtensionManagementTools(extensionManagementService))
                    }
                    if (assistant.recentChatsReferenceEnabled(settings)) {
                        addAll(createConversationTools(conversationRepo, assistant.id))
                    }
                    if (settings.isImMode) {
                        val trigger = conversation.currentMessages.lastOrNull { it.role == MessageRole.USER }
                        addAll(
                            buildSelfConfigTools(
                                assistantId = assistant.id,
                                settingsStore = settingsStore,
                                trigger = trigger?.let { MessageRef(conversation.id, it.id) },
                                triggerText = trigger?.toText().orEmpty(),
                                webSearchEnabled = assistant.enableWebSearch,
                            )
                        )
                    }
                    addAll(createWorkspaceToolsIfReady(assistant, conversation.workspaceCwd, conversation.id.toString()))
                    addAll(createComputerToolsIfReady(assistant, conversation.id.toString()))
                    if (miffanHelpEnabled || extensionManagementEnabled || availableSkills.isNotEmpty()) {
                        addAll(
                            createSkillTools(
                                allSkills = availableSkills,
                                builtInSkills = buildList {
                                    if (miffanHelpEnabled) add(miffanHelpBuiltInSkill)
                                    if (extensionManagementEnabled) add(extensionManagementBuiltInSkill)
                                },
                                workspaceReady = workspaceReady && assistant.workspaceShellEnabled,
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
            launchWithConversationReference(conversationId) {
                extractMemories(conversationId, assistant.id, finalConversation)
            }
        }
    }

    private suspend fun currentWorkspaceToolTargetFor(
        assistant: Assistant,
    ): WorkspaceToolTargetSnapshot? {
        val workspaceId = assistant.workspaceId?.toString() ?: return null
        return workspaceRepository.currentWorkspaceToolTarget(
            assistantId = assistant.id.toString(),
            workspaceId = workspaceId,
            scopeId = assistant.workspaceScopeId?.toString(),
        )
    }

    /**
     * Desktop tools for a remote workspace whose assistant allows computer use. Connecting to the
     * remote cua-driver is bounded so an unreachable machine never stalls the generation.
     */
    private suspend fun createComputerToolsIfReady(assistant: Assistant, conversationId: String): List<Tool> {
        if (!assistant.computerUseEnabled) return emptyList()
        val workspaceId = assistant.workspaceId?.toString() ?: return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (!workspace.isRemote || workspace.shellStatus != WorkspaceShellStatus.READY.name) return emptyList()
        val snapshot = workspaceRepository.currentWorkspaceToolTarget(
            assistant.id.toString(), workspaceId, assistant.workspaceScopeId?.toString(),
        )?.copy(conversationId = conversationId) ?: return emptyList()
        return try {
            withTimeout(20_000) {
                createComputerTools(
                    snapshot = snapshot,
                    approvalRequired = assistant.computerUseApprovalRequired,
                    registry = getKoin().get(),
                    control = getKoin().get(),
                    filesManager = getKoin().get(),
                )
            }
        } catch (error: CancellationException) {
            if (error is TimeoutCancellationException) emptyList<Tool>().also {
                Log.w(TAG, "createComputerToolsIfReady: cua-driver did not answer in time")
            } else throw error
        } catch (error: Exception) {
            Log.w(TAG, "createComputerToolsIfReady: computer tools unavailable", error)
            emptyList()
        }
    }

    private suspend fun createWorkspaceToolsIfReady(
        assistant: Assistant,
        cwd: String? = null,
        conversationId: String,
    ): List<Tool> {
        val workspaceId = assistant.workspaceId?.toString()
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        val ready = if (workspace.isRemote) {
            workspace.shellStatus == WorkspaceShellStatus.READY.name &&
                workspace.remoteHostId?.let { workspaceRepository.getHostById(it) }
                    ?.trustedHostKeySha256 != null
        } else {
            workspace.shellStatus == WorkspaceShellStatus.READY.name
        }
        if (!ready) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(
            assistantId = assistant.id.toString(),
            workspaceId = workspaceId,
            scopeId = assistant.workspaceScopeId?.toString(),
            shellEnabled = assistant.workspaceShellEnabled,
            shellApprovalRequired = assistant.workspaceShellApprovalRequired,
            shellApprovalTarget = assistant.workspaceShellApprovalTarget,
            workspaceRepository = workspaceRepository,
            cwd = cwd,
            conversationId = conversationId,
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

        val candidate = generateTitleCandidate(conversationId, conversation) ?: return@withContext

        // 生成完，conversation可能不是最新了，因此需要重新获取
        conversationRepo.getConversationById(conversation.id)?.let {
            saveConversation(
                conversationId,
                it.copy(title = candidate)
            )
        }
    }

    /**
     * Generate a title without applying it to the conversation.
     *
     * This is used by the title editor so the generated text remains a candidate
     * until the user explicitly confirms it.
     */
    suspend fun generateTitleCandidate(
        conversationId: Uuid,
        conversation: Conversation,
    ): String? = withContext(Dispatchers.IO) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.titleModelId, fallback = settings.fastModelId)
                ?: return@runCatching null
            val provider = model.findProvider(settings.providers) ?: return@runCatching null

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
                params = backgroundTextGenerationParams(
                    model = model,
                    reasoningLevel = if (model.id == settings.fastModelId) {
                        settings.fastModelReasoningLevel
                    } else {
                        ReasoningLevel.AUTO
                    },
                ),
            )
            result.message.toText().trim().takeIf { it.isNotEmpty() }
        }.onFailure {
            if (it is CancellationException) throw it
            it.printStackTrace()
            addError(
                error = it,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_title),
                solution = ChatErrorSolution.CheckTitleModelSettings,
            )
        }.getOrNull()
    }

    // ---- 整理记忆 ----

    // Serializes extractions so two quick turns cannot both create the same memory.
    private val memoryExtractionLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun extractMemories(
        conversationId: Uuid,
        assistantId: Uuid,
        conversation: Conversation,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            // Read the assistant now: memory may have been switched off while the reply streamed.
            val assistant = settings.getAssistantById(assistantId) ?: return@runCatching
            if (!assistant.memoryExtractionEnabled(settings)) return@runCatching
            val messages = conversation.currentMessages
            val window = memoryExtractionWindow(messages) ?: return@runCatching
            // Only the fast model runs this, so it never adds hidden cost on the chat model.
            val model = settings.findModelById(settings.fastModelId) ?: return@runCatching
            val provider = model.findProvider(settings.providers) ?: return@runCatching

            val ownerId = if (assistant.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                assistant.id.toString()
            }
            memoryExtractionLock.withLock {
                val memories = memoryRepository.getMemoriesOfAssistant(ownerId)
                val result = providerManager.getProviderByType(provider).generateText(
                    providerSetting = provider,
                    messages = listOf(
                        UIMessage.user(
                            buildMemoryExtractionPrompt(
                                memories = memories,
                                window = window,
                                today = LocalDate.now().toLocalString(true),
                            )
                        )
                    ),
                    params = backgroundTextGenerationParams(model, settings.fastModelReasoningLevel),
                )
                val operations = parseMemoryOperations(result.message.toText(), memories.map { it.id }.toSet())
                if (operations.isEmpty()) return@withLock

                val origin = RevisionOrigin(
                    author = RevisionAuthor.AGENT,
                    trigger = messages.lastOrNull { it.role == MessageRole.USER }
                        ?.let { MessageRef(conversationId, it.id) },
                )
                withContext(origin) {
                    operations.forEach { operation ->
                        when (operation) {
                            is MemoryOperation.Create -> memoryRepository.addMemory(ownerId, operation.content)
                            is MemoryOperation.Edit -> memoryRepository.updateContent(operation.id, operation.content)
                        }
                    }
                }
            }
        }.onFailure {
            if (it is CancellationException) throw it
            // Background work: a failure here must not interrupt the chat with an error banner.
            Logging.log(TAG, "extractMemories: $it")
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
                params = backgroundTextGenerationParams(
                    model = model,
                    reasoningLevel = if (model.id == settings.fastModelId) {
                        settings.fastModelReasoningLevel
                    } else {
                        ReasoningLevel.AUTO
                    },
                ),
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
        val copiedNodes = currentConversation.copyMessagePathForFork(messageId) { message ->
            message.copy(parts = message.parts.map { it.copyWithForkedFileUrl() })
        } ?: throw NotFoundException("Message not found")

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

    suspend fun selectMessagePath(
        conversationId: Uuid,
        nodeId: Uuid,
    ): Boolean {
        val currentConversation = getConversationFlow(conversationId).value
        if (currentConversation.getMessageNode(nodeId) == null) return false
        saveConversation(conversationId, currentConversation.selectNode(nodeId))
        return true
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
        workspaceRepository.closeConversationTerminal(conversation.id.toString())
        discardSession(conversation.id)
        conversationRepo.deleteConversation(conversation)
    }

    suspend fun deleteConversationsOfAssistant(assistantId: Uuid) {
        workspaceRepository.closeAssistantTerminals(assistantId.toString())
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
internal fun Settings.withWorkspaceShellAllowedFor(
    binding: Assistant,
    target: WorkspaceToolTargetSnapshot,
): Settings = copy(
    assistants = assistants.map { candidate ->
        if (candidate.id == binding.id &&
            candidate.workspaceId == binding.workspaceId &&
            candidate.workspaceScopeId == binding.workspaceScopeId &&
            candidate.workspacePermissionRevision == binding.workspacePermissionRevision &&
            candidate.workspaceShellEnabled &&
            target.assistantId == candidate.id.toString() &&
            target.workspacePermissionRevision == candidate.workspacePermissionRevision &&
            target.workspaceId == candidate.workspaceId?.toString() &&
            target.scopeId == candidate.workspaceScopeId?.toString()
        ) {
            candidate.withWorkspaceShellApproval(required = false, target = target)
        } else {
            candidate
        }
    },
)
