package com.petcamel

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.*

class GameSurfaceView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback, Runnable {

    // ── State ──────────────────────────────────────────────────────────────────
    private val stateManager = CamelStateManager(context)
    @Volatile var camelState = stateManager.load()

    private val persistence = GamePersistence(context).also { it.load() }

    private val world = GameWorld()
    private val camel = CamelEntity(40f, 30f)

    // ── Wandering NPC camels ───────────────────────────────────────────────────
    private val wanderCamels = listOf(
        WanderCamel(16f, 14f, "Kesi"),
        WanderCamel(49f, 12f, "Farouk"),
        WanderCamel(65f, 28f, "Nadia"),
        WanderCamel(24f, 66f, "Beni")
    ).also { list ->
        list[0].herdRole = WanderCamel.HerdRole.SCOUT        // Kesi
        list[1].herdRole = WanderCamel.HerdRole.LEADER       // Farouk
        list[2].herdRole = WanderCamel.HerdRole.GUARDIAN     // Nadia
        list[3].herdRole = WanderCamel.HerdRole.TROUBLEMAKER // Beni
        list.forEachIndexed { i, wc -> wc.bondCount = persistence.camelBonds[i] }
        list.forEach { it.pickTarget(world) }
    }

    // ── Village NPCs ───────────────────────────────────────────────────────────
    private val npcs: List<NpcEntity> = world.locations
        .filter { it.type == LocationType.VILLAGE }
        .flatMapIndexed { i, loc ->
            listOf(
                NpcEntity(loc.tileX - 1.5f, loc.tileY - 1f, loc.tileX, loc.tileY,
                    if (i % 2 == 0) NpcEntity.Type.PETTER else NpcEntity.Type.FEEDER, i * 2),
                NpcEntity(loc.tileX + 1.5f, loc.tileY + 1f, loc.tileX, loc.tileY,
                    if (i % 2 == 0) NpcEntity.Type.FEEDER else NpcEntity.Type.PETTER, i * 2 + 1)
            )
        }.also { list -> list.forEach { npc -> npc.pickTarget(world) } }

    // ── Camera ─────────────────────────────────────────────────────────────────
    private var tileSize = 64f
    private var camX = 0f; private var camY = 0f

    // ── Game loop ──────────────────────────────────────────────────────────────
    private var gameThread: Thread? = null
    @Volatile private var running = false

    // ── Input ──────────────────────────────────────────────────────────────────
    @Volatile private var inputDx = 0f
    @Volatile private var inputDy = 0f
    private var lastInputMs = System.currentTimeMillis()
    private val autoWanderAfterMs = 30_000L

    // ── Player play state ──────────────────────────────────────────────────────
    private var playerPlaying = false
    private var playerPlayTimer = 0f
    private var playerPlayAngle = 0f
    private var playerPlayCX = 0f; private var playerPlayCY = 0f
    private val playDuration = 6f
    private val playRadius = 1.5f

    // ── Ambient music ──────────────────────────────────────────────────────────
    private val music = AmbientMusicPlayer()

    // ── Instructions overlay ───────────────────────────────────────────────────
    private var showInstructions = false
    private var instructionPage = 0
    private var checkedInstructions = false
    private val instructionPages = arrayOf(
        arrayOf("DESERT COMPANION" to true, "" to false,
            "Welcome to the Sahara!" to false, "Your camel needs care" to false,
            "and friendship to thrive." to false, "" to false,
            "♥  Keep the love bar full" to false, "★  Explore 13 locations" to false,
            "~  Bond with wild camels" to false, "" to false,
            "Tap your camel any time" to false, "to groom it (3x/day)" to false),
        arrayOf("CONTROLS" to true, "" to false,
            "D-PAD  →  Move around" to false, "FEED   →  3x per day (+30♥)" to false,
            "Tap camel  →  Groom (+♥ +XP)" to false, "" to false,
            "Walk over food on the" to false, "ground to collect it (+5♥)" to false,
            "" to false,
            "After 30s of no input" to false, "your camel wanders the" to false,
            "desert on its own." to false),
        arrayOf("THE WORLD" to true, "" to false,
            "OASIS    +5♥ on arrival" to false, "VILLAGE  NPCs pet & feed (+5♥)" to false,
            "PYRAMID  Ancient wonders" to false, "STABLE   Rest = no decay" to false,
            "" to false,
            "Food & cactus fruit spawn" to false, "around the map — explore!" to false,
            "Day/Night cycle ~10 min." to false, "NPCs sleep at night." to false,
            "" to false),
        arrayOf("GROW TOGETHER" to true, "" to false,
            "Move & interact to earn XP" to false, "Level up = unlock abilities" to false,
            "" to false,
            "Play with wild camels 3x" to false, "→ they start FOLLOWING you!" to false,
            "" to false,
            "Your camel has personality" to false, "TRAITS that shape its MOOD." to false,
            "Tap ≡ for the Discovery" to false, "Log, streak & abilities." to false)
    )

    // ── HUD / UI state ─────────────────────────────────────────────────────────
    private var locationLabel = ""
    @Volatile private var locationLabelTimer = 0f
    private var currentLocation: WorldLocation? = null
    private var camelFacingLeft = false
    private var dpadUp = false; private var dpadDown = false
    private var dpadLeft = false; private var dpadRight = false
    private var dpadCX = 0f; private var dpadCY = 0f; private var dpadR = 0f

    // ── Journal ────────────────────────────────────────────────────────────────
    private var showJournal = false

    // ── Minimap ────────────────────────────────────────────────────────────────
    private var minimapBmp: Bitmap? = null

    // ── Day/Night cycle ────────────────────────────────────────────────────────
    private val cycleDurationMs = 600_000L
    private val dayPhase get() = (System.currentTimeMillis() % cycleDurationMs) / cycleDurationMs.toFloat()
    private var prevDayPhase = dayPhase
    private var nightOasisX = 0f; private var nightOasisY = 0f
    private var nightOasisActive = false; private var nightOasisTimer = 0f
    private var nightOasisSpawnedThisCycle = false

    // ── Daily surprise ─────────────────────────────────────────────────────────
    private var surpriseX = 0f; private var surpriseY = 0f; private var surpriseActive = false
    private var collectSparkleTimer = 0f

    // ── Float animations [wx, wy, timer, maxTimer, colorR, colorG, colorB, iconType] ──
    // iconType: 0=♥, 1=★
    private val floatAnims = mutableListOf<FloatArray>()

    // ── Mood animations ────────────────────────────────────────────────────────
    private var kickTimer = 0f
    private var prevLoveForMood = 0f
    private var trotActive = false; private var trotTimer = 0f; private var trotCooldown = 30f
    private var heartBubbleTimer = 20f

    // ── Star positions for night sky (lazy, seeded) ────────────────────────────
    private val starPositions: Array<FloatArray> by lazy {
        val r = java.util.Random(42)
        Array(50) { floatArrayOf(r.nextFloat(), r.nextFloat() * 0.72f) }
    }

    // ── Mood system ────────────────────────────────────────────────────────────
    private var moodBubbleTimer = 0f          // counts down; bubble visible when > 0
    private val moodBubbleCycle = 25f         // show bubble every ~25s

    // ── Micro-events ───────────────────────────────────────────────────────────
    private enum class MicroEvent {
        SANDSTORM, SHOOTING_STAR, FOX, MIRAGE, HERD_CUDDLE, HERD_CIRCLE, PYRAMID_GLOW
    }
    private var activeMicroEvent: MicroEvent? = null
    private var microEventTimer = 0f
    private var nextMicroEventTimer = 30f
    // per-event state
    private var sandstormAlpha = 0f
    private var starSX = 0f; private var starSY = 0f
    private var foxTileX = 0f; private var foxTileY = 0f

    // ── Grooming ───────────────────────────────────────────────────────────────
    private var groomLabel = ""
    private var groomLabelTimer = 0f

    // ── Feed limit ─────────────────────────────────────────────────────────────
    private var feedLabel = ""
    private var feedLabelTimer = 0f

    // ── World food items [tileX, tileY, type]  type: 0=regular 1=cactus ───────
    private val foodItems = mutableListOf<FloatArray>()
    private var foodSpawnTimer = 45f
    private val foodSpawnInterval = 70f
    private val maxFoodItems = 5

    // ── Stable ─────────────────────────────────────────────────────────────────
    private var inStable = false
    private var stableAnchorTimer = 0f

    // ── XP / level-up ─────────────────────────────────────────────────────────
    private var xpMoveAccum = 0f
    private var levelUpTimer = 0f

    // ── Tile base colours ──────────────────────────────────────────────────────
    private val tileColors = mapOf(
        Tile.SAND          to Color.rgb(224, 214, 176),
        Tile.DEEP_SAND     to Color.rgb(200, 190, 152),
        Tile.DUNE          to Color.rgb(212, 202, 164),
        Tile.WATER         to Color.rgb( 58,  96, 140),
        Tile.GRASS         to Color.rgb(108, 148,  88),
        Tile.STONE_PATH    to Color.rgb(160, 152, 136),
        Tile.PYRAMID       to Color.rgb(192, 178, 142),
        Tile.PYRAMID_STEPS to Color.rgb(204, 192, 158),
        Tile.PALM          to Color.rgb( 64, 108,  52),
        Tile.CACTUS        to Color.rgb( 72, 112,  60),
        Tile.BUILDING_FRONT to Color.rgb(215, 192, 150)
    )

