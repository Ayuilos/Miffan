package me.ayuilos.miffan.data.model

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.ayuilos.miffan.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.InstantSerializer
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * A conversation stores every message branch as a flat adjacency tree.
 *
 * [selectedRootId] selects the active root and every node's [MessageNode.selectedChildId]
 * selects the next node on the active path. Keeping the tree flat avoids recursive
 * serialization and makes a branch switch independent of the number of descendants.
 */
@Serializable
data class Conversation(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    val selectedRootId: Uuid? = null,
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    val customSystemPrompt: String? = null,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),
    // Absolute path inside the workspace rootfs
    val workspaceCwd: String? = null,
    // 所属文件夹（助手内分组），null 表示未归入任何文件夹
    val folderId: Uuid? = null,
    @Transient
    val newConversation: Boolean = false
) {
    @Transient
    private var nodesByIdCache: Map<Uuid, MessageNode>? = null

    private val nodesById: Map<Uuid, MessageNode>
        get() = nodesByIdCache ?: messageNodes.associateBy(MessageNode::id).also { nodesByIdCache = it }

    @Transient
    private var nodesByMessageIdCache: Map<Uuid, MessageNode>? = null

    private val nodesByMessageId: Map<Uuid, MessageNode>
        get() = nodesByMessageIdCache
            ?: messageNodes.associateBy { it.message.id }.also { nodesByMessageIdCache = it }

    @Transient
    private var childrenByParentIdCache: Map<Uuid?, List<MessageNode>>? = null

    private val childrenByParentId: Map<Uuid?, List<MessageNode>>
        get() = childrenByParentIdCache
            ?: messageNodes.groupBy(MessageNode::parentId).also { childrenByParentIdCache = it }

    val files: List<Uri>
        get() = messageNodes
            .flatMap { it.message.parts }
            .collectAllParts()
            .mapNotNull { it.fileUri() }

    /** The selected root followed by each selected child. */
    val currentMessageNodes: List<MessageNode>
        get() {
            val path = ArrayList<MessageNode>()
            val visited = HashSet<Uuid>()
            var nodeId = selectedRootId ?: childrenByParentId[null]?.firstOrNull()?.id
            while (nodeId != null && visited.add(nodeId)) {
                val node = nodesById[nodeId] ?: break
                path += node
                nodeId = node.selectedChildId
            }
            return path
        }

    val currentMessages: List<UIMessage>
        get() = currentMessageNodes.map(MessageNode::message)

    fun getMessageNodeByMessage(message: UIMessage): MessageNode? = nodesByMessageId[message.id]

    fun getMessageNodeByMessageId(messageId: Uuid): MessageNode? = nodesByMessageId[messageId]

    fun getMessageNode(nodeId: Uuid): MessageNode? = nodesById[nodeId]

    fun getChildren(parentId: Uuid?): List<MessageNode> = childrenByParentId[parentId].orEmpty()

    fun getSiblings(nodeId: Uuid): List<MessageNode> {
        val node = nodesById[nodeId] ?: return emptyList()
        return getChildren(node.parentId)
    }

    fun getPathToNode(nodeId: Uuid): List<MessageNode> {
        val reversed = ArrayList<MessageNode>()
        val visited = HashSet<Uuid>()
        var node = nodesById[nodeId]
        while (node != null && visited.add(node.id)) {
            reversed += node
            node = node.parentId?.let(nodesById::get)
        }
        return reversed.asReversed()
    }

    fun appendMessage(message: UIMessage): Conversation = addNodeAndSelect(
        MessageNode(
            message = message,
            parentId = currentMessageNodes.lastOrNull()?.id,
        )
    )

    fun addNodeAndSelect(node: MessageNode): Conversation {
        require(node.id !in nodesById) { "Duplicate message node id: ${node.id}" }
        require(node.parentId == null || node.parentId in nodesById) {
            "Parent message node not found: ${node.parentId}"
        }

        val withSelectedParent = if (node.parentId == null) {
            messageNodes
        } else {
            messageNodes.map { current ->
                if (current.id == node.parentId) current.withSelectedChild(node.id) else current
            }
        }
        return copy(
            messageNodes = withSelectedParent + node,
            selectedRootId = if (node.parentId == null) node.id else selectedRootId,
        )
    }

    /** Select a sibling and therefore its complete remembered descendant branch. */
    fun selectNode(nodeId: Uuid): Conversation {
        val path = getPathToNode(nodeId)
        if (path.isEmpty() || path.first().parentId != null) return this
        val selectedChildren = path.zipWithNext().associate { (parent, child) -> parent.id to child.id }
        return copy(
            selectedRootId = path.first().id,
            messageNodes = messageNodes.map { current ->
                selectedChildren[current.id]?.let(current::withSelectedChild) ?: current
            }
        )
    }

    fun updateMessage(messageId: Uuid, update: (UIMessage) -> UIMessage): Conversation {
        val node = nodesByMessageId[messageId] ?: return this
        return copy(
            messageNodes = messageNodes.map { current ->
                if (current.id == node.id) current.withMessage(update(current.message)) else current
            }
        )
    }

    /**
     * Merge a provider's full streamed message path into the tree.
     * Existing ids are updated in place; a changed id creates/selects a sibling branch.
     */
    fun updateCurrentMessages(messages: List<UIMessage>): Conversation {
        if (messages.isEmpty()) return this

        val mutableNodes = messageNodes.toMutableList()
        val nodeIndexById = mutableNodes.mapIndexed { index, node -> node.id to index }.toMap().toMutableMap()
        val nodeIdByMessageId = mutableNodes.associate { it.message.id to it.id }.toMutableMap()
        var rootId = selectedRootId ?: mutableNodes.firstOrNull { it.parentId == null }?.id
        var parentId: Uuid? = null

        fun selectChild(selectedNodeId: Uuid) {
            if (parentId == null) {
                rootId = selectedNodeId
                return
            }
            val parentIndex = nodeIndexById[parentId] ?: return
            val parent = mutableNodes[parentIndex]
            if (parent.selectedChildId != selectedNodeId) {
                mutableNodes[parentIndex] = parent.withSelectedChild(selectedNodeId)
            }
        }

        messages.forEach { message ->
            val existingNodeId = nodeIdByMessageId[message.id]
            val existingIndex = existingNodeId?.let(nodeIndexById::get)
            val existing = existingIndex?.let(mutableNodes::get)

            val selectedNode = if (existing != null && existing.parentId == parentId) {
                if (existing.message != message) {
                    mutableNodes[existingIndex] = existing.withMessage(message)
                }
                mutableNodes[existingIndex]
            } else {
                val node = MessageNode(message = message, parentId = parentId)
                nodeIndexById[node.id] = mutableNodes.size
                nodeIdByMessageId[message.id] = node.id
                mutableNodes += node
                node
            }

            selectChild(selectedNode.id)
            parentId = selectedNode.id
        }

        return copy(messageNodes = mutableNodes, selectedRootId = rootId)
    }

    companion object {
        fun ofId(
            id: Uuid,
            assistantId: Uuid = DEFAULT_ASSISTANT_ID,
            messages: List<MessageNode> = emptyList(),
            newConversation: Boolean = false
        ) = Conversation(
            id = id,
            assistantId = assistantId,
            messageNodes = messages,
            selectedRootId = messages.firstOrNull { it.parentId == null }?.id,
            newConversation = newConversation,
        )

        fun linear(
            id: Uuid = Uuid.random(),
            assistantId: Uuid,
            messages: List<UIMessage>,
            title: String = "",
        ): Conversation {
            val nodes = messages.toLinearMessageNodes()
            return Conversation(
                id = id,
                assistantId = assistantId,
                title = title,
                messageNodes = nodes,
                selectedRootId = nodes.firstOrNull()?.id,
            )
        }
    }
}

