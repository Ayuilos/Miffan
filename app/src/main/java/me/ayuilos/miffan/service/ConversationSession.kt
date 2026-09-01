package me.ayuilos.miffan.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import me.ayuilos.miffan.data.model.Conversation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

private const val TAG = "ConversationSession"
private const val IDLE_TIMEOUT_MS = 5_000L

data class QueuedMessage(
    val id: Uuid = Uuid.random(),
    val content: List<UIMessagePart>,
    val answer: Boolean = true,
)

data class MessageQueueState(
    val messages: List<QueuedMessage> = emptyList(),
    val paused: Boolean = false,
)

class ConversationSession(
    val id: Uuid,
    initial: Conversation,
    private val scope: CoroutineScope,
    private val onIdle: (Uuid) -> Unit,
    private val onQueueReady: (ConversationSession) -> Unit = {},
) {
    // 会话状态
    val state = MutableStateFlow(initial)

    // 原子引用计数
    private val refCount = AtomicInteger(0)

    // 处理状态（如 OCR 识别中）
    val processingStatus = MutableStateFlow<String?>(null)

    // 生成任务（内聚在 session 中）
    private val _generationJob = MutableStateFlow<Job?>(null)
    private val unfinishedJobs = mutableSetOf<Job>()
    @Volatile
    var generationVersion: Long = 0
        private set
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()
    val isGenerating: Boolean get() = _generationJob.value != null
    private val _messageQueue = MutableStateFlow(MessageQueueState())
    val messageQueue: StateFlow<MessageQueueState> = _messageQueue.asStateFlow()
    val isInUse: Boolean
        get() = refCount.get() > 0 || isGenerating || _messageQueue.value.messages.isNotEmpty() ||
            synchronized(this) { unfinishedJobs.isNotEmpty() }

    // 空闲检查任务
    private var idleCheckJob: Job? = null
    @Volatile
    private var closed = false

    fun acquire(): Int = refCount.incrementAndGet().also {
        cancelIdleCheck()
        Log.d(TAG, "acquire $id (refs=$it)")
    }

    fun release(): Int = refCount.decrementAndGet().also {
        Log.d(TAG, "release $id (refs=$it)")
        if (it <= 0) scheduleIdleCheck()
    }

    // 作用域 API - 短请求（REST）
    inline fun <T> withRef(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    // 作用域 API - 长连接（SSE、挂起函数）
    suspend inline fun <T> withRefSuspend(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    /** Register the replacement before cancelling the old turn, and await its final cleanup. */
    @Synchronized
    fun launchGeneration(message: QueuedMessage? = null, block: suspend () -> Unit): Job {
        // Keep the whole cleanup chain: a second interruption may cancel a turn that is
        // still waiting for an earlier turn's tools to stop.
        val previousJobs = unfinishedJobs.toList()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            previousJobs.joinAll()
            block()
        }
        if (message != null) {
            job.invokeOnCompletion {
                // Cancellation can happen before the coroutine starts or before input is saved.
                if (!closed && state.value.getMessageNodeByMessageId(message.id) == null) {
                    _messageQueue.update { queue ->
                        if (queue.messages.any { it.id == message.id }) queue
                        else queue.copy(messages = listOf(message) + queue.messages)
                    }
                }
            }
        }
        setJob(job)
        job.start()
        return job
    }

    @Synchronized
    fun setJob(job: Job?) {
        val previousJob = _generationJob.value
        if (previousJob === job) return
        generationVersion++
        _generationJob.value = job
        if (job != null) unfinishedJobs.add(job)
        previousJob?.cancel()
        job?.invokeOnCompletion { cause ->
            synchronized(this) {
                unfinishedJobs.remove(job)
                // A cancelled turn must never clear or advance its replacement's queue.
                if (!_generationJob.compareAndSet(job, null)) return@invokeOnCompletion
                if (cause != null) pauseQueue()
                requestQueueDispatch()
                if (refCount.get() <= 0) {
                    scheduleIdleCheck()
                }
            }
        }
    }

    fun getJob(): Job? = _generationJob.value

    fun enqueueMessage(message: QueuedMessage) {
        if (closed || message.content.isEmptyInputMessage()) return
        val snapshot = message.copy(content = message.content.toList())
        _messageQueue.update {
            it.copy(
                messages = it.messages + snapshot,
                paused = it.paused && it.messages.isNotEmpty(),
            )
        }
        cancelIdleCheck()
        requestQueueDispatch()
    }

    fun removeQueuedMessage(id: Uuid): QueuedMessage? {
        while (true) {
            val current = _messageQueue.value
            val message = current.messages.find { it.id == id } ?: return null
            if (_messageQueue.compareAndSet(current, current.copy(messages = current.messages - message))) {
                if (!isInUse) scheduleIdleCheck()
                return message
            }
        }
    }

    fun takeNextQueuedMessage(): QueuedMessage? {
        if (isGenerating || _messageQueue.value.paused || state.value.hasPendingToolApprovals()) return null
        return _messageQueue.value.messages.firstOrNull()?.let { removeQueuedMessage(it.id) }
    }

    fun pauseQueue() {
        _messageQueue.update { it.copy(paused = true) }
    }

    fun resumeQueue() {
        _messageQueue.update { it.copy(paused = false) }
        requestQueueDispatch()
    }

    @Synchronized
    fun stopGeneration(): List<Job> {
        pauseQueue()
        return unfinishedJobs.toList().also { jobs -> jobs.forEach { it.cancel() } }
    }

    private fun requestQueueDispatch() {
        scope.launch {
            synchronized(this@ConversationSession) {
                if (!closed && !isGenerating && !_messageQueue.value.paused && _messageQueue.value.messages.isNotEmpty()) {
                    onQueueReady(this@ConversationSession)
                }
            }
        }
    }

    private fun scheduleIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (!isInUse) {
                onIdle(id)
            }
        }
    }

    private fun cancelIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    fun cleanup() {
        closed = true
        pauseQueue()
        val job = _generationJob.value
        _generationJob.value = null
        job?.cancel()
        _messageQueue.value = MessageQueueState()
        idleCheckJob?.cancel()
        idleCheckJob = null
    }
}
