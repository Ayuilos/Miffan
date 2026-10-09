package com.jcraft.jsch

/**
 * JSch only exposes channel flow-control sizes inside its own package. Its forwarding channels
 * open with a 128 KiB receive window, which caps a stream at window / round-trip time (about
 * 4 MB/s over Tailscale); screen streams need several times that.
 */
object ChannelWindow {
    /** Must be called after `openChannel` and before `connect`, which advertises these sizes. */
    fun widen(channel: Channel, windowBytes: Int, packetBytes: Int) {
        channel.setLocalWindowSizeMax(windowBytes)
        channel.setLocalWindowSize(windowBytes)
        channel.setLocalPacketSize(packetBytes)
    }
}
