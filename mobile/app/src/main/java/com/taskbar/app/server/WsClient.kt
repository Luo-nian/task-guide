package com.taskbar.app.server

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import java.util.concurrent.locks.ReentrantLock

/**
 * v5.31.0：极简 WebSocket **客户端**（RFC 6455，只做我们用到的那一小块）。
 *
 * 为什么手写：项目只有 `ktor-server-*`，没有 ktor-client / okhttp；沙箱离线编译加不了新依赖。
 * 而客户端模式只需要「收文本帧 + 回 pong」这一件事 —— 130 行足够，且零依赖风险。
 *
 * 用途：手机当客户端时连服务器的 `/ws?token=<base64(master)>`，
 * 服务器把 ChangeBus 上的每条变更实时推过来（电脑端走的就是同一条通道）→
 * **有变化才同步，平时零请求**，比 8 秒轮询省电且更快。
 */
internal class WsClient(
    host: String,
    port: Int,
    token: String,
    private val onText: (String) -> Unit
) : AutoCloseable {

    private val socket = Socket()
    private val out: OutputStream
    private val input: InputStream
    private val writeLock = ReentrantLock()
    @Volatile private var closed = false

    init {
        socket.tcpNoDelay = true
        socket.soTimeout = 0                 // 阻塞读；靠 close() 打断
        socket.connect(InetSocketAddress(host, port), 6000)
        out = socket.getOutputStream()
        input = socket.getInputStream()
        handshake(token)
    }

    private fun handshake(token: String) {
        val key = Base64.getEncoder().encodeToString(TbCrypto.randBytes(16))
        val hostHeader = (socket.inetAddress?.hostAddress ?: "server") + ":" + socket.port
        val req = buildString {
            append("GET /ws?token=$token HTTP/1.1\r\n")
            append("Host: $hostHeader\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: $key\r\n")
            append("Sec-WebSocket-Version: 13\r\n\r\n")
        }
        out.write(req.toByteArray())
        out.flush()
        // 读响应头（到空行为止）
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) throw IllegalStateException("握手失败：连接被关闭")
            sb.append(b.toChar())
            if (sb.length > 8192) throw IllegalStateException("握手响应过大")
        }
        if (!sb.startsWith("HTTP/1.1 101")) {
            throw IllegalStateException("握手被拒：" + sb.lineSequence().firstOrNull())
        }
    }

    /** 阻塞读循环：直到连接关闭或出错 */
    fun readLoop() {
        try {
            while (!closed) {
                val frame = readFrame() ?: break
                when (frame.opcode) {
                    0x1 -> onText(frame.payload.toString(Charsets.UTF_8))
                    0x8 -> break                                  // close
                    0x9 -> sendFrame(0xA, frame.payload)          // ping → pong
                    else -> { /* pong / binary：忽略 */ }
                }
            }
        } catch (_: Exception) {
        } finally {
            close()
        }
    }

    // ==================== 帧读写 ====================

    private class Frame(val opcode: Int, val payload: ByteArray)

    private fun readFrame(): Frame? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.read()
        if (b1 < 0) return null
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var len: Long = (b1 and 0x7F).toLong()
        if (len == 126L) {
            len = ((input.read() shl 8) or input.read()).toLong()
        } else if (len == 127L) {
            len = 0
            repeat(8) { len = (len shl 8) or input.read().toLong() }
        }
        val mask = ByteArray(4)
        if (masked) readFully(mask)
        val payload = ByteArray(len.toInt())
        readFully(payload)
        if (masked) {
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        return Frame(opcode, payload)
    }

    private fun readFully(buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw IllegalStateException("连接中断")
            off += n
        }
    }

    /** 发文本帧（客户端必须 mask） */
    fun sendText(text: String) = sendFrame(0x1, text.toByteArray(Charsets.UTF_8))

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        if (closed) return
        writeLock.lock()
        try {
            val head = ArrayList<Byte>(14)
            head.add((0x80 or opcode).toByte())          // FIN + opcode
            val n = payload.size
            when {
                n < 126 -> head.add((0x80 or n).toByte())            // MASK + len
                n < 65536 -> {
                    head.add((0x80 or 126).toByte())
                    head.add((n shr 8).toByte()); head.add(n.toByte())
                }
                else -> {
                    head.add((0x80 or 127).toByte())
                    for (i in 7 downTo 0) head.add(((n.toLong() shr (8 * i)) and 0xFF).toByte())
                }
            }
            val mask = TbCrypto.randBytes(4)
            head.addAll(mask.toList())
            val masked = ByteArray(n) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
            out.write(head.toByteArray())
            out.write(masked)
            out.flush()
        } catch (_: Exception) {
            close()
        } finally {
            writeLock.unlock()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
    }
}
