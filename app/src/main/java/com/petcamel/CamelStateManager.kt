package com.petcamel

import android.content.Context

class CamelStateManager(context: Context) {
    private val prefs = context.getSharedPreferences("camel_state", Context.MODE_PRIVATE)

    fun load(): CamelState {
        if (!prefs.contains("is_named")) return CamelState.default()
        val traitsStr = prefs.getString("traits", "") ?: ""
        val traits: Set<CamelTrait> = if (traitsStr.isBlank()) emptySet() else
            traitsStr.split(",").mapNotNull { runCatching { CamelTrait.valueOf(it.trim()) }.getOrNull() }.toSet()
        val mood = runCatching { CamelMood.valueOf(prefs.getString("mood", CamelMood.HAPPY.name) ?: "") }
            .getOrDefault(CamelMood.HAPPY)
        val abilitiesStr = prefs.getString("unlocked_abilities", "") ?: ""
        val abilities: Set<String> = if (abilitiesStr.isBlank()) emptySet() else abilitiesStr.split(",").toSet()
        return CamelState(
            name                 = prefs.getString("name", "") ?: "",
            loveAtLastInteraction= prefs.getFloat("love", 50f),
            lastInteractionTime  = prefs.getLong("last_interaction", System.currentTimeMillis()),
            isNamed              = prefs.getBoolean("is_named", false),
            traits               = traits,
            mood                 = mood,
            moodUntil            = prefs.getLong("mood_until", 0L),
            xp                   = prefs.getInt("xp", 0),
            level                = prefs.getInt("level", 1),
            groomsToday          = prefs.getInt("grooms_today", 0),
            lastGroomDay         = prefs.getLong("last_groom_day", 0L),
            unlockedAbilities    = abilities
        )
    }

    fun save(state: CamelState) {
        prefs.edit()
            .putString("name",               state.name)
            .putFloat("love",                state.loveAtLastInteraction)
            .putLong("last_interaction",     state.lastInteractionTime)
            .putBoolean("is_named",          state.isNamed)
            .putString("traits",             state.traits.joinToString(",") { it.name })
            .putString("mood",               state.mood.name)
            .putLong("mood_until",           state.moodUntil)
            .putInt("xp",                    state.xp)
            .putInt("level",                 state.level)
            .putInt("grooms_today",          state.groomsToday)
            .putLong("last_groom_day",       state.lastGroomDay)
            .putString("unlocked_abilities", state.unlockedAbilities.joinToString(","))
            .apply()
    }
}
