package com.hpre.app.cast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FCastClientTest {

    @Test
    fun `packet without body has size 1 and opcode only`() {
        val packet = FCastClient.encodePacket(FCastClient.OPCODE_PAUSE, null)
        assertEquals(5, packet.size)
        assertEquals(1, ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(FCastClient.OPCODE_PAUSE.toByte(), packet[4])
    }

    @Test
    fun `packet size counts opcode plus utf8 body`() {
        val body = """{"url":"https://v/1","container":"video/mp4"}"""
        val packet = FCastClient.encodePacket(FCastClient.OPCODE_PLAY, body)
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        assertEquals(5 + bodyBytes.size, packet.size)
        assertEquals(1 + bodyBytes.size, ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(FCastClient.OPCODE_PLAY.toByte(), packet[4])
        assertArrayEquals(bodyBytes, packet.copyOfRange(5, packet.size))
    }

    @Test
    fun `oversized packet is rejected`() {
        val huge = "x".repeat(FCastClient.MAX_PACKET_BYTES)
        assertThrows(IllegalArgumentException::class.java) {
            FCastClient.encodePacket(FCastClient.OPCODE_PLAY, huge)
        }
    }
}
