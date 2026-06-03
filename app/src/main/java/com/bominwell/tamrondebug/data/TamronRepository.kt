package com.bominwell.tamrondebug.data

import com.bominwell.cctvsdk.EncoderApiClient
import com.bominwell.cctvsdk.TamronHttpViscaClient
import com.bominwell.cctvsdk.VideoEncodeConfig

class TamronRepository(
    encoderBaseUrl: String? = null
) {
    val camera = TamronHttpViscaClient
    private var encoderClient = encoderBaseUrl?.takeIf { it.isNotBlank() }?.let { EncoderApiClient(it) }

    init {
        camera.init()
    }

    fun configureEncoderBaseUrl(baseUrl: String?) {
        encoderClient = baseUrl?.takeIf { it.isNotBlank() }?.let { EncoderApiClient(it) }
    }

    suspend fun queryEncode(): VideoEncodeConfig {
        val encoder = encoderClient ?: error("未配置编码板 HTTP 地址，无法读取分辨率/帧率/码率")
        return encoder.queryVideoEncode()
    }

    suspend fun applyEncode(config: VideoEncodeConfig): String {
        val encoder = encoderClient ?: error("未配置编码板 HTTP 地址，无法设置分辨率/帧率/码率")
        return encoder.setVideoEncode(config)
    }

    suspend fun sendRawHex(hex: String): String {
        val result = camera.sendRawHex(hex)
        return result.resultHex
    }
}
