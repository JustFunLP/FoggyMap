package de.foggymap.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import androidx.core.graphics.drawable.toBitmap
import android.location.Location
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import de.foggymap.app.App
import de.foggymap.app.R
import de.foggymap.app.core.FogBuilder
import de.foggymap.app.core.Geo
import de.foggymap.app.core.LatLon
import de.foggymap.app.data.GroupMember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.color
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.lineProgress
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.max
import org.maplibre.android.style.expressions.Expression.product
import org.maplibre.android.style.expressions.Expression.subtract
import org.maplibre.android.style.expressions.Expression.sum
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleBlur
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circlePitchAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeOpacity
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconPitchAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillAntialias
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionBase
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionColor
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionHeight
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionOpacity
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionPattern
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionVerticalGradient
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.fillPattern
import org.maplibre.android.style.layers.PropertyFactory.lineBlur
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineGradient
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * Owns everything FoggyMap draws on top of the base map:
 *  fog (three feathered layers + procedural cloud texture), the glowing fog edge, explored street
 *  lines, revealed 3D buildings, reveal animations, group members and the location puck.
 *
 * Layer order (bottom → top): base map … road labels | fog | fog edge | explored lines |
 * 3D buildings | reveal animation | place labels | location puck.
 * Revealed buildings stand *above* the fog, so their facades are never cut off by it, while
 * unrevealed buildings are simply not part of the 3D layer.
 */
