package com.bominwell.cctvsdk

/**
 * 腾龙机芯原始 VISCA 参数与 CameraConfigInfo UI 参数的转换工具。
 *
 * 原则：
 * 1. TamronHttpViscaClient 只负责查询机芯原始值。
 * 2. 本类负责把原始值转换成业务层使用的 CameraConfigInfo。
 * 3. CameraConfigInfo 中除 shutter 外，大部分值按 0~100 表示。
 * 4. shutter 按 0~19 表示。
 *
 * 重要说明：
 * 你当前 MP3010M-EV 实测：
 *
 * 81 01 04 4B 00 00 01 01 FF -> 90 41 FF 90 51 FF，成功
 * 81 01 04 4B 00 00 00 03 FF -> 90 61 02 FF，参数不被当前机芯/固件接受
 *
 * 所以下发光圈时，暂时避开：
 * 0x00 CLOSE
 * 0x03 F22
 *
 * App 可设置范围先限制为：
 * 0x04 F16 ~ 0x11 F1.8
 *
 * 查询时如果读到 0x00 或 0x03，会映射到 UI 最小值 0。
 */
object TamronCameraConfigConverter {

    private const val WB_MODE_AUTO = 0x00
    private const val WB_MODE_MANUAL = 0x05

    private const val AE_MODE_FULL_AUTO = 0x00

    private const val WB_GAIN_MAX_CODE = 0xFF
    private const val EXP_GAIN_MAX_CODE = 0x1C
    private const val FOCUS_NEAR_LIMIT_MIN_MM = 10
    private const val FOCUS_NEAR_LIMIT_MAX_MM = 800
    private const val ZOOM_POSITION_MIN = 0x0000
    private const val ZOOM_POSITION_OPTICAL_MAX = 0x4000
    private const val ZOOM_POSITION_DIGITAL_MAX = 0x7C00

    private val OPTICAL_ZOOM_POINTS = intArrayOf(
        0x0000, // 1x
        0x18C3, // 2x
        0x2430, // 3x
        0x2B0C, // 4x
        0x3049, // 5x
        0x3430, // 6x
        0x37CF, // 7x
        0x3AAA, // 8x
        0x3D86, // 9x
        0x4000  // 10x
    )

    private val DIGITAL_ZOOM_POSITIONS = intArrayOf(
        0x4000, // digital 1x
        0x6000, // digital 2x
        0x7000, // digital 4x
        0x7800, // digital 8x
        0x7A80, // digital 12x
        0x7C00  // digital 16x
    )

    private val DIGITAL_ZOOM_RATIOS = floatArrayOf(
        1f,
        2f,
        4f,
        8f,
        12f,
        16f
    )

    /**
     * 查询兼容表。
     *
     * 用于把机芯返回的真实 Iris code 映射回 UI 百分比。
     * 包含 CLOSE / F22，避免查询到这些值时无法识别。
     */
    private val IRIS_QUERY_CODES = intArrayOf(
        0x00, // CLOSE
        0x03, // F22
        0x04, // F16
        0x05, // F14
        0x06, // F11
        0x07, // F9.6
        0x08, // F8
        0x09, // F6.8
        0x0A, // F5.6
        0x0B, // F4.8
        0x0C, // F4
        0x0D, // F3.4
        0x0E, // F2.8
        0x0F, // F2.4
        0x10, // F2
        0x11  // F1.8
    )

    /**
     * App 实际下发光圈档位。
     *
     * 当前实测 0x03 会返回 90 61 02 FF，所以先不下发 F22。
     * 当前也不下发 0x00 CLOSE，避免用户误操作导致画面全黑或固件拒绝。
     *
     * UI:
     * 0   -> F16
     * 100 -> F1.8
     */
    private val IRIS_SETTING_CODES = intArrayOf(
        0x04, // F16
        0x05, // F14
        0x06, // F11
        0x07, // F9.6
        0x08, // F8
        0x09, // F6.8
        0x0A, // F5.6
        0x0B, // F4.8
        0x0C, // F4
        0x0D, // F3.4
        0x0E, // F2.8
        0x0F, // F2.4
        0x10, // F2
        0x11  // F1.8
    )

