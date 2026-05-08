package com.petcamel

enum class CamelTrait { CURIOUS, LAZY, PROUD, MISCHIEVOUS, GENTLE, LOYAL, GLUTTON }

enum class CamelMood { HAPPY, PLAYFUL, SLEEPY, LONELY, EXCITED, MOODY, ENERGETIC, CURIOUS, AFFECTIONATE }

data class CamelState(
    val name: String,
    val loveAtLastInteraction: Float,
    val lastInteractionTime: Long,
    val isNamed: Boolean,
    val traits: Set<CamelTrait> = emptySet(),
    val mood: CamelMood = CamelMood.HAPPY,
    val moodUntil: Long = 0L,
    val xp: Int = 0,
    val level: Int = 1,
    val groomsToday: Int = 0,
    val lastGroomDay: Long = 0L,
    val unlockedAbilities: Set<String> = emptySet()
) {
    companion object {
        const val MAX_LOVE = 100f
        const val DECAY_RATE_PER_MS = MAX_LOVE / (48L * 60 * 60 * 1000).toFloat()
        const val FEED_BOOST = 30f
        const val PET_BOOST = 20f

        fun default() = CamelState(
            name = "",
            loveAtLastInteraction = 50f,
            lastInteractionTime = System.currentTimeMillis(),
            isNamed = false
        )
    }

    fun currentLove(): Float {
        val elapsed = System.currentTimeMillis() - lastInteractionTime
        val decayed = loveAtLastInteraction - (elapsed * DECAY_RATE_PER_MS)
        return decayed.coerceIn(0f, MAX_LOVE)
    }

    fun withFeed(): CamelState = copy(
        loveAtLastInteraction = (currentLove() + FEED_BOOST).coerceAtMost(MAX_LOVE),
        lastInteractionTime = System.currentTimeMillis()
    )

    fun withPet(): CamelState = copy(
        loveAtLastInteraction = (currentLove() + PET_BOOST).coerceAtMost(MAX_LOVE),
        lastInteractionTime = System.currentTimeMillis()
    )

    fun withName(newName: String): CamelState = copy(name = newName, isNamed = true)

    fun xpForNextLevel() = level * 100

    fun moodIcon() = when (mood) {
        CamelMood.HAPPY        -> "☺"
        CamelMood.SLEEPY       -> "z"
        CamelMood.PLAYFUL      -> "~"
        CamelMood.LONELY       -> "."
        CamelMood.AFFECTIONATE -> "♥"
        CamelMood.EXCITED      -> "!"
        CamelMood.MOODY        -> "x"
        CamelMood.ENERGETIC    -> "*"
        CamelMood.CURIOUS      -> "?"
    }

    fun traitSummary() = traits.joinToString(" ") { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }
}