class FogMapController(private val context: Context, private val map: MapLibreMap, private val style: Style) {
    private var fog: FogBuilder? = null
    private var lastSeq = 0L
    /** Refreshes run in their own scope and are conflated: a burst of reveals never cancels a running rebuild. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pending = Channel<LatLon?>(Channel.CONFLATED)
    private var breathing: ValueAnimator? = null
    private var revealAnim: ValueAnimator? = null
    var animationsEnabled = true
    var onTrackingDismissed: (() -> Unit)? = null

    fun install() {
        style.addImage(IMG_CLOUDS, FogTexture.create())
        val empty = FogBuilder.EMPTY
        val worldFog = FogBuilder(51.0).fogGeoJson(0.0)
        for (id in listOf(SRC_FOG_A, SRC_FOG_B, SRC_FOG_C)) addSource(id, worldFog, tolerance = 0.2f)
        for (id in listOf(SRC_EDGE, SRC_BLDG, SRC_BLDG_NEW, SRC_FLASH, SRC_FRIENDS)) addSource(id, empty)
        GeoJsonSource(SRC_TRAIL, GeoJsonOptions().withLineMetrics(true).withTolerance(0.3f)).let { src -> style.addSource(src); src.setGeoJson(empty) }

        val below = if (style.getLayer("place-labels") != null) "place-labels" else null
        fun add(layer: Layer) { if (below != null) style.addLayerBelow(layer, below) else style.addLayer(layer) }

        // Feathered fog: the further from revealed ground, the denser.
        add(FillLayer(L_FOG_A, SRC_FOG_A).withProperties(fillColor(FOG), fillOpacity(0.50f), fillAntialias(true)))
        add(FillLayer(L_FOG_B, SRC_FOG_B).withProperties(fillColor(FOG), fillOpacity(0.55f), fillAntialias(true)))
        add(FillLayer(L_FOG_C, SRC_FOG_C).withProperties(fillColor(FOG), fillOpacity(0.78f), fillAntialias(true)))
        add(FillLayer(L_FOG_CLOUDS, SRC_FOG_C).withProperties(fillPattern(IMG_CLOUDS), fillOpacity(0.92f), fillAntialias(false)))
        // Streets shimmer faintly through the fog (same warm colour as on the map, much dimmer),
        // so you can see where streets are without the fog giving away the city.
        add(LineLayer(L_GHOST, "omt").withSourceLayer("transportation").withProperties(
            lineColor(ROAD), lineBlur(0.6f), lineOpacity(Expression.raw(GHOST_OPACITY)), lineWidth(Expression.raw(GHOST_WIDTH)),
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)).also { it.setFilter(Expression.raw(GHOST_FILTER)); it.minZoom = 12f })
        // Soft glow along the fog border.
        add(LineLayer(L_EDGE_GLOW, SRC_EDGE).withProperties(lineColor(VIOLET), lineOpacity(0.4f), lineBlur(7f),
            lineWidth(interpolate(linear(), zoom(), stop(12, 2f), stop(17, 9f), stop(20, 16f))), lineJoin(Property.LINE_JOIN_ROUND)))
        // Your own path as a fading light trail – visible immediately, even before the street is matched.
        // Warm white, so it reads as "light on the street" and not as one more colour.
        val trailFade = interpolate(linear(), lineProgress(),
            stop(0.0, color(Color.argb(0, 255, 241, 214))), stop(0.6, color(Color.argb(70, 255, 241, 214))),
            stop(1.0, color(Color.argb(220, 255, 246, 230))))
        add(LineLayer(L_TRAIL, SRC_TRAIL).withProperties(lineGradient(trailFade), lineBlur(0.8f),
            lineWidth(interpolate(linear(), zoom(), stop(12, 1.2f), stop(18, 3.4f))), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
        // Revealed buildings in 3D as a night city: facades with lit windows, dark flat roofs on top.
        // Every building is at least ~8 m high, so houses without height data still read as 3D.
        style.addImage(IMG_WINDOWS, WindowTexture.create(context.resources.displayMetrics.density.toInt().coerceAtLeast(1)))
        add(FillExtrusionLayer(L_BLDG, SRC_BLDG).withProperties(fillExtrusionPattern(IMG_WINDOWS),
            fillExtrusionHeight(shownHeight()), fillExtrusionBase(get("mh")), fillExtrusionOpacity(1f),
            fillExtrusionVerticalGradient(true)).also { it.minZoom = 13.5f })
        add(FillExtrusionLayer(L_BLDG_ROOF, SRC_BLDG).withProperties(fillExtrusionColor(ROOF),
            // The roof slab starts exactly where the facade ends: no overlapping walls, so nothing z-fights (flickers).
            fillExtrusionHeight(sum(shownHeight(), literal(0.6f))), fillExtrusionBase(shownHeight()),
            fillExtrusionOpacity(1f), fillExtrusionVerticalGradient(false)).also { it.minZoom = 13.5f })
        add(FillExtrusionLayer(L_BLDG_NEW, SRC_BLDG_NEW).withProperties(fillExtrusionPattern(IMG_WINDOWS),
            fillExtrusionHeight(literal(0f)), fillExtrusionBase(get("mh")), fillExtrusionOpacity(1f),
            fillExtrusionVerticalGradient(true)).also { it.minZoom = 13.5f })
        add(FillExtrusionLayer(L_BLDG_NEW_ROOF, SRC_BLDG_NEW).withProperties(fillExtrusionColor(ROOF),
            fillExtrusionHeight(literal(0f)), fillExtrusionBase(literal(0f)),
            fillExtrusionOpacity(1f), fillExtrusionVerticalGradient(false)).also { it.minZoom = 13.5f })
        // Reveal animation: fog dissolving over the new area, plus an expanding violet glow.
        add(FillLayer(L_DISSOLVE, SRC_FLASH).withProperties(fillColor(FOG), fillOpacity(0f)))
        add(FillLayer(L_DISSOLVE_CLOUDS, SRC_FLASH).withProperties(fillPattern(IMG_CLOUDS), fillOpacity(0f)))
        add(LineLayer(L_FLASH, SRC_FLASH).withProperties(lineColor(LAVENDER), lineOpacity(0f), lineBlur(4f), lineWidth(2f)))
        // Group members who share their live position: a walking figure, its viewing cone and the name.
        val pr = context.resources.displayMetrics.density.toInt().coerceIn(1, 4)
        for ((name, bmp) in Figures.all(pr)) style.addImage(name, bmp)
        add(SymbolLayer(L_FRIENDS_CONE, SRC_FRIENDS).withProperties(iconImage(Figures.coneName(Figures.FRIEND)), iconRotate(get("b")),
            iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP), iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            iconAllowOverlap(true), iconIgnorePlacement(true)).also { it.setFilter(Expression.has("b")) })
        add(SymbolLayer(L_FRIENDS, SRC_FRIENDS).withProperties(iconImage(get("img")), iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT), iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
            iconAllowOverlap(true), iconIgnorePlacement(true)))
        add(SymbolLayer(L_FRIENDS_LABEL, SRC_FRIENDS).withProperties(textField(get("name")), textFont(arrayOf("Noto Sans Bold")),
            textSize(12f), textColor("#E0FBFF"), textHaloColor("#0B0D1A"), textHaloWidth(1.6f), textAnchor(Property.TEXT_ANCHOR_TOP),
            textOffset(arrayOf(0f, 0.5f)), textAllowOverlap(true), textIgnorePlacement(true)))
        setBuildingsVisible(App.prefs.buildings3d)
        installPuck()
        scope.launch { for (hint in pending) runCatching { refresh(hint) }.onFailure { android.util.Log.e(TAG, "fog refresh failed", it) } }
    }

    /** Shown building height: at least 7 m (OpenMapTiles uses 5 m when OSM has no height), slightly exaggerated. */
    private fun shownHeight() = product(max(get("h"), literal(7f)), literal(1.15f))

