package com.bominwell.tamrondebug.ui

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bominwell.cctvsdk.DayNightMode
import com.bominwell.cctvsdk.ExposureMode
import com.bominwell.cctvsdk.FlickerMode
import com.bominwell.cctvsdk.TamronCameraConfigConverter
import com.bominwell.cctvsdk.WhiteBalanceMode
import com.bominwell.tamrondebug.R
import com.bominwell.tamrondebug.player.EasyPlayerController
import com.bominwell.tamrondebug.player.EasyPlayerRtspView
import com.bominwell.tamrondebug.player.rememberEasyPlayerController
import com.bominwell.tamrondebug.viewmodel.CameraParamRecord
import com.bominwell.tamrondebug.viewmodel.MediaRecord
import com.bominwell.tamrondebug.viewmodel.TamronDebugViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private const val UI_PREFS_NAME = "tamron_debug_ui_prefs"
private const val KEY_WIDE_PREVIEW_MODE = "key_wide_preview_mode"

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun TamronDebugScreen(vm: TamronDebugViewModel) {
    val s by vm.state.collectAsState()
    val context = LocalContext.current

    val uiPrefs = remember(context.applicationContext) {
        context.applicationContext.getSharedPreferences(
            UI_PREFS_NAME,
            Context.MODE_PRIVATE
        )
    }

    var isWidePreviewMode by remember {
        mutableStateOf(
            uiPrefs.getBoolean(KEY_WIDE_PREVIEW_MODE, true)
        )
    }

    val switchPreviewMode = {
        val newMode = !isWidePreviewMode
        isWidePreviewMode = newMode
        uiPrefs.edit()
            .putBoolean(KEY_WIDE_PREVIEW_MODE, newMode)
            .apply()
    }

    var isRecording by remember { mutableStateOf(false) }
    var recordingStartedAtMillis by remember { mutableStateOf<Long?>(null) }
    var recordingElapsedMillis by remember { mutableStateOf(0L) }

    val playerController = rememberEasyPlayerController(
        onEvent = vm::appendPlayerLog,
        onRecordingChanged = { recording ->
            if (recording) {
                if (!isRecording || recordingStartedAtMillis == null) {
                    recordingStartedAtMillis = System.currentTimeMillis()
                    recordingElapsedMillis = 0L
                }
            } else {
                recordingStartedAtMillis = null
                recordingElapsedMillis = 0L
                vm.refreshMediaRecords()
            }
            isRecording = recording
        },
        onPlaybackStats = vm::updatePlaybackStats
    )

    var showRecordDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var showPlaybackDialog by remember { mutableStateOf(false) }
    var showVideoRecordDialog by remember { mutableStateOf(false) }
    var showScreenshotDialog by remember { mutableStateOf(false) }
    var videoRecordName by remember { mutableStateOf("") }
    var screenshotName by remember { mutableStateOf("") }

    LaunchedEffect(isRecording, recordingStartedAtMillis) {
        val startedAt = recordingStartedAtMillis
        if (isRecording && startedAt != null) {
            while (true) {
                recordingElapsedMillis = System.currentTimeMillis() - startedAt
                delay(1000L)
            }
        }
    }

    if (showRecordDialog) {
        RecordParamsDialog(
            onDismiss = { showRecordDialog = false },
            onConfirm = { name ->
                vm.recordCameraParams(name)
                showRecordDialog = false
            }
        )
    }

    if (showRestoreDialog) {
        RestoreParamsDialog(
            records = s.paramRecords,
            onDismiss = { showRestoreDialog = false },
            onConfirm = { fileName ->
                vm.restoreRecordedParams(fileName)
                showRestoreDialog = false
            },
            onDelete = vm::deleteParamRecord
        )
    }

    if (showPlaybackDialog) {
        PlaybackDialog(
            records = s.mediaRecords,
            onDismiss = { showPlaybackDialog = false },
            onOpen = vm::openMediaRecord,
            onDelete = vm::deleteMediaRecord
        )
    }

    if (showVideoRecordDialog) {
        VideoRecordNameDialog(
            defaultName = videoRecordName,
            onDismiss = { showVideoRecordDialog = false },
            onConfirm = { fileName ->
                playerController.startRecord(context, fileName)
                showVideoRecordDialog = false
            }
        )
    }

    if (showScreenshotDialog) {
        ScreenshotNameDialog(
            defaultName = screenshotName,
            onDismiss = { showScreenshotDialog = false },
            onConfirm = { fileName ->
                playerController.capture(context, fileName)
                showScreenshotDialog = false
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        PlayerAndLogArea(
            rtspUrl = s.rtspUrl,
            useHardwareDecode = s.useHardwareDecode,
            playKey = s.playKey,
            controller = playerController,
            log = s.log,
            isWidePreviewMode = isWidePreviewMode,
            onPlayerDoubleTap = switchPreviewMode,
            onPlayerEvent = vm::appendPlayerLog
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                SectionCard(stringResource(R.string.section_player_settings)) {
                    TextInputRow(stringResource(R.string.label_rtsp_address), s.rtspUrl, vm::updateRtspUrl)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(s.useHardwareDecode, onCheckedChange = vm::setHardwareDecode)
                        Text(stringResource(R.string.label_hardware_decode))
                    }
                    Text(
                        stringResource(
                            R.string.playback_stats,
                            formatPlaybackValue(s.playbackFrameRate, "fps"),
                            formatPlaybackValue(s.playbackBitRateKbps, "kbps")
                        )
                    )
                    Row {
                        Button(onClick = vm::restartPlayer, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.button_restart_player))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                vm.refreshMediaRecords()
                                showPlaybackDialog = true
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.button_playback))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Button(
                            onClick = {
                                if (isRecording) {
                                    playerController.stopRecord()
                                } else {
                                    videoRecordName = defaultMediaFileName("record")
                                    showVideoRecordDialog = true
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                stringResource(
                                    if (isRecording) R.string.button_stop_record else R.string.button_start_record
                                )
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                screenshotName = defaultMediaFileName("capture")
                                showScreenshotDialog = true
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.button_screenshot))
                        }
                    }
                    if (isRecording) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(
                                R.string.recording_duration,
                                formatRecordingDuration(recordingElapsedMillis)
                            )
                        )
                    }
                }
            }

            item {
                SectionCard(stringResource(R.string.section_ping)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        TextInputRow(
                            "Ping IP",
                            s.pingIp,
                            vm::updatePingIp,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = vm::pingOnce) { Text("Ping") }
                    }
                }
            }

            item {
                SectionCard(stringResource(R.string.section_camera_control)) {
                    Button(onClick = vm::queryCameraConfig, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.button_read_params))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Button(onClick = { showRecordDialog = true }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.button_record_params))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                vm.refreshParamRecords()
                                showRestoreDialog = true
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.button_restore_params))
                        }
                    }
                }
            }

            item {
                SectionCard(stringResource(R.string.section_zoom_focus)) {
                    Row {
                        PressControlButton(stringResource(R.string.button_zoom_in), Modifier.weight(1f), vm::zoomTeleStart, vm::zoomStop)
                        Spacer(Modifier.width(8.dp))
                        PressControlButton(stringResource(R.string.button_zoom_out), Modifier.weight(1f), vm::zoomWideStart, vm::zoomStop)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row {
                        PressControlButton(
                            stringResource(R.string.button_focus_far),
                            Modifier.weight(1f),
                            vm::focusFarStart,
                            vm::focusStop,
                            enabled = !s.focusAuto
                        )
                        Spacer(Modifier.width(8.dp))
                        PressControlButton(
                            stringResource(R.string.button_focus_near),
                            Modifier.weight(1f),
                            vm::focusNearStart,
                            vm::focusStop,
                            enabled = !s.focusAuto
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    RadioLine(stringResource(R.string.focus_auto), s.focusAuto) { vm.setFocusAutoMode(true) }
                    RadioLine(stringResource(R.string.focus_manual), !s.focusAuto) { vm.setFocusAutoMode(false) }
                    Button(onClick = vm::onePushFocus, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.button_one_push_focus))
                    }
                    IntSliderRow(stringResource(R.string.label_zoom_speed), s.zoomSpeed, 0..7, vm::setZoomSpeed)
                    IntSliderRow(stringResource(R.string.label_focus_speed), s.focusSpeed, 0..7, vm::setFocusSpeed)
                    IntSliderRow(
                        stringResource(R.string.label_focus_near_limit),
                        s.focusNearLimitPercent,
                        0..100,
                        vm::setFocusNearLimit,
                        valueText = TamronCameraConfigConverter::focusNearLimitPercentToDisplayText
                    )
                }
            }

            item {
                SectionCard(stringResource(R.string.section_white_balance)) {
                    for (mode in WhiteBalanceMode.values()) {
                        RadioLine(stringResource(mode.stringResId()), s.wbMode == mode) { vm.setWhiteBalanceMode(mode) }
                    }
                    Button(onClick = vm::onePushWhiteBalance, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.button_one_push_wb))
                    }
                    IntSliderRow("R Gain", s.wbRed, 0..100, vm::setWbRed)
                    IntSliderRow("B Gain", s.wbBlue, 0..100, vm::setWbBlue)
                }
            }

            item {
                SectionCard(stringResource(R.string.section_exposure)) {
                    for (mode in ExposureMode.values()) {
                        RadioLine(stringResource(mode.stringResId()), s.exposureMode == mode) { vm.setExposureMode(mode) }
                    }
                    IntSliderRow(stringResource(R.string.label_iris), s.iris, 0..100, vm::setIris)
                    IntSliderRow(stringResource(R.string.label_shutter), s.shutter, 0..19, vm::setShutter)
                    IntSliderRow(stringResource(R.string.label_gain), s.gain, 0..100, vm::setGain)
                }
            }

            item {
                SectionCard(stringResource(R.string.section_image_params)) {
                    IntSliderRow(stringResource(R.string.label_brightness), s.brightness, 0..100, vm::setBrightness)
                    IntSliderRow(stringResource(R.string.label_saturation), s.saturation, 0..100, vm::setSaturation)
                    IntSliderRow(stringResource(R.string.label_sharpness), s.sharpness, 0..100, vm::setSharpness)
                    IntSliderRow(stringResource(R.string.label_hue), s.hue, 0..100, vm::setHue)
                    IntSliderRow(stringResource(R.string.label_gamma), s.gamma, 0..3, vm::setGamma)
                }
            }

            item {
                SectionCard(stringResource(R.string.section_day_night_noise)) {
                    for (mode in DayNightMode.values()) {
                        RadioLine(stringResource(mode.stringResId()), s.dayNightMode == mode) { vm.setDayNightMode(mode) }
                    }
                    IntSliderRow(stringResource(R.string.label_auto_icr_threshold), s.autoIcrThreshold, 0..28, vm::setAutoIcrThreshold)
                    IntSliderRow(stringResource(R.string.label_nr2d), s.nr2d, 0..5, vm::setNr2d)
                    IntSliderRow(stringResource(R.string.label_nr3d), s.nr3d, 0..5, vm::setNr3d)
                    SwitchLine(stringResource(R.string.label_wdr), s.wdr, vm::setWdr)
                    SwitchLine(stringResource(R.string.label_blc), s.blc, vm::setBlc)
                    SwitchLine(stringResource(R.string.label_eis), s.eis, vm::setEis)
                    Text(stringResource(R.string.label_flicker_detection))
                    for (mode in FlickerMode.values()) {
                        RadioLine(stringResource(mode.stringResId()), s.flickerMode == mode) { vm.setFlickerMode(mode) }
                    }
                    SwitchLine(stringResource(R.string.label_defog), s.defog, vm::setDefog)
                    IntSliderRow(stringResource(R.string.label_defog_level), s.defogLevel, 0..3, vm::setDefogLevel)
                }
            }

            item {
                SectionCard(stringResource(R.string.section_raw_visca)) {
                    TextInputRow("Hex", s.rawHex, vm::updateRawHex)
                    Button(onClick = vm::sendRawHex, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.button_send_raw))
                    }
                }
            }
        }
    }
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
private fun PlayerAndLogArea(
    rtspUrl: String,
    useHardwareDecode: Boolean,
    playKey: Int,
    controller: EasyPlayerController,
    log: String,
    isWidePreviewMode: Boolean,
    onPlayerDoubleTap: () -> Unit,
    onPlayerEvent: (String) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (isWidePreviewMode) {
            val playerHeight = maxWidth * 9f / 16f
            val logHeight = playerHeight / 3f

            Column(modifier = Modifier.fillMaxWidth()) {
                PlayerPanel(
                    rtspUrl = rtspUrl,
                    useHardwareDecode = useHardwareDecode,
                    playKey = playKey,
                    controller = controller,
                    onPlayerDoubleTap = onPlayerDoubleTap,
                    onPlayerEvent = onPlayerEvent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(playerHeight)
                )
                LogPanel(
                    log = log,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(logHeight)
                )
            }
        } else {
            val playerHeight = maxWidth * 9f / 32f

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(playerHeight)
            ) {
                PlayerPanel(
                    rtspUrl = rtspUrl,
                    useHardwareDecode = useHardwareDecode,
                    playKey = playKey,
                    controller = controller,
                    onPlayerDoubleTap = onPlayerDoubleTap,
                    onPlayerEvent = onPlayerEvent,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
                LogPanel(
                    log = log,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun PlayerPanel(
    rtspUrl: String,
    useHardwareDecode: Boolean,
    playKey: Int,
    controller: EasyPlayerController,
    onPlayerDoubleTap: () -> Unit,
    onPlayerEvent: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.background(Color.Black)
    ) {
        EasyPlayerRtspView(
            url = rtspUrl,
            useHardwareDecode = useHardwareDecode,
            playKey = playKey,
            controller = controller,
            modifier = Modifier.fillMaxSize(),
            onDoubleTap = onPlayerDoubleTap,
            onEvent = onPlayerEvent
        )
    }
}

@Composable
private fun RecordParamsDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_record_params)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.label_record_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim()) }
            ) {
                Text(stringResource(R.string.button_record))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) }
        }
    )
}

