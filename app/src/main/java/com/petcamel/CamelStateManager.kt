package com.petcamel

import android.content.Context

class CamelStateManager(context: Context) {
    private val prefs = context.getSharedPreferences("camel_state", Context.MODE_PRIVATE)

    fun load(): CamelState {
        if (!prefs.contains("is_named")) return CamelState.default()
        return CamelState(
            name = prefs.getString("name", "") ?: "",
            loveAtLastInteraction = prefs.getFloat("love", 50f),
            lastInteractionTime = prefs.getLong("last_interaction", System.currentTimeMillis()),
            isNamed = prefs.getBoolean("is_named", false)
        )
    }

    fun save(state: CamelState) {
        prefs.edit()
            .putString("name", state.name)
            .putFloat("love", state.loveAtLastInteraction)
            .putLong("last_interaction", state.lastInteractionTime)
            .putBoolean("is_named", state.isNamed)
            .apply()
    }
}
