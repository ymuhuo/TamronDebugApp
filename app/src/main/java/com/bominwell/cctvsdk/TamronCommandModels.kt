package com.bominwell.cctvsdk

enum class WhiteBalanceMode(val code: Int) {
    ATW1(0x00),
    INDOOR(0x01),
    OUTDOOR(0x02),
    ONE_PUSH(0x03),
    ATW2(0x04),
    MANUAL(0x05)
}

enum class ExposureMode(val code: Int) {
    FULL_AUTO(0x00),
    MANUAL(0x03),
    SHUTTER_PRIORITY(0x0A),
    IRIS_PRIORITY(0x0B)
}

enum class DayNightMode {
    DAY,
    NIGHT,
    AUTO
}

enum class FlickerMode(val code: Int) {
    AUTO(0x02),
    OFF(0x03)
}

enum class RateControlMode(val value: String) {
    CBR("CBR"),
    VBR("VBR")
}

data class VideoEncodeConfig(
    val resolution: String = "1920X1080",
    val frameRate: Int = 30,
    val bitRateKbps: Int = 4096,
    val rateControlMode: RateControlMode = RateControlMode.CBR
)
