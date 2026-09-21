package com.ornahelper.autoplay.data

/** A point on screen in real (unscaled) pixel coordinates. */
data class TapPoint(val x: Int, val y: Int)

/** A rectangle on screen in real (unscaled) pixel coordinates. */
data class ScreenRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun normalized(): ScreenRect = ScreenRect(
        left = minOf(left, right),
        top = minOf(top, bottom),
        right = maxOf(left, right),
        bottom = maxOf(top, bottom)
    )
}

/** A calibrated region: where to look, and what color/tone means "full" or "present". */
data class CalibratedRegion(val rect: ScreenRect, val referenceColor: Int)

/**
 * All settings needed to run the bot loop. Every calibrated field is nullable so the
 * bot only acts on what has actually been calibrated.
 */
data class BotConfig(
    var hpBar: CalibratedRegion? = null,
    var mpBar: CalibratedRegion? = null,
    var enemyIndicator: CalibratedRegion? = null,
    var attackButton: TapPoint? = null,
    var hpPotionButton: TapPoint? = null,
    var mpPotionButton: TapPoint? = null,
    var moveTapPoint: TapPoint? = null,

    var hpThresholdPercent: Int = 50,
    var mpThresholdPercent: Int = 30,
    var tickIntervalMs: Long = 500,
    var potionCooldownMs: Long = 2000,
    var attackTapIntervalMs: Long = 800,
    var idleMoveIntervalMs: Long = 1500,
    var maxRuntimeMinutes: Int = 0,

    var colorTolerance: Int = 40,
    var enemyPresentTolerance: Int = 30
) {
    val isCombatReady: Boolean
        get() = attackButton != null
}

data class BotStatus(
    val running: Boolean,
    val hpPercent: Int,
    val mpPercent: Int,
    val enemyPresent: Boolean,
    val lastFrameAt: Long
)

enum class CalibrationStep(val isRect: Boolean, val isOptional: Boolean = false) {
    HP_BAR(isRect = true),
    MP_BAR(isRect = true),
    ENEMY_INDICATOR(isRect = true),
    ATTACK_BUTTON(isRect = false),
    HP_POTION_BUTTON(isRect = false),
    MP_POTION_BUTTON(isRect = false),
    MOVE_POINT(isRect = false, isOptional = true)
}
