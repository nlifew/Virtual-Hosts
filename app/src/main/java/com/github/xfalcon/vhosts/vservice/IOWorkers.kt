package com.github.xfalcon.vhosts.vservice

import android.util.Log
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val TAG = "IOWorkers"

class IOWorkers(
    private val fd: FileDescriptor,
    private val protector: IProtector,
): AutoCloseable {

    private val outboundsThread = thread(start = false) {
        dispatchOutboundsPacket()
    }

    private val inboundsChannel = FileOutputStream(fd).channel
    private val udpRelay = UdpRelay(protector, inboundsChannel)
    private val udpRelayThread = Thread(udpRelay)

    fun start() {
        outboundsThread.start()
        udpRelayThread.start()
    }

    private fun dispatchOutboundsPacket() {
        val outboundsChannel = FileInputStream(fd).channel

        try {
            while (!Thread.interrupted()) {
                val byteBuf = ByteBufferPool.acquire()
                val bytes = outboundsChannel.read(byteBuf)
                Log.d(TAG, "dispatchOutboundsPacket: read $bytes bytes")

                if (bytes < 0) {
                    ByteBufferPool.release(byteBuf)
                    break
                }
                if (bytes == 0) {
                    ByteBufferPool.release(byteBuf)
                    continue
                }
                handleOutboundsPacket(byteBuf)
            }
        } catch (e: IOException) {
            Log.e(TAG, "dispatchOutboundsPacket: ", e)
        }

        Log.i(TAG, "dispatchOutboundsPacket: dead")
    }

    private fun handleOutboundsPacket(byteBuffer: ByteBuffer) {
        byteBuffer.flip()
        val packet = Packet(byteBuffer)
        Log.d(TAG, "handleOutboundsPacket: " +
                "recv ip packet: udp:'${packet.isUDP}', tcp:'${packet.isTCP}'"
        )

        when {
            packet.isUDP && fakeDns(packet) -> {
                inboundsChannel.write(packet.backingBuffer)
                ByteBufferPool.release(packet.backingBuffer)
            }
            packet.isUDP -> {
                udpRelay.outbounds(packet)
            }
            else -> {
                ByteBufferPool.release(packet.backingBuffer)
            }
        }
    }

    private fun fakeDns(packet: Packet): Boolean {
        if (packet.udpHeader.destinationPort != 53) {
            return false
        }
        val resp = DnsChange.handle_dns_packet(packet)
        return resp != null
    }

    override fun close() {
        outboundsThread.interrupt() // FIXME 对 BIO 无效
        udpRelayThread.interrupt()
    }
}
