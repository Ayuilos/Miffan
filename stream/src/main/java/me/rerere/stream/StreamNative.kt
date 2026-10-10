package me.rerere.stream

internal object StreamNative {
    init { System.loadLibrary("miffanstream") }
    external fun identity(): Array<String>
    external fun http(fd: Int, certificate: String, key: String, pin: String, request: ByteArray, timeoutMillis: Int): ByteArray
    external fun run(session: StreamSession, address: String, version: String, gfe: String,
        codecSupport: Int, url: String, width: Int, height: Int, fps: Int, bitrate: Int, formats: Int,
        key: ByteArray, iv: ByteArray): Int
    external fun interrupt()
    external fun input(kind: Int, a: Int, b: Int, c: Int, d: Int): Int
    external fun idr()
    external fun networkStats(): LongArray
}
