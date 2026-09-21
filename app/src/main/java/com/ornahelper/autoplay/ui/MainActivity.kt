package com.ornahelper.autoplay.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ornahelper.autoplay.R
import com.ornahelper.autoplay.data.ConfigRepository
import com.ornahelper.autoplay.service.OrnaAccessibilityService
import com.ornahelper.autoplay.service.OrnaBotService

/**
 * Entry screen: walks the user through the three permissions the bot needs
 * (overlay, accessibility, screen capture), lets them tune thresholds, and
 * launches the calibration wizard / bot service. All actual automation runs
 * in [OrnaBotService] so it keeps working while this activity is closed.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var configRepository: ConfigRepository

    private lateinit var textOverlayStatus: TextView
    private lateinit var textAccessibilityStatus: TextView
    private lateinit var textServiceStatus: TextView

    private lateinit var textHpThreshold: TextView
    private lateinit var textMpThreshold: TextView
    private lateinit var textTickInterval: TextView
    private lateinit var textMaxRuntime: TextView

    private lateinit var seekHpThreshold: SeekBar
    private lateinit var seekMpThreshold: SeekBar
    private lateinit var seekTickInterval: SeekBar
    private lateinit var seekMaxRuntime: SeekBar

    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshStatus() }

    private val accessibilitySettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshStatus() }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                val intent = OrnaBotService.buildStartProjectionIntent(this, result.resultCode, data)
                ContextCompat.startForegroundService(this, intent)
                Toast.makeText(this, "已啟動背景服務，請切換到遊戲畫面開始校準", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "未授權畫面擷取，無法啟動自動化", Toast.LENGTH_SHORT).show()
            }
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        configRepository = ConfigRepository(this)
        bindViews()
        bindListeners()
        requestNotificationPermissionIfNeeded()
        refreshConfigUi()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun bindViews() {
        textOverlayStatus = findViewById(R.id.text_overlay_status)
        textAccessibilityStatus = findViewById(R.id.text_accessibility_status)
        textServiceStatus = findViewById(R.id.text_service_status)

        textHpThreshold = findViewById(R.id.text_hp_threshold)
        textMpThreshold = findViewById(R.id.text_mp_threshold)
        textTickInterval = findViewById(R.id.text_tick_interval)
        textMaxRuntime = findViewById(R.id.text_max_runtime)

        seekHpThreshold = findViewById(R.id.seek_hp_threshold)
        seekMpThreshold = findViewById(R.id.seek_mp_threshold)
        seekTickInterval = findViewById(R.id.seek_tick_interval)
        seekMaxRuntime = findViewById(R.id.seek_max_runtime)
    }

    private fun bindListeners() {
        findViewById<Button>(R.id.btn_overlay_permission).setOnClickListener { requestOverlayPermission() }
        findViewById<Button>(R.id.btn_accessibility_settings).setOnClickListener { openAccessibilitySettings() }
        findViewById<Button>(R.id.btn_start_projection).setOnClickListener { requestProjection() }
        findViewById<Button>(R.id.btn_start_calibration).setOnClickListener { requestCalibration() }
        findViewById<Button>(R.id.btn_save_config).setOnClickListener { saveConfigFromUi() }
        findViewById<Button>(R.id.btn_toggle_bot).setOnClickListener { toggleBot() }

        seekHpThreshold.setOnSeekBarChangeListener(labelUpdater { textHpThreshold.text = getString(R.string.label_hp_threshold, it) })
        seekMpThreshold.setOnSeekBarChangeListener(labelUpdater { textMpThreshold.text = getString(R.string.label_mp_threshold, it) })
        seekTickInterval.setOnSeekBarChangeListener(labelUpdater { textTickInterval.text = getString(R.string.label_tick_interval, it) })
        seekMaxRuntime.setOnSeekBarChangeListener(labelUpdater { textMaxRuntime.text = getString(R.string.label_max_runtime, it) })
    }

    private fun labelUpdater(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = onChange(progress)
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private fun refreshConfigUi() {
        val cfg = configRepository.load()
        seekHpThreshold.progress = cfg.hpThresholdPercent
        seekMpThreshold.progress = cfg.mpThresholdPercent
        seekTickInterval.progress = cfg.tickIntervalMs.toInt()
        seekMaxRuntime.progress = cfg.maxRuntimeMinutes

        textHpThreshold.text = getString(R.string.label_hp_threshold, cfg.hpThresholdPercent)
        textMpThreshold.text = getString(R.string.label_mp_threshold, cfg.mpThresholdPercent)
        textTickInterval.text = getString(R.string.label_tick_interval, cfg.tickIntervalMs.toInt())
        textMaxRuntime.text = getString(R.string.label_max_runtime, cfg.maxRuntimeMinutes)
    }

    private fun saveConfigFromUi() {
        val cfg = configRepository.load()
        cfg.hpThresholdPercent = seekHpThreshold.progress
        cfg.mpThresholdPercent = seekMpThreshold.progress
        cfg.tickIntervalMs = seekTickInterval.progress.coerceAtLeast(100).toLong()
        cfg.maxRuntimeMinutes = seekMaxRuntime.progress
        configRepository.save(cfg)
        Toast.makeText(this, "設定已儲存", Toast.LENGTH_SHORT).show()
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "已擁有懸浮視窗權限", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        overlayPermissionLauncher.launch(intent)
    }

    private fun openAccessibilitySettings() {
        accessibilitySettingsLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun requestProjection() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "請先授予懸浮視窗權限", Toast.LENGTH_SHORT).show()
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun requestCalibration() {
        val intent = Intent(this, OrnaBotService::class.java).setAction(OrnaBotService.ACTION_SHOW_CALIBRATION)
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "請切換到遊戲畫面，依照懸浮面板上的提示完成校準", Toast.LENGTH_LONG).show()
    }

    private fun toggleBot() {
        val intent = Intent(this, OrnaBotService::class.java).setAction(OrnaBotService.ACTION_TOGGLE_BOT)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, OrnaAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun refreshStatus() {
        val overlayGranted = Settings.canDrawOverlays(this)
        textOverlayStatus.text = getString(
            if (overlayGranted) R.string.status_overlay_granted else R.string.status_overlay_denied
        )

        val accessibilityEnabled = isAccessibilityServiceEnabled() || OrnaAccessibilityService.isEnabled
        textAccessibilityStatus.text = getString(
            if (accessibilityEnabled) R.string.status_accessibility_enabled else R.string.status_accessibility_disabled
        )

        textServiceStatus.text = getString(
            if (OrnaBotService.isServiceRunning) R.string.status_service_running else R.string.status_service_stopped
        )
    }
}
