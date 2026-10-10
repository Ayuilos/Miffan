package me.ayuilos.miffan.data.repository

import me.rerere.stream.StreamMouseButton

/** X11 physical keysyms to Windows VK; composed text goes through the SSH clipboard. */
internal object StreamKeys {
    fun virtualKey(sym: Int): Int? = when (sym) {
        in 'a'.code..'z'.code -> sym - 32
        in 'A'.code..'Z'.code, in '0'.code..'9'.code -> sym
        in 0xffbe..0xffd5 -> 0x70 + sym - 0xffbe // F1–F24
        in 0xffb0..0xffb9 -> 0x60 + sym - 0xffb0 // keypad digits
        0xff08 -> 0x08; 0xff09, 0xfe20 -> 0x09; 0xff0d -> 0x0d; 0xff1b -> 0x1b
        0xff13 -> 0x13; 0xff14 -> 0x91; 0xff61, 0xff15 -> 0x2c; 0xff67 -> 0x5d
        0xffff, 0xff9f -> 0x2e; 0xff63, 0xff9e -> 0x2d
        0xff50, 0xff95 -> 0x24; 0xff51, 0xff96 -> 0x25; 0xff52, 0xff97 -> 0x26
        0xff53, 0xff98 -> 0x27; 0xff54, 0xff99 -> 0x28; 0xff55, 0xff9a -> 0x21
        0xff56, 0xff9b -> 0x22; 0xff57, 0xff9c -> 0x23; 0xff58, 0xff9d -> 0x0c
        0xffe1 -> 0xa0; 0xffe2 -> 0xa1; 0xffe3 -> 0xa2; 0xffe4 -> 0xa3
        0xffe7, 0xffeb -> 0x5b; 0xffe8, 0xffec -> 0x5c; 0xffe9 -> 0xa4; 0xffea -> 0xa5
        0xffe5, 0xffe6 -> 0x14; 0xff7f -> 0x90
        0xff80, ' '.code -> 0x20; 0xff89 -> 0x09; 0xff8d -> 0x0d
        0xffaa -> 0x6a; 0xffab -> 0x6b; 0xffac -> 0x6c; 0xffad -> 0x6d
        0xffae -> 0x6e; 0xffaf -> 0x6f; 0xffbd -> 0xbb
        '-'.code, '_'.code -> 0xbd; '='.code, '+'.code -> 0xbb
        '['.code, '{'.code -> 0xdb; ']'.code, '}'.code -> 0xdd
        ';'.code, ':'.code -> 0xba; '\''.code, '"'.code -> 0xde
        '`'.code, '~'.code -> 0xc0; '\\'.code, '|'.code -> 0xdc
        ','.code, '<'.code -> 0xbc; '.'.code, '>'.code -> 0xbe; '/'.code, '?'.code -> 0xbf
        '!'.code -> 0x31; '@'.code -> 0x32; '#'.code -> 0x33; '$'.code -> 0x34
        '%'.code -> 0x35; '^'.code -> 0x36; '&'.code -> 0x37; '*'.code -> 0x38
        '('.code -> 0x39; ')'.code -> 0x30
        else -> null
    }
    fun modifier(vk: Int): Int = when (vk) {
        0xa0, 0xa1 -> 1; 0xa2, 0xa3 -> 2; 0xa4, 0xa5 -> 4; 0x5b, 0x5c -> 8
        else -> 0
    }
    fun extended(sym: Int) = sym in setOf(0xffe4, 0xffea, 0xffe7, 0xffe8, 0xffeb, 0xffec,
        0xff50, 0xff51, 0xff52, 0xff53, 0xff54, 0xff55, 0xff56, 0xff57,
        0xff63, 0xffff, 0xff67, 0xff61, 0xffaf, 0xff8d)
}

internal class StreamDesktopInput(
    private val position: (Int, Int, Int, Int) -> Unit,
    private val button: (StreamMouseButton, Boolean) -> Unit,
    private val scroll: (Int, Int) -> Unit,
    private val keyboard: (Int, Boolean, Int) -> Unit,
) {
    private var buttons = 0
    private val held = mutableSetOf<Int>()
    @Synchronized fun pointer(x: Int, y: Int, mask: Int, size: RemoteVideoSize) {
        position(x, y, size.width, size.height)
        for ((bit, kind) in listOf(1 to StreamMouseButton.LEFT, 2 to StreamMouseButton.MIDDLE, 4 to StreamMouseButton.RIGHT))
            if ((buttons xor mask) and bit != 0) button(kind, mask and bit != 0)
        val rising = mask and buttons.inv()
        val vertical = (if (rising and 8 != 0) 120 else 0) - (if (rising and 16 != 0) 120 else 0)
        val horizontal = (if (rising and 64 != 0) 120 else 0) - (if (rising and 32 != 0) 120 else 0)
        if (vertical != 0 || horizontal != 0) scroll(vertical, horizontal)
        buttons = mask
    }
    @Synchronized fun key(sym: Int, down: Boolean) {
        val vk = StreamKeys.virtualKey(sym) ?: return
        if (down) held.add(vk) else held.remove(vk)
        val mods = held.fold(0) { value, key -> value or StreamKeys.modifier(key) }
        keyboard(vk, down, mods or if (StreamKeys.extended(sym)) 16 else 0)
    }
    /** Keep the user's physical modifiers held while sending a plain Ctrl+V. */
    @Synchronized fun paste() {
        val previous = held.toSet()
        val modifiers = previous.filter { StreamKeys.modifier(it) != 0 }
        modifiers.forEach { keyboard(it, false, if (it in setOf(0xa3, 0xa5, 0x5b, 0x5c)) 16 else 0) }
        keyboard(0xa2, true, 2); keyboard(0x56, true, 2)
        keyboard(0x56, false, 2); keyboard(0xa2, false, 0)
        var mods = 0
        modifiers.forEach { mods = mods or StreamKeys.modifier(it); keyboard(it, true, mods or if (it in setOf(0xa3, 0xa5, 0x5b, 0x5c)) 16 else 0) }
    }
}
