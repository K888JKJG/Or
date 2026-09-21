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
        put("monsterSpawnArea", c.monsterSpawnArea?.toJson())
        put("mapAnchor", c.mapAnchor?.toJson())
        put("confirmButton", c.confirmButton?.toJson())
        put("battleAttackSlot", c.battleAttackSlot?.toJson())
        put("resultContinueButton", c.resultContinueButton?.toJson())
        put("itemsButton", c.itemsButton?.toJson())
        put("tickIntervalMs", c.tickIntervalMs)
        put("mapTapCooldownMs", c.mapTapCooldownMs)
        put("confirmTapCooldownMs", c.confirmTapCooldownMs)
        put("battleTapIntervalMs", c.battleTapIntervalMs)
        put("continueTapCooldownMs", c.continueTapCooldownMs)
        put("itemsLongPressMs", c.itemsLongPressMs)
        put("itemsRecoveryIntervalMs", c.itemsRecoveryIntervalMs)
        put("maxRuntimeMinutes", c.maxRuntimeMinutes)
        put("stateAnchorTolerance", c.stateAnchorTolerance)
        put("monsterColorTolerance", c.monsterColorTolerance)
        put("minMonsterMatchedSamples", c.minMonsterMatchedSamples)
    }

    private fun fromJson(o: JSONObject): BotConfig {
        val defaults = BotConfig()
        return BotConfig(
            monsterSpawnArea = o.optRegionOrNull("monsterSpawnArea"),
            mapAnchor = o.optRegionOrNull("mapAnchor"),
            confirmButton = o.optButtonOrNull("confirmButton"),
            battleAttackSlot = o.optButtonOrNull("battleAttackSlot"),
            resultContinueButton = o.optButtonOrNull("resultContinueButton"),
            itemsButton = o.optButtonOrNull("itemsButton"),
            tickIntervalMs = o.optLong("tickIntervalMs", defaults.tickIntervalMs),
            mapTapCooldownMs = o.optLong("mapTapCooldownMs", defaults.mapTapCooldownMs),
            confirmTapCooldownMs = o.optLong("confirmTapCooldownMs", defaults.confirmTapCooldownMs),
            battleTapIntervalMs = o.optLong("battleTapIntervalMs", defaults.battleTapIntervalMs),
            continueTapCooldownMs = o.optLong("continueTapCooldownMs", defaults.continueTapCooldownMs),
            itemsLongPressMs = o.optLong("itemsLongPressMs", defaults.itemsLongPressMs),
            itemsRecoveryIntervalMs = o.optLong("itemsRecoveryIntervalMs", defaults.itemsRecoveryIntervalMs),
            maxRuntimeMinutes = o.optInt("maxRuntimeMinutes", defaults.maxRuntimeMinutes),
            stateAnchorTolerance = o.optInt("stateAnchorTolerance", defaults.stateAnchorTolerance),
            monsterColorTolerance = o.optInt("monsterColorTolerance", defaults.monsterColorTolerance),
            minMonsterMatchedSamples = o.optInt("minMonsterMatchedSamples", defaults.minMonsterMatchedSamples)
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

    private fun CalibratedButton.toJson(): JSONObject = JSONObject().apply {
        put("point", point.toJson())
        put("anchor", anchor.toJson())
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

    private fun JSONObject.optButtonOrNull(key: String): CalibratedButton? {
        val o = optJSONObject(key) ?: return null
        val point = o.optPointOrNull("point") ?: return null
        val anchor = o.optRegionOrNull("anchor") ?: return null
        return CalibratedButton(point, anchor)
    }

    companion object {
        private const val PREFS_NAME = "orna_helper_config"
        private const val KEY_CONFIG = "bot_config_json"
    }
}