    /**
     * 快门机芯档位。
     *
     * 这里使用 50/25fps 常用快门档位。
     * CameraConfigInfo.shutter 对外是 0~19，
     * 但机芯实际支持的常用档位这里是 17 个，所以转换时会做等比例映射。
     *
     * 0  -> 1/25
     * 19 -> 1/10000
     */
    private val SHUTTER_CODES = intArrayOf(
        0x05, // 1/25
        0x06, // 1/50
        0x07, // 1/75
        0x08, // 1/100
        0x09, // 1/120
        0x0A, // 1/180
        0x0B, // 1/250
        0x0C, // 1/350
        0x0D, // 1/500
        0x0E, // 1/725
        0x0F, // 1/1000
        0x10, // 1/1500
        0x11, // 1/2000
        0x12, // 1/3000
        0x13, // 1/4000
        0x14, // 1/6000
        0x15  // 1/10000
    )

    /**
     * 将机芯原始配置转换为 CameraConfigInfo。
     *
     * @param raw 机芯原始配置。
     * @param out 外部传入的配置对象，会直接填充这个对象。
     * @return 填充后的 CameraConfigInfo。
     */
    @JvmStatic
    fun fillCameraConfigInfo(
        raw: TamronHttpViscaClient.CameraConfigRawState,
        out: CameraConfigInfo
    ): CameraConfigInfo {
        raw.wbModeCode?.let {
            out.wbModel = wbModeCodeToUiMode(it)
        }

        raw.wbRedCode?.let {
            out.wbRed = codeToPercent(it, WB_GAIN_MAX_CODE)
        }

        raw.wbBlueCode?.let {
            out.wbBlue = codeToPercent(it, WB_GAIN_MAX_CODE)
        }

        raw.expModeCode?.let {
            out.ExpMode = aeModeCodeToUiMode(it)
        }

        raw.gainCode?.let {
            out.gain = codeToPercent(it, EXP_GAIN_MAX_CODE)
        }

        raw.irisCode?.let {
            out.iris = irisCodeToUiPercent(it)
        }

        raw.shutterCode?.let {
            out.shutter = shutterCodeToUiIndex(it)
        }

        return out
    }

    /**
     * 创建新的 CameraConfigInfo。
     */
    @JvmStatic
    fun toCameraConfigInfo(
        raw: TamronHttpViscaClient.CameraConfigRawState
    ): CameraConfigInfo {
        return fillCameraConfigInfo(raw, CameraConfigInfo())
    }

    /**
     * 白平衡模式转换。
     *
     * 当前业务层 setWhiteBalance(mode, blue, red) 是：
     * mode == 0 -> 自动
     * mode != 0 -> 手动
     *
     * 所以这里也只返回 0 / 1。
     */
    private fun wbModeCodeToUiMode(modeCode: Int): Int {
        return if (modeCode == WB_MODE_MANUAL) {
            1
        } else {
            0
        }
    }

    /**
     * 曝光模式转换。
     *
     * 当前业务层 setExposureModel(mode, aperture, shutterSpeed, gain) 是：
     * mode == 0 -> 全自动曝光
     * mode != 0 -> 手动曝光
     *
     * 所以这里也只返回 0 / 1。
     */
    private fun aeModeCodeToUiMode(modeCode: Int): Int {
        return if (modeCode == AE_MODE_FULL_AUTO) {
            0
        } else {
            1
        }
    }

    /**
     * 0~最大码值 转 0~100。
     */
    private fun codeToPercent(code: Int, maxCode: Int): Int {
        if (maxCode <= 0) return 0

        val safeCode = code.coerceIn(0, maxCode)
        return roundToInt(safeCode * 100f / maxCode)
            .coerceIn(0, 100)
    }

