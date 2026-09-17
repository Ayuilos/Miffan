package me.ayuilos.miffan.data.repository

/** A pending workspace tool must not resume after its assistant/host/workspace grant changes. */
class WorkspaceToolTargetChangedException : IllegalStateException(
    "Workspace tool target or permission changed; request this action again",
)
