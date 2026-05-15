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
    private val camel = CamelEntity(80f, 88f)   // open sand south of Heart of the Sahara

    // ── Wandering NPC camels ───────────────────────────────────────────────────
    private val wanderCamels = listOf(
        WanderCamel( 32f,  28f, "Kesi"),
        WanderCamel( 98f,  24f, "Farouk"),
        WanderCamel(130f,  56f, "Nadia"),
        WanderCamel( 48f, 132f, "Beni")
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

    // ── 3-D renderer ──────────────────────────────────────────────────────────
    private val renderer = Renderer3D()

    // ── Camera (2-D legacy — minimap only) ────────────────────────────────────
    private var tileSize = 64f
    private var camX = 0f; private var camY = 0f

    // ── Game loop ──────────────────────────────────────────────────────────────
    private var gameThread: Thread? = null
    @Volatile private var running = false

    // ── Input ──────────────────────────────────────────────────────────────────
    @Volatile private var inputDx = 0f
    @Volatile private var inputDy = 0f
    private var lastInputMs = System.currentTimeMillis()
    private val autoWanderAfterMs = 15_000L

    // ── Camera orbit touch ────────────────────────────────────────────────────
    private var dpadPointerId = -1
    private var camPointerId  = -1
    private var camLastX = 0f; private var camLastY = 0f

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
            "Welcome to the Sahara!" to false,
            "Care for your camel and" to false,
            "explore the desert together." to false,
            "" to false,
            "♥  Fill the love bar daily" to false,
            "★  Earn XP to level up" to false,
            "~  Bond with wild camels" to false,
            "z  Rest in the stable" to false,
            "" to false,
            "Tap ≡ to view your Journal," to false,
            "streak & unlocked abilities." to false),
        arrayOf("DAILY CARE" to true, "" to false,
            "D-PAD  →  Move around" to false,
            "FEED   →  +15♥  (3x/day)" to false,
            "GROOM  →  Tap your camel" to false,
            "         3x/day, +♥ & XP" to false,
            "" to false,
            "15s idle → auto-wander" to false,
            "Love decays over 6 hours." to false,
            "" to false,
            "STABLE = love won't decay" to false,
            "while your camel rests!" to false),
        arrayOf("THE WORLD" to true, "" to false,
            "13 locations to discover:" to false,
            "" to false,
            "OASIS    +5♥ on arrival" to false,
            "VILLAGE  NPCs give care" to false,
            "PYRAMID  Ancient wonders" to false,
            "STABLE   No love decay" to false,
            "" to false,
            "10-min day & night cycle." to false,
            "NPCs sleep after dark." to false,
            "Night bonus oasis spawns!" to false),
        arrayOf("FOOD & BONDING" to true, "" to false,
            "Food spawns on the map:" to false,
            "Regular   +5♥   +3XP" to false,
            "Cactus    +5♥   +3XP" to false,
            "Relic ★   +15♥  +15XP" to false,
            "" to false,
            "Wild camels: play 3 times" to false,
            "→ they follow you 1 min!" to false,
            "" to false,
            "Each camel has a TRAIT" to false,
            "& MOOD — MOODY halves feed!" to false),
        arrayOf("LEVEL ABILITIES" to true, "" to false,
            "Lv  3  Dash" to false,
            "  Hold move → sprint 2s!" to false,
            "Lv  5  TreasureSense" to false,
            "  Food glows on minimap" to false,
            "Lv  7  Sit" to false,
            "  Idle 8s to slowly heal ♥" to false,
            "Lv 10  SandGlide" to false,
            "  No slowdown on dunes!" to false),
        arrayOf("MORE TO DISCOVER" to true, "" to false,
            "Lv 15  Dance" to false,
            "  Longer NPC play sessions" to false,
            "Lv 20  RelicSense" to false,
            "  Golden relics by pyramids" to false,
            "  (+15♥ and +15XP each!)" to false,
            "" to false,
            "Watch for rare events:" to false,
            "sandstorms, shooting stars," to false,
            "foxes, mirages & more!" to false)
    )

    // ── HUD / UI state ─────────────────────────────────────────────────────────
    private var locationLabel = ""
    @Volatile private var locationLabelTimer = 0f
    private var currentLocation: WorldLocation? = null
    private var camelFacingLeft = false
    private var dpadUp = false; private var dpadDown = false
    private var dpadLeft = false; private var dpadRight = false
    private var dpadCX = 0f; private var dpadCY = 0f; private var dpadR = 0f
    private var joyKnobX = 0f; private var joyKnobY = 0f

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

    // ── Star directions — fixed world-space unit vectors on the upper hemisphere ─
    // Each floatArray is (dx, dy, dz) in world-space (X=East, Y=South, Z=Up).
    // Projection through camera basis makes them stay in the same sky spot as you orbit.
    private val starDirs: Array<FloatArray> by lazy {
        val r = java.util.Random(42)
        Array(90) {
            val theta = r.nextFloat() * 2f * PI.toFloat()          // azimuth (all directions)
            val elev  = r.nextFloat().pow(0.6f) * PI.toFloat() * 0.48f  // 0-86° elevation (biased away from zenith)
            val cosEl = cos(elev); val sinEl = sin(elev)
            floatArrayOf(cosEl * cos(theta), cosEl * sin(theta), sinEl)
        }
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

    // ── Ability system ─────────────────────────────────────────────────────────
    // Dash (Lv 3)
    private var dashHoldTimer = 0f
    private var dashActive    = false
    private var dashTimer     = 0f
    private var dashCooldown  = 0f
    // Sit (Lv 7)
    private var sitting      = false
    private var sitIdleTimer = 0f
    private var sitLoveAccum = 0f
    // RelicSense (Lv 20)
    private var relicSpawnTimer = 90f

    // ── XP / level-up ─────────────────────────────────────────────────────────
    private var xpMoveAccum = 0f
    private var levelUpTimer = 0f


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
        renderer.updateSize(w.toFloat(), h.toFloat())
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
        updateAbilities(dt)

        // Auto wander trigger
        if (System.currentTimeMillis() - lastInputMs > autoWanderAfterMs && !camel.autoWandering && !playerPlaying && !inStable)
            camel.startAutoWander(world)
        if (inStable && camel.autoWandering) camel.autoWandering = false

        // Player play state
        if (playerPlaying) {
            playerPlayTimer -= dt
            if (playerPlayTimer <= 0f) {
                playerPlaying = false
                lastInputMs = System.currentTimeMillis() - autoWanderAfterMs
                camel.x = playerPlayCX; camel.y = playerPlayCY
                snapCamelToWalkable()
            } else {
                playerPlayAngle -= dt * 2.8f
                val dynR = playRadius * (0.65f + 0.50f * abs(sin(playerPlayAngle * 1.5f)).toFloat())
                camel.x = playerPlayCX + cos(playerPlayAngle).toFloat() * dynR
                camel.y = playerPlayCY + sin(playerPlayAngle).toFloat() * dynR
                camelFacingLeft = cos(playerPlayAngle) < 0f
                camel.isMoving = true; camel.walkPhase += dt * 5f
            }
        } else {
            camel.update(dt, inputDx, inputDy, world)
            if (camel.facingX != 0f) camelFacingLeft = camel.facingX < 0f
        }

        // Update NPC camels (pass player coords)
        wanderCamels.forEach { it.update(dt, world, camel.x, camel.y) }

        // Camel approach: when auto-wandering within 5 tiles, walk toward each other.
        // Each wander camel gets a 30 s personal cooldown after triggering so it can
        // freely walk away — but OTHER wander camels with no cooldown can still attract
        // the player camel during that window.
        if (camel.autoWandering && !playerPlaying) {
            for (wc in wanderCamels) {
                if (wc.state == WanderCamel.State.PLAYING || wc.followTimer > 0f) continue
                if (wc.approachCooldown > 0f) continue   // this camel is on its break
                val adx = wc.x - camel.x; val ady = wc.y - camel.y
                val ad = sqrt(adx * adx + ady * ady)
                if (ad in 2.5f..5.0f) {
                    camel.redirectWanderTarget(wc.x, wc.y)
                    wc.redirectWanderTarget(camel.x, camel.y)
                    wc.approachCooldown = 30f   // give this camel its 30 s break
                    break
                }
            }
        }

        // Play proximity (cooldown per WanderCamel prevents re-trigger for 30 s)
        if (camel.autoWandering && !playerPlaying) {
            for (wc in wanderCamels) {
                if (wc.state == WanderCamel.State.PLAYING || wc.playCooldown > 0f ||
                    wc.followTimer > 0f || wc.postFollowCooldown > 0f) continue
                val dx = wc.x - camel.x; val dy = wc.y - camel.y
                if (sqrt(dx * dx + dy * dy) < 2.0f) {
                    val cx = (camel.x + wc.x) / 2f; val cy = (camel.y + wc.y) / 2f
                    wc.startPlaying(cx, cy)
                    playerPlaying = true
                    playerPlayTimer = if ("Dance" in camelState.unlockedAbilities) 10f else playDuration
                    playerPlayCX = cx; playerPlayCY = cy
                    playerPlayAngle = atan2(camel.y - cy, camel.x - cx)
                    camel.autoWandering = false
                    lastInputMs = System.currentTimeMillis()
                    // Camel bond tracking
                    val newBond = persistence.addCamelBond(wanderCamels.indexOf(wc))
                    wc.bondCount = newBond
                    if (newBond >= 3) { wc.followTimer = 60f; wc.postFollowCooldown = 0f }
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
        renderer.updateCamera(camel.x, camel.y)
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

    // ── Ability system ─────────────────────────────────────────────────────────
    private fun updateAbilities(dt: Float) {
        val ab = camelState.unlockedAbilities

        // Dash: hold d-pad for 0.5s to burst at 2× speed for 2s (6s cooldown)
        if ("Dash" in ab && (inputDx != 0f || inputDy != 0f)) {
            dashHoldTimer += dt
            if (dashHoldTimer >= 0.5f && dashCooldown <= 0f && !dashActive) {
                dashActive = true; dashTimer = 2f
                floatAnims.add(floatArrayOf(camel.x, camel.y, 0.4f, 0.4f, 220f, 200f, 150f, 0f))
            }
        } else {
            dashHoldTimer = 0f
        }
        if (dashActive) { dashTimer -= dt; if (dashTimer <= 0f) { dashActive = false; dashCooldown = 6f } }
        if (dashCooldown > 0f) dashCooldown -= dt

        // SandGlide: deep-sand penalty removed; combined with Dash into speedBoost
        val onDeepSand = world.getTile(camel.x.toInt(), camel.y.toInt()) == Tile.DEEP_SAND
        var boost = if (dashActive) 2.0f else 1.0f
        if (onDeepSand && "SandGlide" !in ab) boost *= 0.7f
        camel.speedBoost = boost

        // Sit: auto-sit after 8s of complete stillness; +1♥ every 10s while sitting
        val playerStill = inputDx == 0f && inputDy == 0f && !camel.autoWandering && !playerPlaying
        if ("Sit" in ab && playerStill) {
            sitIdleTimer += dt
            if (sitIdleTimer >= 8f && !sitting) {
                sitting = true
                groomLabel = "Resting~"; groomLabelTimer = 2f
            }
        } else {
            sitIdleTimer = 0f
            if (!playerStill) sitting = false
        }
        if (sitting) {
            sitLoveAccum += dt
            if (sitLoveAccum >= 10f) {
                sitLoveAccum = 0f
                camelState = camelState.copy(
                    loveAtLastInteraction = (camelState.currentLove() + 1f).coerceAtMost(CamelState.MAX_LOVE),
                    lastInteractionTime = System.currentTimeMillis()
                )
            }
        }

        // RelicSense: spawn a glowing relic near pyramids every ~90–150s
        if ("RelicSense" in ab) {
            relicSpawnTimer -= dt
            if (relicSpawnTimer <= 0f) {
                trySpawnRelic()
                relicSpawnTimer = 90f + (Math.random() * 60).toFloat()
            }
        }
    }

    private fun trySpawnRelic() {
        if (foodItems.count { it[2] == 2f } >= 2) return
        val pyramids = world.locations.filter { it.type == LocationType.PYRAMID }
        if (pyramids.isEmpty()) return
        val pyramid = pyramids.random()
        val rng = java.util.Random()
        repeat(50) {
            val angle = Math.random() * Math.PI * 2
            val dist = 3f + rng.nextFloat() * 4f
            val tx = pyramid.tileX + (cos(angle) * dist).toFloat()
            val ty = pyramid.tileY + (sin(angle) * dist).toFloat()
            if (world.isWalkable(tx.toInt(), ty.toInt()) &&
                foodItems.none { abs(it[0] - tx) < 3 && abs(it[1] - ty) < 3 }) {
                foodItems.add(floatArrayOf(tx, ty, 2f))
                return
            }
        }
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
            val isRelic = f[2] == 2f
            val radius = if (isRelic || "Curious" in camelState.unlockedAbilities.map { it.lowercase() }) 1.6f else 1.2f
            val dx = camel.x - f[0]; val dy = camel.y - f[1]
            if (sqrt(dx * dx + dy * dy) < radius) {
                iter.remove()
                val loveGain = if (isRelic) 15f else 5f
                camelState = camelState.copy(
                    loveAtLastInteraction = (camelState.currentLove() + loveGain).coerceAtMost(CamelState.MAX_LOVE),
                    lastInteractionTime = System.currentTimeMillis()
                )
                stateManager.save(camelState)
                val dur = if (isRelic) 1.8f else 1.2f
                floatAnims.add(floatArrayOf(camel.x, camel.y - 0.5f, dur, dur,
                    if (isRelic) 255f else 180f, if (isRelic) 215f else 230f,
                    if (isRelic) 50f else 100f, if (isRelic) 1f else 0f))
                gainXP(if (isRelic) 15 else 3)
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
            val tx = 5 + rng.nextInt(world.width - 10); val ty = 5 + rng.nextInt(world.height - 10)
            if (world.getTile(tx, ty) == Tile.SAND &&
                foodItems.none { kotlin.math.abs(it[0] - tx) < 3 && kotlin.math.abs(it[1] - ty) < 3 }) {
                foodItems.add(floatArrayOf(tx + 0.5f, ty + 0.5f, 0f))
                return
            }
        }
    }

    // ── Render ─────────────────────────────────────────────────────────────────
    private fun renderFrame(canvas: Canvas) {
        drawSky(canvas)
        drawWorld3D(canvas)   // now includes tiles + food + NPCs + camels, depth-sorted
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

    // ── Sky gradient ───────────────────────────────────────────────────────────
    private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun skyLerp(c1: Int, c2: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(c1)   + (Color.red(c2)   - Color.red(c1))   * f).toInt(),
            (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * f).toInt(),
            (Color.blue(c1)  + (Color.blue(c2)  - Color.blue(c1))  * f).toInt()
        )
    }

    private fun drawSky(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val ph = dayPhase
        val skyH = h * 0.42f

        // Compute sky gradient colors based on time of day
        val dayTop  = Color.rgb( 68, 168, 235); val dayMid  = Color.rgb(140, 210, 245); val dayHoriz = Color.rgb(220, 200, 155)
        val ngtTop  = Color.rgb(  8,  12,  55); val ngtMid  = Color.rgb( 15,  25,  80); val ngtHoriz = Color.rgb( 25,  40, 100)
        val dskTop  = Color.rgb( 32,  65, 160); val dskMid  = Color.rgb(200, 120,  60); val dskHoriz = Color.rgb(255, 160,  80)
        val (topC, midC, horizC) = when {
            ph < 0.05f -> { val t = ph / 0.05f
                Triple(skyLerp(ngtTop, dayTop, t * 0.3f), skyLerp(ngtMid, dskMid, t), skyLerp(ngtHoriz, dskHoriz, t)) }
            ph < 0.14f -> { val t = (ph - 0.05f) / 0.09f
                Triple(skyLerp(skyLerp(ngtTop, dayTop, 0.3f), dayTop, t),
                       skyLerp(dskMid, dayMid, t), skyLerp(dskHoriz, dayHoriz, t)) }
            ph < 0.50f -> Triple(dayTop, dayMid, dayHoriz)
            ph < 0.62f -> { val t = (ph - 0.50f) / 0.12f
                Triple(skyLerp(dayTop, dskTop, t), skyLerp(dayMid, dskMid, t), skyLerp(dayHoriz, dskHoriz, t)) }
            ph < 0.68f -> { val t = (ph - 0.62f) / 0.06f
                Triple(skyLerp(dskTop, ngtTop, t), skyLerp(dskMid, ngtMid, t), skyLerp(dskHoriz, ngtHoriz, t)) }
            ph < 0.87f -> Triple(ngtTop, ngtMid, ngtHoriz)
            else -> { val t = (ph - 0.87f) / 0.13f
                Triple(ngtTop, skyLerp(ngtMid, dskMid, t), skyLerp(ngtHoriz, dskHoriz, t)) }
        }
        val shader = android.graphics.LinearGradient(
            0f, 0f, 0f, skyH,
            intArrayOf(topC, midC, horizC), floatArrayOf(0f, 0.6f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        skyPaint.shader = shader
        canvas.drawRect(0f, 0f, w, h, skyPaint)
        skyPaint.shader = null

        // Stars — project fixed world-space directions through camera basis so they
        // stay locked to the sky and scroll naturally when the camera orbits.
        val starAlpha = when {
            ph < 0.05f  -> 200
            ph < 0.14f  -> (200f * (1f - (ph - 0.05f) / 0.09f)).toInt()
            ph < 0.62f  -> 0
            ph < 0.68f  -> ((ph - 0.62f) / 0.06f * 200f).toInt()
            ph < 0.87f  -> 200
            ph < 0.96f  -> (200f * (1f - (ph - 0.87f) / 0.09f)).toInt()
            else        -> 0
        }.coerceIn(0, 200)
        if (starAlpha > 0) drawStars(canvas, starAlpha)

        // Sun — world-space direction: rises east (+X), arcs up, sets west (-X).
        // ph 0.06–0.58. projectDir returns null when camera faces away.
        if (ph in 0.06f..0.58f) {
            val t = (ph - 0.06f) / 0.52f
            val sunDX = cos(t * PI.toFloat())
            val sunDY = 0f
            val sunDZ = (sin(t * PI.toFloat()) * 0.88f + 0.06f).coerceAtLeast(0f)
            val sp = projectDir(sunDX, sunDY, sunDZ)
            if (sp != null) {
                val elevAlpha = (sunDZ / 0.14f * 255f).toInt().coerceIn(0, 255)
                val sx = sp[0]; val sy = sp[1]
                canvas.drawCircle(sx, sy, 36f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(elevAlpha / 6, 255, 230, 80) })
                canvas.drawCircle(sx, sy, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(elevAlpha / 3, 255, 235, 100) })
                canvas.drawCircle(sx, sy, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(elevAlpha, 255, 248, 150) })
                canvas.drawCircle(sx, sy,  9f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(elevAlpha, 255, 255, 220) })
            }
        }

        // Moon — same east-to-west arc but at night (ph 0.63–0.97, wraps at 0–0.03).
        // Slight southward offset (−Y) separates its path from the sun's.
        val moonT: Float? = when {
            ph in 0.63f..0.97f -> (ph - 0.63f) / 0.34f
            ph < 0.03f         -> (ph + 0.37f) / 0.34f
            else               -> null
        }
        if (moonT != null) {
            val t = moonT.coerceIn(0f, 1f)
            val moonDX = cos(t * PI.toFloat())
            val moonDY = -0.18f      // slight south offset to vary from sun path
            val moonDZ = (sin(t * PI.toFloat()) * 0.78f + 0.06f).coerceAtLeast(0f)
            val mp = projectDir(moonDX, moonDY, moonDZ)
            if (mp != null) {
                val elevAlpha = (moonDZ / 0.14f * 230f).toInt().coerceIn(0, 230)
                val mx = mp[0]; val my = mp[1]
                canvas.drawCircle(mx, my, 15f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(elevAlpha / 4, 200, 210, 240) })
                canvas.drawCircle(mx, my, 13f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(elevAlpha, 225, 225, 235) })
                canvas.drawCircle(mx + 6f, my - 2f, 11f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(elevAlpha, 8, 12, 55) })
            }
        }
    }

    // ── 3-D world (tiles + structures) ────────────────────────────────────────

    // Cartoon desert palette — top face, then south-face shade factor
    private val tileTop3D = mapOf(
        Tile.SAND          to Color.rgb(238, 204, 128),
        Tile.DEEP_SAND     to Color.rgb(215, 178, 100),
        Tile.DUNE          to Color.rgb(242, 196, 108),
        Tile.WATER         to Color.rgb(72, 196, 218),
        Tile.GRASS         to Color.rgb(98, 180, 78),
        Tile.STONE_PATH    to Color.rgb(188, 172, 145),
        Tile.PYRAMID       to Color.rgb(228, 198, 138),
        Tile.PYRAMID_STEPS to Color.rgb(212, 184, 122),
        Tile.PALM          to Color.rgb(238, 204, 128),
        Tile.CACTUS        to Color.rgb(238, 204, 128),
        Tile.BUILDING      to Color.rgb(215, 188, 145),  // sandy tan
        Tile.BUILDING_FRONT to Color.rgb(205, 178, 135)  // matching sandy wall
    )

    private fun getTileHeight(tile: Int, tx: Int, ty: Int): Float = when (tile) {
        Tile.WATER          -> 0.0f
        Tile.DUNE           -> 0.38f
        Tile.GRASS          -> 0.14f
        Tile.STONE_PATH     -> 0.06f
        Tile.BUILDING,
        Tile.BUILDING_FRONT -> {
            // ~1-in-11 building tiles are tall minaret towers
            val isTower = ((tx * 7 + ty * 13 + 3) % 11 == 0)
            if (isTower) 4.2f else 2.3f
        }
        Tile.PYRAMID, Tile.PYRAMID_STEPS -> 0.0f  // drawn as true triangular shape
        Tile.PALM           -> 0.0f
        Tile.CACTUS         -> 0.0f
        else                -> 0.0f
    }

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ── 3-D axis-aligned box renderer ─────────────────────────────────────────
    private fun drawBox3D(canvas: Canvas,
                          x0: Float, y0: Float, z0: Float,
                          x1: Float, y1: Float, z1: Float,
                          color: Int) {
        val rdr = renderer
        val cx = (x0 + x1) * 0.5f; val cy = (y0 + y1) * 0.5f
        val b00 = rdr.project(x0, y0, z0); val b10 = rdr.project(x1, y0, z0)
        val b11 = rdr.project(x1, y1, z0); val b01 = rdr.project(x0, y1, z0)
        val t00 = rdr.project(x0, y0, z1); val t10 = rdr.project(x1, y0, z1)
        val t11 = rdr.project(x1, y1, z1); val t01 = rdr.project(x0, y1, z1)
        val p = facePaint
        // South face (normal +Y) — visible when camera is south of box centre
        if (rdr.camY > cy && b01!=null && b11!=null && t11!=null && t01!=null) {
            p.color = Renderer3D.shade(color, 0.62f)
            rdr.quad(canvas, b01, b11, t11, t01, p)
        }
        // North face (normal -Y)
        if (rdr.camY < cy && b10!=null && b00!=null && t00!=null && t10!=null) {
            p.color = Renderer3D.shade(color, 0.88f)
            rdr.quad(canvas, b10, b00, t00, t10, p)
        }
        // East face (normal +X)
        if (rdr.camX > cx && b10!=null && b11!=null && t11!=null && t10!=null) {
            p.color = Renderer3D.shade(color, 0.76f)
            rdr.quad(canvas, b10, b11, t11, t10, p)
        }
        // West face (normal -X)
        if (rdr.camX < cx && b00!=null && b01!=null && t01!=null && t00!=null) {
            p.color = Renderer3D.shade(color, 0.70f)
            rdr.quad(canvas, b01, b00, t00, t01, p)
        }
        // Top face — always on top
        if (t00!=null && t10!=null && t11!=null && t01!=null) {
            p.color = color
            rdr.quad(canvas, t00, t10, t11, t01, p)
        }
    }

    private fun drawWorld3D(canvas: Canvas) {
        val rdr = renderer
        if (rdr.screenW < 1f) return

        val jobs = ArrayList<Pair<Float, () -> Unit>>(450)

        // --- TILES ---
        for (ty in 0 until world.height) {
            for (tx in 0 until world.width) {
                val centerDepth = rdr.depth(tx + 0.5f, ty + 0.5f, 0f)
                if (centerDepth < 0.1f) continue
                val proj = rdr.project(tx + 0.5f, ty + 0.5f, 0f) ?: continue
                if (proj[0] < -300 || proj[0] > width + 300) continue
                if (proj[1] < -500 || proj[1] > height + 300) continue
                val tile = world.getTile(tx, ty)
                val h = getTileHeight(tile, tx, ty)
                // Ground-level tiles (h < 1.0) must never occlude entities.
                // Bias them far behind in the sort so they always draw first.
                // Covers: SAND, WATER, GRASS (0.14), STONE_PATH (0.06), DUNE (0.38), PALM, CACTUS,
                // and outer PYRAMID_STEPS rings (0.7). Only BUILDING (2.3+) and PYRAMID (4.0) sort normally.
                val sortDepth = rdr.depth(tx + 0.5f, ty + 1f, 0f) + if (h < 1.0f) +10000f else 0f
                val txC = tx; val tyC = ty; val tileC = tile; val hC = h
                jobs.add(sortDepth to { drawTile3D(canvas, txC, tyC, tileC, hC) })
                // Tall 3D trees sort at their actual world-space height, not as ground tiles
                if (tile == Tile.PALM) {
                    val pwx = tx + 0.5f; val pwy = ty + 0.5f
                    jobs.add(rdr.depth(pwx, pwy, 2.0f) to { drawTallPalm3D(canvas, pwx, pwy) })
                }
                if (tile == Tile.CACTUS) {
                    val cwx = tx + 0.5f; val cwy = ty + 0.5f
                    jobs.add(rdr.depth(cwx, cwy, 0.75f) to { drawTallCactus3D(canvas, cwx, cwy) })
                }
            }
        }

        // --- PYRAMIDS (per-face jobs — each face sorts independently to prevent camel occlusion) ---
        world.locations.filter { it.type == LocationType.PYRAMID }.forEach { loc ->
            buildPyramidFaceJobs(canvas, loc.tileX.toFloat(), loc.tileY.toFloat())
                .forEach { jobs.add(it) }
        }

        // --- STABLE ROOF + INTERIOR DETAILS ---
        world.locations.filter { it.type == LocationType.STABLE }.forEach { loc ->
            val scx = loc.tileX.toFloat(); val scy = loc.tileY.toFloat()
            val sd = rdr.depth(scx + 0.5f, scy + 1f, 2.3f)
            jobs.add(sd to { drawStable3D(canvas, scx, scy) })
        }

        // --- FOOD ---
        foodItems.forEach { f ->
            val d = rdr.depth(f[0], f[1], 0f)
            jobs.add(d to { drawSingleFoodItem(canvas, f) })
        }

        // --- DAILY SURPRISE ---
        if (surpriseActive) {
            val d = rdr.depth(surpriseX, surpriseY, 0f)
            jobs.add(d to { drawDailySurprise(canvas) })
        }

        // --- NIGHT OASIS ---
        if (nightOasisActive) {
            val d = rdr.depth(nightOasisX, nightOasisY, 0f)
            jobs.add(d to { drawNightOasis(canvas) })
        }

        // --- NPCs ---
        npcs.forEach { npc ->
            val d = rdr.depth(npc.x, npc.y + 0.5f, 0f)
            jobs.add(d to { drawSingleNpc(canvas, npc) })
        }

        // --- WANDER CAMELS ---
        wanderCamels.forEach { wc ->
            val d = rdr.depth(wc.x, wc.y + 0.5f, 0f)
            jobs.add(d to { drawSingleWanderCamel(canvas, wc) })
        }

        // --- PLAYER CAMEL ---
        val playerDepth = rdr.depth(camel.x, camel.y + 0.5f, 0f)
        jobs.add(playerDepth to { drawCamel(canvas) })

        // Sort furthest first, then draw
        jobs.sortByDescending { it.first }
        jobs.forEach { it.second() }
    }

    private fun drawSingleFoodItem(canvas: Canvas, f: FloatArray) {
        val pulse = ((sin(System.nanoTime() / 600_000_000.0) + 1.0) / 2.0).toFloat()
        val proj = renderer.project(f[0], f[1], 0.2f) ?: return
        val sx = proj[0]; val sy = proj[1]; val ts = renderer.scaleAt(proj[2])
        if (sx < -ts || sx > width + ts || sy < -ts || sy > height + ts) return
        if (f[2] == 2f) {
            canvas.drawCircle(sx, sy, ts * 0.30f + pulse * 4f, p(Color.argb(65, 255, 215, 50)))
            val rp = Path().apply {
                moveTo(sx, sy - ts * 0.22f); lineTo(sx + ts*0.15f, sy)
                lineTo(sx, sy + ts * 0.22f); lineTo(sx - ts*0.15f, sy); close()
            }
            canvas.drawPath(rp, p(Color.rgb(255, 210, 50)))
            canvas.drawPath(rp, p(Color.rgb(170, 130, 20), Paint.Style.STROKE).apply { strokeWidth = ts * 0.03f })
        } else if (f[2] == 1f) {
            canvas.drawCircle(sx, sy - ts * 0.4f, ts * 0.13f + pulse * 2f, p(Color.argb(70, 220, 60, 60)))
            canvas.drawCircle(sx, sy - ts * 0.4f, ts * 0.10f, p(Color.rgb(210, 55, 55)))
            canvas.drawLine(sx, sy - ts * 0.42f, sx - ts * 0.04f, sy - ts * 0.54f,
                p(Color.rgb(55, 140, 55), Paint.Style.STROKE).apply { strokeWidth = ts * 0.025f })
        } else {
            canvas.drawCircle(sx, sy, ts * 0.24f + pulse * 2f, p(Color.argb(50, 220, 170, 60)))
            canvas.drawCircle(sx, sy + ts * 0.04f, ts * 0.18f, p(Color.rgb(200, 150, 50)))
            canvas.drawCircle(sx, sy - ts * 0.04f, ts * 0.12f, p(Color.rgb(220, 170, 70)))
            val sp = p(Color.rgb(75, 155, 55), Paint.Style.STROKE).apply { strokeWidth = ts * 0.03f }
            for (i in -1..1) canvas.drawLine(
                sx + i * ts * 0.07f, sy - ts * 0.04f, sx + i * ts * 0.05f, sy - ts * 0.22f, sp)
        }
    }

    private fun drawSingleNpc(canvas: Canvas, npc: NpcEntity) {
        val proj = renderer.project(npc.x, npc.y, 0f) ?: return
        val sx = proj[0]; val sy = proj[1]; val ts = renderer.scaleAt(proj[2])
        if (sx < -ts * 2 || sx > width + ts * 2 || sy < -ts * 2 || sy > height + ts * 2) return
        drawNpc3D(canvas, npc.x, npc.y, npc.facingDX, npc.facingDY, npc.walkPhase, npc.isMoving)
        if (npc.showInteractTimer > 0f) drawInteractIcon(canvas, sx, sy, ts * 1.43f, npc)
    }

    private fun drawSingleWanderCamel(canvas: Canvas, wc: WanderCamel) {
        val proj = renderer.project(wc.x, wc.y, 0f) ?: return
        val sx = proj[0]; val sy = proj[1]
        val ts = renderer.scaleAt(proj[2])
        if (sx < -ts*2 || sx > width+ts*2 || sy < -ts*2 || sy > height+ts*2) return
        val wcHopZ = if (wc.state == WanderCamel.State.PLAYING) maxOf(0f, sin(wc.playAngle * 3f).toFloat()) * 0.28f else 0f
        drawCamelBox3D(canvas, wc.x, wc.y, wc.moveVx, wc.moveVy,
            wc.walkPhase, wc.isMoving, shade = true, zOffset = wcHopZ)
        if (wc.state == WanderCamel.State.PLAYING) drawPlaySparkles(canvas, sx, sy, ts)
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

    private fun drawTile3D(canvas: Canvas, tx: Int, ty: Int, tile: Int, h: Float) {
        val rdr = renderer
        val topColor = tileTop3D[tile] ?: tileTop3D[Tile.SAND]!!

        // Ground-level corners
        val g00 = rdr.project(tx.toFloat(),      ty.toFloat(),      0f)
        val g10 = rdr.project(tx + 1f,            ty.toFloat(),      0f)
        val g11 = rdr.project(tx + 1f,            ty + 1f,           0f)
        val g01 = rdr.project(tx.toFloat(),       ty + 1f,           0f)

        // Top corners (at height h)
        val t00 = rdr.project(tx.toFloat(),      ty.toFloat(),      h)
        val t10 = rdr.project(tx + 1f,            ty.toFloat(),      h)
        val t11 = rdr.project(tx + 1f,            ty + 1f,           h)
        val t01 = rdr.project(tx.toFloat(),       ty + 1f,           h)

        val topArr   = if (h > 0f) arrayOf(t00, t10, t11, t01) else arrayOf(g00, g10, g11, g01)
        if (topArr.any { it == null }) return

        // Draw all four side faces based on camera position — works for any orbit angle
        if (h > 0.01f) {
            val tileCX = tx + 0.5f; val tileCY = ty + 0.5f
            if (rdr.camY > tileCY && g01!=null && g11!=null && t01!=null && t11!=null) {
                facePaint.color = Renderer3D.shade(topColor, 0.62f)
                rdr.quad(canvas, g01, g11, t11, t01, facePaint)
            }
            if (rdr.camY < tileCY && g00!=null && g10!=null && t00!=null && t10!=null) {
                facePaint.color = Renderer3D.shade(topColor, 0.88f)
                rdr.quad(canvas, g10, g00, t00, t10, facePaint)
            }
            if (rdr.camX > tileCX && g10!=null && g11!=null && t10!=null && t11!=null) {
                facePaint.color = Renderer3D.shade(topColor, 0.76f)
                rdr.quad(canvas, g10, g11, t11, t10, facePaint)
            }
            if (rdr.camX < tileCX && g00!=null && g01!=null && t00!=null && t01!=null) {
                facePaint.color = Renderer3D.shade(topColor, 0.70f)
                rdr.quad(canvas, g01, g00, t00, t01, facePaint)
            }
        }

        // Draw top face
        facePaint.color = topColor
        val tArr = topArr.filterNotNull()
        if (tArr.size == 4) rdr.quad(canvas, tArr[0], tArr[1], tArr[2], tArr[3], facePaint)

        // Tile-specific surface detail on top face
        val pTopArr = topArr.filterNotNull()
        if (pTopArr.size < 4) return
        val cx = pTopArr.map { it[0] }.average().toFloat()
        val cy = pTopArr.map { it[1] }.average().toFloat()
        val scale = renderer.scaleAt(renderer.depth(tx + 0.5f, ty + 0.5f, h))

        when (tile) {
            Tile.WATER -> {
                val wp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(160, 120, 220, 240); style = Paint.Style.STROKE; strokeWidth = scale * 0.06f
                }
                canvas.drawLine(cx - scale * 0.25f, cy, cx + scale * 0.25f, cy, wp)
                canvas.drawLine(cx - scale * 0.15f, cy + scale * 0.12f, cx + scale * 0.15f, cy + scale * 0.12f, wp)
            }
            Tile.GRASS -> {
                val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(72, 148, 56); strokeWidth = scale * 0.04f; style = Paint.Style.STROKE }
                for (gi in 0..2) {
                    val gx = cx + (gi - 1) * scale * 0.22f
                    canvas.drawLine(gx, cy + scale * 0.1f, gx - scale * 0.04f, cy - scale * 0.15f, gp)
                }
            }
            Tile.STONE_PATH -> {
                val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(90, 100, 88, 70); strokeWidth = scale * 0.03f; style = Paint.Style.STROKE }
                canvas.drawLine(cx - scale * 0.3f, cy - scale * 0.05f, cx + scale * 0.3f, cy - scale * 0.05f, lp)
                canvas.drawLine(cx - scale * 0.3f, cy + scale * 0.1f, cx + scale * 0.3f, cy + scale * 0.1f, lp)
            }
            Tile.PALM, Tile.CACTUS -> { /* 3D tree drawn as separate depth-sorted job */ }
            Tile.DUNE -> {
                val dp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 255, 228, 168); strokeWidth = scale * 0.05f; style = Paint.Style.STROKE }
                canvas.drawArc(android.graphics.RectF(cx - scale * 0.35f, cy - scale * 0.1f, cx + scale * 0.35f, cy + scale * 0.35f), 180f, 180f, false, dp)
            }
            Tile.BUILDING -> {
                val isTower = ((tx * 7 + ty * 13 + 3) % 11 == 0)
                fun bp(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c }
                // Top face: add dome circle decoration
                val domeP = bp(Renderer3D.shade(topColor, 0.88f))
                canvas.drawCircle(cx, cy - scale * 0.04f, scale * 0.28f, domeP)
                val domeHighColor = Renderer3D.shade(topColor, 1.08f)
                val domeHighClamped = Color.rgb(
                    Color.red(domeHighColor).coerceIn(0, 255),
                    Color.green(domeHighColor).coerceIn(0, 255),
                    Color.blue(domeHighColor).coerceIn(0, 255)
                )
                canvas.drawCircle(cx - scale * 0.06f, cy - scale * 0.10f, scale * 0.14f, bp(domeHighClamped))
                if (isTower) {
                    // Tower top: small decorative circle
                    val towerTopP = renderer.project(tx + 0.5f, ty + 0.5f, getTileHeight(tile, tx, ty) + 0.3f)
                    if (towerTopP != null) {
                        canvas.drawCircle(towerTopP[0], towerTopP[1],
                            renderer.scaleAt(towerTopP[2]) * 0.22f, bp(Renderer3D.shade(topColor, 0.9f)))
                    }
                }
            }
            Tile.BUILDING_FRONT -> {
                if (h > 0.01f && rdr.camY > ty + 0.5f) {
                    val g01p = g01 ?: return; val g11p = g11 ?: return
                    val t01p = t01 ?: return; val t11p = t11 ?: return
                    // Arched window
                    val wx0 = g01p[0] + (g11p[0] - g01p[0]) * 0.22f
                    val wx1 = g01p[0] + (g11p[0] - g01p[0]) * 0.78f
                    val fTop = t01p[1] + (g01p[1] - t01p[1]) * 0.20f
                    val fBot = t01p[1] + (g01p[1] - t01p[1]) * 0.62f
                    val fTop2 = t11p[1] + (g11p[1] - t11p[1]) * 0.20f
                    val fBot2 = t11p[1] + (g11p[1] - t11p[1]) * 0.62f
                    // Window dark fill with arch peak
                    val winPath = Path().apply {
                        moveTo(wx0, fBot); lineTo(wx1, fBot2)
                        lineTo(wx1, fTop2 + (fBot2 - fTop2) * 0.35f)
                        // arch peak
                        lineTo((wx0 + wx1) * 0.5f, fTop + (fBot - fTop) * 0.05f)
                        lineTo(wx0, fTop + (fBot - fTop) * 0.35f)
                        close()
                    }
                    facePaint.color = Color.rgb(42, 28, 14)
                    canvas.drawPath(winPath, facePaint)
                    // Orange awning strip above window
                    val awningH = (fTop + (fBot - fTop) * 0.30f)
                    val awning2H = (fTop2 + (fBot2 - fTop2) * 0.30f)
                    val awnPath = Path().apply {
                        moveTo(wx0 - (wx1 - wx0) * 0.08f, fTop + (fBot - fTop) * 0.28f)
                        lineTo(wx1 + (wx1 - wx0) * 0.08f, fTop2 + (fBot2 - fTop2) * 0.28f)
                        lineTo(wx1 + (wx1 - wx0) * 0.08f, awning2H)
                        lineTo(wx0 - (wx1 - wx0) * 0.08f, awningH)
                        close()
                    }
                    facePaint.color = Color.rgb(205, 72, 38)
                    canvas.drawPath(awnPath, facePaint)
                }
            }
            Tile.PYRAMID, Tile.PYRAMID_STEPS -> { /* drawn as true pyramid shape */ }
            else -> {}
        }
    }

    // Tall 3D palm tree — trunk projects in world space so perspective is correct.
    // Trunk reaches ~4.2 tiles high (pyramid apex = 5.5). 8 fronds droop from top.
    private fun drawTallPalm3D(canvas: Canvas, wx: Float, wy: Float) {
        val rdr = renderer
        val trunkH = 4.2f
        val baseP = rdr.project(wx, wy, 0f) ?: return
        val topP  = rdr.project(wx, wy, trunkH) ?: return
        val sw = rdr.scaleAt(baseP[2])
        val trunkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(112, 72, 36); strokeWidth = sw * 0.15f
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(baseP[0], baseP[1], topP[0], topP[1], trunkPaint)
        val leafPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        }
        val tsTop = rdr.scaleAt(topP[2])
        for (i in 0 until 8) {
            val a = i * PI.toFloat() / 4f
            val tipP = rdr.project(wx + cos(a).toFloat() * 1.5f, wy + sin(a).toFloat() * 1.5f, trunkH - 0.9f) ?: continue
            leafPaint.color = if (i % 2 == 0) Color.rgb(68, 155, 52) else Color.rgb(52, 128, 42)
            leafPaint.strokeWidth = tsTop * 0.14f
            canvas.drawLine(topP[0], topP[1], tipP[0], tipP[1], leafPaint)
        }
        canvas.drawCircle(topP[0], topP[1], tsTop * 0.22f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(44, 118, 36) })
    }

    // Taller 3D cactus — ~1.5 tiles high (just above camel ~1.28 tiles).
    // Arms branch in world-X so they foreshorten naturally with camera orbit.
    private fun drawTallCactus3D(canvas: Canvas, wx: Float, wy: Float) {
        val rdr = renderer
        val cactusH = 1.5f
        val baseP = rdr.project(wx, wy, 0f) ?: return
        val topP  = rdr.project(wx, wy, cactusH) ?: return
        val sw = rdr.scaleAt(baseP[2])
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(68, 118, 56); strokeWidth = sw * 0.22f
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(baseP[0], baseP[1], topP[0], topP[1], paint)
        val arm1H = cactusH * 0.52f
        val lJoin = rdr.project(wx, wy, arm1H)
        val lEnd  = rdr.project(wx - 0.48f, wy, arm1H)
        val lTop  = rdr.project(wx - 0.48f, wy, arm1H + 0.48f)
        if (lJoin != null && lEnd != null && lTop != null) {
            canvas.drawLine(lJoin[0], lJoin[1], lEnd[0], lEnd[1], paint)
            canvas.drawLine(lEnd[0],  lEnd[1],  lTop[0], lTop[1],  paint)
        }
        val arm2H = cactusH * 0.68f
        val rJoin = rdr.project(wx, wy, arm2H)
        val rEnd  = rdr.project(wx + 0.48f, wy, arm2H)
        val rTop  = rdr.project(wx + 0.48f, wy, arm2H + 0.40f)
        if (rJoin != null && rEnd != null && rTop != null) {
            canvas.drawLine(rJoin[0], rJoin[1], rEnd[0], rEnd[1], paint)
            canvas.drawLine(rEnd[0],  rEnd[1],  rTop[0], rTop[1],  paint)
        }
    }

    // Returns per-face draw jobs so each face sorts independently in the global jobs list.
    // Stone course lines are drawn within each face's lambda.
    private fun buildPyramidFaceJobs(canvas: Canvas, cx: Float, cy: Float): List<Pair<Float, () -> Unit>> {
        val rdr = renderer
        val r = 5.0f; val apH = 5.5f
        val base = Color.rgb(222, 190, 132)
        val sw = rdr.project(cx - r, cy + r, 0f)
        val se = rdr.project(cx + r, cy + r, 0f)
        val ne = rdr.project(cx + r, cy - r, 0f)
        val nw = rdr.project(cx - r, cy - r, 0f)
        val ap = rdr.project(cx, cy, apH) ?: return emptyList()

        val triP  = Paint(Paint.ANTI_ALIAS_FLAG)
        val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f }
        val ridgeP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Renderer3D.shade(base, 0.40f); style = Paint.Style.STROKE; strokeWidth = 2.5f
        }

        fun tri(p0: FloatArray?, p1: FloatArray?, p2: FloatArray?, col: Int) {
            if (p0 == null || p1 == null || p2 == null) return
            triP.color = col
            canvas.drawPath(Path().apply {
                moveTo(p0[0], p0[1]); lineTo(p1[0], p1[1]); lineTo(p2[0], p2[1]); close()
            }, triP)
        }

        fun courses(col: Int, leftPt: (rh: Float, h: Float) -> FloatArray?, rightPt: (rh: Float, h: Float) -> FloatArray?) {
            lineP.color = Renderer3D.shade(col, 0.56f)
            val n = 9
            for (i in 1 until n) {
                val t = i.toFloat() / n; val h = t * apH; val rh = r * (1f - t)
                val pL = leftPt(rh, h) ?: continue; val pR = rightPt(rh, h) ?: continue
                canvas.drawLine(pL[0], pL[1], pR[0], pR[1], lineP)
            }
        }

        // +1.5 depth bias pushes each pyramid face behind same-depth entities (painter's algorithm fix)
        val bias = 1.5f
        val jobs = mutableListOf<Pair<Float, () -> Unit>>()

        if (rdr.camY > cy) {  // south face
            val col = Renderer3D.shade(base, 0.64f)
            jobs.add((rdr.depth(cx, cy + r * 0.6f, apH * 0.3f) + bias) to {
                tri(sw, se, ap, col)
                courses(col, { rh, h -> rdr.project(cx - rh, cy + rh, h) },
                             { rh, h -> rdr.project(cx + rh, cy + rh, h) })
            })
        }
        if (rdr.camY < cy) {  // north face
            val col = Renderer3D.shade(base, 0.90f)
            jobs.add((rdr.depth(cx, cy - r * 0.6f, apH * 0.3f) + bias) to {
                tri(ne, nw, ap, col)
                courses(col, { rh, h -> rdr.project(cx + rh, cy - rh, h) },
                             { rh, h -> rdr.project(cx - rh, cy - rh, h) })
            })
        }
        if (rdr.camX > cx) {  // east face
            val col = Renderer3D.shade(base, 0.77f)
            jobs.add((rdr.depth(cx + r * 0.6f, cy, apH * 0.3f) + bias) to {
                tri(se, ne, ap, col)
                courses(col, { rh, h -> rdr.project(cx + rh, cy + rh, h) },
                             { rh, h -> rdr.project(cx + rh, cy - rh, h) })
            })
        }
        if (rdr.camX < cx) {  // west face
            val col = Renderer3D.shade(base, 0.83f)
            jobs.add((rdr.depth(cx - r * 0.6f, cy, apH * 0.3f) + bias) to {
                tri(nw, sw, ap, col)
                courses(col, { rh, h -> rdr.project(cx - rh, cy - rh, h) },
                             { rh, h -> rdr.project(cx - rh, cy + rh, h) })
            })
        }

        // Ridge edge lines — draw on top of faces (smaller depth = drawn later = in front)
        val ridgeD = (jobs.minOfOrNull { it.first } ?: 0f) - 0.5f
        jobs.add(ridgeD to {
            listOf(sw, se, ne, nw).forEach { c ->
                if (c != null) canvas.drawLine(c[0], c[1], ap[0], ap[1], ridgeP)
            }
        })
        return jobs
    }

    // ── Stable structure ──────────────────────────────────────────────────────
    // Roof and decorative interior drawn on top of the BUILDING wall tiles placed by placeStable.
    // Walls span: left x=cx-3, right x=cx+4, back y=cy+4; front open at y=cy-2.
    private fun drawStable3D(canvas: Canvas, cx: Float, cy: Float) {
        val wallH = 2.3f
        val roofColor  = Color.rgb(162, 126, 72)
        val beamColor  = Color.rgb(112, 76, 38)
        val hayColor   = Color.rgb(210, 174, 66)
        // Hay bales drawn FIRST so the roof always paints over them (hides them from above)
        drawBox3D(canvas, cx + 1.3f, cy + 1.3f, 0f, cx + 2.2f, cy + 2.2f, 0.56f, hayColor)
        drawBox3D(canvas, cx - 2.2f, cy + 1.3f, 0f, cx - 1.3f, cy + 2.2f, 0.56f, hayColor)
        // Front overhang beam, then main roof slab — roof drawn last so it covers everything below
        drawBox3D(canvas, cx - 3f, cy - 3.1f, wallH - 0.06f, cx + 4f, cy - 2f, wallH + 0.14f, beamColor)
        drawBox3D(canvas, cx - 3f, cy - 2f, wallH, cx + 4f, cy + 4f, wallH + 0.30f, roofColor)
    }

    // ── NPCs ───────────────────────────────────────────────────────────────────
    private fun drawNpcs(canvas: Canvas) {
        npcs.forEach { npc ->
            val proj = renderer.project(npc.x, npc.y, 0f) ?: return@forEach
            val sx = proj[0]; val sy = proj[1]; val ts = renderer.scaleAt(proj[2])
            if (sx < -ts * 2 || sx > width + ts * 2 || sy < -ts * 2 || sy > height + ts * 2) return@forEach
            drawNpc3D(canvas, npc.x, npc.y, npc.facingDX, npc.facingDY, npc.walkPhase, npc.isMoving)
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

    // 3D world-space NPC — ~1.2 tiles tall, built from drawBox3D like the camel.
    // Rotation uses sinA=facingDX, cosA=facingDY so the NPC faces its movement direction.
    private fun drawNpc3D(canvas: Canvas, wx: Float, wy: Float,
                          facingDX: Float, facingDY: Float,
                          walkPhase: Float, isMoving: Boolean) {
        val rdr = renderer
        fun wxy(lx: Float, ly: Float) =
            floatArrayOf(wx + lx * facingDY + ly * facingDX, wy - lx * facingDX + ly * facingDY)
        fun bx(lx0: Float, ly0: Float, lx1: Float, ly1: Float): FloatArray {
            val c = arrayOf(wxy(lx0, ly0), wxy(lx1, ly0), wxy(lx1, ly1), wxy(lx0, ly1))
            return floatArrayOf(c.minOf { it[0] }, c.minOf { it[1] },
                                c.maxOf { it[0] }, c.maxOf { it[1] })
        }
        val sw = if (isMoving) sin(walkPhase.toDouble()).toFloat() * 0.035f else 0f

        val skinC   = Color.rgb(200, 160, 110)
        val robeC   = Color.rgb(240, 235, 215)
        val accentC = Color.rgb(180,  60,  40)
        val darkC   = Color.rgb( 60,  50,  40)
        val turbanC = Color.rgb(200, 180, 140)

        data class B(val x0:Float,val y0:Float,val z0:Float,val x1:Float,val y1:Float,val z1:Float,val c:Int)
        val boxes = ArrayList<B>(6)
        // Legs (alternating swing on Y axis)
        run { val b = bx(-0.09f, -0.05f - sw, 0.00f,  0.07f - sw); boxes.add(B(b[0],b[1],0f,b[2],b[3],0.44f,darkC)) }
        run { val b = bx( 0.00f, -0.05f + sw, 0.09f,  0.07f + sw); boxes.add(B(b[0],b[1],0f,b[2],b[3],0.44f,darkC)) }
        // Robe body
        run { val b = bx(-0.13f, -0.07f, 0.13f, 0.07f); boxes.add(B(b[0],b[1],0.30f,b[2],b[3],0.92f,robeC)) }
        // Belt stripe
        run { val b = bx(-0.13f, -0.07f, 0.13f, 0.07f); boxes.add(B(b[0],b[1],0.52f,b[2],b[3],0.59f,accentC)) }
        // Head
        run { val b = bx(-0.09f, -0.07f, 0.09f, 0.07f); boxes.add(B(b[0],b[1],0.92f,b[2],b[3],1.06f,skinC)) }
        // Turban
        run { val b = bx(-0.12f, -0.09f, 0.12f, 0.09f); boxes.add(B(b[0],b[1],1.05f,b[2],b[3],1.20f,turbanC)) }

        boxes.sortByDescending { rdr.depth((it.x0+it.x1)*0.5f,(it.y0+it.y1)*0.5f,(it.z0+it.z1)*0.5f) }
        boxes.forEach { drawBox3D(canvas, it.x0,it.y0,it.z0, it.x1,it.y1,it.z1, it.c) }
    }

    // ── True 3-D camel built from axis-aligned boxes ──────────────────────────
    private fun drawCamelBox3D(
        canvas: Canvas,
        wx: Float, wy: Float,
        facingX: Float, facingY: Float,
        walkPhase: Float,
        isMoving: Boolean,
        isPlaying: Boolean = false,
        sitting: Boolean = false,
        hasSaddle: Boolean = false,
        saddleColorIdx: Int = 0,
        shade: Boolean = false,
        zOffset: Float = 0f   // vertical hop offset (tiles) for dance animation
    ) {
        val rdr = renderer
        val bodyC = if (shade) Color.rgb(172,132,68) else Color.rgb(218,178,98)
        val darkC = if (shade) Color.rgb(138,100,46) else Color.rgb(175,130,62)
        val lightC = if (shade) Color.rgb(198,158,88) else Color.rgb(242,210,138)
        val hoofC  = Color.rgb(68,44,18)

        // Clockwise rotation by angle where sinA=facingX, cosA=facingY (facing south = facingY=1)
        fun wxy(lx: Float, ly: Float): FloatArray =
            floatArrayOf(wx + lx * facingY + ly * facingX, wy - lx * facingX + ly * facingY)
        fun bx(lx0:Float,ly0:Float,lx1:Float,ly1:Float): FloatArray {
            val c = arrayOf(wxy(lx0,ly0),wxy(lx1,ly0),wxy(lx1,ly1),wxy(lx0,ly1))
            return floatArrayOf(c.minOf{it[0]},c.minOf{it[1]},c.maxOf{it[0]},c.maxOf{it[1]})
        }
        val sw = if (isMoving||isPlaying) sin(walkPhase.toDouble()).toFloat()*0.06f else 0f

        data class B(val x0:Float,val y0:Float,val z0:Float,val x1:Float,val y1:Float,val z1:Float,val c:Int)
        val boxes = ArrayList<B>(14)

        if (sitting) {
            for (lx0 in listOf(-0.22f, 0.09f)) for (ly0 in listOf(-0.20f, 0.08f)) {
                val b = bx(lx0, ly0, lx0+0.13f, ly0+0.13f)
                boxes.add(B(b[0],b[1],0f,b[2],b[3],0.22f,hoofC))
            }
        } else {
            run { val b=bx(-0.22f,-0.22f-sw,-0.09f,-0.09f-sw); boxes.add(B(b[0],b[1],0.09f,b[2],b[3],0.48f,darkC)); boxes.add(B(b[0],b[1],0f,b[2],b[3],0.10f,hoofC)) }
            run { val b=bx( 0.09f,-0.22f+sw, 0.22f,-0.09f+sw); boxes.add(B(b[0],b[1],0.09f,b[2],b[3],0.48f,darkC)); boxes.add(B(b[0],b[1],0f,b[2],b[3],0.10f,hoofC)) }
            run { val b=bx(-0.22f, 0.08f+sw,-0.09f, 0.20f+sw); boxes.add(B(b[0],b[1],0.09f,b[2],b[3],0.48f,bodyC)); boxes.add(B(b[0],b[1],0f,b[2],b[3],0.10f,hoofC)) }
            run { val b=bx( 0.09f, 0.08f-sw, 0.22f, 0.20f-sw); boxes.add(B(b[0],b[1],0.09f,b[2],b[3],0.48f,bodyC)); boxes.add(B(b[0],b[1],0f,b[2],b[3],0.10f,hoofC)) }
        }
        run { val b=bx(-0.28f,-0.38f, 0.28f, 0.28f); boxes.add(B(b[0],b[1],0.38f,b[2],b[3],0.87f,bodyC)) }
        run { val b=bx(-0.18f,-0.24f, 0.18f, 0.08f); boxes.add(B(b[0],b[1],0.74f,b[2],b[3],1.28f,bodyC)) }
        run { val b=bx(-0.10f, 0.12f, 0.10f, 0.36f); boxes.add(B(b[0],b[1],0.64f,b[2],b[3],1.06f,bodyC)) }
        run { val b=bx(-0.14f, 0.28f, 0.14f, 0.56f); boxes.add(B(b[0],b[1],0.90f,b[2],b[3],1.28f,bodyC)) }
        run { val b=bx(-0.10f, 0.50f, 0.10f, 0.74f); boxes.add(B(b[0],b[1],0.96f,b[2],b[3],1.14f,lightC)) }
        if (hasSaddle && !shade) {
            val sc = intArrayOf(Color.rgb(180,55,32),Color.rgb(48,78,168),Color.rgb(118,48,165),
                                Color.rgb(48,128,68),Color.rgb(192,148,28),Color.rgb(28,128,145))
            run { val b=bx(-0.16f,-0.12f,0.16f,0.06f); boxes.add(B(b[0],b[1],0.86f,b[2],b[3],1.00f,sc[saddleColorIdx.coerceIn(0,sc.size-1)])) }
        }
        val z0 = zOffset
        boxes.sortByDescending { rdr.depth((it.x0+it.x1)*0.5f,(it.y0+it.y1)*0.5f,(it.z0+it.z1)*0.5f+z0) }
        boxes.forEach { drawBox3D(canvas,it.x0,it.y0,it.z0+z0,it.x1,it.y1,it.z1+z0,it.c) }

        val frontFacing = facingX * (rdr.camX - wx) + facingY * (rdr.camY - wy) > 0
        if (frontFacing) {
            val eyeC = if (shade) Color.rgb(40,20,5) else Color.rgb(20,10,2)
            for (side in listOf(0.12f, -0.12f)) {
                val ep = wxy(side, 0.50f)
                val epr = rdr.project(ep[0],ep[1],1.14f + z0) ?: continue
                val er = rdr.scaleAt(epr[2])*0.065f
                canvas.drawCircle(epr[0],epr[1],er*1.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE})
                canvas.drawCircle(epr[0],epr[1],er,      Paint(Paint.ANTI_ALIAS_FLAG).apply{color=eyeC})
                canvas.drawCircle(epr[0]+er*0.4f,epr[1]-er*0.4f,er*0.42f, Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE})
            }
        }
    }

    // ── Camel drawing ──────────────────────────────────────────────────────────
    private fun drawCamel(canvas: Canvas) {
        if (renderer.project(camel.x, camel.y, 0f) == null) return
        val love = camelState.currentLove()
        val hasSaddle = persistence.unlockedCosmetics.isNotEmpty()
        val saddleColorIdx = persistence.unlockedCosmetics.minOrNull() ?: 0
        val trotBoost = if (trotActive) 1.6f else 1f
        val effectivePhase = camel.walkPhase * trotBoost
        val hopZ = if (playerPlaying) maxOf(0f, sin(playerPlayAngle * 3f).toFloat()) * 0.28f else 0f
        drawCamelBox3D(canvas, camel.x, camel.y, camel.facingX, camel.facingY,
            effectivePhase, camel.isMoving || playerPlaying,
            isPlaying = playerPlaying,
            sitting = sitting,
            hasSaddle = hasSaddle, saddleColorIdx = saddleColorIdx,
            shade = false, zOffset = hopZ)
        // HUD overlays using projected screen position
        val proj2 = renderer.project(camel.x, camel.y, 0f) ?: return
        val sx = proj2[0]; val sy = proj2[1]; val ts3D = renderer.scaleAt(proj2[2])
        if (playerPlaying) drawPlaySparkles(canvas, sx, sy, ts3D)
        if (moodBubbleTimer > 0f) drawMoodBubble(canvas, sx, sy)
        if (inStable) {
            val t = (System.nanoTime() / 1_200_000_000L % 3).toInt()
            val zText = when (t) { 0 -> "z"; 1 -> "zz"; else -> "zzz" }
            val zp = p(Color.rgb(160, 190, 255)).apply {
                typeface = Typeface.MONOSPACE; textSize = ts3D * 0.28f; textAlign = Paint.Align.CENTER
            }
            canvas.drawText(zText, sx + ts3D * 0.5f, sy - ts3D * 1.2f, zp)
        }
    }

    private fun drawWanderCamels(canvas: Canvas) {
        wanderCamels.forEach { wc ->
            val proj = renderer.project(wc.x, wc.y, 0f) ?: return@forEach
            val sx = proj[0]; val sy = proj[1]
            val ts = renderer.scaleAt(proj[2])
            if (sx < -ts*2 || sx > width+ts*2 || sy < -ts*2 || sy > height+ts*2) return@forEach
            canvas.save(); canvas.translate(sx, sy)
            val bob = if (wc.isMoving) sin(wc.walkPhase).toFloat() * ts * .025f else 0f
            drawCamelSprite(canvas, ts, bob, wc.facingLeft, shade = true)
            canvas.restore()
            if (wc.state == WanderCamel.State.PLAYING) drawPlaySparkles(canvas, sx, sy, ts)
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
        hasSaddle: Boolean = false, saddleColorIdx: Int = 0, trotBoost: Float = 1f,
        sitting: Boolean = false
    ) {
        canvas.save()
        if (flipLeft) canvas.scale(-1f, 1f)
        // Shift sprite up so feet (leg bottom = 2.40*ts below origin) sit at the projected ground point
        canvas.translate(0f, -2.40f * ts)

        // --- BASIC MOTION OFFSETS ---
        val bobY = bob * ts * 0.12f
        val droop = moodDroop.coerceIn(0f, 1f)

        // --- PAINTS ---
        val bodyBase = if (shade) 0xFFB98A55.toInt() else 0xFFCFA46A.toInt()
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, -1.6f * ts, 0f, 1.2f * ts,
                bodyBase,
                0xFF8E6A3F.toInt(),
                Shader.TileMode.CLAMP
            )
            style = Paint.Style.FILL
        }

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF5A3A1E.toInt()
            strokeWidth = ts * 0.06f
            style = Paint.Style.STROKE
        }

        val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x33FFFFFF
            style = Paint.Style.FILL
        }

        val eyeWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.FILL
        }

        val eyeBlack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt()
            style = Paint.Style.FILL
        }

        val saddlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = when (saddleColorIdx) {
                1 -> 0xFFAA3333.toInt()
                2 -> 0xFF3366AA.toInt()
                3 -> 0xFF228844.toInt()
                else -> 0xFF8844AA.toInt()
            }
            style = Paint.Style.FILL
        }

        // --- BODY (tall + slim) ---
        val bodyRect = RectF(
            -0.55f * ts, -0.25f * ts + bobY,
            0.55f * ts, 1.05f * ts + bobY
        )
        canvas.drawOval(bodyRect, bodyPaint)
        canvas.drawOval(bodyRect, linePaint)

        // --- HUMP (tall + narrow) ---
        val humpRect = RectF(
            -0.35f * ts, -0.9f * ts + bobY,
            0.35f * ts, -0.25f * ts + bobY
        )
        canvas.drawOval(humpRect, bodyPaint)
        canvas.drawOval(humpRect, linePaint)

        // --- NECK (long S-curve) ---
        val neck = Path().apply {
            moveTo(0.25f * ts, -0.25f * ts + bobY)
            quadTo(0.55f * ts, -1.0f * ts + bobY, 0.35f * ts, -1.55f * ts + bobY)
            quadTo(0.15f * ts, -1.25f * ts + bobY, 0.05f * ts, -0.55f * ts + bobY)
            close()
        }
        canvas.drawPath(neck, bodyPaint)
        canvas.drawPath(neck, linePaint)

        // --- HEAD (small + rounded) ---
        val headRect = RectF(
            0.05f * ts, -1.75f * ts + bobY,
            0.55f * ts, -1.25f * ts + bobY
        )
        canvas.drawOval(headRect, bodyPaint)
        canvas.drawOval(headRect, linePaint)

        // --- MUZZLE ---
        val muzzleRect = RectF(
            0.45f * ts, -1.55f * ts + bobY,
            0.85f * ts, -1.30f * ts + bobY
        )
        canvas.drawOval(muzzleRect, bodyPaint)
        canvas.drawOval(muzzleRect, linePaint)

        // --- BIG ROUND EYE ---
        val eyeCx = 0.32f * ts
        val eyeCy = -1.52f * ts + bobY + droop * ts * 0.08f
        val eyeR = ts * 0.11f
        canvas.drawCircle(eyeCx, eyeCy, eyeR, eyeWhite)
        canvas.drawCircle(eyeCx, eyeCy, eyeR * 0.55f, eyeBlack)
        canvas.drawCircle(eyeCx + eyeR * 0.25f, eyeCy - eyeR * 0.25f, eyeR * 0.22f, eyeWhite)

        // --- SMILE + NOSTRILS ---
        val smile = Path().apply {
            moveTo(0.55f * ts, -1.33f * ts + bobY)
            quadTo(0.65f * ts, -1.25f * ts + bobY, 0.75f * ts, -1.33f * ts + bobY)
        }
        canvas.drawPath(smile, linePaint)

        canvas.drawCircle(0.62f * ts, -1.42f * ts + bobY, ts * 0.03f, linePaint)
        canvas.drawCircle(0.72f * ts, -1.42f * ts + bobY, ts * 0.03f, linePaint)

        // --- LEGS ---
        fun drawLeg(baseX: Float, topY: Float, splay: Float) {
            val leg = Path().apply {
                moveTo(baseX, topY)
                quadTo(
                    baseX + splay * ts * 0.15f,
                    topY + ts * 0.75f,
                    baseX,
                    topY + ts * 1.45f
                )
                quadTo(
                    baseX - ts * 0.08f,
                    topY + ts * 0.75f,
                    baseX,
                    topY
                )
                close()
            }
            canvas.drawPath(leg, bodyPaint)
            canvas.drawPath(leg, linePaint)
        }

        if (sitting) {
            for (lx in listOf(-0.30f, 0.30f, -0.22f, 0.22f))
                canvas.drawOval(RectF(lx*ts - ts*0.07f, 0.95f*ts + bobY, lx*ts + ts*0.07f, 1.12f*ts + bobY), bodyPaint)
        } else {
            drawLeg(-0.30f * ts, 0.95f * ts + bobY, -0.05f)
            drawLeg( 0.30f * ts, 0.95f * ts + bobY,  0.05f)
            drawLeg(-0.22f * ts, 1.05f * ts + bobY, -0.10f)
            drawLeg( 0.22f * ts, 1.05f * ts + bobY,  0.10f)
        }

        // --- TAIL + TUFT ---
        val tail = Path().apply {
            moveTo(-0.50f * ts, 0.10f * ts + bobY)
            quadTo(-0.75f * ts, 0.55f * ts + bobY, -0.55f * ts, 0.95f * ts + bobY)
        }
        canvas.drawPath(tail, linePaint)
        canvas.drawOval(
            RectF(-0.60f * ts, 0.90f * ts + bobY, -0.45f * ts, 1.05f * ts + bobY),
            linePaint
        )

        // --- SADDLE (optional) ---
        if (hasSaddle && !shade) {
            val sRect = RectF(
                -0.40f * ts, 0.10f * ts + bobY,
                0.40f * ts, 0.55f * ts + bobY
            )
            canvas.drawRoundRect(sRect, ts * 0.20f, ts * 0.20f, saddlePaint)
            canvas.drawRoundRect(sRect, ts * 0.20f, ts * 0.20f, linePaint)
        }

        // --- HIGHLIGHTS ---
        val highlight = Path().apply {
            moveTo(-0.25f * ts, -0.85f * ts + bobY)
            quadTo(0.00f * ts, -1.10f * ts + bobY, 0.25f * ts, -0.85f * ts + bobY)
            quadTo(0.00f * ts, -0.60f * ts + bobY, -0.25f * ts, -0.85f * ts + bobY)
            close()
        }
        canvas.drawPath(highlight, highlightPaint)

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
        // Stars are now drawn in drawSky (sky background layer), not as overlay
    }

    // Project a world-space direction vector (not a position) to screen coords.
    // Returns null if the direction is behind the camera.
    private fun projectDir(dx: Float, dy: Float, dz: Float): FloatArray? {
        val rdr = renderer
        val csz = dx * rdr.fwdX + dy * rdr.fwdY + dz * rdr.fwdZ
        if (csz < 0.02f) return null
        val csx = dx * rdr.rgtX + dy * rdr.rgtY           // rgtZ is always 0
        val csy = dx * rdr.upX  + dy * rdr.upY  + dz * rdr.upZ
        return floatArrayOf(
            rdr.screenW * 0.5f + csx / csz * rdr.fovScale,
            rdr.screenH * 0.5f - csy / csz * rdr.fovScale
        )
    }

    private fun drawStars(canvas: Canvas, alpha: Int) {
        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(alpha, 255, 255, 255) }
        for (dir in starDirs) {
            val p = projectDir(dir[0], dir[1], dir[2]) ?: continue
            // Only draw stars that project into the upper screen area (sky, not ground)
            if (p[1] > height * 0.55f) continue
            canvas.drawCircle(p[0], p[1], 2.5f, sp)
        }
    }

    private fun drawNightOasis(canvas: Canvas) {
        val proj = renderer.project(nightOasisX, nightOasisY, 0.1f) ?: return
        val sx = proj[0]; val sy = proj[1]; val ts = renderer.scaleAt(proj[2])
        val pulse = ((sin(System.nanoTime() / 400_000_000.0) + 1.0) / 2.0).toFloat()
        val r = ts * (0.5f + pulse * 0.2f)
        canvas.drawCircle(sx, sy, r, p(Color.argb((100 + pulse * 80).toInt(), 50, 220, 200)))
        canvas.drawCircle(sx, sy, r * 0.5f, p(Color.argb((150 + pulse * 60).toInt(), 150, 255, 240)))
    }

    // ── Daily surprise ─────────────────────────────────────────────────────────
    private fun drawDailySurprise(canvas: Canvas) {
        if (!surpriseActive) return
        val proj = renderer.project(surpriseX, surpriseY, 0.3f) ?: return
        val sx = proj[0]; val sy = proj[1]; val ts = renderer.scaleAt(proj[2])
        if (sx < -ts*2 || sx > width+ts*2 || sy < -ts*2 || sy > height+ts*2) return
        val pulse = ((sin(System.nanoTime() / 500_000_000.0) + 1.0) / 2.0).toFloat()
        val glowR = ts * (0.45f + pulse * 0.15f)
        canvas.drawCircle(sx, sy, glowR, p(Color.argb((60 + pulse * 80).toInt(), 255, 220, 30)))
        val sp = p(Color.rgb(255, 220, 30)).apply { textSize = ts * 0.5f; textAlign = Paint.Align.CENTER }
        canvas.drawText("★", sx, sy + ts * 0.18f, sp)
    }

    // ── Float animations ───────────────────────────────────────────────────────
    private fun drawFloatAnims(canvas: Canvas) {
        floatAnims.forEach { a ->
            val progress = 1f - a[2] / a[3]
            val wz = 0.4f + progress * 1.2f
            val proj = renderer.project(a[0], a[1], wz) ?: return@forEach
            val sx = proj[0]; val sy = proj[1]
            val ts = renderer.scaleAt(proj[2])
            val alpha = ((1f - progress) * 255).toInt().coerceIn(0, 255)
            val icon = if (a.size > 7 && a[7] == 1f) "★" else "♥"
            val ap = p(Color.argb(alpha, a[4].toInt(), a[5].toInt(), a[6].toInt())).apply {
                textSize = ts * 0.35f; textAlign = Paint.Align.CENTER
            }
            canvas.drawText(icon, sx, sy, ap)
        }
    }

    // ── Collect sparkle ────────────────────────────────────────────────────────
    private fun drawCollectSparkle(canvas: Canvas) {
        val proj = renderer.project(camel.x, camel.y, 0.5f) ?: return
        val sx = proj[0]; val sy = proj[1]; val ts = renderer.scaleAt(proj[2])
        val progress = 1f - collectSparkleTimer / 0.8f
        val alpha = ((1f - progress) * 220).toInt().coerceIn(0, 220)
        val sp = p(Color.argb(alpha, 255, 220, 30), Paint.Style.STROKE).apply { strokeWidth = 3f }
        for (i in 0..7) {
            val angle = i * Math.PI / 4
            val len = progress * ts * 0.6f
            canvas.drawLine(sx, sy, sx + cos(angle).toFloat() * len, sy + sin(angle).toFloat() * len, sp)
        }
    }

    // ── Food items ─────────────────────────────────────────────────────────────
    private fun drawFoodItems(canvas: Canvas) {
        val pulse = ((sin(System.nanoTime() / 600_000_000.0) + 1.0) / 2.0).toFloat()
        foodItems.forEach { f ->
            val proj = renderer.project(f[0], f[1], 0.2f) ?: return@forEach
            val sx = proj[0]; val sy = proj[1]; val ts = renderer.scaleAt(proj[2])
            if (sx < -ts || sx > width + ts || sy < -ts || sy > height + ts) return@forEach
            if (f[2] == 2f) {
                canvas.drawCircle(sx, sy, ts * 0.30f + pulse * 4f, p(Color.argb(65, 255, 215, 50)))
                val rp = Path().apply {
                    moveTo(sx, sy - ts * 0.22f); lineTo(sx + ts*0.15f, sy)
                    lineTo(sx, sy + ts * 0.22f); lineTo(sx - ts*0.15f, sy); close()
                }
                canvas.drawPath(rp, p(Color.rgb(255, 210, 50)))
                canvas.drawPath(rp, p(Color.rgb(170, 130, 20), Paint.Style.STROKE).apply { strokeWidth = ts * 0.03f })
            } else if (f[2] == 1f) {
                canvas.drawCircle(sx, sy - ts * 0.4f, ts * 0.13f + pulse * 2f, p(Color.argb(70, 220, 60, 60)))
                canvas.drawCircle(sx, sy - ts * 0.4f, ts * 0.10f, p(Color.rgb(210, 55, 55)))
                canvas.drawLine(sx, sy - ts * 0.42f, sx - ts * 0.04f, sy - ts * 0.54f,
                    p(Color.rgb(55, 140, 55), Paint.Style.STROKE).apply { strokeWidth = ts * 0.025f })
            } else {
                canvas.drawCircle(sx, sy, ts * 0.24f + pulse * 2f, p(Color.argb(50, 220, 170, 60)))
                canvas.drawCircle(sx, sy + ts * 0.04f, ts * 0.18f, p(Color.rgb(200, 150, 50)))
                canvas.drawCircle(sx, sy - ts * 0.04f, ts * 0.12f, p(Color.rgb(220, 170, 70)))
                val sp = p(Color.rgb(75, 155, 55), Paint.Style.STROKE).apply { strokeWidth = ts * 0.03f }
                for (i in -1..1) canvas.drawLine(
                    sx + i * ts * 0.07f, sy - ts * 0.04f, sx + i * ts * 0.05f, sy - ts * 0.22f, sp)
            }
        }
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
                val fp3 = renderer.project(foxTileX, foxTileY, 0.1f)
                if (fp3 != null) {
                    val fsx = fp3[0]; val fsy = fp3[1]; val fts = renderer.scaleAt(fp3[2])
                    val fp = p(Color.rgb(210, 130, 65))
                    canvas.drawCircle(fsx, fsy, fts * 0.22f, fp)
                    canvas.drawCircle(fsx - fts * 0.11f, fsy - fts * 0.2f, fts * 0.08f, fp)
                    canvas.drawCircle(fsx + fts * 0.11f, fsy - fts * 0.2f, fts * 0.08f, fp)
                    canvas.drawCircle(fsx + fts * 0.12f, fsy + fts * 0.05f, fts * 0.06f, p(Color.WHITE))
                    canvas.drawCircle(fsx + fts * 0.04f, fsy - fts * 0.06f, fts * 0.035f, p(Color.rgb(50,30,10)))
                }
            }
            MicroEvent.MIRAGE -> {
                val pulse = ((sin(System.nanoTime() / 300_000_000.0) * 0.5 + 0.5)).toFloat()
                canvas.drawRect(0f, height * 0.65f, width.toFloat(), height.toFloat(),
                    p(Color.argb((pulse * 38).toInt(), 160, 210, 255)))
            }
            MicroEvent.PYRAMID_GLOW -> {
                val pulse = ((sin(System.nanoTime() / 400_000_000.0) + 1) / 2).toFloat()
                world.locations.filter { it.type == LocationType.PYRAMID }.forEach { loc ->
                    val pp3 = renderer.project(loc.tileX.toFloat(), loc.tileY.toFloat(), 2f) ?: return@forEach
                    val pts = renderer.scaleAt(pp3[2])
                    canvas.drawCircle(pp3[0], pp3[1], pts * (3f + pulse * 1.5f),
                        p(Color.argb((60 + pulse * 60).toInt(), 255, 240, 160)))
                }
            }
            else -> {}
        }
    }

    // ── Groom label ────────────────────────────────────────────────────────────
    private fun drawGroomLabel(canvas: Canvas) {
        val proj = renderer.project(camel.x, camel.y, 1.8f) ?: return
        val sx = proj[0]; val sy = proj[1]; val ts = renderer.scaleAt(proj[2])
        val alpha = ((groomLabelTimer / 1.8f).coerceIn(0f, 1f) * 255).toInt()
        val gp = p(Color.argb(alpha, 255, 210, 140)).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = ts * 0.32f; textAlign = Paint.Align.CENTER
        }
        canvas.drawText(groomLabel, sx, sy, gp)
    }

    // ── Walkable snap ──────────────────────────────────────────────────────────
    private fun snapCamelToWalkable() {
        if (world.isWalkable(camel.x.toInt(), camel.y.toInt())) return
        for (r in 1..5) {
            for (dx in -r..r) for (dy in -r..r) {
                if (abs(dx) != r && abs(dy) != r) continue
                val tx = camel.x + dx; val ty = camel.y + dy
                if (world.isWalkable(tx.toInt(), ty.toInt())) { camel.x = tx; camel.y = ty; return }
            }
        }
    }

    // ── Spawn helpers ──────────────────────────────────────────────────────────
    private fun spawnNightOasis() {
        val rng = java.util.Random()
        repeat(50) {
            val tx = 5 + rng.nextInt(world.width - 10); val ty = 5 + rng.nextInt(world.height - 10)
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
            canvas.drawCircle(cx + (i - (instructionPages.size - 1) / 2f) * 20f, dotY, if (i == instructionPage) 7f else 5f, p(dc))
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
        // TreasureSense: show food and relic dots on minimap
        if ("TreasureSense" in camelState.unlockedAbilities) {
            foodItems.forEach { f ->
                val dc = when (f[2].toInt()) {
                    2    -> Color.rgb(255, 210, 50)   // relic: gold
                    1    -> Color.rgb(200, 80,  60)   // cactus fruit: red
                    else -> Color.rgb(220, 160, 50)   // food: orange
                }
                canvas.drawCircle(mx + (f[0] / world.width) * ms, my + (f[1] / world.height) * ms, 2.5f, p(dc))
            }
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
        val cx = dpadCX; val cy = dpadCY; val r = dpadR * 1.1f
        canvas.drawCircle(cx, cy, r, dpadBg)
        val knobR = r * 0.40f
        val active = inputDx != 0f || inputDy != 0f
        val knobPaint = if (active) dpadActive else Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(130, 210, 200, 185); style = Paint.Style.FILL
        }
        canvas.drawCircle(cx + if (active) joyKnobX else 0f,
                          cy + if (active) joyKnobY else 0f, knobR, knobPaint)
    }

    // ── Input ──────────────────────────────────────────────────────────────────
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val actionMasked = event.actionMasked
        val actionIndex  = event.actionIndex

        val btnW=130f; val btnH=64f; val feedX=width-btnW-16f; val feedY=height-btnH-16f
        val ms = 88f; val mx = width - ms - 14f; val my = 14f
        val jbx = mx + ms / 2f; val jby = my + ms + 36f

        // ── Tap actions on last-finger-lift ───────────────────────────────────
        if (actionMasked == MotionEvent.ACTION_UP) {
            val tx = event.getX(0); val ty = event.getY(0)
            clearDpad(); dpadPointerId = -1; camPointerId = -1

            if (showInstructions) {
                if (instructionPage < instructionPages.size - 1) instructionPage++
                else { showInstructions = false; persistence.seenInstructions = true; persistence.save() }
                return true
            }
            if (showJournal) { showJournal = false; return true }

            val (hbx, hby) = helpButtonCenter()
            if (sqrt((tx-hbx)*(tx-hbx) + (ty-hby)*(ty-hby)) < 38f) {
                showInstructions = true; instructionPage = 0; return true
            }
            if (sqrt((tx-jbx)*(tx-jbx) + (ty-jby)*(ty-jby)) < 44f) { showJournal = true; return true }

            // Grooming: tap near camel (only counts if the finger barely moved — i.e. not a camera drag)
            val movedFar = sqrt((tx - camLastX)*(tx - camLastX) + (ty - camLastY)*(ty - camLastY)) > 20f
            if (!movedFar) {
                val camelProj = renderer.project(camel.x, camel.y, 0f)
                if (camelProj != null) {
                    val ts3D = renderer.scaleAt(camelProj[2])
                    if (sqrt((tx-camelProj[0])*(tx-camelProj[0]) + (ty-camelProj[1])*(ty-camelProj[1])) < ts3D * 1.4f && !playerPlaying) {
                        handleGroom(); return true
                    }
                }
            }
            return true
        }

        if (showInstructions) return true
        if (showJournal) return true

        // ── Multi-touch routing ───────────────────────────────────────────────
        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val px = event.getX(0); val py = event.getY(0)
                val pid = event.getPointerId(0)
                if (px >= feedX && px <= feedX+btnW && py >= feedY && py <= feedY+btnH) { handleFeed(); return true }
                if (isOnDpad(px, py)) {
                    dpadPointerId = pid; updateDpad(px, py)
                    lastInputMs = System.currentTimeMillis(); camel.autoWandering = false; playerPlaying = false
                } else {
                    camPointerId = pid; camLastX = px; camLastY = py
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val px = event.getX(actionIndex); val py = event.getY(actionIndex)
                val pid = event.getPointerId(actionIndex)
                if (px >= feedX && px <= feedX+btnW && py >= feedY && py <= feedY+btnH) { handleFeed(); return true }
                if (isOnDpad(px, py) && dpadPointerId == -1) {
                    dpadPointerId = pid; updateDpad(px, py)
                    lastInputMs = System.currentTimeMillis(); camel.autoWandering = false; playerPlaying = false
                } else if (camPointerId == -1) {
                    camPointerId = pid; camLastX = px; camLastY = py
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val px = event.getX(i); val py = event.getY(i)
                    when (pid) {
                        dpadPointerId -> {
                            updateDpad(px, py)
                            if (inputDx != 0f || inputDy != 0f) {
                                lastInputMs = System.currentTimeMillis(); camel.autoWandering = false; playerPlaying = false
                            }
                        }
                        camPointerId -> {
                            val dx = px - camLastX; val dy = py - camLastY
                            renderer.azDeg += dx * 0.30f
                            renderer.elDeg = (renderer.elDeg - dy * 0.25f).coerceIn(15f, 80f)
                            renderer.updateBasis()
                            renderer.updateCamera(camel.x, camel.y)
                            camLastX = px; camLastY = py
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val pid = event.getPointerId(actionIndex)
                if (pid == dpadPointerId) { dpadPointerId = -1; clearDpad() }
                if (pid == camPointerId)  camPointerId = -1
            }
            MotionEvent.ACTION_CANCEL -> {
                clearDpad(); dpadPointerId = -1; camPointerId = -1
            }
        }
        return true
    }

    private fun isOnDpad(px: Float, py: Float): Boolean {
        val dx = px - dpadCX; val dy = py - dpadCY
        return sqrt(dx * dx + dy * dy) <= dpadR * 2.2f
    }

    private fun updateDpad(tx: Float, ty: Float) {
        val dx = tx - dpadCX; val dy = ty - dpadCY
        val dist = sqrt(dx * dx + dy * dy)
        if (dist > dpadR * 2.2f) { clearDpad(); return }
        if (dist < dpadR * 0.14f) { inputDx = 0f; inputDy = 0f; joyKnobX = 0f; joyKnobY = 0f; return }
        val jx = dx / dist  // normalized joystick X: right = +1
        val jy = dy / dist  // normalized joystick Y: screen-down = +1
        val knobMaxR = dpadR * 0.85f
        joyKnobX = jx * dist.coerceAtMost(knobMaxR)
        joyKnobY = jy * dist.coerceAtMost(knobMaxR)
        // Map joystick to camera-relative world movement.
        // Screen-right = camera right; screen-up (jy < 0) = camera forward.
        val fwdX = renderer.fwdX; val fwdY = renderer.fwdY
        val fwdLen = sqrt(fwdX * fwdX + fwdY * fwdY).coerceAtLeast(0.001f)
        val nfwdX = fwdX / fwdLen; val nfwdY = fwdY / fwdLen
        val worldX = jx * renderer.rgtX + (-jy) * nfwdX
        val worldY = jx * renderer.rgtY + (-jy) * nfwdY
        val len = sqrt(worldX * worldX + worldY * worldY).coerceAtLeast(0.001f)
        inputDx = worldX / len; inputDy = worldY / len
    }

    private fun clearDpad() {
        inputDx = 0f; inputDy = 0f
        dpadUp = false; dpadDown = false; dpadLeft = false; dpadRight = false
        joyKnobX = 0f; joyKnobY = 0f
    }

    private fun handleFeed() {
        val day = System.currentTimeMillis() / 86_400_000L
        val state = if (camelState.lastFeedDay != day)
            camelState.copy(feedsToday = 0, lastFeedDay = day) else camelState
        if (state.feedsToday >= 3) {
            feedLabel = "No food left today!"; feedLabelTimer = 1.8f; camelState = state; return
        }
        val boost = if (state.mood == CamelMood.MOODY) CamelState.FEED_BOOST / 2f else CamelState.FEED_BOOST
        camelState = state.copy(
            loveAtLastInteraction = (state.currentLove() + boost).coerceAtMost(CamelState.MAX_LOVE),
            lastInteractionTime = System.currentTimeMillis(),
            feedsToday = state.feedsToday + 1,
            lastFeedDay = day
        )
        stateManager.save(camelState); lastInputMs = System.currentTimeMillis()
        val moodNote = if (state.mood == CamelMood.MOODY) " (moody)" else ""
        feedLabel = "Fed! (+${boost.toInt()}♥)$moodNote"; feedLabelTimer = 1.8f
    }

    fun onResume() { camelState = stateManager.load(); checkLoginStreak(); music.resume() }
    fun onPause()  { stateManager.save(camelState); music.pause() }
}