    /** Diagnostics for debug builds / the emulator test. */
    fun debugState(): String {
        val cp = map.cameraPosition
        return "camera=${cp.target?.latitude?.let { "%.5f".format(it) }},${cp.target?.longitude?.let { "%.5f".format(it) }} " +
            "z=${"%.1f".format(cp.zoom)} tilt=${cp.tilt.toInt()} bearing=${cp.bearing.toInt()} follow=$follow puck=${puckPos?.let { "%.5f,%.5f".format(it.latitude, it.longitude) }}"
    }

    private fun addSource(id: String, json: String, tolerance: Float = 0.375f) {
        val src = GeoJsonSource(id, GeoJsonOptions().withTolerance(tolerance).withBuffer(64))
        style.addSource(src)
        src.setGeoJson(json)
    }

    private fun source(id: String): GeoJsonSource? = style.getSourceAs(id)

    fun setBuildingsVisible(v: Boolean) {
        val vis = if (v) Property.VISIBLE else Property.NONE
        for (id in listOf(L_BLDG, L_BLDG_ROOF, L_BLDG_NEW, L_BLDG_NEW_ROOF)) style.getLayer(id)?.setProperties(visibility(vis))
    }

    /** Requests a fog update (new database rows). Cheap to call often – requests are merged. */
    fun requestRefresh(anchorHint: LatLon?) { pending.trySend(anchorHint) }

