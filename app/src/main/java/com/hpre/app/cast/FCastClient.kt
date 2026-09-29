package com.hpre.app.cast

import org.json.JSONObject
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal FCast protocol v2 sender. Frame = u32 little-endian size (opcode included) + u8 opcode +
 * optional UTF-8 JSON body. Receivers expose TCP port 46899; FCast is the open alternative to the
 * proprietary Google Cast SDK this app deliberately does not depend on.
 */
class FCastClient(
    private val host: String,
    private val port: Int = DEFAULT_PORT,
    private val connectTimeoutMs: Int = 4_000
) {
    private var socket: Socket? = null
    private var out: OutputStream? = null

    val isConnected: Boolean
        get() = socket?.isConnected == true && socket?.isClosed == false

    /** Opens the socket and announces protocol version 2. Throws on failure. */
    fun connect() {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        s.soTimeout = 0
        socket = s
        out = s.getOutputStream()
        sendMessage(OPCODE_VERSION, """{"version":2}""")
    }

    fun play(container: String, url: String, timeSeconds: Double? = null, speed: Double? = null) {
        val body = JSONObject()
            .put("container", container)
            .put("url", url)
        if (timeSeconds != null) body.put("time", timeSeconds)
        if (speed != null) body.put("speed", speed)
        sendMessage(OPCODE_PLAY, body.toString())
    }

    fun pause() = sendMessage(OPCODE_PAUSE, null)
    fun resume() = sendMessage(OPCODE_RESUME, null)
    fun stop() = sendMessage(OPCODE_STOP, null)

    fun seek(timeSeconds: Double) =
        sendMessage(OPCODE_SEEK, JSONObject().put("time", timeSeconds).toString())

    fun setVolume(volume: Double) =
        sendMessage(OPCODE_SET_VOLUME, JSONObject().put("volume", volume).toString())

    fun close() {
        runCatching { socket?.close() }
        socket = null
        out = null
    }

    private fun sendMessage(opcode: Int, bodyJson: String?) {
        val stream = out ?: throw IllegalStateException("Not connected")
        stream.write(encodePacket(opcode, bodyJson))
        stream.flush()
    }

    companion object {
        const val DEFAULT_PORT = 46899
        const val OPCODE_PLAY = 1
        const val OPCODE_PAUSE = 2
        const val OPCODE_RESUME = 3
        const val OPCODE_STOP = 4
        const val OPCODE_SEEK = 5
        const val OPCODE_SET_VOLUME = 8
        const val OPCODE_VERSION = 11
        const val MAX_PACKET_BYTES = 32_000

        /** Wire format: [size u32 LE][opcode u8][body bytes], size counts opcode + body. */
        fun encodePacket(opcode: Int, bodyJson: String?): ByteArray {
            val body = bodyJson?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            val size = 1 + body.size
            require(size <= MAX_PACKET_BYTES) { "FCast packet too large: $size" }
            val buffer = ByteBuffer.allocate(4 + size).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(size)
            buffer.put(opcode.toByte())
            buffer.put(body)
            return buffer.array()
        }
    }
}
