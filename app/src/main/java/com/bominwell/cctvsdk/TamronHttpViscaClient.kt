package com.bominwell.cctvsdk

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * 腾龙 / TL3010 / MP3010M-EV 编码板 HTTP VISCA 控制工具类
 */
object TamronHttpViscaClient : Closeable {

    private const val TAG = "TamronHttpVisca"
    private const val CUSTOM_SAVE_TIMEOUT_MS = 3000

    enum class HttpBodyMode {
        RAW_JSON_BODY,
        FORM_FIELD_JSON
    }

    data class Config @JvmOverloads constructor(
        val ip: String,
        val port: Int = 1236,
        val address: Int = 0x81,
        val path: String = "/",
        val connectTimeoutMs: Int = 1500,
        val readTimeoutMs: Int = 1500,
        val bytesToRead: Int = 16,
        val viscaTimeoutMs: Int = 500,
        val contentType: String = "application/x-www-form-urlencoded; charset=UTF-8",
        val accept: String = "application/json, text/javascript, */*; q=0.01",
        val userAgent: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
        val bodyMode: HttpBodyMode = HttpBodyMode.RAW_JSON_BODY,
        val formJsonFieldName: String = "data",
        val enableSonyVariableSpeedCommands: Boolean = false,
        val enableLog: Boolean = true
    )

    data class ViscaHttpResult(
        val success: Boolean,
        val requestId: Int,
        val sentHex: String,
        val requestBody: String,
        val responseCode: Int = -1,
        val responseBody: String = "",
        val resultBytes: ByteArray = ByteArray(0),
        val resultHex: String = "",
        val responseFramesHex: List<String> = emptyList(),
        val hasAck: Boolean = false,
        val hasCompletion: Boolean = false,
        val hasViscaError: Boolean = false,
        val jsonRpcErrorCode: Int? = null,
        val jsonRpcErrorMessage: String? = null,
        val error: String? = null
    ) {
        fun isCommandFinished(): Boolean = success && hasCompletion && !hasViscaError
        fun isInquiryOk(): Boolean = success && !hasViscaError && resultBytes.isNotEmpty()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ViscaHttpResult
            if (success != other.success) return false
            if (requestId != other.requestId) return false
            if (sentHex != other.sentHex) return false
            if (requestBody != other.requestBody) return false
            if (responseCode != other.responseCode) return false
            if (responseBody != other.responseBody) return false
            if (!resultBytes.contentEquals(other.resultBytes)) return false
            if (resultHex != other.resultHex) return false
            if (responseFramesHex != other.responseFramesHex) return false
            if (hasAck != other.hasAck) return false
            if (hasCompletion != other.hasCompletion) return false
            if (hasViscaError != other.hasViscaError) return false
            if (jsonRpcErrorCode != other.jsonRpcErrorCode) return false
            if (jsonRpcErrorMessage != other.jsonRpcErrorMessage) return false
            if (error != other.error) return false
            return true
        }