@Composable
private fun VideoRecordNameDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var fileName by remember(defaultName) { mutableStateOf(defaultName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_start_record)) },
        text = {
            OutlinedTextField(
                value = fileName,
                onValueChange = { fileName = it },
                label = { Text(stringResource(R.string.label_record_file_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(fileName.trim()) }) {
                Text(stringResource(R.string.button_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) }
        }
    )
}

@Composable
private fun ScreenshotNameDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var fileName by remember(defaultName) { mutableStateOf(defaultName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_screenshot)) },
        text = {
            OutlinedTextField(
                value = fileName,
                onValueChange = { fileName = it },
                label = { Text(stringResource(R.string.label_screenshot_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(fileName.trim()) }) {
                Text(stringResource(R.string.button_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) }
        }
    )
}

@Composable
private fun RestoreParamsDialog(
    records: List<CameraParamRecord>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    var selectedFileName by remember(records) {
        mutableStateOf(records.firstOrNull()?.fileName.orEmpty())
    }
    val selectedRecord = records.firstOrNull { it.fileName == selectedFileName }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.Top
            ) {
                Text(stringResource(R.string.dialog_restore_params), modifier = Modifier.weight(0.8f))
                if (selectedRecord != null) {
                    Text(
                        text = selectedRecord.filePath,
                        modifier = Modifier.weight(1.2f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End
                    )
                }
            }
        },
        text = {
            if (records.isEmpty()) {
                Text(stringResource(R.string.empty_param_records))
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    for (record in records) {
                        RestoreRecordLine(
                            record = record,
                            checked = selectedFileName == record.fileName,
                            onSelect = { selectedFileName = record.fileName },
                            onDelete = { onDelete(record.fileName) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedFileName.isNotBlank(),
                onClick = { onConfirm(selectedFileName) }
            ) {
                Text(stringResource(R.string.button_restore))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) }
        }
    )
}

@Composable
private fun RestoreRecordLine(
    record: CameraParamRecord,
    checked: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        RadioButton(selected = checked, onClick = onSelect)
        Text(record.displayName, modifier = Modifier.weight(1f))
        TextButton(onClick = onDelete) { Text(stringResource(R.string.button_delete)) }
    }
}