    /**
     * 机芯 Iris code 转 UI 百分比。
     *
     * 注意：
     * 当前 App 下发只允许 F16~F1.8。
     * 如果查询到 CLOSE 或 F22，统一显示为 UI 最小值 0。
     */
    private fun irisCodeToUiPercent(code: Int): Int {
        if (IRIS_SETTING_CODES.isEmpty()) return 0
        if (IRIS_SETTING_CODES.size == 1) return 0

        val safeCode = when {
            code <= 0x04 -> 0x04
            code >= 0x11 -> 0x11
            else -> code
        }

        val nearestIndex = findNearestIndex(safeCode, IRIS_SETTING_CODES)

        return roundToInt(nearestIndex * 100f / (IRIS_SETTING_CODES.size - 1))
            .coerceIn(0, 100)
    }

    /**
     * 快门机芯码值转 UI 下标 0~19。
     */
    private fun shutterCodeToUiIndex(code: Int): Int {
        if (SHUTTER_CODES.isEmpty()) return 0
        if (SHUTTER_CODES.size == 1) return 0

        val nearestIndex = findNearestIndex(code, SHUTTER_CODES)

        return roundToInt(nearestIndex * 19f / (SHUTTER_CODES.size - 1))
            .coerceIn(0, 19)
    }

    /**
     * UI 快门下标 0~19 转机芯快门码值。
     */
    @JvmStatic
    fun shutterIndexToTamronCode(index: Int): Int {
        if (SHUTTER_CODES.isEmpty()) return 0x05
        if (SHUTTER_CODES.size == 1) return SHUTTER_CODES[0]

        val safeIndex = index.coerceIn(0, 19)
        val codeIndex = roundToInt(safeIndex * (SHUTTER_CODES.size - 1).toFloat() / 19f)
            .coerceIn(0, SHUTTER_CODES.size - 1)

        return SHUTTER_CODES[codeIndex]
    }

    /**
     * UI 光圈百分比 0~100 转机芯光圈码值。
     *
     * 当前下发范围：
     * 0   -> 0x04 F16
     * 100 -> 0x11 F1.8
     */
    @JvmStatic
    fun irisPercentToTamronCode(percent: Int): Int {
        if (IRIS_SETTING_CODES.isEmpty()) return 0x04
        if (IRIS_SETTING_CODES.size == 1) return IRIS_SETTING_CODES[0]

        val safePercent = percent.coerceIn(0, 100)
        val index = roundToInt(safePercent * (IRIS_SETTING_CODES.size - 1).toFloat() / 100f)
            .coerceIn(0, IRIS_SETTING_CODES.size - 1)

        return IRIS_SETTING_CODES[index]
    }

    /**
     * UI 光圈百分比转显示文本。
     *
     * 用于日志，例如：
     * 0   -> F16
     * 100 -> F1.8
     */
    @JvmStatic
    fun irisPercentToDisplayText(percent: Int): String {
        val code = irisPercentToTamronCode(percent)
        return irisCodeToDisplayText(code)
    }

    @JvmStatic
    fun focusNearLimitPercentToMm(percent: Int): Int {
        val safePercent = percent.coerceIn(0, 100)
        return roundToInt(
            FOCUS_NEAR_LIMIT_MAX_MM -
                    (FOCUS_NEAR_LIMIT_MAX_MM - FOCUS_NEAR_LIMIT_MIN_MM) * safePercent / 100f
        )
    }

    @JvmStatic
    fun focusNearLimitPercentToDisplayText(percent: Int): String {
        return "${focusNearLimitPercentToMm(percent)} mm"
    }

    @JvmStatic
    fun zoomPositionToMagnification(position: Int): Float {
        val safePosition = position.coerceIn(ZOOM_POSITION_MIN, ZOOM_POSITION_DIGITAL_MAX)
        return if (safePosition <= ZOOM_POSITION_OPTICAL_MAX) {
            opticalZoomPositionToRatio(safePosition)
        } else {
            10f * interpolateByPosition(safePosition, DIGITAL_ZOOM_POSITIONS, DIGITAL_ZOOM_RATIOS)
        }
    }

