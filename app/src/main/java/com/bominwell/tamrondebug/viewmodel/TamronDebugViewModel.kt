package com.bominwell.tamrondebug.viewmodel

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Environment
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bominwell.cctvsdk.DayNightMode
import com.bominwell.cctvsdk.ExposureMode
import com.bominwell.cctvsdk.FlickerMode
import com.bominwell.cctvsdk.RateControlMode
import com.bominwell.cctvsdk.TamronCameraConfigConverter
import com.bominwell.cctvsdk.TamronHttpViscaClient
import com.bominwell.cctvsdk.VideoEncodeConfig
import com.bominwell.cctvsdk.WhiteBalanceMode
import com.bominwell.tamrondebug.R
import com.bominwell.tamrondebug.data.TamronRepository
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class TamronDebugViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(TamronDebugState())
    val state: StateFlow<TamronDebugState> = _state.asStateFlow()

    private val repository = TamronRepository()
    private val commandMutex = Mutex()
    private val appContext: Application = getApplication()
    private val paramsDir: File
        get() = File(getApplication<Application>().filesDir, "tamron_camera_params")
    private val legacyParamsFile: File
        get() = File(getApplication<Application>().filesDir, "tamron_camera_params.json")

    init {
        refreshParamRecords()
        refreshMediaRecords()
    }

    private fun text(@StringRes resId: Int, vararg args: Any): String {
        return appContext.getString(resId, *args)
    }

    private fun onOff(value: Boolean): String {
        return text(if (value) R.string.state_on else R.string.state_off)
    }

    private fun WhiteBalanceMode.localizedLabel(): String {
        return text(
            when (this) {
                WhiteBalanceMode.ATW1 -> R.string.wb_atw1
                WhiteBalanceMode.INDOOR -> R.string.wb_indoor
                WhiteBalanceMode.OUTDOOR -> R.string.wb_outdoor
                WhiteBalanceMode.ONE_PUSH -> R.string.wb_one_push
                WhiteBalanceMode.ATW2 -> R.string.wb_atw2
                WhiteBalanceMode.MANUAL -> R.string.wb_manual
            }
        )
    }

    private fun ExposureMode.localizedLabel(): String {
        return text(
            when (this) {
                ExposureMode.FULL_AUTO -> R.string.exposure_full_auto
                ExposureMode.MANUAL -> R.string.exposure_manual
                ExposureMode.SHUTTER_PRIORITY -> R.string.exposure_shutter_priority
                ExposureMode.IRIS_PRIORITY -> R.string.exposure_iris_priority
            }
        )
    }

    private fun DayNightMode.localizedLabel(): String {
        return text(
            when (this) {
                DayNightMode.DAY -> R.string.day_night_day
                DayNightMode.NIGHT -> R.string.day_night_night
                DayNightMode.AUTO -> R.string.day_night_auto
            }
        )
    }

    private fun FlickerMode.localizedLabel(): String {
        return text(
            when (this) {
                FlickerMode.AUTO -> R.string.flicker_auto
                FlickerMode.OFF -> R.string.flicker_off
            }
        )
    }

    fun updateRtspUrl(value: String) = _state.update { it.copy(rtspUrl = value) }
    fun updatePingIp(value: String) = _state.update { it.copy(pingIp = value.trim()) }
    fun setHardwareDecode(enable: Boolean) = _state.update { it.copy(useHardwareDecode = enable, playKey = it.playKey + 1) }
    fun restartPlayer() = _state.update { it.copy(playKey = it.playKey + 1) }

    fun pingOnce() = viewModelScope.launch {
        val host = state.value.pingIp.trim()
        if (host.isBlank()) {
            appendLog("Ping 失败: 请输入 IP")
            return@launch
        }

        appendLog("Ping $host ...")
        try {
            val result = withContext(Dispatchers.IO) { executePing(host) }
            appendLog(result)
        } catch (e: Exception) {
            appendLog("Ping $host 异常: ${e.message}")
        }
    }

    fun refreshParamRecords() {
        _state.update { it.copy(paramRecords = loadParamRecords()) }
    }

    fun refreshMediaRecords() {
        _state.update { it.copy(mediaRecords = loadMediaRecords()) }
    }

    fun openMediaRecord(fileName: String) {
        val file = resolveMediaFile(fileName)
        if (file == null || !file.exists()) {
            refreshMediaRecords()
            appendLog("打开回放失败: 未找到文件 $fileName")
            return
        }

        val app = getApplication<Application>()
        val mimeType = mediaMimeType(file)
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            clipData = ClipData.newUri(app.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            app.startActivity(intent)
            appendLog("打开回放: ${file.absolutePath}")
        } catch (e: ActivityNotFoundException) {
            appendLog("打开回放失败: 未找到可播放该文件的系统应用")
        } catch (e: Exception) {
            appendLog("打开回放失败: ${e.message}")
        }
    }

    fun deleteMediaRecord(fileName: String) {
        val file = resolveMediaFile(fileName)
        if (file == null || !file.exists()) {
            refreshMediaRecords()
            appendLog("删除回放记录失败: 未找到文件 $fileName")
            return
        }

        val path = file.absolutePath
        if (file.delete()) {
            MediaScannerConnection.scanFile(getApplication(), arrayOf(path), null, null)
            refreshMediaRecords()
            appendLog("已删除回放记录: $path")
        } else {
            appendLog("删除回放记录失败: $path")
        }
    }

    fun recordCameraParams(recordName: String) = viewModelScope.launch {
        val displayName = recordName.trim()
        if (displayName.isBlank()) {
            appendLog("记录机芯参数失败: 请输入记录名称")
            return@launch
        }

        commandMutex.withLock {
            try {
                _state.update { it.copy(busy = true) }
                val raw = repository.camera.queryRawState()
                val snapshot = buildSnapshot(raw, state.value).copy(
                    recordName = displayName,
                    createdAtMillis = System.currentTimeMillis()
                )
                val file = recordFileForName(displayName)
                file.writeText(snapshot.toJson().toString(2), Charsets.UTF_8)
                applySnapshotToState(snapshot)
                refreshParamRecords()
                appendLog("已记录机芯参数 [$displayName]: ${file.absolutePath}")
            } catch (e: Exception) {
                appendLog("记录机芯参数失败: ${e.message}")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun restoreRecordedParams(fileName: String) = viewModelScope.launch {
        val safeFileName = File(fileName).name
        if (safeFileName.isBlank()) {
            appendLog("恢复机芯参数失败: 请选择记录")
            return@launch
        }

        commandMutex.withLock {
            try {
                _state.update { it.copy(busy = true) }
                val file = recordFileByFileName(safeFileName)
                if (!file.exists()) {
                    refreshParamRecords()
                    appendLog("恢复机芯参数失败: 未找到记录文件 ${file.absolutePath}")
                    return@withLock
                }

                val snapshot = RecordedCameraParams.fromJson(JSONObject(file.readText(Charsets.UTF_8)))
                applySnapshotToState(snapshot)

                val failed = mutableListOf<String>()
                suspend fun send(name: String, block: suspend () -> TamronHttpViscaClient.ViscaHttpResult) {
                    val result = block()
                    if (!result.isCommandFinished()) {
                        failed += "$name(${formatViscaFailure(result)})"
                    }
                }

                send("zoomStop") { repository.camera.zoomStop() }
                send("focusStop") { repository.camera.focusStop() }
                send("focusMode") {
                    if (snapshot.focusAuto) repository.camera.focusAuto() else repository.camera.focusManual()
                }
                send("focusNearLimit") { repository.camera.setFocusNearLimitPercent(snapshot.focusNearLimitPercent) }
                send("whiteBalanceMode") { repository.camera.setWhiteBalanceMode(snapshot.wbMode) }
                send("whiteBalanceR") { repository.camera.setWhiteBalanceRGainPercent(snapshot.wbRed) }
                send("whiteBalanceB") { repository.camera.setWhiteBalanceBGainPercent(snapshot.wbBlue) }

                send("exposureMode") { repository.camera.setExposureMode(snapshot.exposureMode) }
                delay(EXPOSURE_MODE_SETTLE_MS)

                if (snapshot.exposureMode == ExposureMode.MANUAL) {
                    send("iris") { repository.camera.setIrisPercent(snapshot.iris) }
                    send("shutter") { repository.camera.setShutterIndex(snapshot.shutter) }
                    send("gain") { repository.camera.setGainPercent(snapshot.gain) }
                } else {
                    send("exposureComp") { repository.camera.setExposureCompPercent(snapshot.exposureComp) }
                }

                send("sharpness") { repository.camera.setSharpnessPercent(snapshot.sharpness) }
                send("saturation") { repository.camera.setSaturationPercent(snapshot.saturation) }
                send("hue") { repository.camera.setHuePercent(snapshot.hue) }
                send("gamma") { repository.camera.setGamma(snapshot.gamma) }
                send("dayNight") { repository.camera.setDayNight(snapshot.dayNightMode, snapshot.autoIcrThreshold) }
                send("noiseReduction") { repository.camera.setNoiseReduction(snapshot.nr3d, snapshot.nr2d) }
                send("wdr") { repository.camera.setWdr(snapshot.wdr) }
                send("blc") { repository.camera.setBackLight(snapshot.blc) }
                send("eis") { repository.camera.setEis(snapshot.eis) }
                send("digitalZoom") { repository.camera.setDigitalZoom(snapshot.digitalZoom) }
                send("flicker") { repository.camera.setFlickerMode(snapshot.flickerMode) }
                send("defog") { repository.camera.setDefog(snapshot.defog, snapshot.defogLevel) }

                val title = snapshot.recordName.ifBlank { file.nameWithoutExtension }
                if (failed.isEmpty()) {
                    appendLog("已从记录 [$title] 恢复机芯参数")
                } else {
                    appendLog("恢复记录 [$title] 部分失败: ${failed.joinToString("; ")}")
                }
            } catch (e: Exception) {
                appendLog("恢复机芯参数失败: ${e.message}")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun restoreRecordedParams() {
        val first = state.value.paramRecords.firstOrNull()
        if (first == null) {
            appendLog("恢复机芯参数失败: 暂无参数记录")
        } else {
            restoreRecordedParams(first.fileName)
        }
    }

    fun savePowerOnConfig() = runCommand(text(R.string.command_save_power_on_config)) {
        val result = repository.camera.savePowerOnSettings()
        if (result.success && !result.hasViscaError) {
            delay(POWER_ON_CONFIG_SETTLE_MS)
        }
        result
    }

    fun deleteParamRecord(fileName: String) {
        val safeFileName = File(fileName).name
        if (safeFileName.isBlank()) {
            appendLog("删除参数记录失败: 请选择记录")
            return
        }

        try {
            val file = recordFileByFileName(safeFileName)
            if (!file.exists()) {
                refreshParamRecords()
                appendLog("删除参数记录失败: 未找到记录文件 ${file.absolutePath}")
                return
            }

            val displayName = loadParamRecords()
                .firstOrNull { it.fileName == file.name }
                ?.displayName
                ?: file.nameWithoutExtension

            if (file.delete()) {
                refreshParamRecords()
                appendLog("已删除参数记录 [$displayName]")
            } else {
                appendLog("删除参数记录失败: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            appendLog("删除参数记录失败: ${e.message}")
        }
    }

    fun zoomTeleStart() = runCommand(text(R.string.command_zoom_tele)) { repository.camera.zoomTele(state.value.zoomSpeed) }
    fun zoomWideStart() = runCommand(text(R.string.command_zoom_wide)) { repository.camera.zoomWide(state.value.zoomSpeed) }
    fun zoomStop() = runCommand(text(R.string.command_zoom_stop)) {
        val result = repository.camera.zoomStop()
        refreshZoomPosition()
        result
    }

    fun focusFarStart() = runCommand(text(R.string.command_focus_far)) {
        ensureManualFocusForJog()
        repository.camera.focusFar(state.value.focusSpeed)
    }

    fun focusNearStart() = runCommand(text(R.string.command_focus_near)) {
        ensureManualFocusForJog()
        repository.camera.focusNear(state.value.focusSpeed)
    }

    fun focusStop() = runCommand(text(R.string.command_focus_stop)) { repository.camera.focusStop() }
    fun focusAuto() = setFocusAutoMode(true)
    fun focusManual() = setFocusAutoMode(false)
    fun onePushFocus() = runCommand(text(R.string.button_one_push_focus)) { repository.camera.onePushFocus(true) }

    fun setFocusAutoMode(enable: Boolean) {
        _state.update { it.copy(focusAuto = enable) }
        runCommand(text(if (enable) R.string.command_auto_focus else R.string.command_manual_focus)) {
            if (enable) repository.camera.focusAuto() else repository.camera.focusManual()
        }
    }

    fun setZoomSpeed(value: Int) = _state.update { it.copy(zoomSpeed = value.coerceIn(0, 7)) }
    fun setFocusSpeed(value: Int) = _state.update { it.copy(focusSpeed = value.coerceIn(0, 7)) }

    fun setFocusNearLimit(value: Int) {
        val next = value.coerceIn(0, 100)
        _state.update { it.copy(focusNearLimitPercent = next) }
        runCommand(
            text(
                R.string.command_set_focus_near_limit,
                TamronCameraConfigConverter.focusNearLimitPercentToDisplayText(next)
            )
        ) {
            repository.camera.setFocusNearLimitPercent(next)
        }
    }

    fun setWhiteBalanceMode(mode: WhiteBalanceMode) {
        _state.update { it.copy(wbMode = mode) }
        runCommand(text(R.string.command_wb_mode, mode.localizedLabel())) { repository.camera.setWhiteBalanceMode(mode) }
    }

    fun onePushWhiteBalance() = runCommand(text(R.string.wb_one_push)) { repository.camera.onePushWhiteBalance() }

    fun setWbRed(value: Int) {
        _state.update { it.copy(wbRed = value.coerceIn(0, 100)) }
        runCommand(text(R.string.command_set_wb_r)) {
            repository.camera.setWhiteBalanceRGainPercent(state.value.wbRed)
        }
    }

    fun setWbBlue(value: Int) {
        _state.update { it.copy(wbBlue = value.coerceIn(0, 100)) }
        runCommand(text(R.string.command_set_wb_b)) {
            repository.camera.setWhiteBalanceBGainPercent(state.value.wbBlue)
        }
    }

    fun setExposureMode(mode: ExposureMode) {
        _state.update { it.copy(exposureMode = mode) }
        runCommand(text(R.string.command_exposure_mode, mode.localizedLabel())) { applyExposureMode(mode) }
    }

    fun setIris(value: Int) {
        val next = value.coerceIn(0, 100)
        _state.update { it.copy(iris = next, exposureMode = ExposureMode.MANUAL) }
        runCommand(text(R.string.command_set_iris, TamronCameraConfigConverter.irisPercentToDisplayText(next))) {
            sendSingleManualExposureValue(ManualExposureField.IRIS)
        }
    }

    fun setShutter(value: Int) {
        val next = value.coerceIn(0, 19)
        _state.update { it.copy(shutter = next, exposureMode = ExposureMode.MANUAL) }
        runCommand(text(R.string.command_set_shutter)) {
            sendSingleManualExposureValue(ManualExposureField.SHUTTER)
        }
    }

    fun setGain(value: Int) {
        val next = value.coerceIn(0, 100)
        _state.update { it.copy(gain = next, exposureMode = ExposureMode.MANUAL) }
        runCommand(text(R.string.command_set_gain)) {
            sendSingleManualExposureValue(ManualExposureField.GAIN)
        }
    }

    fun setExposureComp(value: Int) {
        val next = value.coerceIn(0, 100)
        _state.update { it.copy(exposureComp = next, brightness = next) }
        runCommand(text(R.string.command_set_exposure_comp)) {
            sendFinalValue { repository.camera.setExposureCompPercent(next) }
        }
    }

    fun setBrightness(value: Int) {
        val next = value.coerceIn(0, 100)
        _state.update { it.copy(brightness = next, exposureComp = next) }
        runCommand(text(R.string.command_set_brightness)) {
            sendFinalValue { repository.camera.setBrightnessPercent(next) }
        }
    }

    fun setSaturation(value: Int) {
        val next = value.coerceIn(0, 100)
        _state.update { it.copy(saturation = next) }
        runCommand(text(R.string.command_set_saturation)) {
            sendFinalValue { repository.camera.setSaturationPercent(next) }
        }
    }

    fun setSharpness(value: Int) {
        val next = value.coerceIn(0, 100)
        _state.update { it.copy(sharpness = next) }
        runCommand(text(R.string.command_set_sharpness)) {
            sendFinalValue { repository.camera.setSharpnessPercent(next) }
        }
    }

    fun setContrast(value: Int) {
        val contrast = value.coerceIn(0, 100)
        _state.update { it.copy(contrast = contrast, gamma = contrastToGammaCode(contrast)) }
        runCommand(text(R.string.command_set_contrast)) {
            sendFinalValue { repository.camera.setContrastPercent(contrast) }
        }
    }

    fun setHue(value: Int) {
        val next = value.coerceIn(0, 100)
        _state.update { it.copy(hue = next) }
        runCommand(text(R.string.command_set_hue)) {
            sendFinalValue { repository.camera.setHuePercent(next) }
        }
    }

    fun setGamma(value: Int) {
        val gamma = value.coerceIn(0, 3)
        _state.update { it.copy(gamma = gamma, contrast = gammaCodeToContrast(gamma)) }
        runCommand(text(R.string.command_set_gamma)) {
            sendFinalValue { repository.camera.setGamma(gamma) }
        }
    }

    fun setDayNightMode(mode: DayNightMode) {
        _state.update { it.copy(dayNightMode = mode) }
        runCommand(text(R.string.command_day_night_mode, mode.localizedLabel())) {
            repository.camera.setDayNight(mode, state.value.autoIcrThreshold)
        }
    }

    fun setAutoIcrThreshold(value: Int) {
        _state.update { it.copy(autoIcrThreshold = value.coerceIn(0, 28)) }
        if (state.value.dayNightMode == DayNightMode.AUTO) {
            runCommand(text(R.string.command_set_auto_icr_threshold)) {
                repository.camera.setDayNight(DayNightMode.AUTO, state.value.autoIcrThreshold)
            }
        }
    }

    fun setNr2d(value: Int) {
        _state.update { it.copy(nr2d = value.coerceIn(0, 5)) }
        runCommand(text(R.string.command_set_2dnr)) {
            repository.camera.setNoiseReduction(state.value.nr3d, state.value.nr2d)
        }
    }

    fun setNr3d(value: Int) {
        _state.update { it.copy(nr3d = value.coerceIn(0, 5)) }
        runCommand(text(R.string.command_set_3dnr)) {
            repository.camera.setNoiseReduction(state.value.nr3d, state.value.nr2d)
        }
    }

    fun setWdr(value: Boolean) {
        _state.update { it.copy(wdr = value) }
        runCommand(text(R.string.command_switch_state, text(R.string.label_wdr), onOff(value))) {
            repository.camera.setWdr(value)
        }
    }

    fun setBlc(value: Boolean) {
        _state.update { it.copy(blc = value) }
        runCommand(text(R.string.command_switch_state, text(R.string.label_blc), onOff(value))) {
            repository.camera.setBackLight(value)
        }
    }

    fun setEis(value: Boolean) {
        _state.update { it.copy(eis = value) }
        runCommand(text(R.string.command_switch_state, text(R.string.label_eis), onOff(value))) {
            repository.camera.setEis(value)
        }
    }

    fun setDigitalZoom(value: Boolean) {
        _state.update { it.copy(digitalZoom = value) }
        runCommand(text(R.string.command_switch_state, text(R.string.label_digital_zoom), onOff(value))) {
            repository.camera.setDigitalZoom(value)
        }
    }

    fun setFlickerMode(mode: FlickerMode) {
        _state.update { it.copy(flickerMode = mode) }
        runCommand(text(R.string.command_flicker_mode, mode.localizedLabel())) {
            repository.camera.setFlickerMode(mode)
        }
    }

    fun setDefog(value: Boolean) {
        _state.update { it.copy(defog = value) }
        runCommand(text(R.string.command_switch_state, text(R.string.label_defog), onOff(value))) {
            repository.camera.setDefog(value, state.value.defogLevel)
        }
    }

    fun setDefogLevel(value: Int) {
        _state.update { it.copy(defogLevel = value.coerceIn(0, 3)) }
        if (state.value.defog) {
            runCommand(text(R.string.command_set_defog_level)) {
                repository.camera.setDefog(true, state.value.defogLevel)
            }
        }
    }

    fun setResolution(value: String) {
        _state.update { it.copy(resolution = normalizeResolution(value)) }
    }

    fun setFrameRate(value: Int) = _state.update { it.copy(frameRate = normalizeFrameRate(value)) }
    fun setBitRate(value: Int) = _state.update { it.copy(bitRateKbps = normalizeBitRate(value)) }
    fun setRateControl(mode: RateControlMode) = _state.update { it.copy(rateControlMode = mode) }

    fun applyEncodeConfig() = runCommand(text(R.string.command_set_encode_config)) {
        repository.configureEncoderBaseUrl(encoderBaseUrlFromPingIp())
        repository.applyEncode(
            VideoEncodeConfig(
                resolution = normalizeResolution(state.value.resolution),
                frameRate = normalizeFrameRate(state.value.frameRate),
                bitRateKbps = normalizeBitRate(state.value.bitRateKbps),
                rateControlMode = state.value.rateControlMode
            )
        )
    }

    fun updateRawHex(value: String) = _state.update { it.copy(rawHex = value) }

    fun sendRawHex() = viewModelScope.launch {
        commandMutex.withLock {
            val hex = state.value.rawHex
            try {
                _state.update { it.copy(busy = true) }
                val reply = repository.sendRawHex(hex)
                appendLog("RAW $hex -> $reply")
            } catch (e: Exception) {
                appendLog("RAW 失败: ${e.message}")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun queryCameraConfig() = viewModelScope.launch {
        commandMutex.withLock {
            try {
                _state.update { it.copy(busy = true) }
                val raw = repository.camera.queryRawState()
                applySnapshotToState(buildSnapshot(raw, state.value))
                val encodeResult = runCatching {
                    repository.configureEncoderBaseUrl(encoderBaseUrlFromPingIp())
                    repository.queryEncode()
                }
                encodeResult.onSuccess { config ->
                    applyEncodeConfigToState(config)
                    val frameRate = resolvedEncodeFrameRate(config)
                    val frameRateSource = if (state.value.playbackFrameRate > 0) "当前播放流" else "编码板接口"
                    appendLog(
                        "编码板参数: 分辨率=${normalizeResolution(config.resolution)}, " +
                                "帧率=$frameRate($frameRateSource), " +
                                "码率=${normalizeBitRate(config.bitRateKbps)}kbps"
                    )
                }
                if (encodeResult.isFailure) {
                    appendLog("编码板参数读取失败: ${encodeResult.exceptionOrNull()?.message}")
                }
                appendLog(if (encodeResult.isSuccess) "读取机芯参数和编码板参数成功" else "读取机芯参数成功")
            } catch (e: Exception) {
                appendLog("读取机芯参数失败: ${e.message}")
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun appendPlayerLog(text: String) {
        appendLog(text)
    }

    fun updatePlaybackStats(frameRate: Int, bitRateKbps: Int) {
        _state.update {
            if (frameRate <= 0 && bitRateKbps <= 0) {
                return@update it.copy(playbackFrameRate = 0, playbackBitRateKbps = 0)
            }
            val normalizedPlaybackFrameRate = frameRate.takeIf { value -> value > 0 }
                ?.let(::normalizeFrameRate)
            val displayFrameRate = normalizedPlaybackFrameRate
                ?: it.playbackFrameRate.takeIf { value -> value > 0 }
                ?: it.frameRate.takeIf { value -> bitRateKbps > 0 && value > 0 }
                ?: 0
            it.copy(
                playbackFrameRate = displayFrameRate,
                playbackBitRateKbps = bitRateKbps.coerceAtLeast(0),
                frameRate = normalizedPlaybackFrameRate ?: it.frameRate
            )
        }
    }

    private suspend fun ensureManualFocusForJog() {
        if (state.value.focusAuto) {
            repository.camera.focusManual()
            _state.update { it.copy(focusAuto = false) }
        }
    }

    /**
     * 只切换曝光模式，不再自动重发光圈/快门/增益。
     *
     * 原来的逻辑在切 Manual 后会立刻下发 state 中的 iris/shutter/gain。
     * 如果 state 不是机芯真实值，容易把用户没有想改的曝光参数一起覆盖。
     */
    private suspend fun applyExposureMode(mode: ExposureMode): TamronHttpViscaClient.ViscaHttpResult {
        val modeResult = repository.camera.setExposureMode(mode)
        if (modeResult.isCommandFinished() && mode == ExposureMode.MANUAL) {
            delay(EXPOSURE_MODE_SETTLE_MS)
        }
        return modeResult
    }

    /**
     * 单独设置某一个手动曝光参数。
     *
     * 设置光圈时只发光圈，不再连带重发快门和增益。
     * 设置快门时只发快门。
     * 设置增益时只发增益。
     */
    private suspend fun sendSingleManualExposureValue(
        field: ManualExposureField,
        ensureManualMode: Boolean = true
    ): TamronHttpViscaClient.ViscaHttpResult {
        if (ensureManualMode) {
            val modeResult = repository.camera.setExposureMode(ExposureMode.MANUAL)
            if (!modeResult.isCommandFinished()) {
                return modeResult
            }
            delay(EXPOSURE_MODE_SETTLE_MS)
        }

        val current = state.value
        return when (field) {
            ManualExposureField.IRIS -> sendFinalValue {
                repository.camera.setIrisPercent(current.iris)
            }
            ManualExposureField.SHUTTER -> sendFinalValue {
                repository.camera.setShutterIndex(current.shutter)
            }
            ManualExposureField.GAIN -> sendFinalValue {
                repository.camera.setGainPercent(current.gain)
            }
        }
    }

    private suspend fun sendFinalValue(
        block: suspend () -> TamronHttpViscaClient.ViscaHttpResult
    ): TamronHttpViscaClient.ViscaHttpResult {
        val first = block()
        delay(FINAL_VALUE_RESEND_DELAY_MS)
        val second = block()
        return when {
            second.isCommandFinished() -> second
            first.isCommandFinished() -> first
            second.success && !second.hasViscaError -> second
            first.success && !first.hasViscaError -> first
            else -> second
        }
    }

    private fun runCommand(name: String, block: suspend () -> Any) {
        viewModelScope.launch {
            commandMutex.withLock {
                try {
                    _state.update { it.copy(busy = true) }
                    val reply = block()
                    val logMsg = when (reply) {
                        is TamronHttpViscaClient.ViscaHttpResult -> formatCommandLog(name, reply)
                        is ByteArray -> text(
                            R.string.log_success,
                            name,
                            reply.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                        )
                        else -> text(R.string.log_success, name, reply)
                    }
                    appendLog(logMsg)
                } catch (e: Exception) {
                    appendLog(text(R.string.log_exception, name, e.message.orEmpty()))
                } finally {
                    _state.update { it.copy(busy = false) }
                }
            }
        }
    }

    private fun formatCommandLog(
        name: String,
        result: TamronHttpViscaClient.ViscaHttpResult
    ): String {
        val tx = result.sentHex.ifBlank { "empty" }
        val rx = result.resultHex.ifBlank { "empty" }
        val frames = result.responseFramesHex
            .takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = ", frames=[", postfix = "]")
            .orEmpty()
        val detail = "tx=$tx, rx=$rx$frames, ack=${result.hasAck}, completion=${result.hasCompletion}"
        return when {
            result.isCommandFinished() -> {
                text(R.string.log_success, name, detail)
            }
            result.hasViscaError -> {
                text(R.string.log_failure_with_detail, name, formatViscaFailure(result), detail)
            }
            result.success -> {
                text(R.string.log_unconfirmed, name, detail)
            }
            else -> {
                text(R.string.log_failure_with_detail, name, formatViscaFailure(result), detail)
            }
        }
    }

    private fun formatViscaFailure(result: TamronHttpViscaClient.ViscaHttpResult): String {
        return result.error
            ?: result.jsonRpcErrorMessage
            ?: result.resultHex.takeIf { it.isNotBlank() }
            ?: "sent=${result.sentHex}, ack=${result.hasAck}, completion=${result.hasCompletion}"
    }

    private fun appendLog(text: String) {
        _state.update {
            val next = if (it.log.isBlank()) text else "${it.log}\n$text"
            it.copy(log = next.takeLast(8000))
        }
    }

    private fun executePing(host: String): String {
        val process = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "1", host)
            .redirectErrorStream(true)
            .start()

        val finished = process.waitFor(2500, TimeUnit.MILLISECONDS)
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!finished) {
            process.destroyForcibly()
            return "Ping $host 超时"
        }

        val summary = output.lineSequence()
            .map { it.trim() }
            .firstOrNull {
                it.contains("bytes from", ignoreCase = true) ||
                        it.contains("time=", ignoreCase = true) ||
                        it.contains("packet", ignoreCase = true)
            }
            ?: output.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }
            ?: "无输出"

        return if (process.exitValue() == 0) {
            "Ping $host 成功: $summary"
        } else {
            "Ping $host 失败: $summary"
        }
    }

    private fun loadMediaRecords(): List<MediaRecord> {
        return mediaDirectories()
            .flatMap { dir ->
                dir.listFiles { file -> file.isFile && isPlaybackFile(file) }?.toList() ?: emptyList()
            }
            .distinctBy { it.absolutePath }
            .map { file ->
                MediaRecord(
                    fileName = file.name,
                    displayName = file.name,
                    typeLabel = mediaTypeLabel(file),
                    mimeType = mediaMimeType(file),
                    lastModifiedMillis = file.lastModified()
                )
            }
            .sortedByDescending { it.lastModifiedMillis }
    }

    private fun resolveMediaFile(fileName: String): File? {
        val cleanName = File(fileName).name
        if (cleanName.isBlank()) return null

        return mediaDirectories()
            .map { dir -> File(dir, cleanName) }
            .firstOrNull { file -> file.exists() && file.isFile }
    }

    private fun mediaDirectories(): List<File> {
        val app = getApplication<Application>()
        val movieRoot = app.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(app.filesDir, "movies")
        val pictureRoot = app.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: File(app.filesDir, "pictures")

        return listOf(
            File(movieRoot, "TamronCameraDebug"),
            File(pictureRoot, "TamronCameraDebug"),
            File(File(app.filesDir, "movies"), "TamronCameraDebug"),
            File(File(app.filesDir, "pictures"), "TamronCameraDebug")
        )
    }

    private fun isPlaybackFile(file: File): Boolean {
        return when (file.extension.lowercase()) {
            "mp4", "jpg", "jpeg", "png" -> true
            else -> false
        }
    }

    private fun mediaTypeLabel(file: File): String {
        return when (file.extension.lowercase()) {
            "mp4" -> text(R.string.media_type_record)
            else -> text(R.string.media_type_screenshot)
        }
    }

    private fun mediaMimeType(file: File): String {
        return when (file.extension.lowercase()) {
            "mp4" -> "video/mp4"
            "png" -> "image/png"
            else -> "image/jpeg"
        }
    }

    private fun loadParamRecords(): List<CameraParamRecord> {
        return try {
            migrateLegacyParamsFileIfNeeded()
            val dir = paramsDir
            if (!dir.exists()) return emptyList()

            dir.listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) }
                ?.map { file ->
                    val json = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull()
                    val displayName = json
                        ?.optString("recordName")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: file.nameWithoutExtension
                    val createdAtMillis = json?.optLong("createdAtMillis", file.lastModified()) ?: file.lastModified()
                    CameraParamRecord(
                        fileName = file.name,
                        displayName = displayName,
                        createdAtMillis = createdAtMillis,
                        filePath = file.absolutePath
                    )
                }
                ?.sortedByDescending { it.createdAtMillis }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun ensureParamsDir(): File {
        val dir = paramsDir
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun migrateLegacyParamsFileIfNeeded() {
        val legacy = legacyParamsFile
        if (!legacy.exists()) return

        val target = File(ensureParamsDir(), "default.json")
        if (target.exists()) return

        val text = legacy.readText(Charsets.UTF_8)
        val targetText = runCatching {
            JSONObject(text).apply {
                if (!has("recordName")) put("recordName", text(R.string.default_record_name))
                if (!has("createdAtMillis")) put("createdAtMillis", legacy.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis())
            }.toString(2)
        }.getOrElse { text }
        target.writeText(targetText, Charsets.UTF_8)
    }

    private fun recordFileForName(recordName: String): File {
        val sanitized = recordName
            .trim()
            .replace(Regex("\\s+"), "_")
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(64)
        val withoutExtension = if (sanitized.endsWith(".json", ignoreCase = true)) {
            sanitized.dropLast(5)
        } else {
            sanitized
        }
        val baseName = withoutExtension.takeUnless { it.isBlank() || it == "." || it == ".." }
            ?: "record_${System.currentTimeMillis()}"
        return File(ensureParamsDir(), "$baseName.json")
    }

    private fun recordFileByFileName(fileName: String): File {
        val cleanName = File(fileName).name
        val jsonName = if (cleanName.endsWith(".json", ignoreCase = true)) cleanName else "$cleanName.json"
        return File(ensureParamsDir(), jsonName)
    }

    private fun buildSnapshot(
        raw: TamronHttpViscaClient.CameraConfigRawState,
        fallback: TamronDebugState
    ): RecordedCameraParams {
        val info = TamronCameraConfigConverter.toCameraConfigInfo(raw)
        val exposureComp = raw.expCompCode?.let { codeToPercent(it - 0x01, 0x0C) } ?: fallback.exposureComp
        val nrCode = raw.nrCode
        val focusAuto = when (raw.focusModeCode) {
            0x02 -> true
            0x03 -> false
            else -> fallback.focusAuto
        }

        return RecordedCameraParams(
            zoomSpeed = fallback.zoomSpeed,
            zoomPositionCode = raw.zoomPositionCode ?: fallback.zoomPositionCode,
            focusSpeed = fallback.focusSpeed,
            focusAuto = focusAuto,
            focusNearLimitPercent = fallback.focusNearLimitPercent,
            wbMode = enumByCode(raw.wbModeCode, WhiteBalanceMode.values(), fallback.wbMode) { it.code },
            wbRed = raw.wbRedCode?.let { codeToPercent(it, 0xFF) } ?: info.wbRed,
            wbBlue = raw.wbBlueCode?.let { codeToPercent(it, 0xFF) } ?: info.wbBlue,
            exposureMode = enumByCode(raw.expModeCode, ExposureMode.values(), fallback.exposureMode) { it.code },
            iris = raw.irisCode?.let { info.iris } ?: fallback.iris,
            shutter = raw.shutterCode?.let { info.shutter } ?: fallback.shutter,
            gain = raw.gainCode?.let { codeToPercent(it, 0x1C) } ?: info.gain,
            exposureComp = exposureComp,
            brightness = exposureComp,
            saturation = raw.colorGainCode?.let { codeToPercent(it, 0x0E) } ?: fallback.saturation,
            sharpness = raw.sharpnessCode?.let { codeToPercent(it, 0x0F) } ?: fallback.sharpness,
            contrast = raw.gammaCode?.let { gammaCodeToContrast(it) } ?: fallback.contrast,
            hue = raw.colorHueCode?.let { codeToPercent(it, 0x0E) } ?: fallback.hue,
            gamma = raw.gammaCode?.coerceIn(0, 3) ?: fallback.gamma,
            dayNightMode = when (raw.icrCode) {
                0x02 -> DayNightMode.NIGHT
                0x03 -> DayNightMode.DAY
                else -> fallback.dayNightMode
            },
            autoIcrThreshold = fallback.autoIcrThreshold,
            nr2d = nrCode?.let { it and 0x0F }?.coerceIn(0, 5) ?: fallback.nr2d,
            nr3d = nrCode?.let { (it shr 4) and 0x0F }?.coerceIn(0, 5) ?: fallback.nr3d,
            wdr = codeToSwitch(raw.wdrCode, fallback.wdr),
            blc = codeToSwitch(raw.blcCode, fallback.blc),
            eis = codeToSwitch(raw.eisCode, fallback.eis),
            digitalZoom = codeToSwitch(raw.digitalZoomCode, fallback.digitalZoom),
            flickerMode = enumByCode(raw.flickerCode, FlickerMode.values(), fallback.flickerMode) { it.code },
            defog = codeToSwitch(raw.defogCode, fallback.defog),
            defogLevel = fallback.defogLevel
        )
    }

    private fun applySnapshotToState(snapshot: RecordedCameraParams) {
        _state.update {
            it.copy(
                zoomSpeed = snapshot.zoomSpeed,
                zoomPositionCode = snapshot.zoomPositionCode,
                zoomMagnificationText = TamronCameraConfigConverter.zoomPositionToDisplayText(snapshot.zoomPositionCode),
                focusSpeed = snapshot.focusSpeed,
                focusAuto = snapshot.focusAuto,
                focusNearLimitPercent = snapshot.focusNearLimitPercent,
                wbMode = snapshot.wbMode,
                wbRed = snapshot.wbRed,
                wbBlue = snapshot.wbBlue,
                exposureMode = snapshot.exposureMode,
                iris = snapshot.iris,
                shutter = snapshot.shutter,
                gain = snapshot.gain,
                exposureComp = snapshot.exposureComp,
                brightness = snapshot.brightness,
                saturation = snapshot.saturation,
                sharpness = snapshot.sharpness,
                contrast = snapshot.contrast,
                hue = snapshot.hue,
                gamma = snapshot.gamma,
                dayNightMode = snapshot.dayNightMode,
                autoIcrThreshold = snapshot.autoIcrThreshold,
                nr2d = snapshot.nr2d,
                nr3d = snapshot.nr3d,
                wdr = snapshot.wdr,
                blc = snapshot.blc,
                eis = snapshot.eis,
                digitalZoom = snapshot.digitalZoom,
                flickerMode = snapshot.flickerMode,
                defog = snapshot.defog,
                defogLevel = snapshot.defogLevel
            )
        }
    }

    private fun applyEncodeConfigToState(config: VideoEncodeConfig) {
        _state.update {
            it.copy(
                resolution = normalizeResolution(config.resolution),
                frameRate = resolvedEncodeFrameRate(config),
                bitRateKbps = normalizeBitRate(config.bitRateKbps),
                rateControlMode = config.rateControlMode
            )
        }
    }

    private suspend fun refreshZoomPosition() {
        repository.camera.queryZoomPosition()?.let { position ->
            _state.update {
                it.copy(
                    zoomPositionCode = position,
                    zoomMagnificationText = TamronCameraConfigConverter.zoomPositionToDisplayText(position)
                )
            }
        }
    }

    private fun resolvedEncodeFrameRate(config: VideoEncodeConfig): Int {
        val playbackFrameRate = state.value.playbackFrameRate
        return if (playbackFrameRate > 0) {
            normalizeFrameRate(playbackFrameRate)
        } else {
            normalizeFrameRate(config.frameRate)
        }
    }

    private fun normalizeBitRate(value: Int): Int {
        return ((value + BIT_RATE_STEP / 2) / BIT_RATE_STEP * BIT_RATE_STEP)
            .coerceIn(MIN_BIT_RATE_KBPS, MAX_BIT_RATE_KBPS)
    }

    private fun normalizeResolution(value: String): String {
        val normalized = value.uppercase().replace(" ", "")
        return if (normalized.contains("1280") && normalized.contains("720")) {
            RESOLUTION_720P
        } else {
            RESOLUTION_1080P
        }
    }

    private fun normalizeFrameRate(value: Int): Int {
        return ALLOWED_FRAME_RATES.minBy { kotlin.math.abs(it - value) }
    }

    private fun encoderBaseUrlFromPingIp(): String? {
        val host = state.value.pingIp.trim()
        if (host.isBlank()) return null
        return if (host.startsWith("http://", true) || host.startsWith("https://", true)) {
            host.trimEnd('/')
        } else {
            "http://$host"
        }
    }

    private fun codeToPercent(code: Int, maxCode: Int): Int {
        if (maxCode <= 0) return 0
        return ((code.coerceIn(0, maxCode) * 100f / maxCode) + 0.5f).toInt().coerceIn(0, 100)
    }

    private fun codeToSwitch(code: Int?, fallback: Boolean): Boolean {
        return when (code) {
            0x02 -> true
            0x03 -> false
            else -> fallback
        }
    }

    private fun gammaCodeToContrast(code: Int): Int {
        return when (code.coerceIn(0, 3)) {
            3 -> 12
            0 -> 50
            1 -> 62
            else -> 87
        }
    }

    private fun contrastToGammaCode(value: Int): Int {
        return when (value.coerceIn(0, 100)) {
            in 0..24 -> 3
            in 25..50 -> 0
            in 51..74 -> 1
            else -> 2
        }
    }

    private fun <T> enumByCode(code: Int?, values: Array<T>, fallback: T, codeOf: (T) -> Int): T {
        return values.firstOrNull { codeOf(it) == code } ?: fallback
    }

    private enum class ManualExposureField {
        IRIS,
        SHUTTER,
        GAIN
    }

    private companion object {
        const val RESOLUTION_1080P = "1920X1080"
        const val RESOLUTION_720P = "1280X720"
        const val BIT_RATE_STEP = 1024
        const val MIN_BIT_RATE_KBPS = 1024
        const val MAX_BIT_RATE_KBPS = 21504
        const val EXPOSURE_MODE_SETTLE_MS = 350L
        const val FINAL_VALUE_RESEND_DELAY_MS = 120L
        const val POWER_ON_CONFIG_SETTLE_MS = 1500L
        val ALLOWED_FRAME_RATES = intArrayOf(25, 30, 50, 60)
    }
}

private data class RecordedCameraParams(
    val zoomSpeed: Int,
    val zoomPositionCode: Int?,
    val focusSpeed: Int,
    val focusAuto: Boolean,
    val focusNearLimitPercent: Int,
    val wbMode: WhiteBalanceMode,
    val wbRed: Int,
    val wbBlue: Int,
    val exposureMode: ExposureMode,
    val iris: Int,
    val shutter: Int,
    val gain: Int,
    val exposureComp: Int,
    val brightness: Int,
    val saturation: Int,
    val sharpness: Int,
    val contrast: Int,
    val hue: Int,
    val gamma: Int,
    val dayNightMode: DayNightMode,
    val autoIcrThreshold: Int,
    val nr2d: Int,
    val nr3d: Int,
    val wdr: Boolean,
    val blc: Boolean,
    val eis: Boolean,
    val digitalZoom: Boolean,
    val flickerMode: FlickerMode,
    val defog: Boolean,
    val defogLevel: Int,
    val recordName: String = "",
    val createdAtMillis: Long = 0L
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("recordName", recordName)
            .put("createdAtMillis", createdAtMillis)
            .put("zoomSpeed", zoomSpeed)
            .put("zoomPositionCode", zoomPositionCode)
            .put("focusSpeed", focusSpeed)
            .put("focusAuto", focusAuto)
            .put("focusNearLimitPercent", focusNearLimitPercent)
            .put("wbMode", wbMode.name)
            .put("wbRed", wbRed)
            .put("wbBlue", wbBlue)
            .put("exposureMode", exposureMode.name)
            .put("iris", iris)
            .put("shutter", shutter)
            .put("gain", gain)
            .put("exposureComp", exposureComp)
            .put("brightness", brightness)
            .put("saturation", saturation)
            .put("sharpness", sharpness)
            .put("contrast", contrast)
            .put("hue", hue)
            .put("gamma", gamma)
            .put("dayNightMode", dayNightMode.name)
            .put("autoIcrThreshold", autoIcrThreshold)
            .put("nr2d", nr2d)
            .put("nr3d", nr3d)
            .put("wdr", wdr)
            .put("blc", blc)
            .put("eis", eis)
            .put("digitalZoom", digitalZoom)
            .put("flickerMode", flickerMode.name)
            .put("defog", defog)
            .put("defogLevel", defogLevel)
    }

    companion object {
        fun fromJson(json: JSONObject): RecordedCameraParams {
            val defaults = TamronDebugState()
            return RecordedCameraParams(
                zoomSpeed = json.optInt("zoomSpeed", defaults.zoomSpeed).coerceIn(0, 7),
                zoomPositionCode = json.optNullableInt("zoomPositionCode"),
                focusSpeed = json.optInt("focusSpeed", defaults.focusSpeed).coerceIn(0, 7),
                focusAuto = json.optBoolean("focusAuto", defaults.focusAuto),
                focusNearLimitPercent = json.optInt("focusNearLimitPercent", defaults.focusNearLimitPercent).coerceIn(0, 100),
                wbMode = enumByName(json.optString("wbMode"), WhiteBalanceMode.values(), defaults.wbMode),
                wbRed = json.optInt("wbRed", defaults.wbRed).coerceIn(0, 100),
                wbBlue = json.optInt("wbBlue", defaults.wbBlue).coerceIn(0, 100),
                exposureMode = enumByName(json.optString("exposureMode"), ExposureMode.values(), defaults.exposureMode),
                iris = json.optInt("iris", defaults.iris).coerceIn(0, 100),
                shutter = json.optInt("shutter", defaults.shutter).coerceIn(0, 19),
                gain = json.optInt("gain", defaults.gain).coerceIn(0, 100),
                exposureComp = json.optInt("exposureComp", defaults.exposureComp).coerceIn(0, 100),
                brightness = json.optInt("brightness", defaults.brightness).coerceIn(0, 100),
                saturation = json.optInt("saturation", defaults.saturation).coerceIn(0, 100),
                sharpness = json.optInt("sharpness", defaults.sharpness).coerceIn(0, 100),
                contrast = json.optInt("contrast", defaults.contrast).coerceIn(0, 100),
                hue = json.optInt("hue", defaults.hue).coerceIn(0, 100),
                gamma = json.optInt("gamma", defaults.gamma).coerceIn(0, 3),
                dayNightMode = enumByName(json.optString("dayNightMode"), DayNightMode.values(), defaults.dayNightMode),
                autoIcrThreshold = json.optInt("autoIcrThreshold", defaults.autoIcrThreshold).coerceIn(0, 28),
                nr2d = json.optInt("nr2d", defaults.nr2d).coerceIn(0, 5),
                nr3d = json.optInt("nr3d", defaults.nr3d).coerceIn(0, 5),
                wdr = json.optBoolean("wdr", defaults.wdr),
                blc = json.optBoolean("blc", defaults.blc),
                eis = json.optBoolean("eis", defaults.eis),
                digitalZoom = json.optBoolean("digitalZoom", defaults.digitalZoom),
                flickerMode = enumByName(json.optString("flickerMode"), FlickerMode.values(), defaults.flickerMode),
                defog = json.optBoolean("defog", defaults.defog),
                defogLevel = json.optInt("defogLevel", defaults.defogLevel).coerceIn(0, 3),
                recordName = json.optString("recordName", ""),
                createdAtMillis = json.optLong("createdAtMillis", 0L)
            )
        }

        private fun <T : Enum<T>> enumByName(name: String, values: Array<T>, fallback: T): T {
            return values.firstOrNull { it.name == name } ?: fallback
        }

        private fun JSONObject.optNullableInt(name: String): Int? {
            return if (has(name) && !isNull(name)) optInt(name) else null
        }
    }
}
