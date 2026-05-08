package com.petcamel

import android.content.Context
import org.json.JSONObject

class GamePersistence(context: Context) {
    private val prefs = context.getSharedPreferences("game_extras", Context.MODE_PRIVATE)
    var discoveredLocations = mutableMapOf<String, Long>()
    var npcFriendship = IntArray(6)
    var camelBonds = IntArray(4)
    var unlockedCosmetics = mutableSetOf<Int>()
    var collectiblesCount = 0
    var lastSurpriseDay = -1L

    fun load() {
        // Load discovered locations
        val locsJson = prefs.getString("discovered_locations", null)
        if (locsJson != null) {
            try {
                val obj = JSONObject(locsJson)
                val it = obj.keys()
                while (it.hasNext()) {
                    val key = it.next()
                    discoveredLocations[key] = obj.getLong(key)
                }
            } catch (_: Exception) {}
        }

        // Load NPC friendship
        for (i in 0 until 6) {
            npcFriendship[i] = prefs.getInt("npc_friendship_$i", 0)
        }

        // Load camel bonds
        for (i in 0 until 4) {
            camelBonds[i] = prefs.getInt("camel_bond_$i", 0)
        }

        // Load unlocked cosmetics
        val cosmetics = prefs.getString("unlocked_cosmetics", "")
        if (!cosmetics.isNullOrEmpty()) {
            cosmetics.split(",").forEach { s ->
                val v = s.trim().toIntOrNull()
                if (v != null) unlockedCosmetics.add(v)
            }
        }

        collectiblesCount = prefs.getInt("collectibles_count", 0)
        lastSurpriseDay = prefs.getLong("last_surprise_day", -1L)
    }

    fun save() {
        val editor = prefs.edit()

        // Save discovered locations
        val locsObj = JSONObject()
        discoveredLocations.forEach { (name, day) -> locsObj.put(name, day) }
        editor.putString("discovered_locations", locsObj.toString())

        // Save NPC friendship
        for (i in 0 until 6) {
            editor.putInt("npc_friendship_$i", npcFriendship[i])
        }

        // Save camel bonds
        for (i in 0 until 4) {
            editor.putInt("camel_bond_$i", camelBonds[i])
        }

        // Save unlocked cosmetics
        editor.putString("unlocked_cosmetics", unlockedCosmetics.joinToString(","))

        editor.putInt("collectibles_count", collectiblesCount)
        editor.putLong("last_surprise_day", lastSurpriseDay)
        editor.apply()
    }

    /** Returns true if this is a new discovery */
    fun recordDiscovery(name: String): Boolean {
        if (discoveredLocations.containsKey(name)) return false
        discoveredLocations[name] = currentDay()
        save()
        return true
    }

    /** Returns true when friendship hits exactly 5 */
    fun addNpcFriendship(id: Int): Boolean {
        if (id < 0 || id >= npcFriendship.size) return false
        npcFriendship[id]++
        save()
        return npcFriendship[id] == 5
    }

    /** Returns the new bond count */
    fun addCamelBond(id: Int): Int {
        if (id < 0 || id >= camelBonds.size) return 0
        camelBonds[id]++
        save()
        return camelBonds[id]
    }

    fun unlockCosmetic(id: Int) {
        unlockedCosmetics.add(id)
        save()
    }

    fun currentDay() = System.currentTimeMillis() / 86_400_000L
}
