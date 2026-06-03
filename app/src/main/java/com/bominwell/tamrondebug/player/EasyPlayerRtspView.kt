@file:Suppress("DEPRECATION")

package com.bominwell.tamrondebug.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.preference.PreferenceManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.easydarwin.video.Client
import org.easydarwin.video.EasyPlayerClient

@Composable
fun rememberEasyPlayerController(
    onEvent: (String) -> Unit,
    onRecordingChanged: (Boolean) -> Unit = {},
    onPlaybackStats: (frameRate: Int, bitRateKbps: Int) -> Unit = { _, _ -> }
): EasyPlayerController {
    val latestOnEvent = rememberUpdatedState(onEvent)
    val latestOnRecordingChanged = rememberUpdatedState(onRecordingChanged)
    val latestOnPlaybackStats = rememberUpdatedState(onPlaybackStats)
    return remember {
        EasyPlayerController(
            onEvent = { latestOnEvent.value(it) },
            onRecordingChanged = { latestOnRecordingChanged.value(it) },
            onPlaybackStats = { frameRate, bitRateKbps -> latestOnPlaybackStats.value(frameRate, bitRateKbps) }
        )
    }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
fun EasyPlayerRtspView(
    url: String,
    modifier: Modifier = Modifier,
    useHardwareDecode: Boolean = true,
    playKey: Int = 0,
    controller: EasyPlayerController? = null,
    onDoubleTap: () -> Unit = {},
    onEvent: (String) -> Unit = {}
) {
    val rememberedController = rememberEasyPlayerController(onEvent)
    val activeController = controller ?: rememberedController
    val latestOnDoubleTap = rememberUpdatedState(onDoubleTap)

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val rootView = FrameLayout(context)

            val surfaceView = SurfaceView(context).apply {
                activeController.attachSurfaceView(this)

                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        activeController.start(
                            context = context,
                            surface = holder.surface,
                            url = url,
                            useHardwareDecode = useHardwareDecode,
                            playKey = playKey
                        )
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int
                    ) {
                        if (holder.surface.isValid) {
                            activeController.updateSurface(holder.surface)
                        }
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        activeController.detachSurfaceView(this@apply)
                        activeController.stop()
                    }
                })
            }

            rootView.addView(
                surfaceView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            val gestureDetector = GestureDetector(
                context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean {
                        return true
                    }

                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        latestOnDoubleTap.value()
                        return true
                    }
                }
            )

            val touchLayer = View(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                isClickable = true
                isFocusable = false

                setOnTouchListener { _, event ->
                    gestureDetector.onTouchEvent(event)
                    true
                }
            }

            rootView.addView(
                touchLayer,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            rootView
        },
        update = { rootView ->
            val surfaceView = rootView.getChildAt(0) as? SurfaceView ?: return@AndroidView
            val touchLayer = rootView.getChildAt(1)

            touchLayer?.bringToFront()
            activeController.attachSurfaceView(surfaceView)

            val surface = surfaceView.holder.surface
            if (surface != null && surface.isValid) {
                activeController.start(
                    context = surfaceView.context,
                    surface = surface,
                    url = url,
                    useHardwareDecode = useHardwareDecode,
                    playKey = playKey
                )
            }
        }
    )

    DisposableEffect(activeController) {
        onDispose {
            activeController.stop()
        }
    }
}

