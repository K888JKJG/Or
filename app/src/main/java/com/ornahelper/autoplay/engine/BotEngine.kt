package com.ornahelper.autoplay.engine

import android.graphics.Bitmap
import com.ornahelper.autoplay.data.BotConfig
import com.ornahelper.autoplay.data.BotState
import com.ornahelper.autoplay.data.BotStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives the auto-farm loop as a small screen-state machine:
 *
 * - MAP: find a monster inside the calibrated spawn area and tap it; while here, also
 *   periodically long-press the items button to auto-recover HP/MP.
 * - CONFIRM: tap the battle-confirm button.
 * - BATTLE: repeatedly tap the attack slot until the screen changes.
 * - RESULT: tap "continue" to go back to the map.
 *
 * Which screen is showing is decided each tick by [detectState]: map and battle each have
 * their own dedicated region checked independently against its own calibrated reference
 * color, while confirm and result are recognized by checking whether their button's tap
 * point currently looks green — see that function for why. All game-specific knowledge
 * lives in the calibrated [BotConfig] supplied by [configProvider]; this class only
 * contains the decision loop.
 */
class BotEngine(
    private val configProvider: () -> BotConfig,
    private val frameProvider: () -> Bitmap?,
    private val tapper: (Int, Int) -> Boolean,
    private val longPresser: (Int, Int, Long) -> Boolean,
    private val onStatus: (BotStatus) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    private var lastMapTapAt = 0L
    private var lastConfirmTapAt = 0L
    private var lastBattleTapAt = 0L
    private var lastContinueTapAt = 0L
    private var lastItemsRecoveryAt = 0L
    private var previousState = BotState.UNKNOWN
    private var startedAt = 0L

    fun start() {
        if (isRunning) return
        isRunning = true
        startedAt = System.currentTimeMillis()
        lastMapTapAt = 0L
        lastConfirmTapAt = 0L
        lastBattleTapAt = 0L
        lastContinueTapAt = 0L
        lastItemsRecoveryAt = 0L
        previousState = BotState.UNKNOWN

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
            onStatus(BotStatus(isRunning, BotState.UNKNOWN, 0L))
            return
        }
        val now = System.currentTimeMillis()
        val state = detectState(frame, cfg)

        when (state) {
            BotState.MAP -> {
                if (previousState != BotState.MAP || now - lastItemsRecoveryAt >= cfg.itemsRecoveryIntervalMs) {
                    cfg.itemsButton?.let { btn ->
                        if (longPresser(btn.point.x, btn.point.y, cfg.itemsLongPressMs)) lastItemsRecoveryAt = now
                    }
                }
                if (now - lastMapTapAt >= cfg.mapTapCooldownMs) {
                    cfg.monsterSpawnArea?.let { area ->
                        val target = BarReader.findLargestClusterCentroid(
                            frame, area, cfg.monsterColorTolerance, cfg.minMonsterClusterSamples
                        )
                        if (target != null && tapper(target.x, target.y)) lastMapTapAt = now
                    }
                }
            }
            BotState.CONFIRM -> {
                if (now - lastConfirmTapAt >= cfg.confirmTapCooldownMs) {
                    cfg.confirmButton?.let { btn ->
                        if (tapper(btn.point.x, btn.point.y)) lastConfirmTapAt = now
                    }
                }
            }
            BotState.BATTLE -> {
                if (now - lastBattleTapAt >= cfg.battleTapIntervalMs) {
                    cfg.battleAttackSlot?.let { btn ->
                        if (tapper(btn.point.x, btn.point.y)) lastBattleTapAt = now
                    }
                }
            }
            BotState.RESULT -> {
                if (now - lastContinueTapAt >= cfg.continueTapCooldownMs) {
                    cfg.resultContinueButton?.let { btn ->
                        if (tapper(btn.point.x, btn.point.y)) lastContinueTapAt = now
                    }
                }
            }
            BotState.UNKNOWN -> Unit
        }

        previousState = state
        onStatus(BotStatus(isRunning, state, now))
    }

    /**
     * Confirm and result are both just "a screen with a green button to tap", so they're told
     * apart from everything else by checking whether their own calibrated tap point currently
     * looks green (hue/saturation/value) — not by comparing colors against each other. Checking
     * these first means a stray map/battle anchor match never overrides an actual green button.
     *
     * Map and battle are visually stable full screens, so each gets its own dedicated region
     * ([BotConfig.mapAnchor] / [BotConfig.battleAnchor]) compared independently against its own
     * calibrated reference color. Only one should ever match a real frame; if a badly chosen pair
     * of regions somehow both match, the closer one wins.
     */
    private fun detectState(frame: Bitmap, cfg: BotConfig): BotState {
        cfg.confirmButton?.let { btn ->
            if (BarReader.regionLooksGreen(frame, btn.anchor.rect)) return BotState.CONFIRM
        }
        cfg.resultContinueButton?.let { btn ->
            if (BarReader.regionLooksGreen(frame, btn.anchor.rect)) return BotState.RESULT
        }

        val mapDistance = cfg.mapAnchor?.let { BarReader.regionColorDistance(frame, it) }
        val battleDistance = cfg.battleAnchor?.let { BarReader.regionColorDistance(frame, it) }

        return when {
            battleDistance != null && battleDistance <= cfg.stateAnchorTolerance &&
                (mapDistance == null || battleDistance <= mapDistance) -> BotState.BATTLE
            mapDistance != null && mapDistance <= cfg.stateAnchorTolerance -> BotState.MAP
            else -> BotState.UNKNOWN
        }
    }
}
