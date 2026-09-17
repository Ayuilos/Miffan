package me.rerere.ai.ui

import kotlinx.serialization.Serializable

/** App-owned identity captured when a Workspace tool call is created, never read from model input. */
@Serializable
data class WorkspaceToolTargetSnapshot(
    val assistantId: String,
    val workspacePermissionRevision: String,
    val workspaceId: String,
    val scopeId: String?,
    val kind: String,
    val localRoot: String? = null,
    val remoteHostId: String? = null,
    val remoteRoot: String? = null,
    val hostConnectionRevision: String? = null,
    // Display labels describe the originally approved target. Renames do not change identity.
    val workspaceName: String,
    val remoteHostName: String? = null,
    val remoteHostLabel: String? = null,
) {
    fun sameTarget(other: WorkspaceToolTargetSnapshot?): Boolean = other != null &&
        assistantId == other.assistantId &&
        workspacePermissionRevision == other.workspacePermissionRevision &&
        workspaceId == other.workspaceId &&
        scopeId == other.scopeId &&
        kind == other.kind &&
        localRoot == other.localRoot &&
        remoteHostId == other.remoteHostId &&
        remoteRoot == other.remoteRoot &&
        hostConnectionRevision == other.hostConnectionRevision
}