class EasyPlayerController(
    private val onEvent: (String) -> Unit,
    private val onRecordingChanged: (Boolean) -> Unit = {},
    private val onPlaybackStats: (frameRate: Int, bitRateKbps: Int) -> Unit = { _, _ -> }
) {
    private var client: EasyPlayerClient? = null
    private var surfaceView: SurfaceView? = null
    private var appContext: Context? = null
    private var currentUrl: String = ""
    private var currentHardDecode: Boolean = true
    private var currentPlayKey: Int = -1
    private var currentRecordPath: String = ""
    private val statsHandler = Handler(Looper.getMainLooper())
    private var mediaFrameRate: Int = 0
    private var framesSinceLastStats: Int = 0
    private var lastStatsBytes: Long = 0L
    private var lastStatsMillis: Long = 0L
    private var statsRunning: Boolean = false
    private val statsTicker = object : Runnable {
        override fun run() {
            val activeClient = client ?: return
            val now = System.currentTimeMillis()
            val totalBytes = runCatching { activeClient.receivedDataLength() }.getOrDefault(lastStatsBytes)
            val elapsedMs = (now - lastStatsMillis).coerceAtLeast(1L)
            val deltaBytes = (totalBytes - lastStatsBytes).coerceAtLeast(0L)
            val bitRateKbps = ((deltaBytes * 8L * 1000L / elapsedMs) / 1000L).toInt().coerceAtLeast(0)
            val estimatedFps = (framesSinceLastStats * 1000f / elapsedMs).toInt().coerceAtLeast(0)
            val frameRate = mediaFrameRate.takeIf { it > 0 } ?: estimatedFps

            lastStatsBytes = totalBytes
            lastStatsMillis = now
            framesSinceLastStats = 0
            onPlaybackStats(frameRate, bitRateKbps)

            if (statsRunning) {
                statsHandler.postDelayed(this, STATS_INTERVAL_MS)
            }
        }
    }

    fun attachSurfaceView(view: SurfaceView) {
        surfaceView = view
    }

    fun detachSurfaceView(view: SurfaceView) {
        if (surfaceView === view) {
            surfaceView = null
        }
    }

    fun start(
        context: Context,
        surface: Surface,
        url: String,
        useHardwareDecode: Boolean,
        playKey: Int
    ) {
        if (url.isBlank() || !surface.isValid) return
        appContext = context.applicationContext

        if (
            client != null &&
            currentUrl == url &&
            currentHardDecode == useHardwareDecode &&
            currentPlayKey == playKey
        ) {
            client?.setmSurface(surface)
            return
        }

        stop()

        try {
            val sp = PreferenceManager.getDefaultSharedPreferences(context)
            sp.edit().putBoolean("use-sw-codec", !useHardwareDecode).apply()
        } catch (e: Throwable) {
            onEvent("播放器配置失败: ${e.message ?: e.javaClass.simpleName}")
        }

        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                val text = when (resultCode) {
                    EasyPlayerClient.RESULT_VIDEO_SIZE -> {
                        val w = resultData?.getInt(EasyPlayerClient.EXTRA_VIDEO_WIDTH) ?: 0
                        val h = resultData?.getInt(EasyPlayerClient.EXTRA_VIDEO_HEIGHT) ?: 0
                        val fps = resultData?.getInt(EasyPlayerClient.EXTRA_VIDEO_FPS) ?: 0
                        if (fps > 0) {
                            mediaFrameRate = fps
                            onPlaybackStats(mediaFrameRate, currentBitRateKbps())
                        }
                        "视频尺寸: ${w}x${h}${if (fps > 0) ", 帧率=${fps}" else ""}"
                    }

                    EasyPlayerClient.RESULT_VIDEO_DISPLAYED -> {
                        val type = resultData?.getInt(EasyPlayerClient.KEY_VIDEO_DECODE_TYPE)
                        startStats()
                        "视频已显示(${if (type == 1) "硬解" else "软解"})"
                    }

                    EasyPlayerClient.RESULT_RECORD_BEGIN -> {
                        onRecordingChanged(true)
                        "录像开始: ${currentRecordPath.ifBlank { "等待文件路径" }}"
                    }

                    EasyPlayerClient.RESULT_RECORD_END -> {
                        val path = currentRecordPath
                        onRecordingChanged(false)
                        if (path.isNotBlank()) {
                            appContext?.let { scanFile(it, path, "video/mp4") }
                        }
                        currentRecordPath = ""
                        "录像完成: ${path.ifBlank { "未知路径" }}"
                    }

                    EasyPlayerClient.RESULT_EVENT -> {
                        resultData?.getString("event-msg") ?: "事件消息"
                    }

                    EasyPlayerClient.RESULT_TIMEOUT -> "连接超时"
                    EasyPlayerClient.RESULT_UNSUPPORTED_VIDEO -> "不支持的视频格式"
                    EasyPlayerClient.RESULT_UNSUPPORTED_AUDIO -> "不支持的音频格式"
                    EasyPlayerClient.RESULT_FRAME_RECVED -> {
                        framesSinceLastStats += 1
                        return
                    }
                    else -> "状态码: $resultCode"
                }
                onEvent(text)
            }
        }

        try {
            val newClient = EasyPlayerClient(context.applicationContext, surface, receiver)
            newClient.setAudioEnable(false)
            newClient.setOnH264DataListener(object : EasyPlayerClient.OnH264DataListener {
                override fun h264Data(data: ByteArray?, length: Int) {
                    if (length > 0) {
                        framesSinceLastStats += 1
                    }
                }

                override fun curRecordTime(time: Long) = Unit
            })

            val mediaType = Client.EASY_SDK_VIDEO_FRAME_FLAG
            val result = newClient.start(url, Client.TRANSTYPE_TCP, 0, mediaType, "", "")

            if (result < 0) {
                newClient.stop()
                onEvent("EasyPlayer 启动失败: result=$result, URL=$url")
                return
            }

            client = newClient
            currentUrl = url
            currentHardDecode = useHardwareDecode
            currentPlayKey = playKey
            startStats()

            onEvent("EasyPlayer 启动: 结果=$result, 硬解=$useHardwareDecode, URL=$url")
        } catch (e: Throwable) {
            onEvent("EasyPlayer 启动异常: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun updateSurface(surface: Surface) {
        client?.setmSurface(surface)
    }

    fun startRecord(context: Context, fileName: String = ""): Boolean {
        val activeClient = client
        if (activeClient == null) {
            onEvent("录像失败: 播放器尚未启动")
            return false
        }

        if (activeClient.isRecording()) {
            onEvent("录像已在进行: ${currentRecordPath.ifBlank { "未知路径" }}")
            onRecordingChanged(true)
            return true
        }

        val file = createOutputFile(
            context = context,
            environmentDir = Environment.DIRECTORY_MOVIES,
            fallbackDirName = "movies",
            prefix = "record_",
            extension = "mp4",
            requestedName = fileName
        )
        currentRecordPath = file.absolutePath

        return try {
            activeClient.startRecord(file.absolutePath)
            onRecordingChanged(true)
            onEvent("录像请求已发送: ${file.absolutePath}")
            true
        } catch (e: Throwable) {
            currentRecordPath = ""
            onRecordingChanged(false)
            onEvent("录像启动失败: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    fun stopRecord(): Boolean {
        val activeClient = client
        if (activeClient == null) {
            onRecordingChanged(false)
            onEvent("停止录像失败: 播放器尚未启动")
            return false
        }

        if (!activeClient.isRecording() && currentRecordPath.isBlank()) {
            onRecordingChanged(false)
            onEvent("当前未在录像")
            return false
        }

        return try {
            activeClient.stopRecord()
            onEvent("停止录像请求已发送")
            true
        } catch (e: Throwable) {
            onEvent("停止录像失败: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    fun capture(context: Context, fileName: String = ""): Boolean {
        val view = surfaceView
        if (view == null || view.width <= 0 || view.height <= 0 || !view.holder.surface.isValid) {
            onEvent("截图失败: 播放画面尚未就绪")
            return false
        }

        val file = createOutputFile(
            context = context,
            environmentDir = Environment.DIRECTORY_PICTURES,
            fallbackDirName = "pictures",
            prefix = "capture_",
            extension = "jpg",
            requestedName = fileName
        )

        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)

        return try {
            PixelCopy.request(
                view,
                bitmap,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        saveBitmapAsync(context.applicationContext, bitmap, file)
                    } else {
                        bitmap.recycle()
                        onEvent("截图失败: PixelCopy result=$result")
                    }
                },
                Handler(Looper.getMainLooper())
            )
            onEvent("截图请求已发送")
            true
        } catch (e: Throwable) {
            bitmap.recycle()
            onEvent("截图失败: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    fun stop() {
        val wasRecording = client?.isRecording() == true || currentRecordPath.isNotBlank()
        stopStats()

        client?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
        }

        client = null
        currentUrl = ""
        currentHardDecode = true
        currentPlayKey = -1
        mediaFrameRate = 0
        framesSinceLastStats = 0
        onPlaybackStats(0, 0)

        if (wasRecording) {
            currentRecordPath = ""
            onRecordingChanged(false)
        }
    }

    private fun createOutputFile(
        context: Context,
        environmentDir: String,
        fallbackDirName: String,
        prefix: String,
        extension: String,
        requestedName: String = ""
    ): File {
        val root = context.getExternalFilesDir(environmentDir) ?: File(context.filesDir, fallbackDirName)
        val dir = File(root, "TamronCameraDebug").apply { mkdirs() }

        val customName = buildOutputFileName(requestedName, extension)
        if (customName.isNotBlank()) {
            return uniqueFile(dir, customName)
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(dir, "$prefix$stamp.$extension")
    }

    private fun buildOutputFileName(requestedName: String, extension: String): String {
        val sanitized = requestedName
            .trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\p{Cntrl}+"), "_")
            .replace(Regex("\\s+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_', '.', ' ')
            .take(80)

        if (sanitized.isBlank()) return ""

        val baseName = sanitized
            .replace(Regex("\\.(mp4|jpg|jpeg|png)$", RegexOption.IGNORE_CASE), "")
            .trim('_', '.', ' ')

        return if (baseName.isBlank()) "" else "$baseName.$extension"
    }

    private fun uniqueFile(dir: File, fileName: String): File {
        val requested = File(dir, fileName)
        if (!requested.exists()) return requested

        val extension = requested.extension
        val baseName = if (extension.isBlank()) {
            requested.name
        } else {
            requested.name.dropLast(extension.length + 1)
        }

        var index = 1
        while (true) {
            val nextName = if (extension.isBlank()) {
                "${baseName}_$index"
            } else {
                "${baseName}_$index.$extension"
            }

            val next = File(dir, nextName)
            if (!next.exists()) return next
            index += 1
        }
    }

    private fun saveBitmapAsync(context: Context, bitmap: Bitmap, file: File) {
        Thread {
            try {
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                scanFile(context, file.absolutePath, "image/jpeg")
                onEvent("截图完成: ${file.absolutePath}")
            } catch (e: Throwable) {
                onEvent("截图保存失败: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                bitmap.recycle()
            }
        }.start()
    }

    private fun scanFile(context: Context, path: String, mimeType: String) {
        MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(mimeType), null)
    }

    private fun startStats() {
        val activeClient = client ?: return
        statsRunning = true
        lastStatsBytes = runCatching { activeClient.receivedDataLength() }.getOrDefault(0L)
        lastStatsMillis = System.currentTimeMillis()
        framesSinceLastStats = 0
        statsHandler.removeCallbacks(statsTicker)
        statsHandler.postDelayed(statsTicker, STATS_INTERVAL_MS)
    }

    private fun stopStats() {
        statsRunning = false
        statsHandler.removeCallbacks(statsTicker)
        lastStatsBytes = 0L
        lastStatsMillis = 0L
    }

    private fun currentBitRateKbps(): Int {
        val activeClient = client ?: return 0
        val now = System.currentTimeMillis()
        val elapsedMs = (now - lastStatsMillis).coerceAtLeast(1L)
        val totalBytes = runCatching { activeClient.receivedDataLength() }.getOrDefault(lastStatsBytes)
        return (((totalBytes - lastStatsBytes).coerceAtLeast(0L) * 8L * 1000L / elapsedMs) / 1000L)
            .toInt()
            .coerceAtLeast(0)
    }

    private companion object {
        const val STATS_INTERVAL_MS = 1000L
    }
}
