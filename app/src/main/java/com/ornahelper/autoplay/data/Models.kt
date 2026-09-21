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

    companion object {
        /** A small box centered on [point], used to sample a color "signature" for a tapped button. */
        fun around(point: TapPoint, halfSize: Int): ScreenRect = ScreenRect(
            point.x - halfSize, point.y - halfSize, point.x + halfSize, point.y + halfSize
        )
    }
}

/** A calibrated region: where to look, and what color it should be. */
data class CalibratedRegion(val rect: ScreenRect, val referenceColor: Int)

/**
 * A tappable point plus a small sampled color region around it. The anchor doubles as a
 * "signature" for whichever screen this button lives on: if the live frame's color at that
 * same spot matches the anchor, that screen is very likely the one currently showing.
 */
data class CalibratedButton(val point: TapPoint, val anchor: CalibratedRegion)

/** Which of the four game screens the bot currently believes it is looking at. */
enum class BotState { UNKNOWN, MAP, CONFIRM, BATTLE, RESULT }

/**
 * All settings needed to run the bot loop. Every calibrated field is nullable so the
 * bot only acts on what has actually been calibrated.
 *
 * The loop this drives: on the map screen, find a monster in [monsterSpawnArea] and tap it;
 * on the confirm screen, tap [confirmButton]; on the battle screen, repeatedly tap
 * [battleAttackSlot]; on the result screen, tap [resultContinueButton] to go back to the map.
 * [itemsButton] is long-pressed periodically while on the map to auto-recover HP/MP.
 *
 * [mapAnchor] and [battleAnchor] are each checked independently against their own reference
 * color to tell the map and battle screens apart; [confirmButton] and [resultContinueButton]
 * are instead recognized by checking whether their tap point is currently showing a green
 * "call to action" color, since both screens share that same green-button look.
 */
data class BotConfig(
    var monsterSpawnArea: CalibratedRegion? = null,
    var mapAnchor: CalibratedRegion? = null,
    var battleAnchor: CalibratedRegion? = null,
    var confirmButton: CalibratedButton? = null,
    var battleAttackSlot: CalibratedButton? = null,
    var resultContinueButton: CalibratedButton? = null,
    var itemsButton: CalibratedButton? = null,

    var tickIntervalMs: Long = 400,
    var mapTapCooldownMs: Long = 1200,
    var confirmTapCooldownMs: Long = 800,
    var battleTapIntervalMs: Long = 350,
    var continueTapCooldownMs: Long = 800,
    var itemsLongPressMs: Long = 3000,
    var itemsRecoveryIntervalMs: Long = 20_000,
    var maxRuntimeMinutes: Int = 0,

    var stateAnchorTolerance: Int = 40,
    var monsterColorTolerance: Int = 60,
    var minMonsterClusterSamples: Int = 6
) {
    val isCombatReady: Boolean
        get() = monsterSpawnArea != null && mapAnchor != null && battleAnchor != null &&
            confirmButton != null && battleAttackSlot != null && resultContinueButton != null
}

data class BotStatus(
    val running: Boolean,
    val state: BotState,
    val lastFrameAt: Long
)

enum class CalibrationStep(val isRect: Boolean) {
    MONSTER_SPAWN_AREA(isRect = true),
    MAP_ANCHOR(isRect = true),
    BATTLE_ANCHOR(isRect = true),
    CONFIRM_BUTTON(isRect = false),
    BATTLE_ATTACK_SLOT(isRect = false),
    RESULT_CONTINUE_BUTTON(isRect = false),
    ITEMS_BUTTON(isRect = false)
}