@Composable
private fun PlaybackDialog(
    records: List<MediaRecord>,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_playback_records)) },
        text = {
            if (records.isEmpty()) {
                Text(stringResource(R.string.empty_media_records))
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    for (record in records) {
                        PlaybackRecordLine(
                            record = record,
                            onOpen = { onOpen(record.fileName) },
                            onDelete = { onDelete(record.fileName) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_close)) }
        }
    )
}

@Composable
private fun PlaybackRecordLine(
    record: MediaRecord,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("${record.typeLabel}: ${record.displayName}")
        }
        TextButton(onClick = onOpen) {
            Text(
                stringResource(
                    if (record.mimeType.startsWith("video")) R.string.button_play else R.string.button_view
                )
            )
        }
        TextButton(onClick = onDelete) { Text(stringResource(R.string.button_delete)) }
    }
}

@Composable
private fun LogPanel(log: String, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()

    LaunchedEffect(log) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Box(
        modifier = modifier
            .background(Color(0xFF111111))
            .padding(8.dp)
            .verticalScroll(scrollState)
    ) {
        Text(
            text = log.ifBlank { stringResource(R.string.empty_log) },
            color = Color.White,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun RadioLine(text: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        RadioButton(selected = checked, onClick = onClick)
        Text(text)
    }
}

@Composable
private fun SwitchLine(text: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Text(text)
    }
}

private fun formatRecordingDuration(elapsedMillis: Long): String {
    val totalSeconds = (elapsedMillis / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return "${hours.twoDigits()}:${minutes.twoDigits()}:${seconds.twoDigits()}"
}

private fun formatPlaybackValue(value: Int, unit: String): String {
    return if (value > 0) "$value $unit" else "--"
}

private fun Long.twoDigits(): String = toString().padStart(2, '0')

private fun defaultMediaFileName(prefix: String): String {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    return "${prefix}_$stamp"
}

private fun WhiteBalanceMode.stringResId(): Int {
    return when (this) {
        WhiteBalanceMode.ATW1 -> R.string.wb_atw1
        WhiteBalanceMode.INDOOR -> R.string.wb_indoor
        WhiteBalanceMode.OUTDOOR -> R.string.wb_outdoor
        WhiteBalanceMode.ONE_PUSH -> R.string.wb_one_push
        WhiteBalanceMode.ATW2 -> R.string.wb_atw2
        WhiteBalanceMode.MANUAL -> R.string.wb_manual
    }
}

private fun ExposureMode.stringResId(): Int {
    return when (this) {
        ExposureMode.FULL_AUTO -> R.string.exposure_full_auto
        ExposureMode.MANUAL -> R.string.exposure_manual
        ExposureMode.SHUTTER_PRIORITY -> R.string.exposure_shutter_priority
        ExposureMode.IRIS_PRIORITY -> R.string.exposure_iris_priority
    }
}

private fun DayNightMode.stringResId(): Int {
    return when (this) {
        DayNightMode.DAY -> R.string.day_night_day
        DayNightMode.NIGHT -> R.string.day_night_night
        DayNightMode.AUTO -> R.string.day_night_auto
    }
}

private fun FlickerMode.stringResId(): Int {
    return when (this) {
        FlickerMode.AUTO -> R.string.flicker_auto
        FlickerMode.OFF -> R.string.flicker_off
    }
}