    fun dispose() {
        disposed = true
        scope.cancel(); stopAmbient(); revealAnim?.cancel()
        android.view.Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private suspend fun refresh(anchorHint: LatLon?) {
        val items = ArrayList<de.foggymap.app.core.RevealItem>()
        var seq = lastSeq
        withContext(Dispatchers.IO) {
            while (true) {
                val (max, page) = App.db.loadAfter(seq, 5000)
                if (page.isEmpty()) break
                items += page; seq = max
            }
        }
        if (items.isEmpty()) return
        val initial = fog == null
        val builder = fog ?: FogBuilder(items.firstOrNull()?.polygons?.firstOrNull()?.firstOrNull()?.firstOrNull()?.lat
            ?: anchorHint?.lat ?: 51.0).also { fog = it }
        val t0 = android.os.SystemClock.elapsedRealtime()
        val out = withContext(Dispatchers.Default) {
            builder.add(items)
            Rendered(
                builder.fogGeoJson(0.0), builder.fogGeoJson(9.0), builder.fogGeoJson(24.0), builder.edgeGeoJson(),
                builder.buildingsGeoJson(),
                if (initial) null else builder.itemsGeoJson(items),
                if (initial) null else builder.buildingsGeoJson(items),
            )
        }
        lastSeq = seq
        apply(out)
        android.util.Log.i(TAG, "fog updated: +${items.size} items (total ${builder.itemCount}) in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
    }

    private class Rendered(val fogA: String, val fogB: String, val fogC: String, val edge: String,
                           val buildings: String, val newAreas: String?, val newBuildings: String?)

    private fun apply(r: Rendered) {
        source(SRC_FOG_A)?.setGeoJson(r.fogA)
        source(SRC_FOG_B)?.setGeoJson(r.fogB)
        source(SRC_FOG_C)?.setGeoJson(r.fogC)
        source(SRC_EDGE)?.setGeoJson(r.edge)
        if (r.newAreas == null || !animationsEnabled) {
            source(SRC_BLDG)?.setGeoJson(r.buildings)
            return
        }
        // Animation: fog dissolves over the new area while the new buildings rise.
        source(SRC_FLASH)?.setGeoJson(r.newAreas)
        source(SRC_BLDG_NEW)?.setGeoJson(r.newBuildings ?: FogBuilder.EMPTY)
        revealAnim?.cancel()
        val dissolve = style.getLayer(L_DISSOLVE); val clouds = style.getLayer(L_DISSOLVE_CLOUDS)
        val flash = style.getLayer(L_FLASH); val rising = style.getLayer(L_BLDG_NEW); val risingRoof = style.getLayer(L_BLDG_NEW_ROOF)
        revealAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                dissolve?.setProperties(fillOpacity(0.9f * (1 - t)))
                clouds?.setProperties(fillOpacity(0.9f * (1 - t) * (1 - t)))
                flash?.setProperties(lineOpacity(if (t < 0.15f) t / 0.15f else (1 - t) / 0.85f), lineWidth(2f + 14f * t))
                val rise = ((t - 0.25f) / 0.75f).coerceIn(0f, 1f)
                val h = product(shownHeight(), literal(rise))
                rising?.setProperties(fillExtrusionHeight(h))
                risingRoof?.setProperties(fillExtrusionHeight(sum(h, literal(0.6f))), fillExtrusionBase(h))
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    source(SRC_BLDG)?.setGeoJson(r.buildings)
                    source(SRC_BLDG_NEW)?.setGeoJson(FogBuilder.EMPTY)
                    source(SRC_FLASH)?.setGeoJson(FogBuilder.EMPTY)
                }
            })
            start()
        }
    }

    /** Slow "breathing" of the fog rim. Stopped while the app is in the background. */
    fun startAmbient() {
        visible = true
        ensureTicker()
        if (!animationsEnabled || breathing != null) return
        val glow = style.getLayer(L_EDGE_GLOW) ?: return
        var lastValue = -1f
        breathing = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3200
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val v = 0.25f + 0.25f * (a.animatedValue as Float)
                if (kotlin.math.abs(v - lastValue) > 0.025f) { lastValue = v; glow.setProperties(lineOpacity(v)) }
            }
            start()
        }
    }

    fun stopAmbient() { breathing?.cancel(); breathing = null; visible = false; handler.removeCallbacks(ticker) }

    // ------------------------------------------------------------------ walking figures
    //
    // You and your friends are little mannequins that walk on the spot while moving (8 frames, ~8 fps,
    // only while the map is on screen) and stand still otherwise. Side, back or front view follows the
    // walking direction relative to the screen.

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var visible = false
    private var walkFrame = 0
    private val ticker = Runnable { tick() }
    private class Friend(val key: String, val name: String, val pos: LatLon, val heading: Double?, val speed: Double?, val atMs: Long)
    private var friends: List<Friend> = emptyList()
    private var lastFriendsJson = ""

    private fun meWalking(): Boolean {
        val l = lastLoc ?: return false
        return l.hasSpeed() && l.speed > 0.6f && System.currentTimeMillis() - l.time < 8_000
    }
    private fun friendWalking(f: Friend) = (f.speed ?: 0.0) > 0.6 && System.currentTimeMillis() - f.atMs < 8_000

    private fun ensureTicker() {
        if (!visible || !animationsEnabled || disposed) return
        if (meWalking() || friends.any { friendWalking(it) }) { handler.removeCallbacks(ticker); handler.postDelayed(ticker, 125) }
    }

    private fun tick() {
        if (!visible || disposed) return
        walkFrame = (walkFrame + 1) % Figures.FRAMES
        refreshFigures()
        ensureTicker()
    }

    private fun refreshFigures() {
        puckPos?.let { setPuck(it, puckBearing) }
        renderFriends()
    }

    private fun renderFriends() {
        val cam = map.cameraPosition.bearing
        val features = friends.map { f ->
            val walking = friendWalking(f) && animationsEnabled
            val view = Figures.viewFor(f.heading?.let { it - cam })
            val props = StringBuilder("\"name\":${org.json.JSONObject.quote(f.name)},\"img\":\"${Figures.imageName(Figures.FRIEND, view, if (walking) walkFrame else -1)}\"")
            f.heading?.let { props.append(",\"b\":").append("%.0f".format(java.util.Locale.ROOT, it)) }
            FogBuilder.feature(FogBuilder.pointJson(f.pos), props.toString())
        }
        val json = FogBuilder.featureCollection(features)
        if (json == lastFriendsJson) return
        lastFriendsJson = json
        source(SRC_FRIENDS)?.setGeoJson(json)
    }

    /** Group members on the map: the live channel (every 2–3 s) wins over the slower member cards. */
    fun showFriends(members: List<GroupMember>, live: Map<String, de.foggymap.app.data.LiveFriend>) {
        val now = System.currentTimeMillis()
        val byKey = LinkedHashMap<String, Friend>()
        members.filter { !it.isMe && it.live != null }.forEach { byKey[it.key] = Friend(it.key, it.name, it.live!!, null, null, 0) }
        live.values.filter { now - it.atMs < de.foggymap.app.data.LiveLink.STALE_MS }
            .forEach { byKey[it.key] = Friend(it.key, it.name, it.pos, it.heading, it.speed, it.atMs) }
        friends = byKey.values.toList()
        renderFriends()
        ensureTicker()
    }

    // ------------------------------------------------------------------ own location puck & camera
    //
    // FoggyMap draws its own position arrow (a symbol layer above everything, so the fog can never
    // hide it) and moves the camera itself, frame by frame, only while something changes:
    //  - FOLLOW: the map is fixed on you and turned so that the direction you look (compass) or
    //    drive (GPS course) points up. Pinch-zoom and tilt keep it; dragging or rotating releases it.
    //  - FREE: the map stays where you put it.

    enum class Follow { FOLLOW, FREE }

    var follow = Follow.FREE; private set
    private var perspectiveOn = true
    /** North up instead of your viewing direction (compass button). The arrow still shows where you look. */
    var northUp = false; private set
    /** Current map rotation, for the compass button (only reported when it changed by a degree or more). */
    var onBearingChanged: ((Float) -> Unit)? = null
    private var reportedBearing = Float.NaN
    private var puckPos: LatLng? = null          // drawn
    private var targetPos: LatLng? = null        // latest fix
    private var puckBearing = 0.0
    private var targetBearing = 0.0
    private var hasBearing = false
    private var compass: Double? = null
    private var course: Double? = null
    private var courseAt = 0L
    private var lastLoc: Location? = null
    private var gestureActive = false
    private var entering = false
    private var frameScheduled = false
    private var lastFrame = 0L
    private var disposed = false
    private var lastPuckJson = ""
    private val frameCallback = android.view.Choreographer.FrameCallback { onFrame(it) }

    private fun installPuck() {
        addSource(SRC_ME, FogBuilder.EMPTY)
        // Always on top of fog, buildings and labels: how exact the position is (only when it is vague),
        // the viewing cone on the ground, and your figure.
        style.addLayer(CircleLayer(L_ME_ACC, SRC_ME).withProperties(circleRadius(Expression.raw(ACC_RADIUS)), circleColor(VIOLET),
            circleOpacity(0.10f), circleStrokeColor(VIOLET), circleStrokeOpacity(0.35f), circleStrokeWidth(1f),
            circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP)).also { it.setFilter(Expression.raw("[\">\",[\"get\",\"acc\"],10]")) })
        style.addLayer(SymbolLayer(L_ME_CONE, SRC_ME).withProperties(iconImage(Figures.coneName(Figures.ME)), iconRotate(get("b")),
            iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP), iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            iconAllowOverlap(true), iconIgnorePlacement(true)).also { it.setFilter(Expression.has("b")) })
        style.addLayer(SymbolLayer(L_ME, SRC_ME).withProperties(iconImage(get("img")), iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT), iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
            iconAllowOverlap(true), iconIgnorePlacement(true)))
        map.addOnCameraMoveListener { reportBearing() }
        map.addOnCameraIdleListener { reportBearing() }
        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE && follow == Follow.FOLLOW) gestureActive = true
        }
        map.addOnCameraIdleListener {
            if (!gestureActive) return@addOnCameraIdleListener
            gestureActive = false
            if (follow != Follow.FOLLOW) return@addOnCameraIdleListener
            // Zooming or tilting keeps following; dragging the map away or turning it releases it.
            val p = puckPos
            val moved = p != null && map.projection.toScreenLocation(p).let { s ->
                val f = focalPoint(); kotlin.math.hypot((s.x - f.x).toDouble(), (s.y - f.y).toDouble()) > 48 * context.resources.displayMetrics.density
            }
            val turned = !northUp && hasBearing && kotlin.math.abs(angleDelta(map.cameraPosition.bearing, puckBearing)) > 10.0
            if (moved || turned) release() else kick()
        }
    }

    /** Kept for API symmetry with the permission flow; the own puck needs no activation. */
    fun enableLocation(): Boolean = true

    fun updateLocation(loc: Location) {
        lastLoc = loc
        if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 3.0f) { course = loc.bearing.toDouble(); courseAt = android.os.SystemClock.elapsedRealtime() }
        val t = LatLng(loc.latitude, loc.longitude)
        targetPos = t
        val first = puckPos == null
        if (first) puckPos = t
        updateHeading()
        if (first && follow == Follow.FOLLOW) startFollowing() else kick()
        ensureTicker()
    }

    /** Compass azimuth (true north) from the phone's rotation sensor; null when not available. */
    fun updateCompass(azimuth: Double?) {
        compass = azimuth
        if (updateHeading()) kick()   // tiny sensor jitter never causes a redraw
    }

    /** Course over ground while riding/driving (also through a short stop), otherwise the compass. */
    private fun updateHeading(): Boolean {
        val c = course
        val useCourse = c != null && android.os.SystemClock.elapsedRealtime() - courseAt < 20_000
        val h = (if (useCourse) c else compass ?: c) ?: return false
        if (hasBearing && kotlin.math.abs(angleDelta(targetBearing, h)) < 1.5) return false   // ignore sensor jitter
        targetBearing = norm(h)
        if (!hasBearing) { puckBearing = targetBearing; hasBearing = true }
        return true
    }

    private fun kick() {
        if (disposed || frameScheduled) return
        frameScheduled = true
        android.view.Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun onFrame(nanos: Long) {
        frameScheduled = false
        if (disposed) return
        val target = targetPos ?: return
        val dt = if (lastFrame == 0L) 0.016 else ((nanos - lastFrame) / 1e9).coerceIn(0.0, 0.1)
        lastFrame = nanos
        val cur = puckPos ?: target
        val kPos = if (animationsEnabled) 1 - kotlin.math.exp(-dt / 0.28) else 1.0
        val kRot = if (animationsEnabled) 1 - kotlin.math.exp(-dt / 0.20) else 1.0
        val np = LatLng(cur.latitude + (target.latitude - cur.latitude) * kPos, cur.longitude + (target.longitude - cur.longitude) * kPos)
        val nb = norm(puckBearing + angleDelta(puckBearing, targetBearing) * kRot)
        puckPos = np; puckBearing = nb
        setPuck(np, nb)
        if (follow == Follow.FOLLOW && !gestureActive && !entering) {
            val cp = map.cameraPosition
            val bearing = if (northUp) 0.0 else if (hasBearing) nb else cp.bearing
            val t = cp.target
            val moved = t == null || kotlin.math.abs(t.latitude - np.latitude) > 2e-7 || kotlin.math.abs(t.longitude - np.longitude) > 2e-7 ||
                kotlin.math.abs(angleDelta(cp.bearing, bearing)) > 0.05
            if (moved) map.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(np).zoom(cp.zoom).tilt(cp.tilt)
                .bearing(bearing).padding(0.0, topPad(), 0.0, 0.0).build()))
        }
        val dLat = (target.latitude - np.latitude) * 111_320
        val dLon = (target.longitude - np.longitude) * 111_320 * kotlin.math.cos(Math.toRadians(np.latitude))
        val busy = dLat * dLat + dLon * dLon > 0.0025 || kotlin.math.abs(angleDelta(nb, targetBearing)) > 0.3
        if (busy) kick() else lastFrame = 0L
    }

    private fun setPuck(p: LatLng, bearing: Double) {
        val walking = meWalking() && animationsEnabled
        val view = Figures.viewFor(if (hasBearing) bearing - map.cameraPosition.bearing else null)
        val acc = lastLoc?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble() ?: 0.0
        // Accuracy circle radius in screen pixels at zoom 0 (512-px tiles); the layer scales it with the zoom.
        val r0 = acc / (78271.517 * kotlin.math.cos(Math.toRadians(p.latitude)))
        val props = StringBuilder("\"img\":\"${Figures.imageName(Figures.ME, view, if (walking) walkFrame else -1)}\"")
        props.append(",\"acc\":").append(acc.toInt()).append(",\"r0\":").append("%.9f".format(java.util.Locale.ROOT, r0))
        if (hasBearing) props.append(",\"b\":").append("%.1f".format(java.util.Locale.ROOT, bearing))
        val json = FogBuilder.featureCollection(listOf(
            FogBuilder.feature(FogBuilder.pointJson(LatLon(p.latitude, p.longitude)), props.toString())))
        if (json == lastPuckJson) return
        lastPuckJson = json
        source(SRC_ME)?.setGeoJson(json)
    }

    /** Fix the map on your position with your viewing direction up (the location button). */
    fun startFollowing(perspective: Boolean = perspectiveOn) {
        perspectiveOn = perspective
        follow = Follow.FOLLOW
        map.uiSettings.focalPoint = focalPoint()
        val target = puckPos ?: targetPos ?: return
        val cp = map.cameraPosition
        val zoom = cp.zoom.let { if (it < 14.5 || it > 19.5) 17.2 else it }
        entering = true
        map.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(target).zoom(zoom)
            .tilt(if (perspective) 58.0 else 0.0).bearing(if (northUp) 0.0 else if (hasBearing) puckBearing else cp.bearing)
            .padding(0.0, topPad(), 0.0, 0.0).build()), 650, object : MapLibreMap.CancelableCallback {
            override fun onFinish() { entering = false; kick() }
            override fun onCancel() { entering = false; kick() }
        })
    }

    /** The map was moved by hand: stop following. */
    private fun release() {
        if (follow == Follow.FREE) return
        follow = Follow.FREE
        map.uiSettings.focalPoint = null
        onTrackingDismissed?.invoke()
    }

    fun stopFollowing() { follow = Follow.FREE; map.uiSettings.focalPoint = null }

    /**
     * Compass button: north up (also while the map follows you) or back to your viewing direction.
     * With north up the map cannot be turned by hand, so it stays that way.
     */
    fun setNorthUp(on: Boolean, animate: Boolean = true) {
        northUp = on
        map.uiSettings.isRotateGesturesEnabled = !on
        if (follow == Follow.FOLLOW) {
            if (!animate) { kick(); return }
            entering = true
            val cp = map.cameraPosition
            map.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(cp)
                .bearing(if (on) 0.0 else if (hasBearing) puckBearing else cp.bearing).build()), 450, object : MapLibreMap.CancelableCallback {
                override fun onFinish() { entering = false; kick() }
                override fun onCancel() { entering = false; kick() }
            })
        } else if (on) {
            val cp = map.cameraPosition
            if (animate) map.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(cp).bearing(0.0).build()), 450)
            else map.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(cp).bearing(0.0).build()))
        }
    }

    private fun reportBearing() {
        val b = map.cameraPosition.bearing.toFloat()
        if (reportedBearing.isNaN() || kotlin.math.abs(angleDelta(reportedBearing.toDouble(), b.toDouble())) >= 1.0) {
            reportedBearing = b
            onBearingChanged?.invoke(b)
            refreshFigures()      // side/back/front view depends on the map rotation
        }
    }

    val perspective get() = perspectiveOn

    private fun topPad() = if (perspectiveOn) context.resources.displayMetrics.heightPixels * 0.35 else 0.0

    /** Screen point where your position sits while following (centre of the padded viewport). */
    private fun focalPoint(): android.graphics.PointF {
        val dm = context.resources.displayMetrics
        return android.graphics.PointF(dm.widthPixels / 2f, ((dm.heightPixels + topPad()) / 2).toFloat())
    }

    fun setPerspective(on: Boolean) {
        perspectiveOn = on
        if (follow == Follow.FOLLOW) { startFollowing(on); return }
        val cp = map.cameraPosition
        map.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(cp).tilt(if (on) 58.0 else 0.0)
            .padding(0.0, 0.0, 0.0, 0.0).build()), 700)
    }

    fun moveTo(p: LatLon, zoom: Double, perspective: Boolean, animate: Boolean = true) {
        perspectiveOn = perspective
        val pos = CameraPosition.Builder().target(LatLng(p.lat, p.lon)).zoom(zoom).tilt(if (perspective) 58.0 else 0.0).build()
        if (animate) map.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 1200)
        else map.cameraPosition = pos
    }

    // ------------------------------------------------------------------ light trail of your own path

    fun updateTrail(points: List<LatLon>) {
        val json = if (points.size < 2) FogBuilder.EMPTY else FogBuilder.featureCollection(listOf(
            FogBuilder.feature("{\"type\":\"LineString\",\"coordinates\":[" +
                points.joinToString(",") { "[${Geo.round6(it.lon)},${Geo.round6(it.lat)}]" } + "]}", "")))
        source(SRC_TRAIL)?.setGeoJson(json)
    }

    private fun angleDelta(from: Double, to: Double): Double { var d = (to - from) % 360.0; if (d > 180) d -= 360.0; if (d < -180) d += 360.0; return d }
    private fun norm(a: Double) = ((a % 360.0) + 360.0) % 360.0

    companion object {
        const val TAG = "FoggyMap"
        const val FOG = "#080A17"
        const val VIOLET = "#8B5CF6"
        const val LAVENDER = "#C4B5FD"
        const val CYAN = "#22D3EE"
        /** The one street colour (same as the base map's roads in style.json). */
        const val ROAD = "#e9a552"
        const val L_GHOST = "ghost-roads"
        private const val CLS = "[\"get\",\"class\"]"
        private const val CAR = "\"motorway\",\"trunk\",\"primary\",\"secondary\",\"tertiary\",\"minor\",\"service\",\"unclassified\",\"residential\",\"living_street\",\"busway\""
        private const val FOOT = "\"path\",\"track\",\"pedestrian\""
        const val GHOST_FILTER = "[\"all\",[\"match\",$CLS,[$CAR,$FOOT],true,false],[\"!=\",[\"get\",\"brunnel\"],\"tunnel\"]]"
        const val GHOST_OPACITY = "[\"match\",$CLS,[\"motorway\",\"trunk\",\"primary\"],0.42,[\"secondary\",\"tertiary\"],0.34,[$FOOT],0.14,0.26]"
        private const val GHOST_FACTOR = "[\"match\",$CLS,[\"motorway\",\"trunk\",\"primary\"],1.6,[\"secondary\",\"tertiary\"],1.3,[$FOOT],0.45,1.0]"
        const val GHOST_WIDTH = "[\"interpolate\",[\"exponential\",1.4],[\"zoom\"],12,[\"*\",0.5,$GHOST_FACTOR],18,[\"*\",5,$GHOST_FACTOR]]"
        const val IMG_CLOUDS = "fog-clouds"
        const val SRC_FOG_A = "fog-a"; const val SRC_FOG_B = "fog-b"; const val SRC_FOG_C = "fog-c"
        const val SRC_EDGE = "fog-edge"; const val SRC_BLDG = "bldg"
        const val SRC_BLDG_NEW = "bldg-new"; const val SRC_FLASH = "reveal-flash"; const val SRC_FRIENDS = "friends"
        const val L_FOG_A = "fog-a"; const val L_FOG_B = "fog-b"; const val L_FOG_C = "fog-c"; const val L_FOG_CLOUDS = "fog-clouds"
        const val L_EDGE_GLOW = "fog-edge-glow"; const val L_BLDG = "bldg-3d"; const val L_BLDG_NEW = "bldg-3d-new"
        const val L_DISSOLVE = "reveal-dissolve"; const val L_DISSOLVE_CLOUDS = "reveal-dissolve-clouds"; const val L_FLASH = "reveal-flash"
        const val L_FRIENDS = "friends"; const val L_FRIENDS_LABEL = "friends-label"
        const val IMG_WINDOWS = "windows"; const val ROOF = "#1a1c38"
        const val L_BLDG_ROOF = "bldg-roof"; const val L_BLDG_NEW_ROOF = "bldg-new-roof"
        const val SRC_TRAIL = "trail"; const val L_TRAIL = "trail"
        const val SRC_ME = "me"; const val L_ME = "me-figure"; const val L_ME_CONE = "me-cone"; const val L_ME_ACC = "me-accuracy"
        const val L_FRIENDS_CONE = "friends-cone"
        /** Circle radius from metres: r0 = pixels at zoom 0, doubled per zoom level. */
        const val ACC_RADIUS = "[\"interpolate\",[\"exponential\",2],[\"zoom\"],0,[\"get\",\"r0\"],22,[\"*\",[\"get\",\"r0\"],4194304]]"
    }
}
