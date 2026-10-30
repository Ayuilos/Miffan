package me.ayuilos.miffan.data.ai.computer

/**
 * The sizes a partner's own computer comes in. They are limits, not reservations: a Space uses only
 * what it needs, but may grow up to these.
 */
enum class SpaceTier(val cpus: Int, val memoryMb: Int) {
    LIGHT(2, 2048),
    STANDARD(2, 4096),
    PERFORMANCE(4, 8192);

    companion object {
        /** Used when nothing is known about the host. */
        val DEFAULT = STANDARD

        /** The tier matching a Space's recorded limits; null for sizes the app did not set. */
        fun of(cpus: Int?, memoryMb: Int?): SpaceTier? = entries.find { it.cpus == cpus && it.memoryMb == memoryMb }
    }
}

/**
 * What a host can give its Spaces. On Linux the Spaces share the host itself: the recommendation keeps
 * half of its memory (at least 4 GB) with the user, while a size the user picks only has to leave 4 GB
 * ([memoryMb] vs [limitMemoryMb]); limits are not reservations, so a bigger pick is fine. A Space may use
 * up to half of the CPUs (at least two). On macOS every Space runs in cua's one Linux VM, which is the
 * whole budget either way (less 1 GB for the VM itself); the Mac keeps whatever the VM does not take.
 */
data class SpaceCapacity(val cpus: Int, val memoryMb: Int, val limitMemoryMb: Int = memoryMb) {
    /** Whether the user may pick [tier] next to Spaces already holding [usedMemoryMb], for [count] new ones. */
    fun fits(tier: SpaceTier, usedMemoryMb: Int = 0, count: Int = 1): Boolean =
        tier.cpus <= cpus && tier.memoryMb * count.coerceAtLeast(1) <= limitMemoryMb - usedMemoryMb

    /** The largest tier that fits comfortably, else [SpaceTier.LIGHT] (it still runs, only tighter). */
    fun recommend(usedMemoryMb: Int = 0, count: Int = 1): SpaceTier = SpaceTier.entries.lastOrNull {
        it.cpus <= cpus && it.memoryMb * count.coerceAtLeast(1) <= memoryMb - usedMemoryMb
    } ?: SpaceTier.LIGHT

    companion object {
        private const val USER_RESERVE_MB = 4096
        private const val VM_OVERHEAD_MB = 1024

        /** Null when the helper could not read the host's size. */
        fun from(host: CuaSpaceProbe.Host?): SpaceCapacity? {
            host ?: return null
            host.runtime?.let { vm ->
                return SpaceCapacity(cpus = vm.cpus, memoryMb = vm.memoryMb - VM_OVERHEAD_MB)
            }
            val cpus = host.cpus ?: return null
            val memory = host.memoryMb ?: return null
            // CPU limits are shares of time, not reservations: even a two-core host can let a Space use both.
            return SpaceCapacity(cpus = maxOf(2, cpus / 2), memoryMb = memory - maxOf(USER_RESERVE_MB, memory / 2),
                limitMemoryMb = memory - USER_RESERVE_MB)
        }
    }
}
