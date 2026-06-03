package com.bominwell.cctvsdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Locale

/**
 * VISCA 传输层抽象。
 * 机芯本身是 VISCA 协议，实际项目可能通过串口转网口、HTTP 网关、编码板透传等方式发送。
 */
interface ViscaTransport {
    suspend fun send(command: ByteArray, waitReply: Boolean = true): ByteArray
}

/**
 * 原始 TCP 透传 VISCA。
 * 如果你的硬件是“串口转 TCP”，通常只需要改 host/port。
 */
class TcpViscaTransport(
    private val host: String,
    private val port: Int,
    private val timeoutMs: Int = 1500
) : ViscaTransport {
    override suspend fun send(command: ByteArray, waitReply: Boolean): ByteArray = withContext(Dispatchers.IO) {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(host, port), timeoutMs)

            BufferedOutputStream(socket.getOutputStream()).use { out ->
                out.write(command)
                out.flush()

                if (!waitReply) return@withContext ByteArray(0)

                val input = BufferedInputStream(socket.getInputStream())
                val first = readViscaPacket(input)
                val isInquiry = command.size > 1 && command[1].toInt() == 0x09
                if (isInquiry) {
                    first
                } else {
                    // 普通命令通常返回 ACK + Completion。部分网关只回一个包，所以第二包超时不作为失败。
                    try {
                        first + readViscaPacket(input)
                    } catch (_: Exception) {
                        first
                    }
                }
            }
        }
    }

    private fun readViscaPacket(input: BufferedInputStream): ByteArray {
        val data = ArrayList<Byte>()
        while (true) {
            val value = input.read()
            if (value < 0) break
            data.add(value.toByte())
            if (value == 0xFF) break
        }
        return data.toByteArray()
    }
}

/**
 * HTTP 透传 VISCA。
 * 默认以 POST hex=8101040702FF 的方式发给 endpoint；如你的编码板接口不同，只改这里。
 */
class HttpViscaTransport(
    private val endpoint: String,
    private val timeoutMs: Int = 2000
) : ViscaTransport {
    override suspend fun send(command: ByteArray, waitReply: Boolean): ByteArray = withContext(Dispatchers.IO) {
        val body = "hex=${command.toHexString()}"
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        text.hexToByteArrayOrEmpty()
    }
}

fun ByteArray.toHexString(separator: String = ""): String =
    joinToString(separator) { "%02X".format(Locale.US, it.toInt() and 0xFF) }

fun String.hexToByteArrayOrEmpty(): ByteArray {
    val clean = replace("0x", "", ignoreCase = true)
        .replace(Regex("[^0-9A-Fa-f]"), "")
    if (clean.length < 2 || clean.length % 2 != 0) return ByteArray(0)
    return ByteArray(clean.length / 2) { index ->
        clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
