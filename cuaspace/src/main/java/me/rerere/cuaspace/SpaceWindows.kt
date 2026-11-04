package me.rerere.cuaspace

import cua.env.v1.ActivateWindowRequest
import cua.env.v1.ActivateWindowResponse
import cua.env.v1.ImageFormat
import cua.env.v1.ListTargetsRequest
import cua.env.v1.ListTargetsResponse
import cua.env.v1.RestoreWindowRequest
import cua.env.v1.RestoreWindowResponse
import cua.env.v1.ScreenshotRequest
import cua.env.v1.ScreenshotResponse
import cua.env.v1.SetWindowBoundsRequest
import cua.env.v1.SetWindowBoundsResponse
import cua.env.v1.WindowInfo
import cua.env.v1.WindowKind
import cua.env.v1.WindowRef
import cua.env.v1.WindowState

/** One application window a viewer can pick, as the computer reported it a moment ago. */
data class SpaceWindow(
    val id: String,
    val epoch: Long,
    val app: String,
    val appId: String,
    val pid: Int,
    val title: String,
    /** Frame in logical points. */
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val state: State,
    /** Whether its picture can be streamed right now; [limitation] says why not. */
    val available: Boolean,
    val limitation: String?,
    val focused: Boolean,
    val zOrder: Int,
) {
    enum class State { NORMAL, MINIMIZED, HIDDEN }

    internal val ref: WindowRef get() = WindowRef(id = id, epoch = epoch)
}

/**
 * Normal windows of real size, front to back. Windows not on screen are included: on a Mac those are
 * mostly windows on another desktop (Space), which still stream; the caller decides what to offer.
 */
suspend fun SpaceClient.listWindows(): List<SpaceWindow> {
    val response = grpc.unary("cua.env.v1.StreamService/ListTargets",
        ListTargetsRequest(include_windows = true), ListTargetsResponse.ADAPTER)
    return response.targets.mapNotNull { target ->
        val info = target.window ?: return@mapNotNull null
        info.toSpaceWindow(target.available, target.limitation.ifBlank { null })
    }.filter { window ->
        window.width >= MIN_WINDOW_POINTS && window.height >= MIN_WINDOW_POINTS
    }.sortedByDescending { it.zOrder }
}

/** A small JPEG of [window] alone, even when other windows cover it; null when it cannot be captured. */
suspend fun SpaceClient.windowThumbnail(window: SpaceWindow, maxDimension: Int = 320): ByteArray? = runCatching {
    grpc.unary("cua.env.v1.ComputerService/Screenshot", ScreenshotRequest(
        window = window.ref, format = ImageFormat.IMAGE_FORMAT_JPEG, quality = 70, max_dimension = maxDimension,
    ), ScreenshotResponse.ADAPTER).image.toByteArray().takeIf { it.isNotEmpty() }
}.getOrNull()

/** Brings a minimized [window] back. */
suspend fun SpaceClient.restoreWindow(window: SpaceWindow) {
    grpc.unary("cua.env.v1.WindowsService/RestoreWindow", RestoreWindowRequest(window = window.ref), RestoreWindowResponse.ADAPTER)
}

/** Raises [window] and gives it focus, switching to its desktop when needed. */
suspend fun SpaceClient.activateWindow(window: SpaceWindow) {
    grpc.unary("cua.env.v1.WindowsService/ActivateWindow", ActivateWindowRequest(window = window.ref), ActivateWindowResponse.ADAPTER)
}

/** Resizes [window] to [width]×[height] points, keeping its position; returns the size it took. */
suspend fun SpaceClient.resizeWindow(window: SpaceWindow, width: Double, height: Double): Pair<Double, Double>? {
    val response = grpc.unary("cua.env.v1.WindowsService/SetWindowBounds",
        SetWindowBoundsRequest(window = window.ref, width = width, height = height), SetWindowBoundsResponse.ADAPTER)
    return response.window?.bounds?.let { it.width to it.height }
}

private fun WindowInfo.toSpaceWindow(available: Boolean, limitation: String?): SpaceWindow? {
    val ref = ref ?: return null
    if (kind != WindowKind.WINDOW_KIND_STANDARD && kind != WindowKind.WINDOW_KIND_UNSPECIFIED) return null
    return SpaceWindow(
        id = ref.id,
        epoch = ref.epoch,
        app = app?.name.orEmpty(),
        appId = app?.app_id.orEmpty(),
        pid = app?.pid ?: 0,
        title = title,
        x = bounds?.x ?: 0.0,
        y = bounds?.y ?: 0.0,
        width = bounds?.width ?: 0.0,
        height = bounds?.height ?: 0.0,
        state = when (state) {
            WindowState.WINDOW_STATE_MINIMIZED -> SpaceWindow.State.MINIMIZED
            WindowState.WINDOW_STATE_HIDDEN -> SpaceWindow.State.HIDDEN
            else -> SpaceWindow.State.NORMAL
        },
        available = available,
        limitation = limitation,
        focused = focused,
        zOrder = z_order,
    )
}

private const val MIN_WINDOW_POINTS = 120.0
