package com.bominwell.tamrondebug.viewmodel

import com.bominwell.cctvsdk.DayNightMode
import com.bominwell.cctvsdk.ExposureMode
import com.bominwell.cctvsdk.FlickerMode
import com.bominwell.cctvsdk.RateControlMode
import com.bominwell.cctvsdk.WhiteBalanceMode

data class CameraParamRecord(
    val fileName: String,
    val displayName: String,
    val createdAtMillis: Long,
    val filePath: String
)

data class MediaRecord(
    val fileName: String,
    val displayName: String,
    val typeLabel: String,
    val mimeType: String,
    val lastModifiedMillis: Long
)

data class TamronDebugState(
    val rtspUrl: String = "rtsp://172.169.10.65:8554/quality_h264",
    val pingIp: String = "172.169.10.65",
    val useHardwareDecode: Boolean = true,
    val playKey: Int = 0,
    val playbackFrameRate: Int = 0,
    val playbackBitRateKbps: Int = 0,

    val zoomSpeed: Int = 3,
    val focusSpeed: Int = 2,
    val focusAuto: Boolean = true,
    val focusNearLimitPercent: Int = 50,

    val wbMode: WhiteBalanceMode = WhiteBalanceMode.ATW1,
    val wbRed: Int = 50,
    val wbBlue: Int = 50,

    val exposureMode: ExposureMode = ExposureMode.FULL_AUTO,
    val iris: Int = 100,
    val shutter: Int = 0,
    val gain: Int = 0,
    val exposureComp: Int = 50,

    val brightness: Int = 50,
    val saturation: Int = 50,
    val sharpness: Int = 50,
    val contrast: Int = 50,
    val hue: Int = 50,
    val gamma: Int = 0,

    val dayNightMode: DayNightMode = DayNightMode.DAY,
    val autoIcrThreshold: Int = 14,
    val nr2d: Int = 3,
    val nr3d: Int = 3,
    val wdr: Boolean = false,
    val blc: Boolean = false,
    val eis: Boolean = false,
    val flickerMode: FlickerMode = FlickerMode.OFF,
    val defog: Boolean = false,
    val defogLevel: Int = 2,

    val resolution: String = "1920X1080",
    val frameRate: Int = 30,
    val bitRateKbps: Int = 4096,
    val rateControlMode: RateControlMode = RateControlMode.CBR,

    val paramRecords: List<CameraParamRecord> = emptyList(),
    val mediaRecords: List<MediaRecord> = emptyList(),
    val rawHex: String = "81 01 04 07 02 FF",
    val busy: Boolean = false,
    val log: String = ""
)