@Serializable
data class MessageNode(
    val id: Uuid = Uuid.random(),
    val message: UIMessage,
    val parentId: Uuid? = null,
    val selectedChildId: Uuid? = null,
    @Transient
    val isFavorite: Boolean = false,
    @Transient
    val revision: Long = 0,
) {
    val currentMessage: UIMessage get() = message
    val role: MessageRole get() = message.role

    fun withMessage(message: UIMessage): MessageNode =
        if (this.message == message) this else copy(message = message, revision = revision + 1)

    fun withSelectedChild(childId: Uuid?): MessageNode =
        if (selectedChildId == childId) this else copy(selectedChildId = childId, revision = revision + 1)

    companion object {
        fun of(message: UIMessage, parentId: Uuid? = null) = MessageNode(
            message = message,
            parentId = parentId,
        )
    }
}

fun UIMessage.toMessageNode(parentId: Uuid? = null): MessageNode = MessageNode.of(this, parentId)

fun List<UIMessage>.toLinearMessageNodes(): List<MessageNode> {
    if (isEmpty()) return emptyList()
    val nodes = ArrayList<MessageNode>(size)
    var parentId: Uuid? = null
    forEach { message ->
        val node = message.toMessageNode(parentId)
        if (nodes.isNotEmpty()) {
            nodes[nodes.lastIndex] = nodes.last().withSelectedChild(node.id)
        }
        nodes += node
        parentId = node.id
    }
    return nodes
}

/**
 * 递归展开所有 parts，包括工具调用结果中的嵌套 parts。
 */
private fun List<UIMessagePart>.collectAllParts(): List<UIMessagePart> =
    this + filterIsInstance<UIMessagePart.Tool>().flatMap { it.output.collectAllParts() }

/**
 * 提取 part 中引用的本地文件 URI，新增文件类型时只需在此处添加。
 */
private fun UIMessagePart.fileUri(): Uri? = when (this) {
    is UIMessagePart.Image -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Document -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Video -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Audio -> url.takeIf { it.startsWith("file://") }?.toUri()
    else -> null
}