    @JvmStatic
    fun zoomPositionToDisplayText(position: Int?): String {
        if (position == null) return "--"
        return "x${formatZoomRatio(zoomPositionToMagnification(position))}"
    }

    /**
     * 机芯 Iris code 转显示文本。
     */
    @JvmStatic
    fun irisCodeToDisplayText(code: Int): String {
        return when (code) {
            0x00 -> "CLOSE"
            0x03 -> "F22"
            0x04 -> "F16"
            0x05 -> "F14"
            0x06 -> "F11"
            0x07 -> "F9.6"
            0x08 -> "F8"
            0x09 -> "F6.8"
            0x0A -> "F5.6"
            0x0B -> "F4.8"
            0x0C -> "F4"
            0x0D -> "F3.4"
            0x0E -> "F2.8"
            0x0F -> "F2.4"
            0x10 -> "F2"
            0x11 -> "F1.8"
            else -> "未知光圈(0x${code.toString(16).uppercase().padStart(2, '0')})"
        }
    }

    /**
     * 当前 App 允许下发的最小 Iris code。
     */
    @JvmStatic
    fun minWritableIrisCode(): Int {
        return IRIS_SETTING_CODES.firstOrNull() ?: 0x04
    }

    /**
     * 当前 App 允许下发的最大 Iris code。
     */
    @JvmStatic
    fun maxWritableIrisCode(): Int {
        return IRIS_SETTING_CODES.lastOrNull() ?: 0x11
    }

    /**
     * UI 曝光增益百分比 0~100 转机芯增益码值 0x00~0x1C。
     */
    @JvmStatic
    fun exposureGainPercentToTamronCode(percent: Int): Int {
        val safePercent = percent.coerceIn(0, 100)
        return roundToInt(safePercent * EXP_GAIN_MAX_CODE / 100f)
            .coerceIn(0x00, EXP_GAIN_MAX_CODE)
    }

    /**
     * UI 白平衡 R/B 百分比 0~100 转机芯 R/B Gain 码值 0x00~0xFF。
     */
    @JvmStatic
    fun whiteBalanceGainPercentToTamronCode(percent: Int): Int {
        val safePercent = percent.coerceIn(0, 100)
        return roundToInt(safePercent * WB_GAIN_MAX_CODE / 100f)
            .coerceIn(0x00, WB_GAIN_MAX_CODE)
    }

    private fun findNearestIndex(code: Int, codes: IntArray): Int {
        var nearestIndex = 0
        var minDiff = kotlin.math.abs(code - codes[0])

        for (i in 1 until codes.size) {
            val diff = kotlin.math.abs(code - codes[i])
            if (diff < minDiff) {
                minDiff = diff
                nearestIndex = i
            }
        }

        return nearestIndex
    }

    private fun opticalZoomPositionToRatio(position: Int): Float {
        for (i in 1 until OPTICAL_ZOOM_POINTS.size) {
            val end = OPTICAL_ZOOM_POINTS[i]
            if (position <= end) {
                val start = OPTICAL_ZOOM_POINTS[i - 1]
                val fraction = if (end == start) 0f else (position - start).toFloat() / (end - start)
                return i + fraction
            }
        }
        return 10f
    }

    private fun interpolateByPosition(position: Int, positions: IntArray, ratios: FloatArray): Float {
        for (i in 1 until positions.size) {
            val end = positions[i]
            if (position <= end) {
                val start = positions[i - 1]
                val startRatio = ratios[i - 1]
                val endRatio = ratios[i]
                val fraction = if (end == start) 0f else (position - start).toFloat() / (end - start)
                return startRatio + (endRatio - startRatio) * fraction
            }
        }
        return ratios.last()
    }

    private fun formatZoomRatio(value: Float): String {
        val roundedTenth = roundToInt(value * 10f)
        return if (roundedTenth % 10 == 0) {
            (roundedTenth / 10).toString()
        } else {
            "${roundedTenth / 10}.${roundedTenth % 10}"
        }
    }

    private fun roundToInt(value: Float): Int {
        return (value + 0.5f).toInt()
    }
}
