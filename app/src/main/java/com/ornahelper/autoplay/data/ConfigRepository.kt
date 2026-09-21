package com.ornahelper.autoplay.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Persists [BotConfig] as JSON in SharedPreferences. Pure data storage, no game-specific logic. */
class ConfigRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun load(): BotConfig {
        val raw = prefs.getString(KEY_CONFIG, null) ?: return BotConfig()
        return try {
            fromJson(JSONObject(raw))
        } catch (e: Exception) {
            BotConfig()
        }
    }

    @Synchronized
    fun save(config: BotConfig) {
        prefs.edit().putString(KEY_CONFIG, toJson(config).toString()).apply()
    }

    private fun toJson(c: BotConfig): JSONObject = JSONObject().apply {
        put("hpBar", c.hpBar?.toJson())
        put("mpBar", c.mpBar?.toJson())
        put("enemyIndicator", c.enemyIndicator?.toJson())
        put("attackButton", c.attackButton?.toJson())
        put("hpPotionButton", c.hpPotionButton?.toJson())
        put("mpPotionButton", c.mpPotionButton?.toJson())
        put("moveTapPoint", c.moveTapPoint?.toJson())
        put("hpThresholdPercent", c.hpThresholdPercent)
        put("mpThresholdPercent", c.mpThresholdPercent)
        put("tickIntervalMs", c.tickIntervalMs)
        put("potionCooldownMs", c.potionCooldownMs)
        put("attackTapIntervalMs", c.attackTapIntervalMs)
        put("idleMoveIntervalMs", c.idleMoveIntervalMs)
        put("maxRuntimeMinutes", c.maxRuntimeMinutes)
        put("colorTolerance", c.colorTolerance)
        put("enemyPresentTolerance", c.enemyPresentTolerance)
    }

    private fun fromJson(o: JSONObject): BotConfig {
        val defaults = BotConfig()
        return BotConfig(
            hpBar = o.optRegionOrNull("hpBar"),
            mpBar = o.optRegionOrNull("mpBar"),
            enemyIndicator = o.optRegionOrNull("enemyIndicator"),
            attackButton = o.optPointOrNull("attackButton"),
            hpPotionButton = o.optPointOrNull("hpPotionButton"),
            mpPotionButton = o.optPointOrNull("mpPotionButton"),
            moveTapPoint = o.optPointOrNull("moveTapPoint"),
            hpThresholdPercent = o.optInt("hpThresholdPercent", defaults.hpThresholdPercent),
            mpThresholdPercent = o.optInt("mpThresholdPercent", defaults.mpThresholdPercent),
            tickIntervalMs = o.optLong("tickIntervalMs", defaults.tickIntervalMs),
            potionCooldownMs = o.optLong("potionCooldownMs", defaults.potionCooldownMs),
            attackTapIntervalMs = o.optLong("attackTapIntervalMs", defaults.attackTapIntervalMs),
            idleMoveIntervalMs = o.optLong("idleMoveIntervalMs", defaults.idleMoveIntervalMs),
            maxRuntimeMinutes = o.optInt("maxRuntimeMinutes", defaults.maxRuntimeMinutes),
            colorTolerance = o.optInt("colorTolerance", defaults.colorTolerance),
            enemyPresentTolerance = o.optInt("enemyPresentTolerance", defaults.enemyPresentTolerance)
        )
    }

    private fun TapPoint.toJson(): JSONObject = JSONObject().apply {
        put("x", x); put("y", y)
    }

    private fun ScreenRect.toJson(): JSONObject = JSONObject().apply {
        put("left", left); put("top", top); put("right", right); put("bottom", bottom)
    }

    private fun CalibratedRegion.toJson(): JSONObject = JSONObject().apply {
        put("rect", rect.toJson())
        put("referenceColor", referenceColor)
    }

    private fun JSONObject.optPointOrNull(key: String): TapPoint? {
        val o = optJSONObject(key) ?: return null
        return TapPoint(o.optInt("x"), o.optInt("y"))
    }

    private fun JSONObject.optRectOrNull(key: String): ScreenRect? {
        val o = optJSONObject(key) ?: return null
        return ScreenRect(o.optInt("left"), o.optInt("top"), o.optInt("right"), o.optInt("bottom"))
    }

    private fun JSONObject.optRegionOrNull(key: String): CalibratedRegion? {
        val o = optJSONObject(key) ?: return null
        val rect = o.optRectOrNull("rect") ?: return null
        val color = o.optInt("referenceColor")
        return CalibratedRegion(rect, color)
    }

    companion object {
        private const val PREFS_NAME = "orna_helper_config"
        private const val KEY_CONFIG = "bot_config_json"
    }
}
