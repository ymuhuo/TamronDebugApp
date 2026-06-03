package com.bominwell.cctvsdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 编码板/RTSP 服务端参数控制。
 * 注意：MP3010M-EV 机芯主要是 VISCA + HDMI/LVDS 输出，码率、定码率、RTSP 地址一般属于外部编码板。
 * 因此这里保留通用 HTTP 接口模板，按实际编码板接口改 endpoint 和字段名即可。
 */
class EncoderApiClient(
    private val baseUrl: String,
    private val timeoutMs: Int = 2500
) {
    suspend fun setVideoEncode(config: VideoEncodeConfig): String = postForm(
        path = "/api/video/encode",
        params = mapOf(
            "resolution" to config.resolution,
            "frameRate" to config.frameRate.toString(),
            "bitRateKbps" to config.bitRateKbps.toString(),
            "rateControl" to config.rateControlMode.value
        )
    )

    suspend fun queryVideoEncode(): VideoEncodeConfig = getText("/api/video/encode").toVideoEncodeConfig()

    suspend fun setImageParam(name: String, percent: Int): String = postForm(
        path = "/api/image/param",
        params = mapOf(name to percent.coerceIn(0, 100).toString())
    )

    private suspend fun postForm(path: String, params: Map<String, String>): String = withContext(Dispatchers.IO) {
        val url = URL(baseUrl.trimEnd('/') + path)
        val body = params.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    private suspend fun getText(path: String): String = withContext(Dispatchers.IO) {
        val url = URL(baseUrl.trimEnd('/') + path)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doInput = true
            setRequestProperty("Accept", "application/json, text/plain, */*")
        }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (conn.responseCode !in 200..299) {
            error("HTTP ${conn.responseCode}: $text")
        }
        text
    }

    private fun String.toVideoEncodeConfig(): VideoEncodeConfig {
        val text = trim()
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (json != null) return json.toVideoEncodeConfig()

        val values = parseKeyValueText(text)
        return VideoEncodeConfig(
            resolution = values.firstString(RESOLUTION_KEYS).normalizeResolution(),
            frameRate = values.firstInt(FRAME_RATE_KEYS).normalizeFrameRate(),
            bitRateKbps = values.firstInt(BIT_RATE_KEYS).normalizeBitRateKbps(),
            rateControlMode = RateControlMode.values()
                .firstOrNull { it.value.equals(values.firstString(RATE_CONTROL_KEYS), true) }
                ?: RateControlMode.CBR
        )
    }

    private fun JSONObject.toVideoEncodeConfig(): VideoEncodeConfig {
        return VideoEncodeConfig(
            resolution = findString(RESOLUTION_KEYS).normalizeResolution(),
            frameRate = findInt(FRAME_RATE_KEYS).normalizeFrameRate(),
            bitRateKbps = findInt(BIT_RATE_KEYS).normalizeBitRateKbps(),
            rateControlMode = RateControlMode.values()
                .firstOrNull { it.value.equals(findString(RATE_CONTROL_KEYS), true) }
                ?: RateControlMode.CBR
        )
    }

    private fun String.normalizeResolution(): String {
        val value = uppercase().replace(" ", "")
        return when {
            value.contains("1280") && value.contains("720") -> "1280X720"
            else -> "1920X1080"
        }
    }

    private fun Int.normalizeFrameRate(): Int {
        if (this <= 0) return VideoEncodeConfig().frameRate
        return listOf(25, 30, 50, 60).minBy { kotlin.math.abs(it - this) }
    }

    private fun Int.normalizeBitRateKbps(): Int {
        val kbps = if (this > 100000) this / 1000 else this
        return kbps.coerceIn(1024, 21504)
    }

    private fun JSONObject.findString(keys: Set<String>): String {
        return findValue(keys)?.toString()?.trim().orEmpty()
    }

    private fun JSONObject.findInt(keys: Set<String>): Int {
        return findValue(keys).toIntValue()
    }

    private fun JSONObject.findValue(keys: Set<String>): Any? {
        return collectValues(keys, 0).maxWithOrNull(
            compareBy<ValueCandidate> { it.score }.thenBy { it.value.toIntValue() }
        )?.value
    }

    private fun JSONObject.collectValues(keys: Set<String>, pathScore: Int): List<ValueCandidate> {
        val result = mutableListOf<ValueCandidate>()
        val iterator = keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            val normalized = key.normalizedKey()
            val nextScore = pathScore + normalized.pathScore()
            val value = opt(key)
            if (normalized in keys) {
                result += ValueCandidate(value, nextScore + normalized.keyScore())
            }
            when (value) {
                is JSONObject -> result += value.collectValues(keys, nextScore)
                is JSONArray -> result += value.collectValues(keys, nextScore)
            }
        }
        return result
    }

    private fun JSONArray.collectValues(keys: Set<String>, pathScore: Int): List<ValueCandidate> {
        val result = mutableListOf<ValueCandidate>()
        for (i in 0 until length()) {
            when (val child = opt(i)) {
                is JSONObject -> result += child.collectValues(keys, pathScore)
                is JSONArray -> result += child.collectValues(keys, pathScore)
            }
        }
        return result
    }

    private fun parseKeyValueText(text: String): Map<String, String> {
        return text
            .split('&', '\n', '\r', ';')
            .mapNotNull { token ->
                val index = token.indexOf('=').takeIf { it > 0 } ?: token.indexOf(':').takeIf { it > 0 }
                index?.let {
                    token.substring(0, it).trim().normalizedKey() to token.substring(it + 1).trim()
                }
            }
            .toMap()
    }

    private fun Map<String, String>.firstString(keys: Set<String>): String {
        return keys.firstNotNullOfOrNull { key -> get(key)?.takeIf { it.isNotBlank() } }.orEmpty()
    }

    private fun Map<String, String>.firstInt(keys: Set<String>): Int {
        return firstString(keys).toIntValue()
    }

    private fun Any?.toIntValue(): Int {
        return when (this) {
            is Number -> toInt()
            is String -> Regex("""\d+(\.\d+)?""").find(this)?.value?.toFloatOrNull()?.toInt()
            else -> null
        } ?: 0
    }

    private fun String.normalizedKey(): String {
        return lowercase().filter { it.isLetterOrDigit() }
    }

    private fun String.pathScore(): Int {
        var score = 0
        if (contains("encode") || contains("enc")) score += 8
        if (contains("output") || contains("out") || contains("dst") || contains("target")) score += 8
        if (contains("stream") || contains("video") || contains("main")) score += 5
        if (contains("input") || contains("source") || this == "src") score -= 10
        return score
    }

    private fun String.keyScore(): Int {
        var score = pathScore()
        if (this == "framerate" || this == "fps") score += 2
        if (contains("bitrate") || contains("resolution")) score += 2
        return score
    }

    private data class ValueCandidate(
        val value: Any?,
        val score: Int
    )

    private companion object {
        val RESOLUTION_KEYS = setOf("resolution", "res", "size", "videoresolution", "imagesize")
        val FRAME_RATE_KEYS = setOf(
            "framerate",
            "fps",
            "videoframerate",
            "encframerate",
            "streamframerate",
            "outframerate",
            "outputframerate",
            "dstframerate",
            "targetframerate"
        )
        val BIT_RATE_KEYS = setOf("bitratekbps", "bitrate", "bitratevalue", "kbps", "videobitrate", "encbitrate")
        val RATE_CONTROL_KEYS = setOf("ratecontrol", "ratecontrolmode", "rcmode", "bitratetype")
    }
}
