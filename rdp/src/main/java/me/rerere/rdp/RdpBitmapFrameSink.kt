package me.rerere.rdp

import android.graphics.Bitmap
import me.rerere.workspace.screen.RemoteScreenFrameSink

/** Optional RDP-only fast path. Other sinks and VNC retain onPixels(IntArray).
 *
 * Both callbacks run synchronously on the RDP worker, after the frame ACK when enabled.
 * acquireBitmap may return null to use onPixels. A non-null bitmap must be mutable,
 * unrecycled ARGB_8888 at the current onSize dimensions. Keep it alive and prevent
 * resize/recycle until releaseBitmap; every non-null acquisition is released exactly once.
 * releaseBitmap only releases ownership; normal onFrameComplete publishes frameVersion
 * after native pixels have been unlocked. Never publish a partially written bitmap there.
 */
interface RdpBitmapFrameSink : RemoteScreenFrameSink {
    fun acquireBitmap(width: Int, height: Int): Bitmap?
    fun releaseBitmap(bitmap: Bitmap)
}