        override fun hashCode(): Int {
            var result = success.hashCode()
            result = 31 * result + requestId
            result = 31 * result + sentHex.hashCode()
            result = 31 * result + requestBody.hashCode()
            result = 31 * result + responseCode
            result = 31 * result + responseBody.hashCode()
            result = 31 * result + resultBytes.contentHashCode()
            result = 31 * result + resultHex.hashCode()
            result = 31 * result + responseFramesHex.hashCode()
            result = 31 * result + hasAck.hashCode()
            result = 31 * result + hasCompletion.hashCode()
            result = 31 * result + hasViscaError.hashCode()
            result = 31 * result + (jsonRpcErrorCode ?: 0)
            result = 31 * result + (jsonRpcErrorMessage?.hashCode() ?: 0)
            result = 31 * result + (error?.hashCode() ?: 0)
            return result
        }
    }

    private val lock = Any()

    @Volatile
    private var config: Config? = null

    private val requestIdGenerator = AtomicInteger(1)

    @JvmStatic
    fun init() {
        init(Config(ip = "172.169.10.65", port = 1236, address = 0x81))
    }

    @JvmStatic
    fun init(config: Config) {
        synchronized(lock) {
            this.config = config.copy(address = config.address and 0xFF)
            requestIdGenerator.set(1)
        }
    }

    @JvmStatic
    fun isInitialized(): Boolean = config != null

    private fun requireConfig(): Config {
        return config ?: throw IllegalStateException("TamronHttpViscaClient 尚未初始化，请先调用 init()")
    }

    // =========================================================
    // 发送与基础方法
    // =========================================================

    @JvmStatic
    suspend fun sendRawHex(hex: String, bytesToRead: Int? = null, timeoutMs: Int? = null): ViscaHttpResult {
        return sendViscaBytes(hexToBytes(hex), bytesToRead, timeoutMs)
    }

    @JvmStatic
    suspend fun sendViscaBytes(visca: ByteArray, bytesToRead: Int? = null, timeoutMs: Int? = null): ViscaHttpResult = withContext(Dispatchers.IO) {
        val cfg: Config
        val requestId: Int
        synchronized(lock) {
            cfg = requireConfig()
            requestId = requestIdGenerator.getAndIncrement()
        }

        try {
            if (visca.isEmpty()) return@withContext ViscaHttpResult(false, requestId, "", "", error = "VISCA 指令为空")
            if ((visca.last().toInt() and 0xFF) != 0xFF) return@withContext ViscaHttpResult(false, requestId, visca.toHexString(), "", error = "VISCA 指令必须以 FF 结束")

            val requestJson = buildJsonRpcRequest(
                requestId,
                visca,
                bytesToRead ?: cfg.bytesToRead,
                timeoutMs ?: cfg.viscaTimeoutMs
            )

            val requestBody = buildHttpBody(cfg, requestJson)
            postJsonRpc(cfg, requestId, visca, requestBody)
        } catch (e: Exception) {
            ViscaHttpResult(false, requestId, visca.toHexString(), "", error = e.message ?: e.javaClass.simpleName)
        }
    }

    @JvmStatic
    suspend fun sendCameraCommand(vararg body: Int): ViscaHttpResult {
        val cfg = requireConfig()
        val payload = buildViscaCommand(cfg.address, *body)
        return sendViscaBytes(payload)
    }

    // =========================================================
    // 兼容性/业务方法 (配合 TamronRepository / ViewModel)
    // =========================================================

    @JvmStatic suspend fun zoomStop() = sendCameraCommand(0x01, 0x04, 0x07, 0x00)
    @JvmStatic suspend fun zoomTeleStandard() = sendCameraCommand(0x01, 0x04, 0x07, 0x02)
    @JvmStatic suspend fun zoomWideStandard() = sendCameraCommand(0x01, 0x04, 0x07, 0x03)
    @JvmStatic suspend fun zoomTele(speed: Int = 4): ViscaHttpResult {
        val cfg = requireConfig()
        return if (cfg.enableSonyVariableSpeedCommands) {
            sendCameraCommand(0x01, 0x04, 0x07, 0x20 or speed.coerceIn(0, 7))
        } else {
            zoomTeleStandard()
        }
    }
    @JvmStatic suspend fun zoomWide(speed: Int = 4): ViscaHttpResult {
        val cfg = requireConfig()
        return if (cfg.enableSonyVariableSpeedCommands) {
            sendCameraCommand(0x01, 0x04, 0x07, 0x30 or speed.coerceIn(0, 7))
        } else {
            zoomWideStandard()
        }
    }
    
    @JvmStatic suspend fun focusAuto() = sendCameraCommand(0x01, 0x04, 0x38, 0x02)
    @JvmStatic suspend fun focusManual() = sendCameraCommand(0x01, 0x04, 0x38, 0x03)
    @JvmStatic suspend fun focusStop() = sendCameraCommand(0x01, 0x04, 0x08, 0x00)
    @JvmStatic suspend fun focusFarStandard() = sendCameraCommand(0x01, 0x04, 0x08, 0x02)
    @JvmStatic suspend fun focusNearStandard() = sendCameraCommand(0x01, 0x04, 0x08, 0x03)
    @JvmStatic suspend fun focusFar(speed: Int = 4): ViscaHttpResult {
        val cfg = requireConfig()
        return if (cfg.enableSonyVariableSpeedCommands) {
            sendCameraCommand(0x01, 0x04, 0x08, 0x20 or speed.coerceIn(0, 7))
        } else {
            focusFarStandard()
        }
    }
    @JvmStatic suspend fun focusNear(speed: Int = 4): ViscaHttpResult {
        val cfg = requireConfig()
        return if (cfg.enableSonyVariableSpeedCommands) {
            sendCameraCommand(0x01, 0x04, 0x08, 0x30 or speed.coerceIn(0, 7))
        } else {
            focusNearStandard()
        }
    }
    @JvmStatic suspend fun onePushFocus(fullScan: Boolean = true) = sendCameraCommand(0x01, 0x04, 0x18, if (fullScan) 0x01 else 0x10)

    @JvmStatic suspend fun setFocusNearLimitPercent(percent: Int): ViscaHttpResult {
        val code = 0x1000 + ((0xB000 - 0x1000) * percent / 100f).toInt()
        return sendCameraCommand(0x01, 0x04, 0x28, *toViscaNibbles4(code.coerceIn(0x1000, 0xB000)))
    }

    @JvmStatic suspend fun setWhiteBalanceMode(mode: WhiteBalanceMode) = sendCameraCommand(0x01, 0x04, 0x35, mode.code)
    @JvmStatic suspend fun onePushWhiteBalance() = sendCameraCommand(0x01, 0x04, 0x10, 0x05)
    @JvmStatic suspend fun setWhiteBalanceRGainPercent(percent: Int) = redGainDirect(TamronCameraConfigConverter.whiteBalanceGainPercentToTamronCode(percent))
    @JvmStatic suspend fun setWhiteBalanceBGainPercent(percent: Int) = blueGainDirect(TamronCameraConfigConverter.whiteBalanceGainPercentToTamronCode(percent))
    @JvmStatic suspend fun redGainDirect(value: Int) = sendCameraCommand(0x01, 0x04, 0x43, *toViscaNibbles4(value.coerceIn(0, 0xFF)))
    @JvmStatic suspend fun blueGainDirect(value: Int) = sendCameraCommand(0x01, 0x04, 0x44, *toViscaNibbles4(value.coerceIn(0, 0xFF)))

    @JvmStatic suspend fun setExposureMode(mode: ExposureMode) = sendCameraCommand(0x01, 0x04, 0x39, mode.code)
    @JvmStatic suspend fun setIrisPercent(percent: Int) = irisDirect(TamronCameraConfigConverter.irisPercentToTamronCode(percent))
    @JvmStatic suspend fun setShutterIndex(index: Int) = shutterDirect(TamronCameraConfigConverter.shutterIndexToTamronCode(index))
    @JvmStatic suspend fun setGainPercent(percent: Int) = gainDirect(TamronCameraConfigConverter.exposureGainPercentToTamronCode(percent))
    @JvmStatic suspend fun irisDirect(value: Int) = sendCameraCommand(0x01, 0x04, 0x4B, *toViscaNibbles4(value))
    @JvmStatic suspend fun shutterDirect(value: Int) = sendCameraCommand(0x01, 0x04, 0x4A, *toViscaNibbles4(value))
    @JvmStatic suspend fun gainDirect(value: Int) = sendCameraCommand(0x01, 0x04, 0x4C, *toViscaNibbles4(value))

    @JvmStatic suspend fun setExposureCompPercent(percent: Int): ViscaHttpResult {
        sendCameraCommand(0x01, 0x04, 0x3E, 0x02)
        val code = (0x01 + (0x0D - 0x01) * percent / 100f).toInt().coerceIn(0x01, 0x0D)
        return sendCameraCommand(0x01, 0x04, 0x4E, *toViscaNibbles4(code))
    }
    @JvmStatic suspend fun setBrightnessPercent(percent: Int) = setExposureCompPercent(percent)
    @JvmStatic suspend fun setSharpnessPercent(percent: Int) = sendCameraCommand(0x01, 0x04, 0x42, *toViscaNibbles4((0x0F * percent / 100f).toInt().coerceIn(0, 0x0F)))
    @JvmStatic suspend fun setContrastPercent(percent: Int): ViscaHttpResult {
        val gammaCode = when (percent.coerceIn(0, 100)) {
            in 0..24 -> 3
            in 25..50 -> 0
            in 51..74 -> 1
            else -> 2
        }
        return setGamma(gammaCode)
    }
    @JvmStatic suspend fun setSaturationPercent(percent: Int) = sendCameraCommand(0x01, 0x04, 0x49, *toViscaNibbles4((0x0E * percent / 100f).toInt().coerceIn(0, 0x0E)))
    @JvmStatic suspend fun setHuePercent(percent: Int) = sendCameraCommand(0x01, 0x04, 0x4F, *toViscaNibbles4((0x0E * percent / 100f).toInt().coerceIn(0, 0x0E)))
    @JvmStatic suspend fun setGamma(code: Int) = sendCameraCommand(0x01, 0x04, 0x5B, code.coerceIn(0, 3))

    @JvmStatic suspend fun setWdr(enable: Boolean) = sendCameraCommand(0x01, 0x04, 0x3D, if (enable) 0x02 else 0x03)
    @JvmStatic suspend fun setBackLight(enable: Boolean) = sendCameraCommand(0x01, 0x04, 0x33, if (enable) 0x02 else 0x03)
    @JvmStatic suspend fun setEis(enable: Boolean) = sendCameraCommand(0x01, 0x04, 0x34, if (enable) 0x02 else 0x03)
    @JvmStatic suspend fun setDigitalZoom(enable: Boolean) = sendCameraCommand(0x01, 0x04, 0x06, if (enable) 0x02 else 0x03)
    @JvmStatic suspend fun setFlickerMode(mode: FlickerMode) = sendCameraCommand(0x01, 0x04, 0x09, mode.code)
    @JvmStatic suspend fun setDefog(enable: Boolean, level: Int = 2) = if (enable) sendCameraCommand(0x01, 0x04, 0x37, 0x02, level.coerceIn(0, 3)) else sendCameraCommand(0x01, 0x04, 0x37, 0x03, 0x00)
    @JvmStatic suspend fun setNoiseReduction(nr3d: Int, nr2d: Int) = sendCameraCommand(0x01, 0x04, 0x53, (nr3d.coerceIn(0, 5) shl 4) or nr2d.coerceIn(0, 5))

    @JvmStatic suspend fun setDayNight(mode: DayNightMode, autoThreshold: Int = 14): ViscaHttpResult {
        return when (mode) {
            DayNightMode.DAY -> { sendCameraCommand(0x01, 0x04, 0x51, 0x03); sendCameraCommand(0x01, 0x04, 0x01, 0x03) }
            DayNightMode.NIGHT -> { sendCameraCommand(0x01, 0x04, 0x51, 0x03); sendCameraCommand(0x01, 0x04, 0x01, 0x02) }
            DayNightMode.AUTO -> { sendCameraCommand(0x01, 0x04, 0x51, 0x02); sendCameraCommand(0x01, 0x04, 0x21, *toViscaNibbles4(autoThreshold)) }
        }
    }

    @JvmStatic suspend fun savePowerOnSettings(timeoutMs: Int = CUSTOM_SAVE_TIMEOUT_MS): ViscaHttpResult {
        val cfg = requireConfig()
        return sendViscaBytes(
            buildViscaCommand(cfg.address, 0x01, 0x04, 0x3F, 0x01, 0x7F),
            timeoutMs = timeoutMs
        )
    }

    // =========================================================
    // 查询
    // =========================================================

    data class CameraConfigRawState(
        var wbModeCode: Int? = null, var wbRedCode: Int? = null, var wbBlueCode: Int? = null,
        var expModeCode: Int? = null, var gainCode: Int? = null, var irisCode: Int? = null,
        var shutterCode: Int? = null, var zoomPositionCode: Int? = null,
        var expCompCode: Int? = null, var sharpnessCode: Int? = null,
        var nrCode: Int? = null, var colorGainCode: Int? = null, var colorHueCode: Int? = null,
        var gammaCode: Int? = null, var icrCode: Int? = null, var wdrCode: Int? = null, var eisCode: Int? = null,
        var digitalZoomCode: Int? = null, var flickerCode: Int? = null,
        var blcCode: Int? = null, var defogCode: Int? = null, var focusModeCode: Int? = null
    )

    @JvmStatic suspend fun queryRawState(): CameraConfigRawState = withContext(Dispatchers.IO) {
        CameraConfigRawState(
            wbModeCode = queryOneByte(0x09, 0x04, 0x35),
            wbRedCode = queryLastNibbles(0x09, 0x04, 0x43),
            wbBlueCode = queryLastNibbles(0x09, 0x04, 0x44),
            expModeCode = queryOneByte(0x09, 0x04, 0x39),
            gainCode = queryLastNibbles(0x09, 0x04, 0x4C),
            irisCode = queryLastNibbles(0x09, 0x04, 0x4B),
            shutterCode = queryLastNibbles(0x09, 0x04, 0x4A),
            zoomPositionCode = queryLastNibbles(0x09, 0x04, 0x47),
            expCompCode = queryLastNibbles(0x09, 0x04, 0x4E),
            sharpnessCode = queryLastNibbles(0x09, 0x04, 0x42),
            nrCode = queryOneByte(0x09, 0x04, 0x53),
            colorGainCode = queryLastNibbles(0x09, 0x04, 0x49),
            colorHueCode = queryLastNibbles(0x09, 0x04, 0x4F),
            gammaCode = queryOneByte(0x09, 0x04, 0x5B),
            icrCode = queryOneByte(0x09, 0x04, 0x01),
            wdrCode = queryOneByte(0x09, 0x04, 0x3D),
            eisCode = queryOneByte(0x09, 0x04, 0x34),
            digitalZoomCode = queryOneByte(0x09, 0x04, 0x06),
            flickerCode = queryOneByte(0x09, 0x04, 0x09),
            blcCode = queryOneByte(0x09, 0x04, 0x33),
            defogCode = queryOneByte(0x09, 0x04, 0x37),
            focusModeCode = queryOneByte(0x09, 0x04, 0x38)
        )
    }

    @JvmStatic suspend fun queryZoomPosition(): Int? {
        return queryLastNibbles(0x09, 0x04, 0x47)
    }

    private suspend fun queryOneByte(vararg body: Int): Int? {
        val result = sendCameraCommand(*body)
        if (!result.isInquiryOk()) return null
        val bytes = result.resultBytes
        val start = bytes.indexOfFirst { (it.toInt() and 0xFF) == 0x50 }
        return if (start != -1 && start + 1 < bytes.size) bytes[start + 1].toInt() and 0xFF else null
    }

    private suspend fun queryLastNibbles(vararg body: Int): Int? {
        val result = sendCameraCommand(*body)
        if (!result.isInquiryOk()) return null
        val bytes = result.resultBytes
        val start = bytes.indexOfFirst { (it.toInt() and 0xFF) == 0x50 }
        if (start == -1) return null
        val payload = bytes.copyOfRange(start + 1, bytes.size - 1)
        var value = 0
        payload.takeLast(4).forEach { value = (value shl 4) or (it.toInt() and 0x0F) }
        return value
    }

    // =========================================================
    // 内部私有工具
    // =========================================================

    private fun buildJsonRpcRequest(requestId: Int, visca: ByteArray, bytesToRead: Int, timeoutMs: Int): String {
        val params = JSONObject()
        params.put("bytesToRead", bytesToRead)
        params.put("timeoutMs", timeoutMs)
        val viscaArray = JSONArray()
        for (b in visca) viscaArray.put(b.toInt() and 0xFF)
        params.put("viscaTx", viscaArray)
        val root = JSONObject()
        root.put("id", requestId)
        root.put("jsonrpc", "2.0")
        root.put("method", "sendReceiveVisca")
        root.put("params", params)
        return root.toString()
    }

    private fun buildHttpBody(cfg: Config, json: String): String {
        return if (cfg.bodyMode == HttpBodyMode.FORM_FIELD_JSON) {
            "${URLEncoder.encode(cfg.formJsonFieldName, "UTF-8")}=${URLEncoder.encode(json, "UTF-8")}"
        } else {
            json
        }
    }

    private fun postJsonRpc(
        cfg: Config,
        requestId: Int,
        visca: ByteArray,
        requestBody: String
    ): ViscaHttpResult {
        val urlText = cfg.buildUrl()
        if (cfg.enableLog) {
            Log.d(TAG, "POST $urlText contentType=${cfg.contentType} mode=${cfg.bodyMode} body=$requestBody")
        }

        val conn = (URL(urlText).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = cfg.connectTimeoutMs
            readTimeout = cfg.readTimeoutMs
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", cfg.contentType)
            setRequestProperty("Accept", cfg.accept)
            setRequestProperty("User-Agent", cfg.userAgent)
        }

        return try {
            conn.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
            val responseCode = conn.responseCode
            val responseText = readResponseText(conn, responseCode)
            if (cfg.enableLog) Log.d(TAG, "Response: $responseCode - $responseText")
            parseHttpResult(requestId, visca, requestBody, responseCode, responseText)
        } finally {
            conn.disconnect()
        }
    }

    private fun Config.buildUrl(): String {
        val host = ip.trim()
        return if (host.startsWith("http://", true) || host.startsWith("https://", true)) {
            host.trimEnd('/') + normalizePath(path)
        } else {
            "http://$host:$port${normalizePath(path)}"
        }
    }

    private fun normalizePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isBlank()) return "/"
        return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    }

    private fun readResponseText(conn: HttpURLConnection, responseCode: Int): String {
        val stream = try { if (responseCode in 200..299) conn.inputStream else conn.errorStream } catch (_: Exception) { null }
        return stream?.use { it.readBytes() }?.toString(Charsets.UTF_8) ?: ""
    }

    private fun parseHttpResult(requestId: Int, sentVisca: ByteArray, requestBody: String, responseCode: Int, responseBody: String): ViscaHttpResult {
        if (responseCode !in 200..299) return ViscaHttpResult(false, requestId, sentVisca.toHexString(), requestBody, responseCode, responseBody, error = "HTTP $responseCode")
        if (responseBody.isBlank()) return ViscaHttpResult(false, requestId, sentVisca.toHexString(), requestBody, responseCode, responseBody, error = "Empty body")

        return try {
            val json = JSONObject(responseBody)
            if (json.has("error")) {
                val errorObj = json.optJSONObject("error")
                return ViscaHttpResult(
                    false, requestId, sentVisca.toHexString(), requestBody, responseCode, responseBody,
                    jsonRpcErrorCode = errorObj?.optInt("code"),
                    jsonRpcErrorMessage = errorObj?.optString("message"),
                    error = errorObj?.toString() ?: json.opt("error")?.toString() ?: "Unknown JSON-RPC error"
                )
            }
            val resultBytes = extractResultBytes(json)
            val frames = splitViscaFrames(resultBytes)
            ViscaHttpResult(
                success = !frames.any { it.isViscaErrorFrame() },
                requestId = requestId,
                sentHex = sentVisca.toHexString(),
                requestBody = requestBody,
                responseCode = responseCode,
                responseBody = responseBody,
                resultBytes = resultBytes,
                resultHex = resultBytes.toHexString(),
                responseFramesHex = frames.map { it.toHexString() },
                hasAck = frames.any { it.isViscaAckFrame() },
                hasCompletion = frames.any { it.isViscaCompletionFrame() },
                hasViscaError = frames.any { it.isViscaErrorFrame() }
            )
        } catch (e: Exception) {
            ViscaHttpResult(false, requestId, sentVisca.toHexString(), requestBody, responseCode, responseBody, error = e.message)
        }
    }

    private fun extractResultBytes(json: JSONObject): ByteArray {
        json.optJSONArray("result")?.let { return it.toByteArray() }
        val resultObject = json.optJSONObject("result") ?: return ByteArray(0)
        val arrayKeys = listOf("viscaRx", "rx", "data", "bytes", "response", "result")
        for (key in arrayKeys) {
            resultObject.optJSONArray(key)?.let { return it.toByteArray() }
        }
        val hexKeys = listOf("hex", "resultHex", "viscaRxHex", "rxHex", "responseHex")
        for (key in hexKeys) {
            val value = resultObject.optString(key, "")
            if (value.isNotBlank()) return hexToBytes(value)
        }
        return ByteArray(0)
    }

    private fun JSONArray.toByteArray(): ByteArray {
        return ByteArray(length()) { (optInt(it, 0) and 0xFF).toByte() }
    }

    private fun buildViscaCommand(address: Int, vararg body: Int): ByteArray {
        val out = ByteArray(body.size + 2)
        out[0] = (address and 0xFF).toByte()
        for (i in body.indices) out[i + 1] = (body[i] and 0xFF).toByte()
        out[out.lastIndex] = 0xFF.toByte()
        return out
    }

    private fun toViscaNibbles4(value: Int): IntArray = intArrayOf((value shr 12) and 0x0F, (value shr 8) and 0x0F, (value shr 4) and 0x0F, value and 0x0F)

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(Regex("[^0-9A-Fa-f]"), "")
        if (clean.isEmpty()) return ByteArray(0)
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun ByteArray.toHexString(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun splitViscaFrames(data: ByteArray): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var start = 0
        for (i in data.indices) if ((data[i].toInt() and 0xFF) == 0xFF) { result.add(data.copyOfRange(start, i + 1)); start = i + 1 }
        return result
    }

    private fun ByteArray.isViscaAckFrame() = size >= 3 && (this[1].toInt() and 0xF0) == 0x40
    private fun ByteArray.isViscaCompletionFrame() = size >= 3 && (this[1].toInt() and 0xF0) == 0x50
    private fun ByteArray.isViscaErrorFrame() = size >= 3 && (this[1].toInt() and 0xF0) == 0x60

    override fun close() { synchronized(lock) { config = null; requestIdGenerator.set(1) } }
}
