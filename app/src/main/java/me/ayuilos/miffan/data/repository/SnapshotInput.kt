package me.ayuilos.miffan.data.repository

import me.rerere.workspace.screen.RfbKeys
import kotlin.math.hypot

/** One cua-driver action of the snapshot screen, in the latest snapshot's pixels. */
internal sealed interface SnapshotAction {
    data class Click(val x: Int, val y: Int, val button: String = "left", val count: Int = 1) : SnapshotAction
    data class Drag(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int) : SnapshotAction
    data class Scroll(val x: Int, val y: Int, val direction: String, val amount: Int = 1) : SnapshotAction
    data class Key(val name: String, val modifiers: List<String> = emptyList()) : SnapshotAction
    data class Type(val text: String) : SnapshotAction
    data class Clipboard(val text: String) : SnapshotAction
}

/** A press and release this far apart, in snapshot pixels, is a drag rather than a click. */
private const val DRAG_SLOP = 12.0
private const val MAX_CLICKS = 3
private const val MAX_SCROLL = 50

/**
 * Turns the screen page's VNC-style pointer and key events into cua-driver actions for the snapshot
 * screen: a left press and release is a click (or a drag when the pointer travelled), the wheel bits
 * are scrolls at the pointer, and a key pressed with held modifiers is a hotkey. Pointer moves alone
 * send nothing: the picture is still, so there is nothing to hover.
 */
internal class SnapshotInput(private val macos: Boolean, private val windows: Boolean) {
    private var buttons = 0
    private var downX = 0
    private var downY = 0
    private val held = linkedSetOf<String>()

    fun pointer(x: Int, y: Int, next: Int): List<SnapshotAction> {
        val pressed = next and buttons.inv()
        val released = buttons and next.inv()
        buttons = next
        val out = mutableListOf<SnapshotAction>()
        WHEEL.forEach { (mask, direction) -> if (pressed and mask != 0) out += SnapshotAction.Scroll(x, y, direction) }
        if (pressed and LEFT != 0) { downX = x; downY = y }
        if (released and LEFT != 0) out += if (hypot((x - downX).toDouble(), (y - downY).toDouble()) > DRAG_SLOP) {
            SnapshotAction.Drag(downX, downY, x, y)
        } else SnapshotAction.Click(downX, downY)
        if (released and RIGHT != 0) out += SnapshotAction.Click(x, y, "right")
        if (released and MIDDLE != 0) out += SnapshotAction.Click(x, y, "middle")
        return out
    }

    fun key(keysym: Int, down: Boolean): SnapshotAction? {
        modifierName(keysym)?.let { name ->
            if (down) held += name else held -= name
            return null
        }
        if (!down) return null
        val name = keyName(keysym) ?: return null
        return SnapshotAction.Key(name, held.toList())
    }

    private fun modifierName(keysym: Int): String? = when (keysym) {
        RfbKeys.SHIFT_L, 0xFFE2 -> "shift"
        RfbKeys.CONTROL_L, 0xFFE4 -> "ctrl"
        RfbKeys.ALT_L, 0xFFEA -> if (macos) "option" else "alt"
        RfbKeys.META_L, 0xFFE8, RfbKeys.SUPER_L, 0xFFEC -> when {
            macos -> "cmd"
            windows -> "win"
            else -> "super"
        }
        else -> null
    }

    private fun keyName(keysym: Int): String? = when (keysym) {
        RfbKeys.RETURN, 0xFF8D -> "return"
        RfbKeys.TAB -> "tab"
        RfbKeys.ESCAPE -> "escape"
        RfbKeys.BACKSPACE -> "backspace"
        // A Mac's "delete" is the backspace key; its forward delete has its own name.
        RfbKeys.DELETE -> if (macos) "forward_delete" else "delete"
        RfbKeys.HOME -> "home"
        RfbKeys.END -> "end"
        RfbKeys.LEFT -> "left"
        RfbKeys.RIGHT -> "right"
        RfbKeys.UP -> "up"
        RfbKeys.DOWN -> "down"
        RfbKeys.PAGE_UP -> "pageup"
        RfbKeys.PAGE_DOWN -> "pagedown"
        0x20 -> "space"
        in RfbKeys.F1..RfbKeys.F1 + 11 -> "f${keysym - RfbKeys.F1 + 1}"
        in 0x21..0x7E -> keysym.toChar().lowercaseChar().toString()
        else -> null
    }

    companion object {
        private const val LEFT = 1
        private const val MIDDLE = 2
        private const val RIGHT = 4
        private val WHEEL = listOf(8 to "up", 16 to "down", 32 to "left", 64 to "right")

        /**
         * Folds what queued up while the previous action ran: clicks on one spot become a double or
         * triple click, and wheel steps in one direction one longer scroll.
         */
        fun coalesce(actions: List<SnapshotAction>): List<SnapshotAction> {
            val out = mutableListOf<SnapshotAction>()
            for (action in actions) {
                val last = out.lastOrNull()
                when {
                    action is SnapshotAction.Click && last is SnapshotAction.Click && last.button == action.button &&
                        last.count < MAX_CLICKS && hypot((action.x - last.x).toDouble(), (action.y - last.y).toDouble()) <= DRAG_SLOP ->
                        out[out.lastIndex] = last.copy(count = last.count + 1)
                    action is SnapshotAction.Scroll && last is SnapshotAction.Scroll && last.direction == action.direction &&
                        last.amount < MAX_SCROLL ->
                        out[out.lastIndex] = last.copy(amount = minOf(MAX_SCROLL, last.amount + action.amount))
                    else -> out += action
                }
            }
            return out
        }
    }
}