    // ── Paints ─────────────────────────────────────────────────────────────────
    private fun p(color: Int, style: Paint.Style = Paint.Style.FILL) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; this.style = style }

    private val hudBg        = p(Color.argb(180, 12, 12, 12))
    private val hudText      = p(Color.WHITE).apply { typeface = Typeface.MONOSPACE; textSize = 30f }
    private val hudSmall     = p(Color.WHITE).apply { typeface = Typeface.MONOSPACE; textSize = 22f }
    private val loveBarFill  = p(Color.rgb(220, 80, 80))
    private val loveBarTrack = p(Color.argb(100, 255, 255, 255))
    private val dpadBg       = p(Color.argb(150, 10, 10, 10))
    private val dpadActive   = p(Color.argb(210, 210, 200, 180))
    private val dpadText     = p(Color.WHITE).apply { typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
    private val feedBtn      = p(Color.argb(170, 10, 10, 10))
    private val locText      = p(Color.WHITE).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER; textSize = 38f
    }
    private val whiteFill    = p(Color.WHITE)

    // Camel palette
    private val cBody    = p(Color.rgb(213, 170, 102))
    private val cShade   = p(Color.rgb(185, 143,  78))
    private val cHoof    = p(Color.rgb( 72,  42,  12))
    private val cEyeW    = p(Color.WHITE)
    private val cOutline = p(Color.rgb(72, 42, 12), Paint.Style.STROKE).apply {
        strokeWidth = 3.5f; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }

    // Roof colour palette (picked by position hash)
    private val roofPalette = intArrayOf(
        Color.rgb(162, 76, 56),   // terracotta
        Color.rgb(72, 102, 148),  // dusty blue
        Color.rgb(85, 122, 88),   // sage green
        Color.rgb(168, 132, 64)   // gold
    )

    init { holder.addCallback(this); isFocusable = true; isFocusableInTouchMode = true }

    // ── Surface lifecycle ──────────────────────────────────────────────────────
    override fun surfaceCreated(holder: SurfaceHolder) {
        running = true; gameThread = Thread(this).also { it.start() }
        music.start()
    }
    override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        music.stop()
        try { gameThread?.join(1500) } catch (_: InterruptedException) {}
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        tileSize = w / 9f
        dpadR = w * 0.13f; dpadCX = w * 0.22f; dpadCY = h * 0.82f
        buildMinimap(); snapCamera()
    }

    // ── Game loop ──────────────────────────────────────────────────────────────
    override fun run() {
        var lastNs = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            val dt = ((now - lastNs) / 1e9f).coerceAtMost(0.05f); lastNs = now
            update(dt)
            val canvas = try { holder.lockCanvas() } catch (_: Exception) { null } ?: continue
            try { renderFrame(canvas) } finally { holder.unlockCanvasAndPost(canvas) }
            val sleep = 16L - (System.nanoTime() - now) / 1_000_000L
            if (sleep > 0) try { Thread.sleep(sleep) } catch (_: InterruptedException) { break }
        }
    }

    // ── Update ─────────────────────────────────────────────────────────────────
    private fun update(dt: Float) {
        // Day/night cycle
        val phase = dayPhase
        val isNight = phase in 0.6f..0.85f
        if (prevDayPhase > 0.85f && phase < 0.1f && !nightOasisSpawnedThisCycle) {
            spawnNightOasis(); nightOasisSpawnedThisCycle = true
        }
        if (prevDayPhase < 0.85f && phase >= 0.85f) nightOasisSpawnedThisCycle = false
        prevDayPhase = phase

        // Night oasis countdown
        if (nightOasisActive) {
            nightOasisTimer -= dt
            if (nightOasisTimer <= 0f) nightOasisActive = false
            else {
                val dx = camel.x - nightOasisX; val dy = camel.y - nightOasisY
                if (sqrt(dx * dx + dy * dy) < 1.5f) {
                    camelState = camelState.copy(
                        loveAtLastInteraction = (camelState.currentLove() + 20f).coerceAtMost(CamelState.MAX_LOVE),
                        lastInteractionTime = System.currentTimeMillis())
                    stateManager.save(camelState); nightOasisActive = false
                }
            }
        }

        // Daily surprise spawn
        if (persistence.lastSurpriseDay != persistence.currentDay() && !surpriseActive) {
            spawnDailySurprise()
        }
        // Daily surprise collect
        if (surpriseActive) {
            val dx = camel.x - surpriseX; val dy = camel.y - surpriseY
            if (sqrt(dx * dx + dy * dy) < 1.0f) {
                camelState = camelState.copy(
                    loveAtLastInteraction = (camelState.currentLove() + 25f).coerceAtMost(CamelState.MAX_LOVE),
                    lastInteractionTime = System.currentTimeMillis())
                stateManager.save(camelState)
                persistence.collectiblesCount++; persistence.save()
                surpriseActive = false; collectSparkleTimer = 0.8f
                floatAnims.add(floatArrayOf(camel.x, camel.y, 1.5f, 1.5f, 255f, 220f, 30f, 1f))
            }
        }
        if (collectSparkleTimer > 0f) collectSparkleTimer -= dt

        // Food spawning and collection
        updateFoodSpawns(dt)
        if (feedLabelTimer > 0f) feedLabelTimer -= dt

        // Stable: pause love decay while resting inside
        val stableLoc = world.locations.firstOrNull { it.type == LocationType.STABLE }
        inStable = stableLoc != null && run {
            val dx = camel.x - stableLoc.tileX; val dy = camel.y - stableLoc.tileY
            sqrt(dx * dx + dy * dy) < stableLoc.arrivalRadius
        }
        if (inStable) {
            stableAnchorTimer += dt
            if (stableAnchorTimer >= 5f) {
                stableAnchorTimer = 0f
                camelState = camelState.copy(
                    loveAtLastInteraction = camelState.currentLove(),
                    lastInteractionTime = System.currentTimeMillis()
                )
            }
        } else {
            stableAnchorTimer = 0f
        }

        // Mood animations
        val love = camelState.currentLove()
        if (prevLoveForMood < 90f && love >= 90f) kickTimer = 0.5f
        if (kickTimer > 0f) kickTimer -= dt
        trotCooldown -= dt
        if (trotCooldown <= 0f && love in 50f..75f) { trotActive = true; trotTimer = 2f; trotCooldown = 30f }
        if (trotActive) { trotTimer -= dt; if (trotTimer <= 0f) trotActive = false }
        if (love >= 90f) {
            heartBubbleTimer -= dt
            if (heartBubbleTimer <= 0f) {
                floatAnims.add(floatArrayOf(camel.x, camel.y - 0.5f, 1.5f, 1.5f, 230f, 80f, 100f, 0f))
                heartBubbleTimer = 20f
            }
        }
        floatAnims.removeAll { it[2] <= 0f }
        floatAnims.forEach { it[2] -= dt }
        prevLoveForMood = love

        // Show instructions once after camel is named
        if (!checkedInstructions && camelState.isNamed) {
            checkedInstructions = true
            if (!persistence.seenInstructions) { showInstructions = true; instructionPage = 0 }
        }

        // New systems
        updateMood()
        updateMicroEvents(dt)
        updateGroomLabel(dt)
        gainMovementXP(dt)
        if (levelUpTimer > 0f) levelUpTimer -= dt
        if (moodBubbleTimer > 0f) moodBubbleTimer -= dt
        checkWeeklyEvents()

        // Auto wander trigger
        if (System.currentTimeMillis() - lastInputMs > autoWanderAfterMs && !camel.autoWandering && !playerPlaying)
            camel.startAutoWander(world)

        // Player play state
        if (playerPlaying) {
            playerPlayTimer -= dt
            if (playerPlayTimer <= 0f) { playerPlaying = false }
            else {
                playerPlayAngle -= dt * 2.8f
                camel.x = playerPlayCX + cos(playerPlayAngle).toFloat() * playRadius
                camel.y = playerPlayCY + sin(playerPlayAngle).toFloat() * playRadius
                camelFacingLeft = cos(playerPlayAngle) < 0f
                camel.isMoving = true; camel.walkPhase += dt * 5f
            }
        } else {
            camel.update(dt, inputDx, inputDy, world)
            when (camel.direction) {
                CamelEntity.Direction.LEFT  -> camelFacingLeft = true
                CamelEntity.Direction.RIGHT -> camelFacingLeft = false
                else -> {}
            }
        }

        // Update NPC camels (pass player coords)
        wanderCamels.forEach { it.update(dt, world, camel.x, camel.y) }

        // Play proximity (cooldown per WanderCamel prevents re-trigger for 30 s)
        if (camel.autoWandering && !playerPlaying) {
            for (wc in wanderCamels) {
                if (wc.state == WanderCamel.State.PLAYING || wc.playCooldown > 0f) continue
                val dx = wc.x - camel.x; val dy = wc.y - camel.y
                if (sqrt(dx * dx + dy * dy) < 2.0f) {
                    val cx = (camel.x + wc.x) / 2f; val cy = (camel.y + wc.y) / 2f
                    wc.startPlaying(cx, cy)
                    playerPlaying = true; playerPlayTimer = playDuration
                    playerPlayCX = cx; playerPlayCY = cy
                    playerPlayAngle = atan2(camel.y - cy, camel.x - cx)
                    camel.autoWandering = false
                    lastInputMs = System.currentTimeMillis()
                    // Camel bond tracking
                    val newBond = persistence.addCamelBond(wanderCamels.indexOf(wc))
                    wc.bondCount = newBond
                    if (newBond >= 3) wc.followTimer = 120f
                    gainXP(10)
                    break
                }
            }
        }

        // Update village NPCs
        val playerMoving = camel.isMoving || playerPlaying
        for (npc in npcs) {
            val interacted = npc.update(dt, world, camel.x, camel.y, playerMoving, isNight)
            if (interacted) {
                camelState = camelState.copy(
                    loveAtLastInteraction = (camelState.currentLove() + 5f).coerceAtMost(CamelState.MAX_LOVE),
                    lastInteractionTime = System.currentTimeMillis()
                )
                stateManager.save(camelState)
                lastInputMs = System.currentTimeMillis()
                gainXP(5)
                val hitFive = persistence.addNpcFriendship(npc.npcId)
                if (hitFive) {
                    persistence.unlockCosmetic(npc.npcId)
                    floatAnims.add(floatArrayOf(npc.x, npc.y - 0.5f, 2f, 2f, 218f, 165f, 32f, 1f))
                }
            }
        }

        // Speed scales with happiness
        camel.loveRatio = camelState.currentLove() / CamelState.MAX_LOVE

        snapCamera()

        // Location arrival
        val loc = world.getLocationAt(camel.x, camel.y)
        if (loc != currentLocation) {
            currentLocation = loc
            if (loc != null) {
                locationLabel = loc.name; locationLabelTimer = 3.5f
                persistence.recordDiscovery(loc.name)
                if (loc.type == LocationType.OASIS) {
                    val s = camelState
                    camelState = s.copy(
                        loveAtLastInteraction = (s.currentLove() + 5f).coerceAtMost(CamelState.MAX_LOVE),
                        lastInteractionTime = System.currentTimeMillis()
                    )
                    stateManager.save(camelState)
                }
            }
        }
        if (locationLabelTimer > 0f) locationLabelTimer -= dt
    }

    private fun snapCamera() {
        val w = width.toFloat(); val h = height.toFloat()
        camX = (camel.x * tileSize - w / 2f).coerceIn(0f, world.width * tileSize - w)
        camY = (camel.y * tileSize - h / 2f).coerceIn(0f, world.height * tileSize - h)
    }

    // ── Mood system ────────────────────────────────────────────────────────────
    private fun updateMood() {
        val now = System.currentTimeMillis()
        if (now < camelState.moodUntil) return
        val love   = camelState.currentLove()
        val traits = camelState.traits
        val base = when {
            love > 90 -> CamelMood.AFFECTIONATE
            love > 70 -> CamelMood.HAPPY
            love > 50 -> CamelMood.PLAYFUL
            love > 30 -> CamelMood.CURIOUS
            love > 10 -> CamelMood.LONELY
            else      -> CamelMood.MOODY
        }
        val mood = when {
            CamelTrait.LAZY       in traits && base == CamelMood.PLAYFUL -> CamelMood.SLEEPY
            CamelTrait.CURIOUS    in traits && base == CamelMood.HAPPY   -> CamelMood.CURIOUS
            CamelTrait.PROUD      in traits && base == CamelMood.HAPPY   -> CamelMood.EXCITED
            CamelTrait.MISCHIEVOUS in traits && base == CamelMood.HAPPY  -> CamelMood.ENERGETIC
            else -> base
        }
        if (mood != camelState.mood) moodBubbleTimer = 3f
        camelState = camelState.copy(mood = mood, moodUntil = now + 30_000L)
        stateManager.save(camelState)
    }

    // ── Grooming ───────────────────────────────────────────────────────────────
    private fun handleGroom() {
        val day = persistence.currentDay()
        val state = if (camelState.lastGroomDay != day)
            camelState.copy(groomsToday = 0, lastGroomDay = day) else camelState
        if (state.groomsToday >= 3) {
            groomLabel = "Already groomed!"; groomLabelTimer = 1.5f; camelState = state; return
        }
        val (action, gain) = listOf("Brush" to 4f, "Wash" to 6f, "Clean Hooves" to 5f, "Style Fur" to 7f).random()
        camelState = state.copy(
            loveAtLastInteraction = (state.currentLove() + gain).coerceAtMost(CamelState.MAX_LOVE),
            lastInteractionTime   = System.currentTimeMillis(),
            groomsToday           = state.groomsToday + 1,
            lastGroomDay          = day
        )
        stateManager.save(camelState)
        lastInputMs = System.currentTimeMillis()
        groomLabel = action; groomLabelTimer = 1.8f
        floatAnims.add(floatArrayOf(camel.x, camel.y - 0.5f, 1.2f, 1.2f, 255f, 180f, 120f, 0f))
        gainXP(5)
    }

    private fun updateGroomLabel(dt: Float) { if (groomLabelTimer > 0f) groomLabelTimer -= dt }

    // ── XP / Leveling ──────────────────────────────────────────────────────────
    private fun gainMovementXP(dt: Float) {
        if (!camel.isMoving && !playerPlaying) return
        xpMoveAccum += dt
        if (xpMoveAccum >= 1f) { gainXP(1); xpMoveAccum = 0f }
    }

    private fun gainXP(amount: Int) {
        var newXP    = camelState.xp + amount
        var newLevel = camelState.level
        val abilities = camelState.unlockedAbilities.toMutableSet()
        while (newXP >= newLevel * 100) {
            newXP -= newLevel * 100
            newLevel++
            levelUpTimer = 2.5f
            when (newLevel) {
                3  -> abilities.add("Dash")
                5  -> abilities.add("TreasureSense")
                7  -> abilities.add("Sit")
                10 -> abilities.add("SandGlide")
                15 -> abilities.add("Dance")
                20 -> abilities.add("RelicSense")
            }
            floatAnims.add(floatArrayOf(camel.x, camel.y - 1f, 2.5f, 2.5f, 120f, 200f, 255f, 1f))
        }
        camelState = camelState.copy(xp = newXP, level = newLevel, unlockedAbilities = abilities)
        if (levelUpTimer > 0f) stateManager.save(camelState)
    }

    // ── Micro-events ───────────────────────────────────────────────────────────
    private fun updateMicroEvents(dt: Float) {
        if (activeMicroEvent != null) {
            microEventTimer -= dt
            when (activeMicroEvent) {
                MicroEvent.SANDSTORM     -> sandstormAlpha = (sandstormAlpha * 0.998f)
                MicroEvent.SHOOTING_STAR -> { starSX += dt * 500f; starSY += dt * 200f }
                MicroEvent.FOX           -> foxTileX += dt * 3f
                else -> {}
            }
            if (microEventTimer <= 0f) {
                if (activeMicroEvent == MicroEvent.SANDSTORM) sandstormAlpha = 0f
                activeMicroEvent = null
            }
            return
        }
        nextMicroEventTimer -= dt
        if (nextMicroEventTimer <= 0f) {
            spawnMicroEvent()
            nextMicroEventTimer = 25f + (Math.random() * 30f).toFloat()
        }
    }

    private fun spawnMicroEvent() {
        val roll = Math.random()
        when {
            roll < 0.14 -> { activeMicroEvent = MicroEvent.SANDSTORM; microEventTimer = 6f; sandstormAlpha = 0.45f }
            roll < 0.25 -> { activeMicroEvent = MicroEvent.SHOOTING_STAR; microEventTimer = 1.5f
                             starSX = (Math.random() * width * 0.6).toFloat(); starSY = height * 0.1f }
            roll < 0.40 -> { activeMicroEvent = MicroEvent.FOX; microEventTimer = 3f
                             foxTileX = camel.x - 6f; foxTileY = camel.y + (Math.random() * 2 - 1).toFloat() }
            roll < 0.55 -> { activeMicroEvent = MicroEvent.MIRAGE; microEventTimer = 5f }
            roll < 0.70 -> {
                activeMicroEvent = MicroEvent.HERD_CUDDLE; microEventTimer = 4f
                val cx = wanderCamels.map { it.x }.average().toFloat()
                val cy = wanderCamels.map { it.y }.average().toFloat()
                wanderCamels.forEachIndexed { i, wc ->
                    wc.x = cx + (i - 1.5f) * 0.9f; wc.y = cy; wc.isMoving = false
                }
                floatAnims.add(floatArrayOf(cx, cy, 2f, 2f, 255f, 200f, 180f, 0f))
            }
            roll < 0.85 -> {
                activeMicroEvent = MicroEvent.HERD_CIRCLE; microEventTimer = 5f
                val cx = wanderCamels.map { it.x }.average().toFloat()
                val cy = wanderCamels.map { it.y }.average().toFloat()
                wanderCamels.forEach { wc -> wc.startPlaying(cx, cy) }
            }
            else -> { activeMicroEvent = MicroEvent.PYRAMID_GLOW; microEventTimer = 7f }
        }
    }

    // ── Weekly events ──────────────────────────────────────────────────────────
    private fun checkWeeklyEvents() {
        val day = persistence.currentDay()
        if (persistence.lastWeeklyEventDay == day) return
        when {
            day % 7L == 0L -> triggerCamelRaceDay()
            day % 5L == 0L -> triggerHerdGathering()
            day % 3L == 0L -> triggerHerdGathering()
            else -> return
        }
        persistence.lastWeeklyEventDay = day
        persistence.save()
    }

    private fun triggerCamelRaceDay() {
        wanderCamels.forEach { it.pickTarget(world) }
        val loc = world.oases.random()
        floatAnims.add(floatArrayOf(loc.tileX.toFloat(), loc.tileY.toFloat(), 2f, 2f, 255f, 200f, 80f, 1f))
    }

    private fun triggerHerdGathering() {
        val oasis = world.oases.random()
        wanderCamels.forEachIndexed { i, wc ->
            wc.x = oasis.tileX + (i - 1.5f) * 1.2f
            wc.y = oasis.tileY + 1f
        }
    }

    // ── Login streak ───────────────────────────────────────────────────────────
    private fun checkLoginStreak() {
        val day = persistence.currentDay()
        if (persistence.lastLoginDay == day) return
        persistence.loginStreak = if (persistence.lastLoginDay == day - 1) persistence.loginStreak + 1 else 1
        persistence.lastLoginDay = day
        persistence.save()
        if (persistence.loginStreak > 1)
            floatAnims.add(floatArrayOf(camel.x, camel.y - 0.5f, 2f, 2f, 255f, 200f, 80f, 1f))
    }

    // ── Food ───────────────────────────────────────────────────────────────────
    private fun updateFoodSpawns(dt: Float) {
        foodSpawnTimer -= dt
        if (foodSpawnTimer <= 0f && foodItems.size < maxFoodItems) {
            spawnFood()
            foodSpawnTimer = foodSpawnInterval + (Math.random() * 30).toFloat()
        }
        val iter = foodItems.iterator()
        while (iter.hasNext()) {
            val f = iter.next()
            val dx = camel.x - f[0]; val dy = camel.y - f[1]
            if (sqrt(dx * dx + dy * dy) < 1.2f) {
                iter.remove()
                camelState = camelState.copy(
                    loveAtLastInteraction = (camelState.currentLove() + 5f).coerceAtMost(CamelState.MAX_LOVE),
                    lastInteractionTime = System.currentTimeMillis()
                )
                stateManager.save(camelState)
                floatAnims.add(floatArrayOf(camel.x, camel.y - 0.5f, 1.2f, 1.2f, 180f, 230f, 100f, 0f))
                gainXP(3)
            }
        }
    }

    private fun spawnFood() {
        val rng = java.util.Random()
        if (Math.random() < 0.4) {
            repeat(200) {
                val tx = rng.nextInt(world.width); val ty = rng.nextInt(world.height)
                if (world.getTile(tx, ty) == Tile.CACTUS &&
                    foodItems.none { kotlin.math.abs(it[0] - tx) < 3 && kotlin.math.abs(it[1] - ty) < 3 }) {
                    foodItems.add(floatArrayOf(tx + 0.5f, ty + 0.5f, 1f))
                    return
                }
            }
        }
        repeat(100) {
            val tx = 5 + rng.nextInt(70); val ty = 5 + rng.nextInt(70)
            if (world.getTile(tx, ty) == Tile.SAND &&
                foodItems.none { kotlin.math.abs(it[0] - tx) < 3 && kotlin.math.abs(it[1] - ty) < 3 }) {
                foodItems.add(floatArrayOf(tx + 0.5f, ty + 0.5f, 0f))
                return
            }
        }
    }

    // ── Render ─────────────────────────────────────────────────────────────────
    private fun renderFrame(canvas: Canvas) {
        canvas.drawColor(Color.rgb(224, 214, 176))
        drawTiles(canvas)
        drawFoodItems(canvas)
        drawStableStructure(canvas)
        drawDailySurprise(canvas)
        drawNpcs(canvas)
        drawWanderCamels(canvas)
        drawCamel(canvas)
        drawPyramidStructures(canvas)
        drawDayNightOverlay(canvas)
        drawMicroEventOverlay(canvas)
        drawFloatAnims(canvas)
        if (collectSparkleTimer > 0f) drawCollectSparkle(canvas)
        drawHUD(canvas)
        drawDpad(canvas)
        if (groomLabelTimer > 0f) drawGroomLabel(canvas)
        if (feedLabelTimer > 0f) drawFeedLabel(canvas)
        if (locationLabelTimer > 0f) drawLocationBanner(canvas)
        if (showJournal) drawJournal(canvas)
        if (showInstructions) drawInstructions(canvas)
    }

    // ── Tiles ──────────────────────────────────────────────────────────────────
    private fun drawTiles(canvas: Canvas) {
        val ts = tileSize
        val x0 = (camX / ts).toInt().coerceAtLeast(0)
        val y0 = (camY / ts).toInt().coerceAtLeast(0)
        val x1 = ((camX + width) / ts).toInt().coerceAtMost(world.width - 1)
        val y1 = ((camY + height) / ts).toInt().coerceAtMost(world.height - 1)
        for (ty in y0..y1) for (tx in x0..x1)
            drawTile(canvas, world.getTile(tx, ty), tx, ty, tx * ts - camX, ty * ts - camY, ts)
    }

    private fun drawTile(canvas: Canvas, tile: Int, tx: Int, ty: Int, sx: Float, sy: Float, ts: Float) {
        // BUILDING and BUILDING_FRONT handled separately for colour variety
        if (tile == Tile.BUILDING) {
            val rc = roofPalette[((tx * 7 + ty * 13) and 0x7FFFFFFF) % roofPalette.size]
            canvas.drawRect(sx, sy, sx + ts, sy + ts, p(rc))
            val dark = Color.rgb(Color.red(rc) * 2 / 3, Color.green(rc) * 2 / 3, Color.blue(rc) * 2 / 3)
            canvas.drawRect(sx, sy + ts * 0.82f, sx + ts, sy + ts, p(dark))
            val beam = p(dark, Paint.Style.STROKE).apply { strokeWidth = 1.5f }
            canvas.drawLine(sx + ts * .33f, sy + ts * .05f, sx + ts * .33f, sy + ts * .82f, beam)
            canvas.drawLine(sx + ts * .67f, sy + ts * .05f, sx + ts * .67f, sy + ts * .82f, beam)
            return
        }
        if (tile == Tile.BUILDING_FRONT) {
            canvas.drawRect(sx, sy, sx + ts, sy + ts, p(Color.rgb(215, 192, 150)))
            // Shadow strip at top (roof-to-wall junction)
            canvas.drawRect(sx, sy, sx + ts, sy + ts * 0.07f, p(Color.rgb(125, 102, 72)))
            // Window (upper half)
            val wx = sx + ts * 0.18f; val wy = sy + ts * 0.14f
            val ww = ts * 0.64f;      val wh = ts * 0.42f
            canvas.drawRect(wx, wy, wx + ww, wy + wh, p(Color.rgb(62, 48, 32)))
            canvas.drawRect(wx + ts * .04f, wy + ts * .04f, wx + ww * .45f, wy + wh * .42f,
                p(Color.argb(80, 220, 210, 180)))
            // Door arch (lower half)
            val dcx = sx + ts * .5f; val dby = sy + ts * .96f
            val dw = ts * .28f;      val dh = ts * .40f
            canvas.drawRect(dcx - dw / 2, dby - dh, dcx + dw / 2, dby, p(Color.rgb(60, 44, 28)))
            canvas.drawArc(RectF(dcx - dw / 2, dby - dh - dw / 2, dcx + dw / 2, dby - dh + dw / 2),
                180f, 180f, true, p(Color.rgb(60, 44, 28)))
            // Mortar lines (horizontal stone courses)
            val mortar = p(Color.rgb(178, 156, 116), Paint.Style.STROKE).apply { strokeWidth = 0.8f }
            canvas.drawLine(sx, sy + ts * .52f, sx + ts, sy + ts * .52f, mortar)
            canvas.drawLine(sx, sy + ts * .76f, sx + ts, sy + ts * .76f, mortar)
            // Vertical half-brick offset
            canvas.drawLine(sx + ts * .5f, sy + ts * .52f, sx + ts * .5f, sy + ts * .76f, mortar)
            return
        }

        canvas.drawRect(sx, sy, sx + ts, sy + ts, p(tileColors[tile] ?: tileColors[Tile.SAND]!!))
        when (tile) {
            Tile.WATER -> {
                val wp = p(Color.rgb(80, 120, 170), Paint.Style.STROKE).apply { strokeWidth = 1.8f }
                for (row in 0..1) {
                    val wy = sy + ts * (0.38f + row * 0.26f)
                    canvas.drawPath(Path().apply {
                        moveTo(sx + ts*.05f, wy); quadTo(sx + ts*.28f, wy - ts*.06f, sx + ts*.5f, wy)
                        quadTo(sx + ts*.72f, wy + ts*.06f, sx + ts*.95f, wy)
                    }, wp)
                }
            }
            Tile.GRASS -> {
                val gp = p(Color.rgb(80, 130, 60), Paint.Style.STROKE).apply { strokeWidth = 1.5f }
                listOf(.2f to .65f, .5f to .35f, .75f to .70f, .38f to .80f, .62f to .45f).forEach { (fx, fy) ->
                    canvas.drawLine(sx+fx*ts, sy+fy*ts, sx+(fx-.03f)*ts, sy+(fy-.18f)*ts, gp)
                }
            }
            Tile.PALM -> {
                canvas.drawRect(sx+ts*.44f, sy+ts*.3f, sx+ts*.56f, sy+ts*.85f, p(Color.rgb(90,65,40)))
                val lf = p(Color.rgb(50, 100, 40))
                repeat(6) { i ->
                    val a = Math.PI * 2 * i / 6
                    val lx = sx + ts*.5f + cos(a).toFloat() * ts*.28f
                    val ly = sy + ts*.22f + sin(a).toFloat() * ts*.16f
                    canvas.drawOval(RectF(lx-ts*.1f, ly-ts*.055f, lx+ts*.1f, ly+ts*.055f), lf)
                }
                canvas.drawCircle(sx+ts*.5f, sy+ts*.2f, ts*.13f, lf)
            }
            Tile.CACTUS -> {
                val cp = p(Color.rgb(55, 100, 50))
                canvas.drawRoundRect(RectF(sx+ts*.42f,sy+ts*.18f,sx+ts*.58f,sy+ts*.88f),5f,5f,cp)
                canvas.drawRoundRect(RectF(sx+ts*.18f,sy+ts*.38f,sx+ts*.43f,sy+ts*.52f),4f,4f,cp)
                canvas.drawRoundRect(RectF(sx+ts*.57f,sy+ts*.46f,sx+ts*.82f,sy+ts*.60f),4f,4f,cp)
            }
            Tile.STONE_PATH -> {
                val lp = p(Color.rgb(136,128,112), Paint.Style.STROKE).apply { strokeWidth = 1f }
                canvas.drawLine(sx+ts*.1f,sy+ts*.5f,sx+ts*.9f,sy+ts*.5f,lp)
                canvas.drawLine(sx+ts*.5f,sy+ts*.1f,sx+ts*.5f,sy+ts*.9f,lp)
            }
            Tile.PYRAMID, Tile.PYRAMID_STEPS -> {
                val shade = if (tile == Tile.PYRAMID) Color.rgb(192, 178, 142) else Color.rgb(208, 196, 160)
                canvas.drawRect(sx, sy, sx+ts, sy+ts, p(shade))
                if (tile == Tile.PYRAMID_STEPS) {
                    val lp = p(Color.rgb(168,154,118), Paint.Style.STROKE).apply { strokeWidth = 1f }
                    canvas.drawLine(sx, sy+ts*.5f, sx+ts, sy+ts*.5f, lp)
                }
            }
            Tile.DUNE -> canvas.drawArc(RectF(sx,sy+ts*.25f,sx+ts,sy+ts*1.25f),180f,180f,false,
                p(Color.rgb(195,185,145), Paint.Style.STROKE).apply { strokeWidth = 2.5f })
            Tile.DEEP_SAND -> listOf(.25f to .30f,.60f to .68f,.80f to .22f,.42f to .75f).forEach { (fx,fy) ->
                canvas.drawCircle(sx+fx*ts,sy+fy*ts,ts*.035f,p(Color.rgb(180,170,132)))
            }
        }
    }

    // ── 3-D pyramid structures ─────────────────────────────────────────────────
    private fun drawPyramidStructures(canvas: Canvas) {
        world.locations.filter { it.type == LocationType.PYRAMID }.forEach { loc ->
            val cx = loc.tileX * tileSize - camX
            val cy = loc.tileY * tileSize - camY
            drawPyramid3D(canvas, cx, cy)
        }
    }

    private fun drawPyramid3D(canvas: Canvas, cx: Float, cy: Float) {
        val ts = tileSize
        val halfBase = ts * 5.2f
        val height   = ts * 6.8f
        val peakX  = cx
        val peakY  = cy - height + ts * 0.5f
        val baseY  = cy + ts * 0.4f
        val baseL  = cx - halfBase
        val baseR  = cx + halfBase
        val leftFace = Path().apply {
            moveTo(baseL, baseY); lineTo(peakX, peakY); lineTo(cx, baseY); close()
        }
        val rightFace = Path().apply {
            moveTo(cx, baseY); lineTo(peakX, peakY); lineTo(baseR, baseY); close()
        }
        canvas.drawPath(leftFace,  p(Color.rgb(210, 196, 152)))
        canvas.drawPath(rightFace, p(Color.rgb(158, 144, 100)))
        val stoneP = p(Color.rgb(130, 118, 84), Paint.Style.STROKE).apply { strokeWidth = 1.8f }
        for (i in 1..7) {
            val t = i / 8f
            val lx = baseL + (peakX - baseL) * t
            val rx = baseR + (peakX - baseR) * t
            val ly = baseY + (peakY - baseY) * t
            canvas.drawLine(lx, ly, rx, ly, stoneP)
        }
        val ridge = p(Color.rgb(120, 108, 72), Paint.Style.STROKE).apply { strokeWidth = 2f }
        canvas.drawLine(cx, baseY, peakX, peakY, ridge)
        val op = p(Color.rgb(100, 88, 56), Paint.Style.STROKE).apply { strokeWidth = 3f; strokeJoin = Paint.Join.ROUND }
        canvas.drawPath(Path().apply { moveTo(baseL, baseY); lineTo(peakX, peakY); lineTo(baseR, baseY) }, op)
        val archP = p(Color.rgb(80, 68, 40))
        canvas.drawArc(RectF(cx - ts*.18f, baseY - ts*.22f, cx + ts*.18f, baseY + ts*.04f), 180f, 180f, true, archP)
    }

    // ── NPCs ───────────────────────────────────────────────────────────────────
    private fun drawNpcs(canvas: Canvas) {
        val ts = tileSize
        npcs.forEach { npc ->
            val sx = npc.x * ts - camX; val sy = npc.y * ts - camY
            if (sx < -ts * 2 || sx > width + ts * 2 || sy < -ts * 2 || sy > height + ts * 2) return@forEach
            val bob = if (npc.isMoving) sin(npc.walkPhase) * ts * 0.025f else 0f
            drawNpc(canvas, sx, sy + bob, ts, npc.facingLeft)
            if (npc.showInteractTimer > 0f) drawInteractIcon(canvas, sx, sy, ts, npc)
        }
    }

    private fun drawInteractIcon(canvas: Canvas, sx: Float, sy: Float, ts: Float, npc: NpcEntity) {
        val alpha = ((npc.showInteractTimer / 1.8f) * 255).toInt().coerceIn(0, 255)
        val color = if (npc.type == NpcEntity.Type.PETTER) Color.rgb(230, 60, 80) else Color.rgb(240, 160, 40)
        val ip = p(color).apply {
            this.alpha = alpha; textSize = ts * 0.38f; textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val icon = if (npc.type == NpcEntity.Type.PETTER) "♥" else "★"
        canvas.drawText(icon, sx, sy - ts * 0.85f, ip)
        canvas.drawText(icon, sx + ts * 0.28f, sy - ts * 0.65f,
            ip.apply { textSize = ts * 0.26f; this.alpha = alpha * 2 / 3 })
    }

    private fun drawNpc(canvas: Canvas, sx: Float, sy: Float, ts: Float, facingLeft: Boolean) {
        canvas.save()
        if (facingLeft) canvas.scale(-1f, 1f, sx, sy)

        val skin   = p(Color.rgb(200, 160, 110))
        val robe   = p(Color.rgb(240, 235, 215))
        val accent = p(Color.rgb(180,  60,  40))
        val dark   = p(Color.rgb( 60,  50,  40))
        val outline= p(Color.rgb( 60,  50,  40), Paint.Style.STROKE).apply { strokeWidth = ts*.03f }

        canvas.drawRoundRect(RectF(sx-ts*.07f, sy+ts*.22f, sx+ts*.01f, sy+ts*.46f), ts*.04f, ts*.04f, dark)
        canvas.drawRoundRect(RectF(sx+ts*.01f, sy+ts*.22f, sx+ts*.09f, sy+ts*.46f), ts*.04f, ts*.04f, dark)
        canvas.drawRoundRect(RectF(sx-ts*.12f, sy-ts*.14f, sx+ts*.12f, sy+ts*.28f), ts*.06f, ts*.06f, robe)
        canvas.drawRoundRect(RectF(sx-ts*.12f, sy-ts*.14f, sx+ts*.12f, sy+ts*.28f), ts*.06f, ts*.06f, outline)
        canvas.drawRect(sx-ts*.12f, sy+ts*.04f, sx+ts*.12f, sy+ts*.10f, accent)
        canvas.drawRoundRect(RectF(sx+ts*.09f, sy-ts*.10f, sx+ts*.16f, sy+ts*.12f), ts*.04f, ts*.04f, robe)
        canvas.drawCircle(sx, sy-ts*.24f, ts*.13f, skin)
        canvas.drawCircle(sx, sy-ts*.24f, ts*.13f, outline)
        val wrap = p(Color.rgb(200, 180, 140))
        canvas.drawArc(RectF(sx-ts*.13f, sy-ts*.38f, sx+ts*.13f, sy-ts*.13f), 180f, 180f, false, wrap)
        canvas.drawRect(sx-ts*.14f, sy-ts*.30f, sx+ts*.02f, sy-ts*.12f, wrap)
        canvas.drawCircle(sx+ts*.05f, sy-ts*.24f, ts*.025f, dark)

        canvas.restore()
    }

    // ── Camel drawing ──────────────────────────────────────────────────────────
    private fun drawCamel(canvas: Canvas) {
        val sx = camel.x * tileSize - camX; val sy = camel.y * tileSize - camY
        canvas.save(); canvas.translate(sx, sy)
        val love = camelState.currentLove()
        val bob = if (camel.isMoving) sin(camel.walkPhase).toFloat() * tileSize * .025f else 0f
        val moodDroop = if (love < 30f) (30f - love) / 30f else 0f
        val kickProg = if (kickTimer > 0f) 1f - kickTimer / 0.5f else 0f
        val hasSaddle = persistence.unlockedCosmetics.isNotEmpty()
        val saddleColorIdx = persistence.unlockedCosmetics.minOrNull() ?: 0
        val trotBoost = if (trotActive) 1.6f else 1f
        drawCamelSprite(canvas, tileSize, bob, camelFacingLeft,
            moodDroop = moodDroop, kickProgress = kickProg,
            hasSaddle = hasSaddle, saddleColorIdx = saddleColorIdx, trotBoost = trotBoost)
        canvas.restore()
        if (playerPlaying) drawPlaySparkles(canvas, sx, sy, tileSize)
        if (moodBubbleTimer > 0f) drawMoodBubble(canvas, sx, sy)
        if (inStable) {
            val t = (System.nanoTime() / 1_200_000_000L % 3).toInt()
            val zText = when (t) { 0 -> "z"; 1 -> "zz"; else -> "zzz" }
            val zp = p(Color.rgb(160, 190, 255)).apply {
                typeface = Typeface.MONOSPACE; textSize = tileSize * 0.28f; textAlign = Paint.Align.CENTER
            }
            canvas.drawText(zText, sx + tileSize * 0.5f, sy - tileSize * 1.2f, zp)
        }
    }

    private fun drawWanderCamels(canvas: Canvas) {
        val ts = tileSize
        wanderCamels.forEach { wc ->
            val sx = wc.x * ts - camX; val sy = wc.y * ts - camY
            if (sx < -ts*2 || sx > width+ts*2 || sy < -ts*2 || sy > height+ts*2) return@forEach
            canvas.save(); canvas.translate(sx, sy)
            val bob = if (wc.isMoving) sin(wc.walkPhase).toFloat() * ts * .025f else 0f
            drawCamelSprite(canvas, ts, bob, wc.facingLeft, shade = true)
            canvas.restore()
            if (wc.state == WanderCamel.State.PLAYING) drawPlaySparkles(canvas, sx, sy, ts)
            // Name tag for bonded camels
            if (wc.bondCount > 0 || wc.followTimer > 0f) {
                val np = p(Color.WHITE).apply {
                    typeface = Typeface.MONOSPACE; textSize = ts * 0.22f; textAlign = Paint.Align.CENTER
                }
                val tw = np.measureText(wc.name)
                canvas.drawRoundRect(
                    RectF(sx - tw/2 - 6f, sy - ts*0.95f - 18f, sx + tw/2 + 6f, sy - ts*0.95f + 4f),
                    4f, 4f, p(Color.argb(160, 0, 0, 0)))
                canvas.drawText(wc.name, sx, sy - ts * 0.95f, np)
            }
        }
    }

    private fun drawPlaySparkles(canvas: Canvas, sx: Float, sy: Float, ts: Float) {
        val sp = p(Color.rgb(255, 200, 50)).apply { textSize = ts * .3f; textAlign = Paint.Align.CENTER }
        canvas.drawText("♪", sx, sy - ts * .6f, sp)
        canvas.drawText("~", sx + ts * .3f, sy - ts * .35f, sp)
    }

    private fun drawCamelSprite(
        canvas: Canvas, ts: Float, bob: Float, flipLeft: Boolean,
        shade: Boolean = false, moodDroop: Float = 0f, kickProgress: Float = 0f,
        hasSaddle: Boolean = false, saddleColorIdx: Int = 0, trotBoost: Float = 1f
    ) {
        canvas.save()
        if (flipLeft) canvas.scale(-1f, 1f)

        val cy = bob
        val droop = moodDroop * ts * 0.1f
        cOutline.strokeWidth = ts * .045f
        val fill = if (shade) cShade else cBody

        fun leg(x: Float, swing: Float, dark: Boolean, kickOffset: Float = 0f) {
            val lp = if (dark) cShade else fill
            canvas.drawRoundRect(RectF(x-ts*.07f+swing, cy+ts*.09f+kickOffset, x+ts*.07f+swing, cy+ts*.38f+kickOffset), ts*.06f, ts*.06f, lp)
            canvas.drawRoundRect(RectF(x-ts*.07f+swing, cy+ts*.09f+kickOffset, x+ts*.07f+swing, cy+ts*.38f+kickOffset), ts*.06f, ts*.06f, cOutline)
            canvas.drawOval(RectF(x-ts*.09f+swing, cy+ts*.32f+kickOffset, x+ts*.09f+swing, cy+ts*.43f+kickOffset), cHoof)
        }

        val walkSw = if (shade) {
            (sin(System.nanoTime() / 200_000_000f) * ts * .09f)
        } else {
            if (camel.isMoving || playerPlaying)
                (sin(camel.walkPhase * trotBoost) * ts * .09f)
            else 0f
        }

        // Kick offset for back legs when kickProgress > 0
        val kickOff = if (kickProgress > 0f) -sin(kickProgress * Math.PI.toFloat()) * ts * 0.2f else 0f

        leg(-ts*.18f,  walkSw, true, kickOff);  leg(-ts*.07f, -walkSw, false, kickOff)
        leg( ts*.09f,  walkSw, true);            leg( ts*.20f, -walkSw, false)

        canvas.drawOval(RectF(-ts*.32f, cy-ts*.20f, ts*.28f, cy+ts*.18f), fill)
        canvas.drawOval(RectF(-ts*.32f, cy-ts*.20f, ts*.28f, cy+ts*.18f), cOutline)

        // Saddle between body and hump
        if (hasSaddle && !shade) {
            val saddleColors = intArrayOf(
                Color.rgb(180, 50, 30),   // terracotta
                Color.rgb(50, 80, 160),   // blue
                Color.rgb(120, 50, 160),  // purple
                Color.rgb(50, 130, 70),   // green
                Color.rgb(190, 150, 30),  // gold
                Color.rgb(30, 130, 140)   // teal
            )
            val sc = saddleColors[saddleColorIdx.coerceIn(0, saddleColors.size - 1)]
            canvas.drawOval(RectF(-ts*.18f, cy-ts*.14f, ts*.02f, cy+ts*.02f), p(sc))
            canvas.drawOval(RectF(-ts*.18f, cy-ts*.14f, ts*.02f, cy+ts*.02f),
                p(Color.argb(120, 0, 0, 0), Paint.Style.STROKE).apply { strokeWidth = ts*.02f })
        }

        val hump = Path().apply {
            moveTo(-ts*.04f, cy-ts*.17f)
            cubicTo(-ts*.04f, cy-ts*.52f, -ts*.30f, cy-ts*.52f, -ts*.30f, cy-ts*.17f); close()
        }
        canvas.drawPath(hump, fill); canvas.drawPath(hump, cOutline)

        canvas.drawPath(Path().apply {
            moveTo(-ts*.30f, cy+ts*.04f); cubicTo(-ts*.44f, cy, -ts*.44f, cy+ts*.18f, -ts*.32f, cy+ts*.16f)
        }, p(Color.rgb(72,42,12), Paint.Style.STROKE).apply { strokeWidth=ts*.05f; strokeCap=Paint.Cap.ROUND })
        canvas.drawCircle(-ts*.32f, cy+ts*.18f, ts*.055f, cHoof)

        // Neck with mood droop
        val neck = Path().apply {
            moveTo(ts*.18f, cy-ts*.14f+droop)
            cubicTo(ts*.22f, cy-ts*.35f+droop, ts*.34f, cy-ts*.46f+droop, ts*.40f, cy-ts*.56f+droop)
            cubicTo(ts*.46f, cy-ts*.44f+droop, ts*.38f, cy-ts*.32f+droop, ts*.30f, cy-ts*.12f+droop)
            close()
        }
        canvas.drawPath(neck, fill); canvas.drawPath(neck, cOutline)

        // Head with mood droop
        canvas.drawCircle(ts*.44f, cy-ts*.64f+droop, ts*.20f, fill)
        canvas.drawCircle(ts*.44f, cy-ts*.64f+droop, ts*.20f, cOutline)

        canvas.drawOval(RectF(ts*.50f, cy-ts*.56f+droop, ts*.70f, cy-ts*.42f+droop), fill)
        canvas.drawOval(RectF(ts*.50f, cy-ts*.56f+droop, ts*.70f, cy-ts*.42f+droop), cOutline)
        canvas.drawCircle(ts*.665f, cy-ts*.455f+droop, ts*.028f, cHoof)

        val ear = Path().apply {
            moveTo(ts*.34f, cy-ts*.78f+droop)
            cubicTo(ts*.28f, cy-ts*.92f+droop, ts*.44f, cy-ts*.92f+droop, ts*.46f, cy-ts*.78f+droop)
            close()
        }
        canvas.drawPath(ear, fill); canvas.drawPath(ear, cOutline)

        canvas.drawCircle(ts*.48f, cy-ts*.66f+droop, ts*.075f, cHoof)
        canvas.drawCircle(ts*.462f, cy-ts*.678f+droop, ts*.028f, cEyeW)

        canvas.restore()
    }

    // ── Day/Night overlay ─────────────────────────────────────────────────────
    private fun drawDayNightOverlay(canvas: Canvas) {
        val phase = dayPhase
        val alpha = when {
            phase < 0.1f  -> (100f * (1f - phase / 0.1f)).toInt()
            phase < 0.4f  -> 0
            phase < 0.6f  -> ((phase - 0.4f) / 0.2f * 80f).toInt()
            phase < 0.85f -> 90 + ((phase - 0.6f) / 0.25f * 20f).toInt()
            else          -> (110f * ((1f - phase) / 0.15f)).toInt()
        }
        if (alpha <= 0) return
        val isNightPhase = phase in 0.6f..0.85f
        val r: Int; val g: Int; val b: Int
        if (isNightPhase) { r = 10; g = 10; b = 55 } else { r = 200; g = 80; b = 20 }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(),
            p(Color.argb(alpha.coerceIn(0, 180), r, g, b)))
        if (isNightPhase) {
            val starAlpha = when {
                phase < 0.65f -> ((phase - 0.6f) / 0.05f * 200f).toInt()
                phase > 0.82f -> ((0.85f - phase) / 0.03f * 200f).toInt()
                else -> 200
            }.coerceIn(0, 200)
            drawStars(canvas, starAlpha)
        }
        if (nightOasisActive) drawNightOasis(canvas)
    }

    private fun drawStars(canvas: Canvas, alpha: Int) {
        val sp = p(Color.WHITE).apply { this.alpha = alpha }
        starPositions.forEach { pos ->
            canvas.drawCircle(pos[0] * width, pos[1] * height, 2.5f, sp)
        }
    }

    private fun drawNightOasis(canvas: Canvas) {
        val sx = nightOasisX * tileSize - camX; val sy = nightOasisY * tileSize - camY
        val pulse = ((sin(System.nanoTime() / 400_000_000.0) + 1.0) / 2.0).toFloat()
        val r = tileSize * (0.5f + pulse * 0.2f)
        canvas.drawCircle(sx, sy, r, p(Color.argb((100 + pulse * 80).toInt(), 50, 220, 200)))
        canvas.drawCircle(sx, sy, r * 0.5f, p(Color.argb((150 + pulse * 60).toInt(), 150, 255, 240)))
    }

    // ── Daily surprise ─────────────────────────────────────────────────────────
    private fun drawDailySurprise(canvas: Canvas) {
        if (!surpriseActive) return
        val sx = surpriseX * tileSize - camX; val sy = surpriseY * tileSize - camY
        if (sx < -tileSize*2 || sx > width+tileSize*2 || sy < -tileSize*2 || sy > height+tileSize*2) return
        val pulse = ((sin(System.nanoTime() / 500_000_000.0) + 1.0) / 2.0).toFloat()
        val glowR = tileSize * (0.45f + pulse * 0.15f)
        canvas.drawCircle(sx, sy, glowR, p(Color.argb((60 + pulse * 80).toInt(), 255, 220, 30)))
        val sp = p(Color.rgb(255, 220, 30)).apply {
            textSize = tileSize * 0.5f; textAlign = Paint.Align.CENTER
        }
        canvas.drawText("★", sx, sy + tileSize * 0.18f, sp)
    }

    // ── Float animations ───────────────────────────────────────────────────────
    private fun drawFloatAnims(canvas: Canvas) {
        floatAnims.forEach { a ->
            val progress = 1f - a[2] / a[3]
            val sx = a[0] * tileSize - camX
            val sy = a[1] * tileSize - camY - progress * tileSize * 0.8f
            val alpha = ((1f - progress) * 255).toInt().coerceIn(0, 255)
            val icon = if (a.size > 7 && a[7] == 1f) "★" else "♥"
            val ap = p(Color.argb(alpha, a[4].toInt(), a[5].toInt(), a[6].toInt())).apply {
                textSize = tileSize * 0.35f; textAlign = Paint.Align.CENTER
            }
            canvas.drawText(icon, sx, sy, ap)
        }
    }

    // ── Collect sparkle ────────────────────────────────────────────────────────
    private fun drawCollectSparkle(canvas: Canvas) {
        val sx = camel.x * tileSize - camX; val sy = camel.y * tileSize - camY
        val progress = 1f - collectSparkleTimer / 0.8f
        val alpha = ((1f - progress) * 220).toInt().coerceIn(0, 220)
        val sp = p(Color.argb(alpha, 255, 220, 30), Paint.Style.STROKE).apply { strokeWidth = 3f }
        for (i in 0..7) {
            val angle = i * Math.PI / 4
            val len = progress * tileSize * 0.6f
            canvas.drawLine(sx, sy,
                sx + cos(angle).toFloat() * len,
                sy + sin(angle).toFloat() * len, sp)
        }
    }

    // ── Food items ─────────────────────────────────────────────────────────────
    private fun drawFoodItems(canvas: Canvas) {
        val ts = tileSize
        val pulse = ((sin(System.nanoTime() / 600_000_000.0) + 1.0) / 2.0).toFloat()
        foodItems.forEach { f ->
            val sx = f[0] * ts - camX; val sy = f[1] * ts - camY
            if (sx < -ts || sx > width + ts || sy < -ts || sy > height + ts) return@forEach
            if (f[2] == 1f) {
                // Cactus fruit: red berry near cactus top
                canvas.drawCircle(sx, sy - ts * 0.4f, ts * 0.13f + pulse * 2f,
                    p(Color.argb(70, 220, 60, 60)))
                canvas.drawCircle(sx, sy - ts * 0.4f, ts * 0.10f, p(Color.rgb(210, 55, 55)))
                canvas.drawLine(sx, sy - ts * 0.42f, sx - ts * 0.04f, sy - ts * 0.54f,
                    p(Color.rgb(55, 140, 55), Paint.Style.STROKE).apply { strokeWidth = ts * 0.025f })
            } else {
                // Regular food: golden hay pile with green shoots
                canvas.drawCircle(sx, sy, ts * 0.24f + pulse * 2f,
                    p(Color.argb(50, 220, 170, 60)))
                canvas.drawCircle(sx, sy + ts * 0.04f, ts * 0.18f, p(Color.rgb(200, 150, 50)))
                canvas.drawCircle(sx, sy - ts * 0.04f, ts * 0.12f, p(Color.rgb(220, 170, 70)))
                val sp = p(Color.rgb(75, 155, 55), Paint.Style.STROKE).apply { strokeWidth = ts * 0.03f }
                for (i in -1..1) canvas.drawLine(
                    sx + i * ts * 0.07f, sy - ts * 0.04f,
                    sx + i * ts * 0.05f, sy - ts * 0.22f, sp)
            }
        }
    }

    // ── Stable structure ───────────────────────────────────────────────────────
    private fun drawStableStructure(canvas: Canvas) {
        world.locations.filter { it.type == LocationType.STABLE }.forEach { loc ->
            val cx = loc.tileX * tileSize - camX
            val cy = loc.tileY * tileSize - camY
            if (cx < -tileSize * 6 || cx > width + tileSize * 6) return@forEach
            drawStable(canvas, cx, cy)
        }
    }

    private fun drawStable(canvas: Canvas, cx: Float, cy: Float) {
        val ts = tileSize
        val w = ts * 2.8f; val h = ts * 1.8f
        val left = cx - w / 2f; val top = cy - h * 0.65f; val bot = cy + h * 0.35f

        // Walls — wooden planks
        canvas.drawRect(left, top, left + w, bot, p(Color.rgb(148, 96, 48)))
        val pp = p(Color.rgb(112, 68, 28), Paint.Style.STROKE).apply { strokeWidth = ts * 0.025f }
        var planky = top + ts * 0.3f
        while (planky < bot) { canvas.drawLine(left, planky, left + w, planky, pp); planky += ts * 0.3f }
        for (i in 1..3) canvas.drawLine(left + w * i / 4f, top, left + w * i / 4f, bot, pp)

        // Two stall openings with hay visible inside
        listOf(cx - w * 0.26f, cx + w * 0.26f).forEach { dx ->
            val dw = w * 0.26f; val dh = h * 0.55f
            val dl = dx - dw / 2f; val dt2 = bot - dh
            canvas.drawRect(dl, dt2 + dw / 2f, dl + dw, bot, p(Color.rgb(45, 28, 10)))
            canvas.drawArc(RectF(dl, dt2, dl + dw, dt2 + dw), 180f, 180f, true, p(Color.rgb(45, 28, 10)))
            canvas.drawRect(dl + dw * 0.1f, bot - h * 0.18f, dl + dw * 0.9f, bot,
                p(Color.rgb(215, 175, 65)))
        }

        // Roof
        val roofPath = Path().apply {
            moveTo(left - ts * 0.18f, top); lineTo(cx, top - ts * 1.1f)
            lineTo(left + w + ts * 0.18f, top); close()
        }
        canvas.drawPath(roofPath, p(Color.rgb(165, 78, 42)))
        canvas.drawPath(roofPath, p(Color.rgb(118, 52, 20), Paint.Style.STROKE).apply {
            strokeWidth = ts * 0.04f; strokeJoin = Paint.Join.ROUND
        })

        // "STABLE" sign on fascia
        val sp = p(Color.rgb(255, 228, 175)).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = ts * 0.19f; textAlign = Paint.Align.CENTER
        }
        canvas.drawText("STABLE", cx, top - ts * 0.06f, sp)
    }

    // ── Feed label ─────────────────────────────────────────────────────────────
    private fun drawFeedLabel(canvas: Canvas) {
        val btnW = 130f; val btnH = 64f; val btnX = width - btnW - 16f; val feedY = height - btnH - 16f
        val alpha = ((feedLabelTimer / 1.8f).coerceIn(0f, 1f) * 255).toInt()
        val fp = p(Color.argb(alpha, 255, 210, 140)).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 24f; textAlign = Paint.Align.CENTER
        }
        canvas.drawText(feedLabel, btnX + btnW / 2f, feedY - 14f, fp)
    }

    // ── Mood bubble ────────────────────────────────────────────────────────────
    private fun drawMoodBubble(canvas: Canvas, sx: Float, sy: Float) {
        val icon = camelState.moodIcon()
        val alpha = ((moodBubbleTimer / 3f).coerceIn(0f, 1f) * 220).toInt()
        // timer is decremented in update()
        val mp = p(Color.WHITE).apply {
            textSize = tileSize * 0.42f; textAlign = Paint.Align.CENTER; this.alpha = alpha
        }
        val bgR = tileSize * 0.3f
        val bx = sx + tileSize * 0.5f; val by = sy - tileSize * 1.1f
        canvas.drawCircle(bx, by, bgR, p(Color.argb(alpha / 2, 30, 30, 60)))
        canvas.drawText(icon, bx, by + mp.textSize * 0.36f, mp)
    }

    // ── Micro-event overlay ────────────────────────────────────────────────────
    private fun drawMicroEventOverlay(canvas: Canvas) {
        when (activeMicroEvent) {
            MicroEvent.SANDSTORM -> {
                val a = (sandstormAlpha * 255).toInt().coerceIn(0, 160)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(),
                    p(Color.argb(a, 210, 185, 140)))
                // drift particles
                val rng = java.util.Random(System.currentTimeMillis() / 80)
                val dp = p(Color.argb(a / 2, 230, 200, 150)).apply { strokeWidth = 1.5f; style = Paint.Style.STROKE }
                repeat(30) {
                    val px = (rng.nextFloat() * width)
                    val py = (rng.nextFloat() * height)
                    canvas.drawLine(px, py, px + 8f, py + 3f, dp)
                }
            }
            MicroEvent.SHOOTING_STAR -> {
                val sp = p(Color.WHITE).apply { strokeWidth = 3.5f; style = Paint.Style.STROKE }
                canvas.drawLine(starSX, starSY, starSX - 28f, starSY - 12f, sp)
                canvas.drawCircle(starSX, starSY, 4f, p(Color.WHITE))
            }
            MicroEvent.FOX -> {
                val fsx = foxTileX * tileSize - camX
                val fsy = foxTileY * tileSize - camY
                if (fsx in -tileSize..width + tileSize) {
                    val fp = p(Color.rgb(210, 130, 65))
                    canvas.drawCircle(fsx, fsy, tileSize * 0.22f, fp)
                    canvas.drawCircle(fsx - tileSize * 0.11f, fsy - tileSize * 0.2f, tileSize * 0.08f, fp)
                    canvas.drawCircle(fsx + tileSize * 0.11f, fsy - tileSize * 0.2f, tileSize * 0.08f, fp)
                    canvas.drawCircle(fsx + tileSize * 0.12f, fsy + tileSize * 0.05f, tileSize * 0.06f, p(Color.WHITE))
                    canvas.drawCircle(fsx + tileSize * 0.04f, fsy - tileSize * 0.06f, tileSize * 0.035f, p(Color.rgb(50,30,10)))
                }
            }
            MicroEvent.MIRAGE -> {
                val pulse = ((sin(System.nanoTime() / 300_000_000.0) * 0.5 + 0.5)).toFloat()
                canvas.drawRect(0f, height * 0.65f, width.toFloat(), height.toFloat(),
                    p(Color.argb((pulse * 38).toInt(), 160, 210, 255)))
            }
            MicroEvent.PYRAMID_GLOW -> {
                world.locations.filter { it.type == LocationType.PYRAMID }.forEach { loc ->
                    val px = loc.tileX * tileSize - camX
                    val py = loc.tileY * tileSize - camY
                    val pulse = ((sin(System.nanoTime() / 400_000_000.0) + 1) / 2).toFloat()
                    canvas.drawCircle(px, py, tileSize * (3f + pulse * 1.5f),
                        p(Color.argb((60 + pulse * 60).toInt(), 255, 240, 160)))
                }
            }
            else -> {}
        }
    }

    // ── Groom label ────────────────────────────────────────────────────────────
    private fun drawGroomLabel(canvas: Canvas) {
        val sx = camel.x * tileSize - camX
        val sy = camel.y * tileSize - camY
        val alpha = ((groomLabelTimer / 1.8f).coerceIn(0f, 1f) * 255).toInt()
        val gp = p(Color.argb(alpha, 255, 210, 140)).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = tileSize * 0.32f; textAlign = Paint.Align.CENTER
        }
        canvas.drawText(groomLabel, sx, sy - tileSize * 1.6f, gp)
    }

    // ── Spawn helpers ──────────────────────────────────────────────────────────
    private fun spawnNightOasis() {
        val rng = java.util.Random()
        repeat(50) {
            val tx = 5 + rng.nextInt(70); val ty = 5 + rng.nextInt(70)
            if (world.getTile(tx, ty) == Tile.SAND) {
                nightOasisX = tx.toFloat(); nightOasisY = ty.toFloat()
                nightOasisActive = true; nightOasisTimer = 120f; return
            }
        }
    }

    private fun spawnDailySurprise() {
        val rng = java.util.Random()
        repeat(100) {
            val tx = 10 + rng.nextInt(60); val ty = 10 + rng.nextInt(60)
            if (world.getTile(tx, ty) == Tile.SAND) {
                surpriseX = tx + 0.5f; surpriseY = ty + 0.5f
                surpriseActive = true
                persistence.lastSurpriseDay = persistence.currentDay()
                persistence.save(); return
            }
        }
    }

    // ── Help button ────────────────────────────────────────────────────────────
    private fun helpButtonCenter(): Pair<Float, Float> {
        val ms = 88f; val mx = width - ms - 14f; val my = 14f
        return Pair(mx + ms / 2f, my + ms + 36f + 80f)  // below journal button
    }

    private fun drawHelpButton(canvas: Canvas) {
        val (bx, by) = helpButtonCenter()
        canvas.drawCircle(bx, by, 30f, p(Color.argb(180, 40, 60, 40)))
        canvas.drawCircle(bx, by, 30f, p(Color.argb(80, 160, 220, 140), Paint.Style.STROKE).apply { strokeWidth = 2f })
        val bp = p(Color.rgb(160, 220, 160)).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 26f; textAlign = Paint.Align.CENTER
        }
        canvas.drawText("?", bx, by + 9f, bp)
    }

    private fun drawInstructions(canvas: Canvas) {
        val pw = width * 0.88f; val ph = height * 0.76f
        val px = (width - pw) / 2f; val py = (height - ph) / 2f

        // Background panel
        canvas.drawRoundRect(RectF(px, py, px + pw, py + ph), 20f, 20f,
            p(Color.argb(245, 8, 20, 14)))
        canvas.drawRoundRect(RectF(px, py, px + pw, py + ph), 20f, 20f,
            p(Color.argb(140, 100, 200, 120), Paint.Style.STROKE).apply { strokeWidth = 2.5f })

        val page = instructionPages[instructionPage.coerceIn(0, instructionPages.size - 1)]
        var yy = py + 52f
        val cx = px + pw / 2f

        for ((text, isTitle) in page) {
            if (text.isEmpty()) { yy += 14f; continue }
            val lp = if (isTitle) {
                p(Color.rgb(120, 220, 140)).apply {
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    textSize = 34f; textAlign = Paint.Align.CENTER
                }
            } else {
                p(Color.rgb(210, 235, 215)).apply {
                    typeface = Typeface.MONOSPACE; textSize = 22f; textAlign = Paint.Align.CENTER
                }
            }
            canvas.drawText(text, cx, yy, lp)
            yy += if (isTitle) 42f else 28f
        }

        // Page dots
        val dotY = py + ph - 44f
        for (i in instructionPages.indices) {
            val dc = if (i == instructionPage) Color.rgb(120, 220, 140) else Color.argb(100, 180, 180, 180)
            canvas.drawCircle(cx + (i - 1.5f) * 20f, dotY, if (i == instructionPage) 7f else 5f, p(dc))
        }

        // Button
        val isLast = instructionPage == instructionPages.size - 1
        val btnLabel = if (isLast) "Got it! ★" else "Next  →"
        val btnW = 180f; val btnH = 52f
        val btnX = cx - btnW / 2f; val btnY = py + ph - 28f
        canvas.drawRoundRect(RectF(btnX, btnY, btnX + btnW, btnY + btnH), 14f, 14f,
            p(Color.rgb(40, 120, 60)))
        val nbp = p(Color.WHITE).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 24f; textAlign = Paint.Align.CENTER
        }
        canvas.drawText(btnLabel, cx, btnY + btnH * 0.66f, nbp)
    }

    // ── Journal ────────────────────────────────────────────────────────────────
    private fun drawJournalButton(canvas: Canvas) {
        val ms = 88f; val mx = width - ms - 14f; val my = 14f
        val bx = mx + ms / 2f; val by2 = my + ms + 36f
        canvas.drawCircle(bx, by2, 36f, p(Color.argb(200, 30, 30, 50)))
        canvas.drawCircle(bx, by2, 36f, p(Color.argb(100, 200, 180, 120), Paint.Style.STROKE).apply { strokeWidth = 2f })
        val bp = p(Color.rgb(220, 200, 140)).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 30f; textAlign = Paint.Align.CENTER
        }
        canvas.drawText("≡", bx, by2 + 11f, bp)
    }

    private fun drawJournal(canvas: Canvas) {
        val pw = width * 0.85f; val ph = height * 0.72f
        val px = (width - pw) / 2f; val py = (height - ph) / 2f
        canvas.drawRoundRect(RectF(px, py, px + pw, py + ph), 18f, 18f,
            p(Color.argb(230, 12, 18, 28)))
        canvas.drawRoundRect(RectF(px, py, px + pw, py + ph), 18f, 18f,
            p(Color.argb(120, 200, 180, 120), Paint.Style.STROKE).apply { strokeWidth = 2f })
        val title = p(Color.rgb(220, 200, 140)).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 36f; textAlign = Paint.Align.CENTER
        }
        canvas.drawText("Discovery Log", px + pw / 2f, py + 48f, title)

        // Camel traits + mood line
        val info = p(Color.rgb(200, 180, 120)).apply { typeface = Typeface.MONOSPACE; textSize = 20f }
        val traitLine = camelState.traitSummary().ifBlank { "No traits yet" }
        canvas.drawText("Traits: $traitLine", px + 16f, py + 72f, info)
        canvas.drawText("Mood: ${camelState.mood.name.lowercase().replaceFirstChar { it.uppercase() }} ${camelState.moodIcon()}", px + 16f, py + 94f, info)

        val entry = p(Color.rgb(200, 220, 200)).apply {
            typeface = Typeface.MONOSPACE; textSize = 26f
        }
        var yy = py + 100f
        val sortedLocs = persistence.discoveredLocations.entries.sortedBy { it.value }
        if (sortedLocs.isEmpty()) {
            entry.color = Color.rgb(140, 140, 140)
            canvas.drawText("No locations discovered yet.", px + 20f, yy + 26f, entry)
        } else {
            sortedLocs.forEach { (name, day) ->
                yy += 34f
                if (yy > py + ph - 80f) return@forEach
                entry.color = Color.rgb(200, 220, 200)
                canvas.drawText("✓", px + 20f, yy, entry)
                entry.color = Color.WHITE
                canvas.drawText(name, px + 48f, yy, entry)
                entry.color = Color.rgb(160, 180, 160)
                entry.textSize = 21f
                canvas.drawText("Day $day", px + pw - 20f - entry.measureText("Day $day"), yy, entry)
                entry.textSize = 26f
            }
        }
        val coll = p(Color.rgb(220, 200, 120)).apply {
            typeface = Typeface.MONOSPACE; textSize = 22f
        }
        canvas.drawText("Collectibles: ${persistence.collectiblesCount}   Streak: ${persistence.loginStreak} days", px + 16f, py + ph - 36f, coll)
        canvas.drawText("Level ${camelState.level}  |  ${camelState.unlockedAbilities.size} abilities unlocked", px + 16f, py + ph - 58f, coll)
        val close = p(Color.rgb(160, 160, 160)).apply {
            typeface = Typeface.MONOSPACE; textSize = 22f; textAlign = Paint.Align.CENTER
        }
        canvas.drawText("tap to close", px + pw / 2f, py + ph - 12f, close)
    }

    // ── HUD ────────────────────────────────────────────────────────────────────
    private fun drawHUD(canvas: Canvas) {
        val love = camelState.currentLove()
        val name = camelState.name.takeIf { camelState.isNamed && it.isNotBlank() } ?: "???"
        val pad = 14f; val cardW = 250f; val cardH = 104f
        canvas.drawRoundRect(RectF(pad, pad, pad+cardW, pad+cardH), 14f, 14f, hudBg)

        // Name + mood icon
        val moodIcon = camelState.moodIcon()
        hudText.textSize = 26f; canvas.drawText("$name $moodIcon", pad+12f, pad+28f, hudText)

        // Level label
        hudSmall.textSize = 17f
        canvas.drawText("Lv.${camelState.level}", pad+cardW-48f, pad+28f, hudSmall)

        // Love bar
        val bx=pad+12f; val by=pad+38f; val bw=cardW-24f; val bh=14f
        canvas.drawRoundRect(RectF(bx,by,bx+bw,by+bh),bh/2,bh/2,loveBarTrack)
        val loveFill=bw*(love/CamelState.MAX_LOVE)
        if (loveFill>0f) canvas.drawRoundRect(RectF(bx,by,bx+loveFill,by+bh),bh/2,bh/2,loveBarFill)
        hudSmall.textSize=16f; canvas.drawText("${love.toInt()}%",bx+bw+6f,by+bh-1f,hudSmall)

        // XP bar (green)
        val xby=by+bh+8f; val xbh=10f
        val xpBarBg = p(Color.argb(80, 255, 255, 255))
        val xpBarFill = p(Color.rgb(80, 200, 120))
        canvas.drawRoundRect(RectF(bx,xby,bx+bw,xby+xbh),xbh/2,xbh/2,xpBarBg)
        val xpRatio = camelState.xp.toFloat() / camelState.xpForNextLevel().toFloat()
        val xpFill = bw * xpRatio.coerceIn(0f, 1f)
        if (xpFill > 0f) canvas.drawRoundRect(RectF(bx,xby,bx+xpFill,xby+xbh),xbh/2,xbh/2,xpBarFill)
        hudSmall.textSize=14f; canvas.drawText("XP",bx+bw+6f,xby+xbh-1f,hudSmall)

        // Level-up flash
        if (levelUpTimer > 0f) {
            val a = ((levelUpTimer / 2.5f) * 220).toInt().coerceIn(0, 220)
            val lp = p(Color.argb(a, 120, 220, 255)).apply {
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                textSize = 28f; textAlign = Paint.Align.CENTER
            }
            canvas.drawText("LEVEL UP! Lv.${camelState.level}", pad + cardW / 2f, pad + cardH + 30f, lp)
        }

        // Groom count dots
        val day = persistence.currentDay()
        val groomsLeft = 3 - (if (camelState.lastGroomDay == day) camelState.groomsToday else 0)
        for (i in 0 until 3) {
            val dotColor = if (i < groomsLeft) Color.rgb(255, 200, 100) else Color.argb(80, 200, 200, 200)
            canvas.drawCircle(bx + i * 14f + 6f, xby + xbh + 10f, 5f, p(dotColor))
        }
        val gp = p(Color.argb(160, 200, 200, 200)).apply { typeface = Typeface.MONOSPACE; textSize = 13f }
        canvas.drawText("grooms", bx + 50f, xby + xbh + 14f, gp)

        val btnW=130f; val btnH=64f; val btnX=width-btnW-16f; val btnY=height-btnH-16f
        canvas.drawRoundRect(RectF(btnX,btnY,btnX+btnW,btnY+btnH),14f,14f,feedBtn)
        dpadText.textSize=30f; canvas.drawText("FEED",btnX+btnW/2f,btnY+btnH*.66f,dpadText)
        // Feed count dots
        val currentFeedDay = System.currentTimeMillis() / 86_400_000L
        val feedsLeft = if (camelState.lastFeedDay == currentFeedDay) 3 - camelState.feedsToday else 3
        for (i in 0 until 3) {
            val dotC = if (i < feedsLeft) Color.rgb(80, 200, 120) else Color.argb(80, 200, 200, 200)
            canvas.drawCircle(btnX + btnW / 2f - 16f + i * 16f, btnY - 10f, 5.5f, p(dotC))
        }
        drawMinimap(canvas)
        drawJournalButton(canvas)
        drawHelpButton(canvas)
    }

    private fun drawMinimap(canvas: Canvas) {
        val bmp = minimapBmp ?: return
        val ms=88f; val mx=width-ms-14f; val my=14f
        canvas.drawRoundRect(RectF(mx-2f,my-2f,mx+ms+2f,my+ms+2f),4f,4f,p(Color.argb(200,0,0,0)))
        canvas.drawBitmap(bmp,Rect(0,0,world.width,world.height),RectF(mx,my,mx+ms,my+ms),null)
        canvas.drawCircle(mx+(camel.x/world.width)*ms, my+(camel.y/world.height)*ms, 3.5f, whiteFill)
        wanderCamels.forEach { wc ->
            canvas.drawCircle(mx+(wc.x/world.width)*ms, my+(wc.y/world.height)*ms, 2.5f,
                p(Color.rgb(200,160,80)))
        }
        // Pulse for surprise location
        if (surpriseActive) {
            val pulse = ((sin(System.nanoTime() / 600_000_000.0) + 1.0) / 2.0 * 80 + 40).toInt()
            canvas.drawCircle(
                mx + (surpriseX / world.width) * ms,
                my + (surpriseY / world.height) * ms,
                5f, p(Color.argb(pulse, 255, 220, 30)))
        }
    }

    private fun buildMinimap() {
        val bmp = Bitmap.createBitmap(world.width, world.height, Bitmap.Config.ARGB_8888)
        val px = IntArray(world.width * world.height)
        for (y in 0 until world.height) for (x in 0 until world.width)
            px[y*world.width+x] = when(world.getTile(x,y)) {
                Tile.WATER              -> Color.rgb(58,96,140)
                Tile.GRASS              -> Color.rgb(108,148,88)
                Tile.PALM               -> Color.rgb(40,90,40)
                Tile.STONE_PATH         -> Color.rgb(160,152,136)
                Tile.BUILDING           -> Color.rgb(130,80,60)
                Tile.BUILDING_FRONT     -> Color.rgb(200,175,130)
                Tile.PYRAMID, Tile.PYRAMID_STEPS -> Color.rgb(172,160,128)
                Tile.CACTUS             -> Color.rgb(72,112,60)
                Tile.DEEP_SAND          -> Color.rgb(200,190,152)
                Tile.DUNE               -> Color.rgb(212,202,164)
                else                    -> Color.rgb(224,214,176)
            }
        bmp.setPixels(px,0,world.width,0,0,world.width,world.height)
        minimapBmp = bmp
    }

    // ── Location banner ────────────────────────────────────────────────────────
    private fun drawLocationBanner(canvas: Canvas) {
        val alpha = ((locationLabelTimer/3.5f)*255).toInt().coerceIn(0,255)
        locText.alpha = alpha
        val tw = locText.measureText(locationLabel); val cx=width/2f; val by=height*.14f
        canvas.drawRoundRect(RectF(cx-tw/2-22f,by-34f,cx+tw/2+22f,by+12f),12f,12f,p(Color.argb(alpha*2/3,0,0,0)))
        canvas.drawText(locationLabel, cx, by, locText); locText.alpha = 255
    }

    // ── D-pad ──────────────────────────────────────────────────────────────────
    private fun drawDpad(canvas: Canvas) {
        val cx=dpadCX; val cy=dpadCY; val r=dpadR; val btnR=r*.56f
        canvas.drawCircle(cx,cy,btnR*.55f,dpadBg)
        val dirs=listOf(0f to -r,0f to r,-r to 0f,r to 0f)
        val labels=listOf("▲","▼","◀","▶"); val active=listOf(dpadUp,dpadDown,dpadLeft,dpadRight)
        dirs.forEachIndexed { i,(dx,dy) ->
            val bx=cx+dx; val by2=cy+dy
            canvas.drawCircle(bx,by2,btnR,if(active[i])dpadActive else dpadBg)
            dpadText.textSize=btnR*.82f; canvas.drawText(labels[i],bx,by2+dpadText.textSize*.36f,dpadText)
        }
    }

    // ── Input ──────────────────────────────────────────────────────────────────
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tx = event.x; val ty = event.y

        val btnW=130f; val btnH=64f; val feedX=width-btnW-16f; val feedY=height-btnH-16f

        // Journal open/close — only on finger-lift so open and close don't collapse into one tap
        val ms = 88f; val mx = width - ms - 14f; val my = 14f
        val jbx = mx + ms / 2f; val jby = my + ms + 36f
        if (event.action == MotionEvent.ACTION_UP) {
            // Instructions overlay — advance page or close
            if (showInstructions) {
                if (instructionPage < instructionPages.size - 1) {
                    instructionPage++
                } else {
                    showInstructions = false
                    persistence.seenInstructions = true
                    persistence.save()
                }
                return true
            }

            if (showJournal) { showJournal = false; return true }

            // ? help button
            val (hbx, hby) = helpButtonCenter()
            val hdx = tx - hbx; val hdy = ty - hby
            if (sqrt(hdx * hdx + hdy * hdy) < 38f) {
                showInstructions = true; instructionPage = 0; return true
            }

            val ddx = tx - jbx; val ddy = ty - jby
            if (sqrt(ddx * ddx + ddy * ddy) < 44f) { showJournal = true; return true }
            // Grooming: tap near camel on screen
            val camelSX = camel.x * tileSize - camX
            val camelSY = camel.y * tileSize - camY
            val cdx = tx - camelSX; val cdy = ty - camelSY
            if (sqrt(cdx * cdx + cdy * cdy) < tileSize * 1.4f && !playerPlaying) {
                handleGroom(); return true
            }
        }
        if (showInstructions) return true  // swallow all events while instructions open
        if (showJournal) return true  // swallow move/down events while journal is open

        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (tx>=feedX && tx<=feedX+btnW && ty>=feedY && ty<=feedY+btnH) { handleFeed(); return true }
                updateDpad(tx,ty)
                if (inputDx!=0f || inputDy!=0f) {
                    lastInputMs=System.currentTimeMillis(); camel.autoWandering=false; playerPlaying=false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> clearDpad()
        }
        return true
    }

    private fun updateDpad(tx: Float, ty: Float) {
        val dx=tx-dpadCX; val dy=ty-dpadCY; val dist=sqrt(dx*dx+dy*dy)
        if (dist>dpadR*2.2f) { clearDpad(); return }
        dpadUp=false; dpadDown=false; dpadLeft=false; dpadRight=false; inputDx=0f; inputDy=0f
        if (dist<dpadR*.22f) return
        if (abs(dx)>=abs(dy)) { if(dx>0){dpadRight=true;inputDx=1f} else{dpadLeft=true;inputDx=-1f} }
        else { if(dy>0){dpadDown=true;inputDy=1f} else{dpadUp=true;inputDy=-1f} }
    }

    private fun clearDpad() { inputDx=0f; inputDy=0f; dpadUp=false; dpadDown=false; dpadLeft=false; dpadRight=false }

    private fun handleFeed() {
        val day = System.currentTimeMillis() / 86_400_000L
        val state = if (camelState.lastFeedDay != day)
            camelState.copy(feedsToday = 0, lastFeedDay = day) else camelState
        if (state.feedsToday >= 3) {
            feedLabel = "No food left today!"; feedLabelTimer = 1.8f; camelState = state; return
        }
        camelState = state.copy(
            loveAtLastInteraction = (state.currentLove() + CamelState.FEED_BOOST).coerceAtMost(CamelState.MAX_LOVE),
            lastInteractionTime = System.currentTimeMillis(),
            feedsToday = state.feedsToday + 1,
            lastFeedDay = day
        )
        stateManager.save(camelState); lastInputMs = System.currentTimeMillis()
        feedLabel = "Fed! (+${CamelState.FEED_BOOST.toInt()}♥)"; feedLabelTimer = 1.8f
    }

    fun onResume() { camelState = stateManager.load(); checkLoginStreak(); music.resume() }
    fun onPause()  { stateManager.save(camelState); music.pause() }
}
