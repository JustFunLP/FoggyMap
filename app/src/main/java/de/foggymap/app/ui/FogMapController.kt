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
import org.maplibre.android.style.expressions.Expression.color
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.product
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
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionVerticalGradient
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.fillPattern
import org.maplibre.android.style.layers.PropertyFactory.lineBlur
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
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
        for (id in listOf(SRC_EDGE, SRC_ROADS, SRC_BLDG, SRC_BLDG_NEW, SRC_FLASH, SRC_FRIENDS)) addSource(id, empty)

        val below = if (style.getLayer("place-labels") != null) "place-labels" else null
        fun add(layer: Layer) { if (below != null) style.addLayerBelow(layer, below) else style.addLayer(layer) }

        // Feathered fog: the further from revealed ground, the denser.
        add(FillLayer(L_FOG_A, SRC_FOG_A).withProperties(fillColor(FOG), fillOpacity(0.50f), fillAntialias(true)))
        add(FillLayer(L_FOG_B, SRC_FOG_B).withProperties(fillColor(FOG), fillOpacity(0.55f), fillAntialias(true)))
        add(FillLayer(L_FOG_C, SRC_FOG_C).withProperties(fillColor(FOG), fillOpacity(0.78f), fillAntialias(true)))
        add(FillLayer(L_FOG_CLOUDS, SRC_FOG_C).withProperties(fillPattern(IMG_CLOUDS), fillOpacity(0.92f), fillAntialias(false)))
        // Glowing rim of the explored area.
        add(LineLayer(L_EDGE_GLOW, SRC_EDGE).withProperties(lineColor(VIOLET), lineOpacity(0.55f), lineBlur(7f),
            lineWidth(interpolate(linear(), zoom(), stop(12, 2f), stop(17, 9f), stop(20, 16f))), lineJoin(Property.LINE_JOIN_ROUND)))
        add(LineLayer(L_EDGE, SRC_EDGE).withProperties(lineColor(LAVENDER), lineOpacity(0.45f),
            lineWidth(interpolate(linear(), zoom(), stop(12, 0.6f), stop(18, 1.6f))), lineJoin(Property.LINE_JOIN_ROUND)))
        // Explored street centre lines (violet light trails as in the design).
        add(LineLayer(L_ROADS_GLOW, SRC_ROADS).withProperties(lineColor(VIOLET), lineOpacity(0.35f), lineBlur(5f),
            lineWidth(interpolate(linear(), zoom(), stop(12, 2f), stop(18, 14f))), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
        add(LineLayer(L_ROADS, SRC_ROADS).withProperties(lineColor("#C9B8FF"), lineOpacity(0.55f),
            lineWidth(interpolate(linear(), zoom(), stop(12, 0.5f), stop(18, 2.2f))), lineCap(Property.LINE_CAP_ROUND)))
        // Revealed buildings in 3D.
        // Night-city buildings: blue-violet walls that get brighter with height, lit by the warm style light.
        val buildingColor = interpolate(linear(), get("h"),
            stop(0, color(Color.rgb(52, 58, 112))), stop(10, color(Color.rgb(66, 70, 140))),
            stop(25, color(Color.rgb(88, 84, 172))), stop(60, color(Color.rgb(122, 104, 214))))
        add(FillExtrusionLayer(L_BLDG, SRC_BLDG).withProperties(fillExtrusionColor(buildingColor),
            fillExtrusionHeight(get("h")), fillExtrusionBase(get("mh")), fillExtrusionOpacity(0.94f),
            fillExtrusionVerticalGradient(true)).also { it.minZoom = 13.5f })
        add(FillExtrusionLayer(L_BLDG_NEW, SRC_BLDG_NEW).withProperties(fillExtrusionColor(buildingColor),
            fillExtrusionHeight(product(get("h"), literal(0f))), fillExtrusionBase(get("mh")), fillExtrusionOpacity(0.96f),
            fillExtrusionVerticalGradient(true)).also { it.minZoom = 13.5f })
        // Reveal animation: fog dissolving over the new area, plus an expanding violet glow.
        add(FillLayer(L_DISSOLVE, SRC_FLASH).withProperties(fillColor(FOG), fillOpacity(0f)))
        add(FillLayer(L_DISSOLVE_CLOUDS, SRC_FLASH).withProperties(fillPattern(IMG_CLOUDS), fillOpacity(0f)))
        add(LineLayer(L_FLASH, SRC_FLASH).withProperties(lineColor(LAVENDER), lineOpacity(0f), lineBlur(4f), lineWidth(2f)))
        // Group members who share their live position.
        add(CircleLayer(L_FRIENDS, SRC_FRIENDS).withProperties(circleRadius(7f), circleColor(CYAN),
            circleStrokeColor("#0B0D1A"), circleStrokeWidth(2.5f), circleBlur(0.1f)))
        add(SymbolLayer(L_FRIENDS_LABEL, SRC_FRIENDS).withProperties(textField(get("name")), textFont(arrayOf("Noto Sans Bold")),
            textSize(12f), textColor("#E0FBFF"), textHaloColor("#0B0D1A"), textHaloWidth(1.6f), textOffset(arrayOf(0f, 1.4f))))
        setBuildingsVisible(App.prefs.buildings3d)
        installPuck()
        scope.launch { for (hint in pending) runCatching { refresh(hint) }.onFailure { android.util.Log.e(TAG, "fog refresh failed", it) } }
    }

    /** Diagnostics for debug builds / the emulator test. */
    fun debugState(): String {
        val cp = map.cameraPosition
        return "camera=${cp.target?.latitude?.let { "%.5f".format(it) }},${cp.target?.longitude?.let { "%.5f".format(it) }} " +
            "z=${"%.1f".format(cp.zoom)} tilt=${cp.tilt.toInt()} follow=$follow puck=${shown?.let { "%.5f,%.5f".format(it.latitude, it.longitude) }}"
    }

    private fun addSource(id: String, json: String, tolerance: Float = 0.375f) {
        val src = GeoJsonSource(id, GeoJsonOptions().withTolerance(tolerance).withBuffer(64))
        style.addSource(src)
        src.setGeoJson(json)
    }

    private fun source(id: String): GeoJsonSource? = style.getSourceAs(id)

    fun setBuildingsVisible(v: Boolean) {
        val vis = if (v) Property.VISIBLE else Property.NONE
        style.getLayer(L_BLDG)?.setProperties(visibility(vis))
        style.getLayer(L_BLDG_NEW)?.setProperties(visibility(vis))
    }

    /** Requests a fog update (new database rows). Cheap to call often – requests are merged. */
    fun requestRefresh(anchorHint: LatLon?) { pending.trySend(anchorHint) }

    fun dispose() { scope.cancel(); stopAmbient(); revealAnim?.cancel(); puckAnim?.cancel() }

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
                builder.roadsGeoJson(), builder.buildingsGeoJson(),
                if (initial) null else builder.itemsGeoJson(items),
                if (initial) null else builder.buildingsGeoJson(items),
            )
        }
        lastSeq = seq
        apply(out)
        android.util.Log.i(TAG, "fog updated: +${items.size} items (total ${builder.itemCount}) in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
    }

    private class Rendered(val fogA: String, val fogB: String, val fogC: String, val edge: String, val roads: String,
                           val buildings: String, val newAreas: String?, val newBuildings: String?)

    private fun apply(r: Rendered) {
        source(SRC_FOG_A)?.setGeoJson(r.fogA)
        source(SRC_FOG_B)?.setGeoJson(r.fogB)
        source(SRC_FOG_C)?.setGeoJson(r.fogC)
        source(SRC_EDGE)?.setGeoJson(r.edge)
        source(SRC_ROADS)?.setGeoJson(r.roads)
        if (r.newAreas == null || !animationsEnabled) {
            source(SRC_BLDG)?.setGeoJson(r.buildings)
            return
        }
        // Animation: fog dissolves over the new area while the new buildings rise.
        source(SRC_FLASH)?.setGeoJson(r.newAreas)
        source(SRC_BLDG_NEW)?.setGeoJson(r.newBuildings ?: FogBuilder.EMPTY)
        revealAnim?.cancel()
        val dissolve = style.getLayer(L_DISSOLVE); val clouds = style.getLayer(L_DISSOLVE_CLOUDS)
        val flash = style.getLayer(L_FLASH); val rising = style.getLayer(L_BLDG_NEW)
        revealAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                dissolve?.setProperties(fillOpacity(0.9f * (1 - t)))
                clouds?.setProperties(fillOpacity(0.9f * (1 - t) * (1 - t)))
                flash?.setProperties(lineOpacity(if (t < 0.15f) t / 0.15f else (1 - t) / 0.85f), lineWidth(2f + 14f * t))
                val rise = ((t - 0.25f) / 0.75f).coerceIn(0f, 1f)
                rising?.setProperties(fillExtrusionHeight(product(get("h"), literal(rise))))
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
        if (!animationsEnabled || breathing != null) return
        val glow = style.getLayer(L_EDGE_GLOW) ?: return
        var lastValue = -1f
        breathing = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3200
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val v = 0.35f + 0.35f * (a.animatedValue as Float)
                if (kotlin.math.abs(v - lastValue) > 0.025f) { lastValue = v; glow.setProperties(lineOpacity(v)) }
            }
            start()
        }
    }

    fun stopAmbient() { breathing?.cancel(); breathing = null }

    fun showFriends(members: List<GroupMember>) {
        val features = members.filter { !it.isMe && it.live != null }.map { m ->
            FogBuilder.feature(FogBuilder.pointJson(m.live!!), "\"name\":${org.json.JSONObject.quote(m.name)}")
        }
        source(SRC_FRIENDS)?.setGeoJson(FogBuilder.featureCollection(features))
    }

    // ------------------------------------------------------------------ own location puck & camera follow
    //
    // FoggyMap draws its own position arrow (a symbol layer above everything, so the fog can never
    // hide it) and moves the camera itself. That is simpler and cheaper than MapLibre's
    // LocationComponent, whose animators render continuously.

    enum class Follow { FOLLOW, NORTH, FREE }

    var follow = Follow.FOLLOW; private set
    private var perspectiveOn = true
    private var shown: LatLng? = null
    private var heading = 0.0
    private var lastLoc: Location? = null
    private var puckAnim: ValueAnimator? = null

    private fun installPuck() {
        val d = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_puck_arrow)!!
        val px = (44 * context.resources.displayMetrics.density).toInt()
        style.addImage(IMG_PUCK, d.toBitmap(px, px))
        addSource(SRC_ME, FogBuilder.EMPTY)
        // Always on top of fog, buildings and labels.
        style.addLayer(CircleLayer(L_ME_GLOW, SRC_ME).withProperties(circleRadius(26f), circleColor(VIOLET),
            circleOpacity(0.28f), circleBlur(0.9f), circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP)))
        style.addLayer(SymbolLayer(L_ME, SRC_ME).withProperties(iconImage(IMG_PUCK), iconRotate(get("b")),
            iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP), iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            iconAllowOverlap(true), iconIgnorePlacement(true), iconSize(1.0f)))
        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE && follow != Follow.FREE) {
                follow = Follow.FREE
                onTrackingDismissed?.invoke()
            }
        }
    }

    /** Kept for API symmetry with the permission flow; the own puck needs no activation. */
    fun enableLocation(): Boolean = true

    fun updateLocation(loc: Location) {
        val prev = lastLoc
        lastLoc = loc
        if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 1.0f) heading = loc.bearing.toDouble()
        else if (prev != null) {
            val d = FloatArray(2)
            Location.distanceBetween(prev.latitude, prev.longitude, loc.latitude, loc.longitude, d)
            if (d[0] > 6f) heading = ((d[1] + 360f) % 360f).toDouble()
        }
        val target = LatLng(loc.latitude, loc.longitude)
        val from = shown
        puckAnim?.cancel()
        if (from == null || !animationsEnabled) { setPuck(target) }
        else puckAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 850
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                setPuck(LatLng(from.latitude + (target.latitude - from.latitude) * t, from.longitude + (target.longitude - from.longitude) * t))
            }
            start()
        }
        followCamera(target, 850)
    }

    private fun setPuck(p: LatLng) {
        shown = p
        source(SRC_ME)?.setGeoJson(FogBuilder.featureCollection(listOf(
            FogBuilder.feature(FogBuilder.pointJson(LatLon(p.latitude, p.longitude)), "\"b\":${heading.toInt()}"))))
    }

    private fun followCamera(target: LatLng, durationMs: Int) {
        if (follow == Follow.FREE) return
        val cp = map.cameraPosition
        val zoom = cp.zoom.let { if (it < 14.5 || it > 19.5) 17.2 else it }
        // In 3D the puck sits in the lower third so you see more of what lies ahead.
        val topPad = if (perspectiveOn) context.resources.displayMetrics.heightPixels * 0.35 else 0.0
        val pos = CameraPosition.Builder().target(target).zoom(zoom).tilt(if (perspectiveOn) 58.0 else 0.0)
            .bearing(if (follow == Follow.FOLLOW) heading else 0.0)
            .padding(0.0, topPad, 0.0, 0.0)
            .build()
        map.easeCamera(CameraUpdateFactory.newCameraPosition(pos), durationMs)
    }

    fun setFollow(mode: Follow, perspective: Boolean) {
        android.util.Log.i(TAG, "camera follow mode $mode, perspective $perspective")
        follow = mode
        perspectiveOn = perspective
        val target = shown ?: lastLoc?.let { LatLng(it.latitude, it.longitude) }
        if (mode != Follow.FREE && target != null) followCamera(target, 1200)
    }

    fun setPerspective(on: Boolean) {
        perspectiveOn = on
        if (follow != Follow.FREE && shown != null) { followCamera(shown!!, 700); return }
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

    companion object {
        const val TAG = "FoggyMap"
        const val FOG = "#080A17"
        const val VIOLET = "#8B5CF6"
        const val LAVENDER = "#C4B5FD"
        const val CYAN = "#22D3EE"
        const val IMG_CLOUDS = "fog-clouds"
        const val SRC_FOG_A = "fog-a"; const val SRC_FOG_B = "fog-b"; const val SRC_FOG_C = "fog-c"
        const val SRC_EDGE = "fog-edge"; const val SRC_ROADS = "explored"; const val SRC_BLDG = "bldg"
        const val SRC_BLDG_NEW = "bldg-new"; const val SRC_FLASH = "reveal-flash"; const val SRC_FRIENDS = "friends"
        const val L_FOG_A = "fog-a"; const val L_FOG_B = "fog-b"; const val L_FOG_C = "fog-c"; const val L_FOG_CLOUDS = "fog-clouds"
        const val L_EDGE_GLOW = "fog-edge-glow"; const val L_EDGE = "fog-edge"; const val L_ROADS_GLOW = "explored-glow"
        const val L_ROADS = "explored"; const val L_BLDG = "bldg-3d"; const val L_BLDG_NEW = "bldg-3d-new"
        const val L_DISSOLVE = "reveal-dissolve"; const val L_DISSOLVE_CLOUDS = "reveal-dissolve-clouds"; const val L_FLASH = "reveal-flash"
        const val L_FRIENDS = "friends"; const val L_FRIENDS_LABEL = "friends-label"
        const val IMG_PUCK = "me-arrow"; const val SRC_ME = "me"; const val L_ME = "me-arrow"; const val L_ME_GLOW = "me-glow"
    }
}
