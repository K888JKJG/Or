package com.ornahelper.autoplay.engine

import android.graphics.Bitmap
import com.ornahelper.autoplay.data.BotConfig
import com.ornahelper.autoplay.data.BotStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives the auto-farm loop: read HP/MP bars, drink potions when low, attack while an
 * enemy is present, otherwise tap the "move" point to look for the next target.
 *
 * All game-specific knowledge (where things are, what colors mean "full") lives in the
 * calibrated [BotConfig] supplied by [configProvider] — this class only contains the
 * decision loop, so it works against any screen layout once calibrated.
 */
class BotEngine(
    private val configProvider: () -> BotConfig,
    private val frameProvider: () -> Bitmap?,
    private val tapper: (Int, Int) -> Boolean,
    private val onStatus: (BotStatus) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    private var lastHpPotionAt = 0L
    private var lastMpPotionAt = 0L
    private var lastAttackAt = 0L
    private var lastMoveAt = 0L
    private var startedAt = 0L

    fun start() {
        if (isRunning) return
        isRunning = true
        startedAt = System.currentTimeMillis()
        lastHpPotionAt = 0L
        lastMpPotionAt = 0L
        lastAttackAt = 0L
        lastMoveAt = 0L

        job = scope.launch {
            while (isActive && isRunning) {
                val cfg = configProvider()
                runCatching { tick(cfg) }

                val maxRuntimeMs = cfg.maxRuntimeMinutes * 60_000L
                if (maxRuntimeMs > 0 && System.currentTimeMillis() - startedAt >= maxRuntimeMs) {
                    isRunning = false
                    break
                }
                delay(cfg.tickIntervalMs.coerceAtLeast(100L))
            }
        }
    }

    fun stop() {
        isRunning = false
        job?.cancel()
        job = null
    }

    private fun tick(cfg: BotConfig) {
        val frame = frameProvider()
        if (frame == null) {
            onStatus(BotStatus(isRunning, -1, -1, enemyPresent = false, lastFrameAt = 0L))
            return
        }
        val now = System.currentTimeMillis()

        val hpPercent = cfg.hpBar?.let { BarReader.readBarPercent(frame, it, cfg.colorTolerance) } ?: -1
        val mpPercent = cfg.mpBar?.let { BarReader.readBarPercent(frame, it, cfg.colorTolerance) } ?: -1

        if (hpPercent in 0 until cfg.hpThresholdPercent) {
            cfg.hpPotionButton?.let { pt ->
                if (now - lastHpPotionAt >= cfg.potionCooldownMs) {
                    if (tapper(pt.x, pt.y)) lastHpPotionAt = now
                }
            }
        }

        if (mpPercent in 0 until cfg.mpThresholdPercent) {
            cfg.mpPotionButton?.let { pt ->
                if (now - lastMpPotionAt >= cfg.potionCooldownMs) {
                    if (tapper(pt.x, pt.y)) lastMpPotionAt = now
                }
            }
        }

        val enemyPresent = cfg.enemyIndicator?.let {
            !BarReader.regionMatchesReference(frame, it, cfg.enemyPresentTolerance)
        } ?: false

        if (enemyPresent) {
            cfg.attackButton?.let { pt ->
                if (now - lastAttackAt >= cfg.attackTapIntervalMs) {
                    if (tapper(pt.x, pt.y)) lastAttackAt = now
                }
            }
        } else {
            cfg.moveTapPoint?.let { pt ->
                if (now - lastMoveAt >= cfg.idleMoveIntervalMs) {
                    if (tapper(pt.x, pt.y)) lastMoveAt = now
                }
            }
        }

        onStatus(BotStatus(isRunning, hpPercent, mpPercent, enemyPresent, now))
    }
}
