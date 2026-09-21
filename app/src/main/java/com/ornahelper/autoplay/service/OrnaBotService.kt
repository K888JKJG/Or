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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.ornahelper.autoplay.R
import com.ornahelper.autoplay.data.BotConfig
import com.ornahelper.autoplay.data.BotState
import com.ornahelper.autoplay.data.BotStatus
import com.ornahelper.autoplay.data.CalibratedButton
import com.ornahelper.autoplay.data.CalibratedRegion
import com.ornahelper.autoplay.data.CalibrationStep
import com.ornahelper.autoplay.data.ConfigRepository
import com.ornahelper.autoplay.engine.BotEngine
import com.ornahelper.autoplay.ui.CalibrationOverlayView

/**
 * The single foreground service that owns everything the bot needs at runtime:
 * screen capture (MediaProjection), the floating control panel, the calibration
 * menu/overlay, and the [BotEngine] loop itself. Kept as one service so these
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

    private var calibrationMenuView: View? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        configRepository = ConfigRepository(this)
        botEngine = BotEngine(
            configProvider = { configRepository.load() },
            frameProvider = { synchronized(bitmapLock) { latestBitmap } },
            tapper = { x, y -> OrnaAccessibilityService.tap(x, y) },
            longPresser = { x, y, durationMs -> OrnaAccessibilityService.longPress(x, y, durationMs) },
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
                showCalibrationMenu()
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
        removeCalibrationMenu()
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
        btnCalibrate.setOnClickListener {
            if (calibrationMenuView != null) removeCalibrationMenu() else showCalibrationMenu()
        }
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
        val stateText = when (status.state) {
            BotState.MAP -> "地圖：尋找怪物中"
            BotState.CONFIRM -> "確認戰鬥"
            BotState.BATTLE -> "戰鬥中"
            BotState.RESULT -> "結算畫面"
            BotState.UNKNOWN -> "辨識中…"
        }
        overlayStatusText?.text = stateText
        updateToggleButtonLabel()
    }

    // ---- Calibration menu -------------------------------------------------------------------
    //
    // Calibration is 9 independent, on-demand items rather than one linear wizard: the menu
    // itself is a small floating panel (like the control panel) that never blocks touches to
    // the game underneath, so the player can freely navigate to whatever screen a given item
    // needs (e.g. actually fight a monster to reach the battle screen) before tapping that
    // item. Only the brief single-gesture capture that follows tapping an item briefly covers
    // the full screen, and it closes itself the instant that one region/point is captured.

    private var resetArmed = false
    private var resetRevertRunnable: Runnable? = null

    private fun showCalibrationMenu() {
        if (calibrationMenuView != null) return
        if (mediaProjection == null) {
            Toast.makeText(this, "請先在主畫面授權畫面擷取", Toast.LENGTH_SHORT).show()
            return
        }

        val root = LayoutInflater.from(this).inflate(R.layout.overlay_calibration_menu, null)
        calibrationMenuView = root
        resetArmed = false

        val itemsContainer = root.findViewById<LinearLayout>(R.id.calib_menu_items)
        val btnReset = root.findViewById<Button>(R.id.calib_menu_btn_reset)
        val btnClose = root.findViewById<Button>(R.id.calib_menu_btn_close)
        rebuildCalibrationMenuItems(itemsContainer)
        btnReset.setOnClickListener { onResetButtonClicked(btnReset, itemsContainer) }
        btnClose.setOnClickListener { removeCalibrationMenu() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 220
        }
        windowManager.addView(root, params)
    }

    /** Requires two taps within 3 seconds before actually wiping every calibrated setting. */
    private fun onResetButtonClicked(btnReset: Button, itemsContainer: LinearLayout) {
        if (!resetArmed) {
            resetArmed = true
            btnReset.text = getString(R.string.calib_menu_reset_confirm)
            resetRevertRunnable = Runnable {
                resetArmed = false
                btnReset.text = getString(R.string.calib_menu_reset)
            }
            mainHandler.postDelayed(resetRevertRunnable!!, 3000L)
            return
        }

        resetRevertRunnable?.let { mainHandler.removeCallbacks(it) }
        resetRevertRunnable = null
        resetArmed = false
        botEngine.stop()
        configRepository.save(BotConfig())
        btnReset.text = getString(R.string.calib_menu_reset)
        rebuildCalibrationMenuItems(itemsContainer)
        Toast.makeText(this, getString(R.string.calib_toast_reset_done), Toast.LENGTH_SHORT).show()
    }

    private fun rebuildCalibrationMenuItems(container: LinearLayout) {
        container.removeAllViews()
        val cfg = configRepository.load()
        for (step in CalibrationStep.values()) {
            val button = Button(this).apply {
                minHeight = 0
                minimumHeight = 0
                textSize = 11f
                setPadding(12, 8, 12, 8)
                text = menuLabelFor(step, cfg)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 4 }
                setOnClickListener { startSingleStepCalibration(step) }
            }
            container.addView(button)
        }
    }

    private fun removeCalibrationMenu() {
        val root = calibrationMenuView ?: return
        resetRevertRunnable?.let { mainHandler.removeCallbacks(it) }
        resetRevertRunnable = null
        resetArmed = false
        runCatching { windowManager.removeView(root) }
        calibrationMenuView = null
    }

    private fun startSingleStepCalibration(step: CalibrationStep) {
        if (calibrationRootView != null) return
        if (mediaProjection == null) {
            Toast.makeText(this, "請先在主畫面授權畫面擷取", Toast.LENGTH_SHORT).show()
            return
        }

        val root = LayoutInflater.from(this).inflate(R.layout.overlay_calibration, null)
        calibrationRootView = root

        val canvas = root.findViewById<CalibrationOverlayView>(R.id.calibration_canvas)
        val instruction = root.findViewById<TextView>(R.id.calibration_instruction)
        val btnCancel = root.findViewById<Button>(R.id.calibration_btn_cancel)
        calibrationOverlayView = canvas
        calibrationInstructionText = instruction

        canvas.frameProvider = { synchronized(bitmapLock) { latestBitmap } }
        canvas.currentStep = step
        canvas.listener = object : CalibrationOverlayView.Listener {
            override fun onRegionCaptured(region: CalibratedRegion) {
                val cfg = configRepository.load()
                applyCalibrationRegion(cfg, step, region)
                configRepository.save(cfg)
                finishSingleStepCalibration(step)
            }

            override fun onButtonCaptured(button: CalibratedButton) {
                val cfg = configRepository.load()
                applyCalibrationButton(cfg, step, button)
                configRepository.save(cfg)
                finishSingleStepCalibration(step)
            }
        }

        instruction.text = instructionFor(step)
        btnCancel.setOnClickListener {
            removeCalibrationOverlay()
            Toast.makeText(this, getString(R.string.calib_toast_cancelled), Toast.LENGTH_SHORT).show()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            0,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(root, params)
    }

    private fun finishSingleStepCalibration(step: CalibrationStep) {
        removeCalibrationOverlay()
        Toast.makeText(this, getString(R.string.calib_toast_step_saved, shortLabelFor(step)), Toast.LENGTH_SHORT).show()
        calibrationMenuView?.findViewById<LinearLayout>(R.id.calib_menu_items)?.let { rebuildCalibrationMenuItems(it) }
    }

    private fun applyCalibrationRegion(cfg: BotConfig, step: CalibrationStep, region: CalibratedRegion) {
        when (step) {
            CalibrationStep.MONSTER_SPAWN_AREA -> cfg.monsterSpawnArea = region
            CalibrationStep.MAP_ANCHOR -> cfg.mapAnchor = region
            CalibrationStep.BATTLE_ANCHOR -> cfg.battleAnchor = region
            CalibrationStep.CONFIRM_ANCHOR -> cfg.confirmAnchor = region
            CalibrationStep.RESULT_ANCHOR -> cfg.resultAnchor = region
            else -> Unit
        }
    }

    private fun applyCalibrationButton(cfg: BotConfig, step: CalibrationStep, button: CalibratedButton) {
        when (step) {
            CalibrationStep.CONFIRM_BUTTON -> cfg.confirmButton = button
            CalibrationStep.BATTLE_ATTACK_SLOT -> cfg.battleAttackSlot = button
            CalibrationStep.RESULT_CONTINUE_BUTTON -> cfg.resultContinueButton = button
            CalibrationStep.ITEMS_BUTTON -> cfg.itemsButton = button
            else -> Unit
        }
    }

    private fun removeCalibrationOverlay() {
        val root = calibrationRootView ?: return
        runCatching { windowManager.removeView(root) }
        calibrationRootView = null
        calibrationOverlayView = null
        calibrationInstructionText = null
    }

    private fun isStepCalibrated(cfg: BotConfig, step: CalibrationStep): Boolean = when (step) {
        CalibrationStep.MONSTER_SPAWN_AREA -> cfg.monsterSpawnArea != null
        CalibrationStep.MAP_ANCHOR -> cfg.mapAnchor != null
        CalibrationStep.BATTLE_ANCHOR -> cfg.battleAnchor != null
        CalibrationStep.CONFIRM_ANCHOR -> cfg.confirmAnchor != null
        CalibrationStep.RESULT_ANCHOR -> cfg.resultAnchor != null
        CalibrationStep.CONFIRM_BUTTON -> cfg.confirmButton != null
        CalibrationStep.BATTLE_ATTACK_SLOT -> cfg.battleAttackSlot != null
        CalibrationStep.RESULT_CONTINUE_BUTTON -> cfg.resultContinueButton != null
        CalibrationStep.ITEMS_BUTTON -> cfg.itemsButton != null
    }

    private fun menuLabelFor(step: CalibrationStep, cfg: BotConfig): String {
        val statusRes = if (isStepCalibrated(cfg, step)) R.string.calib_status_done else R.string.calib_status_missing
        return shortLabelFor(step) + getString(statusRes)
    }

    private fun shortLabelFor(step: CalibrationStep): String = getString(
        when (step) {
            CalibrationStep.MONSTER_SPAWN_AREA -> R.string.calib_menu_item_spawn_area
            CalibrationStep.MAP_ANCHOR -> R.string.calib_menu_item_map_anchor
            CalibrationStep.BATTLE_ANCHOR -> R.string.calib_menu_item_battle_anchor
            CalibrationStep.CONFIRM_ANCHOR -> R.string.calib_menu_item_confirm_anchor
            CalibrationStep.RESULT_ANCHOR -> R.string.calib_menu_item_result_anchor
            CalibrationStep.CONFIRM_BUTTON -> R.string.calib_menu_item_confirm_button
            CalibrationStep.BATTLE_ATTACK_SLOT -> R.string.calib_menu_item_battle_slot
            CalibrationStep.RESULT_CONTINUE_BUTTON -> R.string.calib_menu_item_continue_button
            CalibrationStep.ITEMS_BUTTON -> R.string.calib_menu_item_items_button
        }
    )

    private fun instructionFor(step: CalibrationStep): String = getString(
        when (step) {
            CalibrationStep.MONSTER_SPAWN_AREA -> R.string.calib_step_spawn_area
            CalibrationStep.MAP_ANCHOR -> R.string.calib_step_map_anchor
            CalibrationStep.BATTLE_ANCHOR -> R.string.calib_step_battle_anchor
            CalibrationStep.CONFIRM_ANCHOR -> R.string.calib_step_confirm_anchor
            CalibrationStep.RESULT_ANCHOR -> R.string.calib_step_result_anchor
            CalibrationStep.CONFIRM_BUTTON -> R.string.calib_step_confirm_button
            CalibrationStep.BATTLE_ATTACK_SLOT -> R.string.calib_step_battle_slot
            CalibrationStep.RESULT_CONTINUE_BUTTON -> R.string.calib_step_continue_button
            CalibrationStep.ITEMS_BUTTON -> R.string.calib_step_items_button
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
