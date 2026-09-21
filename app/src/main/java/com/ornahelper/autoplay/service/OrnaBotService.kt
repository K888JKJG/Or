package com.ornahelper.autoplay.service

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcelable
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.ornahelper.autoplay.R
import com.ornahelper.autoplay.data.BotConfig
import com.ornahelper.autoplay.data.BotStatus
import com.ornahelper.autoplay.data.CalibratedRegion
import com.ornahelper.autoplay.data.CalibrationStep
import com.ornahelper.autoplay.data.ConfigRepository
import com.ornahelper.autoplay.data.TapPoint
import com.ornahelper.autoplay.engine.BotEngine
import com.ornahelper.autoplay.ui.CalibrationOverlayView

/**
 * The single foreground service that owns everything the bot needs at runtime:
 * screen capture (MediaProjection), the floating control panel, the calibration
 * wizard overlay, and the [BotEngine] loop itself. Kept as one service so these
 * pieces can share state directly instead of coordinating over IPC.
 */
class OrnaBotService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var configRepository: ConfigRepository
    private lateinit var botEngine: BotEngine
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private val bitmapLock = Any()
    @Volatile private var latestBitmap: Bitmap? = null

    private var controlView: View? = null
    private var controlParams: WindowManager.LayoutParams? = null
    private var overlayStatusText: TextView? = null
    private var overlayToggleButton: Button? = null
    private var dragInitialX = 0
    private var dragInitialY = 0
    private var dragTouchX = 0f
    private var dragTouchY = 0f

    private var calibrationRootView: View? = null
    private var calibrationOverlayView: CalibrationOverlayView? = null
    private var calibrationInstructionText: TextView? = null
    private var workingConfig: BotConfig = BotConfig()
    private var calibrationIndex = 0
    private val calibrationSteps = CalibrationStep.values().toList()

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        configRepository = ConfigRepository(this)
        botEngine = BotEngine(
            configProvider = { configRepository.load() },
            frameProvider = { synchronized(bitmapLock) { latestBitmap } },
            tapper = { x, y -> OrnaAccessibilityService.tap(x, y) },
            onStatus = { status -> mainHandler.post { updateStatusUi(status) } }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PROJECTION -> {
                startForegroundNotification()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = intent.getParcelableExtraCompat<Intent>(EXTRA_DATA)
                if (resultCode == Activity.RESULT_OK && data != null && mediaProjection == null) {
                    setupProjection(resultCode, data)
                }
                showControlOverlay()
            }
            ACTION_TOGGLE_BOT -> {
                startForegroundNotification()
                toggleBot()
            }
            ACTION_SHOW_CALIBRATION -> {
                startForegroundNotification()
                showCalibrationOverlay()
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        botEngine.stop()
        removeCalibrationOverlay()
        controlView?.let { runCatching { windowManager.removeView(it) } }
        controlView = null
        tearDownProjection()
    }

    // ---- Screen capture -----------------------------------------------------------------

    private fun setupProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpm.getMediaProjection(resultCode, data) ?: return
        mediaProjection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                tearDownProjection()
            }
        }, mainHandler)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r ->
            val image = try {
                r.acquireLatestImage()
            } catch (e: Exception) {
                null
            } ?: return@setOnImageAvailableListener
            try {
                val bmp = imageToBitmap(image, width, height)
                synchronized(bitmapLock) { latestBitmap = bmp }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to convert captured frame", e)
            } finally {
                image.close()
            }
        }, mainHandler)
        imageReader = reader

        virtualDisplay = projection.createVirtualDisplay(
            "OrnaHelperCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, mainHandler
        )
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        if (rowPadding == 0) return bitmap
        val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
        bitmap.recycle()
        return cropped
    }

    private fun tearDownProjection() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        synchronized(bitmapLock) { latestBitmap = null }
        botEngine.stop()
    }

    // ---- Control overlay ------------------------------------------------------------------

    private fun showControlOverlay() {
        if (controlView != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_control, null)
        controlView = view

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 120
        }
        controlParams = params

        val handle = view.findViewById<TextView>(R.id.overlay_drag_handle)
        overlayStatusText = view.findViewById(R.id.overlay_status_text)
        overlayToggleButton = view.findViewById(R.id.overlay_btn_toggle)
        val btnCalibrate = view.findViewById<Button>(R.id.overlay_btn_calibrate)
        val btnClose = view.findViewById<Button>(R.id.overlay_btn_close)

        handle.setOnTouchListener(dragListener(params, view))
        overlayToggleButton?.setOnClickListener { toggleBot() }
        btnCalibrate.setOnClickListener { showCalibrationOverlay() }
        btnClose.setOnClickListener { stopSelf() }

        windowManager.addView(view, params)
        updateToggleButtonLabel()
    }

    private fun dragListener(params: WindowManager.LayoutParams, view: View) =
        View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragInitialX = params.x
                    dragInitialY = params.y
                    dragTouchX = event.rawX
                    dragTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = dragInitialX + (event.rawX - dragTouchX).toInt()
                    params.y = dragInitialY + (event.rawY - dragTouchY).toInt()
                    runCatching { windowManager.updateViewLayout(view, params) }
                    true
                }
                else -> false
            }
        }

    private fun toggleBot() {
        if (botEngine.isRunning) botEngine.stop() else botEngine.start()
        updateToggleButtonLabel()
    }

    private fun updateToggleButtonLabel() {
        overlayToggleButton?.text = getString(
            if (botEngine.isRunning) R.string.overlay_stop else R.string.overlay_start
        )
    }

    private fun updateStatusUi(status: BotStatus) {
        val hpText = if (status.hpPercent >= 0) "HP ${status.hpPercent}%" else "HP --"
        val mpText = if (status.mpPercent >= 0) "MP ${status.mpPercent}%" else "MP --"
        val stateText = if (status.enemyPresent) "戰鬥中" else "搜尋中"
        overlayStatusText?.text = "$hpText  $mpText  $stateText"
        updateToggleButtonLabel()
    }

    // ---- Calibration wizard ----------------------------------------------------------------

    private fun showCalibrationOverlay() {
        if (calibrationRootView != null) return
        if (mediaProjection == null) {
            Toast.makeText(this, "請先在主畫面授權畫面擷取", Toast.LENGTH_SHORT).show()
            return
        }
        workingConfig = configRepository.load()
        calibrationIndex = 0

        val root = LayoutInflater.from(this).inflate(R.layout.overlay_calibration, null)
        calibrationRootView = root

        val canvas = root.findViewById<CalibrationOverlayView>(R.id.calibration_canvas)
        val instruction = root.findViewById<TextView>(R.id.calibration_instruction)
        val btnSkip = root.findViewById<Button>(R.id.calibration_btn_skip)
        val btnCancel = root.findViewById<Button>(R.id.calibration_btn_cancel)
        calibrationOverlayView = canvas
        calibrationInstructionText = instruction

        canvas.frameProvider = { synchronized(bitmapLock) { latestBitmap } }
        canvas.listener = object : CalibrationOverlayView.Listener {
            override fun onRegionCaptured(region: CalibratedRegion) {
                applyCalibrationRegion(calibrationSteps[calibrationIndex], region)
                advanceCalibrationStep()
            }

            override fun onPointCaptured(point: TapPoint) {
                applyCalibrationPoint(calibrationSteps[calibrationIndex], point)
                advanceCalibrationStep()
            }
        }

        btnSkip.setOnClickListener { advanceCalibrationStep() }
        btnCancel.setOnClickListener {
            removeCalibrationOverlay()
            Toast.makeText(this, getString(R.string.calib_toast_cancelled), Toast.LENGTH_SHORT).show()
        }

        updateCalibrationStepUi()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            0,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(root, params)
    }

    private fun applyCalibrationRegion(step: CalibrationStep, region: CalibratedRegion) {
        when (step) {
            CalibrationStep.HP_BAR -> workingConfig.hpBar = region
            CalibrationStep.MP_BAR -> workingConfig.mpBar = region
            CalibrationStep.ENEMY_INDICATOR -> workingConfig.enemyIndicator = region
            else -> Unit
        }
    }

    private fun applyCalibrationPoint(step: CalibrationStep, point: TapPoint) {
        when (step) {
            CalibrationStep.ATTACK_BUTTON -> workingConfig.attackButton = point
            CalibrationStep.HP_POTION_BUTTON -> workingConfig.hpPotionButton = point
            CalibrationStep.MP_POTION_BUTTON -> workingConfig.mpPotionButton = point
            CalibrationStep.MOVE_POINT -> workingConfig.moveTapPoint = point
            else -> Unit
        }
    }

    private fun updateCalibrationStepUi() {
        val step = calibrationSteps[calibrationIndex]
        calibrationOverlayView?.currentStep = step
        calibrationInstructionText?.text = instructionFor(step)
    }

    private fun advanceCalibrationStep() {
        calibrationIndex++
        if (calibrationIndex >= calibrationSteps.size) {
            configRepository.save(workingConfig)
            removeCalibrationOverlay()
            Toast.makeText(this, getString(R.string.calib_toast_done), Toast.LENGTH_SHORT).show()
        } else {
            updateCalibrationStepUi()
        }
    }

    private fun removeCalibrationOverlay() {
        val root = calibrationRootView ?: return
        runCatching { windowManager.removeView(root) }
        calibrationRootView = null
        calibrationOverlayView = null
        calibrationInstructionText = null
    }

    private fun instructionFor(step: CalibrationStep): String = getString(
        when (step) {
            CalibrationStep.HP_BAR -> R.string.calib_step_hp_bar
            CalibrationStep.MP_BAR -> R.string.calib_step_mp_bar
            CalibrationStep.ENEMY_INDICATOR -> R.string.calib_step_enemy
            CalibrationStep.ATTACK_BUTTON -> R.string.calib_step_attack
            CalibrationStep.HP_POTION_BUTTON -> R.string.calib_step_hp_potion
            CalibrationStep.MP_POTION_BUTTON -> R.string.calib_step_mp_potion
            CalibrationStep.MOVE_POINT -> R.string.calib_step_move
        }
    )

    // ---- Notification ----------------------------------------------------------------------

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = Intent(this, OrnaBotService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_running))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(0, getString(R.string.stop), stopPending)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "OrnaBotService"
        private const val CHANNEL_ID = "orna_bot_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_PROJECTION = "com.ornahelper.autoplay.action.START_PROJECTION"
        const val ACTION_TOGGLE_BOT = "com.ornahelper.autoplay.action.TOGGLE_BOT"
        const val ACTION_SHOW_CALIBRATION = "com.ornahelper.autoplay.action.SHOW_CALIBRATION"
        const val ACTION_STOP = "com.ornahelper.autoplay.action.STOP"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        fun buildStartProjectionIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, OrnaBotService::class.java).apply {
                action = ACTION_START_PROJECTION
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
    }
}

private inline fun <reified T : Parcelable> Intent.getParcelableExtraCompat(key: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }
