package com.github.xfalcon.vhosts.vservice

import android.util.Log
import com.github.xfalcon.vhosts.util.safeClose
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.WritableByteChannel
import java.util.concurrent.ConcurrentLinkedQueue

private const val TAG = "UdpRelay"

private data class ChannelKey(
    @JvmField val destIp: String,
    @JvmField val destPort: Int,
    @JvmField val srcPort: Int
) {
    constructor(udpPacket: Packet): this(
        udpPacket.ipHeader.destinationAddress.hostAddress ?: "",
        udpPacket.udpHeader.destinationPort,
        udpPacket.udpHeader.sourcePort,
    )
}

class UdpRelay(
    private val protector: IProtector,
    private val inbounds: WritableByteChannel,
): Runnable, AutoCloseable {

    private val selector = Selector.open()
    private val waitingOutboundsQueue = ConcurrentLinkedQueue<Packet>()

    @Volatile
    private var workerThread: Thread? = null

    private val channelCache = LRUCache<ChannelKey, SelectionKey>(50) {
        close(it.value)
    }

    fun outbounds(packet: Packet) {
        waitingOutboundsQueue.add(packet)
        selector.wakeup()
        Log.d(TAG, "outbounds: waiting queue size: ${waitingOutboundsQueue.size}")
    }

    override fun run() {
        workerThread = Thread.currentThread()
        try {
            while (true) {
                workerThread?.checkInterrupted()
                val inN = selector.select()
                Log.d(TAG, "run: udp worker thread wake up.")
                workerThread?.checkInterrupted()
                if (inN > 0) {
                    val keySet = selector.selectedKeys()
                    keySet.forEach { handleInboundsPacket(it) }
                    keySet.clear()
                }
                val outN = waitingOutboundsQueue.size
                for (i in 0 until outN) {
                    handleOutboundsPacket(waitingOutboundsQueue.remove())
                }
            }
        } catch (e: InterruptedException) {
            Log.d(TAG, "run: thread interrupted")
        } catch (e: IOException) {
            Log.e(TAG, "run: IOException !", e)
        }

        selector.close()
        channelCache.forEach { close(it.value) }
        channelCache.clear()

        Log.i(TAG, "run: dead")
    }

    private fun handleInboundsPacket(key: SelectionKey) {
        if (!key.isValid) {
            // channel 被关闭了，或者 selector 被关闭了
            // 可能存在这种情况: LruCache 移出了一个 channel，随后这个 Channel 被关闭，然后走进这里的逻辑，二次关闭 ?
            close(key)
            return
        }
        if (!key.isReadable) {
            return
        }
        val receiveBuffer = ByteBufferPool.acquire()
        val inputChannel = key.channel() as DatagramChannel
        val referencePacket = key.attachment() as Packet

        // 手动 touch 一下 LruCache. 由于是入站包，src 和 dest 是反的
        ChannelKey(
            referencePacket.ipHeader.sourceAddress.hostAddress ?: "",
            referencePacket.udpHeader.sourcePort,
            referencePacket.udpHeader.destinationPort
        ).let {
            channelCache[it]
        }

        receiveBuffer.position(referencePacket.IP_TRAN_SIZE)
        var readBytes = 0
        try {
            readBytes = inputChannel.read(receiveBuffer)
        } catch (e: IOException) {
            Log.e(TAG, "handleInboundsPacket: Network read error", e)
        }

        Log.d(TAG, "handleInboundsPacket: <<<<<<< " +
                "${inputChannel.remoteAddress}: $readBytes")

        referencePacket.updateUDPBuffer(receiveBuffer, readBytes)
        receiveBuffer.position(referencePacket.IP_TRAN_SIZE + readBytes)

        receiveBuffer.flip()
        try {
            inbounds.write(receiveBuffer)
        } finally {
            ByteBufferPool.release(receiveBuffer)
        }
    }

    private fun handleOutboundsPacket(packet: Packet) {
        Log.d(TAG, "handleOutboundsPacket: here")

        // TODO 处理 dns 包
        // 找到对应的 channel。找不到则创建一个新的 udp channel
        val channel = getOrCreateChannel(packet)
        // 往里面写入数据
        if (channel != null) {
            Log.d(TAG, "handleOutboundsPacket: >>>>>>> " +
                    "${channel.remoteAddress}: ${packet.backingBuffer.limit()}")
            
            try {
                while (packet.backingBuffer.hasRemaining()) {
                    channel.write(packet.backingBuffer)
                }
            } catch (e: IOException) {
                Log.e(TAG, "handleOutboundsPacket: " +
                        "failed to write content to '${channel.remoteAddress}'", e
                )
                channelCache.remove(ChannelKey(packet))?.let { close(it) }
            }
        }
        ByteBufferPool.release(packet.backingBuffer)
    }

    private fun getOrCreateChannel(packet: Packet): DatagramChannel? {
        val key = ChannelKey(packet)
        channelCache[key]?.let { return it.channel() as DatagramChannel }

        val channel = DatagramChannel.open()

        try {
            check(protector.protect(channel.socket()))
            channel.connect(InetSocketAddress(key.destIp, key.destPort))
        } catch (e: Exception) {
            Log.e(TAG, "newOutboundsPacket: failed to connect udp channel '$key'", e)
            channel.safeClose()
            return null
        }
        channel.configureBlocking(false)
        packet.swapSourceAndDestination()

        channelCache[key] = channel.register(selector, SelectionKey.OP_READ, packet)
        return channel
    }


    private fun close(key: SelectionKey) {
        // 已经关闭过了
        val buffer = (key.attachment() as? Packet)?.backingBuffer ?: return
        key.attach(null)

        if (key.isValid) { // TODO 作用存疑
            key.cancel()
        }
        val channel = key.channel() as DatagramChannel
        channel.safeClose()
        ByteBufferPool.release(buffer)
    }

    private fun Thread.checkInterrupted() {
        // Thread.interrupted() 和 Thread.isInterrupted() 的区别在于
        // 后者可反复调用，不会在第一次调用以后就 clear 掉相关标志位
        if (isInterrupted) {
            throw InterruptedException("Thread '$name' is interrupted !")
        }
    }

    override fun close() {
        // 让工作线程自己清理
        workerThread?.interrupt()
        workerThread = null
    }
}