package de.foggymap.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.foggymap.app.App
import de.foggymap.app.MainActivity
import de.foggymap.app.R
import de.foggymap.app.core.LatLon
import de.foggymap.app.core.TravelMode
import de.foggymap.app.data.RevealDatabase
import de.foggymap.app.tracking.TrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Sheet { NONE, MENU }

@Composable
fun FoggyRoot(activity: MainActivity) {
    var onboarded by remember { mutableStateOf(App.prefs.onboardingDone) }
    if (!onboarded) OnboardingScreen { App.prefs.onboardingDone = true; onboarded = true }
    else Box(Modifier.fillMaxSize()) {
        MainScreen(activity)
        BadgeCelebrationHost()
        CrashPrompt()
    }
}

@SuppressLint("MissingPermission")
@Composable
fun MainScreen(activity: MainActivity) {
    val context = LocalContext.current
    val tracking by App.tracking.collectAsState()
    val syncUi by App.sync.state.collectAsState()
    val dbVersion by App.dbVersion.collectAsState()
    val location by App.location.collectAsState()

    var controller by remember { mutableStateOf<FogMapController?>(null) }
    /** Map fixed on you with your viewing direction up (the location button). */
    var following by remember { mutableStateOf(false) }
    /** The user moved the map away by hand – do not grab it back automatically. */
    var releasedByUser by remember { mutableStateOf(false) }
    val perspective = App.prefs.perspective
    /** 2D/3D and north-up are buttons on the map now (no detour through the menu). */
    var tilted by remember { mutableStateOf(App.prefs.perspective) }
    var northUp by remember { mutableStateOf(App.prefs.northUp) }
    var mapBearing by remember { mutableFloatStateOf(0f) }
    var sheet by remember { mutableStateOf(if (activity.pendingInvite != null) Sheet.MENU else Sheet.NONE) }
    var menuTab by remember { mutableStateOf(if (activity.pendingInvite != null) MenuTab.COOP else MenuTab.INFO) }
    // An invitation shared into the app opens the Koop tab.
    LaunchedEffect(activity.pendingInvite) { if (activity.pendingInvite != null) { menuTab = MenuTab.COOP; sheet = Sheet.MENU } }
    var permissionProblem by remember { mutableStateOf<String?>(null) }
    var locationEnabled by remember { mutableStateOf(false) }

    // ------------------------------------------------------------------ permissions → start
    fun startTracking() {
        TrackingService.start(context)
        releasedByUser = false
    }
    // Activity recognition (once, with a short explanation first): lets the GPS switch off while you stand.
    var explainMotion by remember { mutableStateOf(false) }
    val motionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { startTracking() }
    fun askMotionThenStart() {
        if (!App.prefs.motionAsked && de.foggymap.app.tracking.Motion.canAsk(context)) { App.prefs.motionAsked = true; explainMotion = true }
        else startTracking()
    }
    if (explainMotion) androidx.compose.material3.AlertDialog(
        onDismissRequest = { explainMotion = false; startTracking() },
        title = { Text("Akku sparen wie Google Maps") },
        text = { Text("Wenn du gleich „Körperliche Aktivität“ erlaubst, merkt dein Handy selbst, ob du stehst, gehst, Rad oder Auto fährst. " +
            "Dann schaltet FoggyMap das GPS beim Stehen ganz aus und sofort wieder ein, wenn du losgehst. Rad und Auto werden besser erkannt. " +
            "Das läuft auf dem stromsparenden Sensor-Chip und bleibt auf deinem Handy.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = {
            explainMotion = false
            if (Build.VERSION.SDK_INT >= 29) motionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION) else startTracking()
        }) { Text("Weiter") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { explainMotion = false; startTracking() }) { Text("Ohne") } },
    )
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { askMotionThenStart() }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        when {
            MainActivity.hasFineLocation(context) -> {
                locationEnabled = controller?.enableLocation() == true
                if (Build.VERSION.SDK_INT >= 33 && !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS))
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else askMotionThenStart()
            }
            hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) -> permissionProblem =
                "Du hast nur den ungefähren Standort erlaubt. FoggyMap braucht den genauen Standort, um zu erkennen, auf welcher Straße du gehst. " +
                    "Bitte in den App-Einstellungen unter Berechtigungen → Standort „Genauen Standort verwenden“ aktivieren."
            else -> permissionProblem =
                "Ohne Standortzugriff kann FoggyMap nichts aufdecken. Du kannst die Berechtigung jederzeit in den App-Einstellungen erteilen " +
                    "(„Nur während der Nutzung der App“ genügt – die Aufzeichnung läuft als sichtbarer Dienst mit Benachrichtigung weiter)."
        }
    }
    fun onStartClicked() {
        if (tracking.active) { TrackingService.stop(context); return }
        val lm = context.getSystemService(LocationManager::class.java)
        if (!androidx.core.location.LocationManagerCompat.isLocationEnabled(lm)) { permissionProblem = "Der Standort ist auf deinem Handy ausgeschaltet. Bitte in den Schnelleinstellungen „Standort“ einschalten."; return }
        if (MainActivity.hasFineLocation(context)) {
            if (Build.VERSION.SDK_INT >= 33 && !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS))
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else askMotionThenStart()
        } else locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    // ------------------------------------------------------------------ map wiring
    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        c.animationsEnabled = App.prefs.fogAnimation
        c.onTrackingDismissed = { following = false; releasedByUser = true }
        c.onBearingChanged = { mapBearing = it }
        c.setNorthUp(App.prefs.northUp, animate = false)
        if (MainActivity.hasFineLocation(context)) locationEnabled = c.enableLocation()
        val last = App.location.value ?: if (MainActivity.hasFineLocation(context)) lastKnown(context) else null
        last?.let { App.location.value = it }
        val start = last?.let { LatLon(it.latitude, it.longitude) }
            ?: App.prefs.lastLat.takeIf { !it.isNaN() }?.let { LatLon(it, App.prefs.lastLon) }
            ?: withContext(Dispatchers.IO) { App.db.lastPosition() }?.let { LatLon(it.first, it.second) }
        if (start != null) c.moveTo(start, 16.6, perspective, animate = false)
        else c.moveTo(LatLon(51.1657, 10.4515), 5.0, false, animate = false)
        // Prepare the street data where you are now, so exploring starts without waiting.
        if (start != null) App.scope.launch { App.tiles.load(de.foggymap.app.core.CellKey.of(start)) }
        c.requestRefresh(start)
    }
    LaunchedEffect(controller, dbVersion) { controller?.requestRefresh(location?.let { LatLon(it.latitude, it.longitude) }) }
    // Capture the instance: reading the state inside onDispose would return the *new* controller
    // and shut down the fog updates right after the map became ready.
    val currentController = controller
    DisposableEffect(currentController) { onDispose { currentController?.dispose() } }
    if (de.foggymap.app.BuildConfig.DEBUG) LaunchedEffect(controller) {
        while (true) { kotlinx.coroutines.delay(10_000); controller?.let { android.util.Log.i("FoggyMap", "ui: " + it.debugState()) } }
    }
    LaunchedEffect(controller, location, locationEnabled) { location?.let { controller?.updateLocation(it) } }
    val manualMode by App.manualMode.collectAsState()
    // While standing (traffic light, station) the figure keeps the last way of moving.
    var lastMoving by remember { mutableStateOf(de.foggymap.app.core.TravelMode.WALK) }
    var showModePicker by remember { mutableStateOf(false) }
    LaunchedEffect(tracking.mode) { if (tracking.mode != de.foggymap.app.core.TravelMode.STILL) lastMoving = tracking.mode }
    LaunchedEffect(controller, manualMode, lastMoving) { controller?.setMyMode(manualMode ?: lastMoving) }
    // While exploring, the map follows you until you move it away by hand.
    LaunchedEffect(controller, tracking.active, location != null) {
        val c = controller ?: return@LaunchedEffect
        if (tracking.active && location != null && !following && !releasedByUser) { c.startFollowing(App.prefs.perspective); following = true }
    }
    LaunchedEffect(controller) { val c = controller ?: return@LaunchedEffect; App.trail.collect { c.updateTrail(it) } }
    // Viewing direction (compass) and – when not exploring – your position, only while the map is visible.
    val compass = remember { Compass(context) { az -> controller?.updateCompass(az) } }
    LaunchedEffect(location) { location?.let { compass.setLocation(it.latitude, it.longitude, it.altitude) } }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, tracking.active, following, locationEnabled) {
        val lm = context.getSystemService(LocationManager::class.java)
        val listener = object : androidx.core.location.LocationListenerCompat {
            override fun onLocationChanged(location: android.location.Location) { if (!App.tracking.value.active) App.showLocation(location) }
        }
        var registered = false
        fun startUiLocation() {
            if (tracking.active || !following || registered || !MainActivity.hasFineLocation(context)) return
            val providers = buildList {
                if (Build.VERSION.SDK_INT >= 31 && lm.allProviders.contains(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
                else { add(LocationManager.GPS_PROVIDER); add(LocationManager.NETWORK_PROVIDER) }
            }
            // High accuracy (satellites), not the default Wi-Fi/cell quality – the figure should stand where you stand.
            val request = androidx.core.location.LocationRequestCompat.Builder(2000L)
                .setQuality(androidx.core.location.LocationRequestCompat.QUALITY_HIGH_ACCURACY).setMinUpdateDistanceMeters(0f).build()
            for (pr in providers) runCatching {
                androidx.core.location.LocationManagerCompat.requestLocationUpdates(lm, pr, request, androidx.core.content.ContextCompat.getMainExecutor(context), listener)
                registered = true
            }
        }
        fun stopUiLocation() { if (registered) { runCatching { androidx.core.location.LocationManagerCompat.removeUpdates(lm, listener) }; registered = false } }
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> {
                    compass.start(); startUiLocation()
                    // Where am I right now? One accurate fix as soon as the app is open – no need to walk first.
                    if (!App.tracking.value.active && MainActivity.hasFineLocation(context))
                        de.foggymap.app.tracking.LocationController.currentLocation(context) { loc -> if (!App.tracking.value.active) App.showLocation(loc) }
                }
                Lifecycle.Event.ON_PAUSE -> { compass.stop(); stopUiLocation() }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs); compass.stop(); stopUiLocation() }
    }
    val liveFriends by App.live.friends.collectAsState()
    LaunchedEffect(controller, syncUi.members, liveFriends) { controller?.showFriends(syncUi.members, liveFriends) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, currentController) {
        val c = currentController
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                // Friends' live positions only while the map is on screen.
                Lifecycle.Event.ON_RESUME -> { c?.setOnScreen(true); App.sync.requestSync(1_000); App.live.setListening(App.sync.state.value.inGroup) }
                Lifecycle.Event.ON_PAUSE -> { c?.setOnScreen(false); App.live.setListening(false) }
                else -> {}
            }
        }
        lifecycle.addObserver(obs)   // replays ON_RESUME if we are already resumed
        onDispose { lifecycle.removeObserver(obs); c?.setOnScreen(false) }
    }

    val density = LocalDensity.current
    val topInset = WindowInsets.statusBars.getTop(density) + with(density) { 70.dp.roundToPx() }

    Box(Modifier.fillMaxSize().background(Fog.Bg)) {
        FogMapView(Modifier.fillMaxSize(), topInset) { controller = it }

        MapControls(tracking, if (syncUi.inGroup) maxOf(syncUi.members.size, 1) else 0, mapBearing, northUp, tilted, following,
            onMenu = { menuTab = MenuTab.INFO; sheet = Sheet.MENU },
            onCompass = {
                northUp = !northUp
                App.prefs.northUp = northUp
                controller?.setNorthUp(northUp)
            },
            onPerspective = {
                tilted = !tilted
                App.prefs.perspective = tilted
                controller?.setPerspective(tilted)
            },
            onLocate = {
                releasedByUser = false
                if (!MainActivity.hasFineLocation(context)) {
                    locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                } else {
                    following = true
                    controller?.startFollowing(App.prefs.perspective)
                }
            },
            onStartStop = { onStartClicked() },
            mode = manualMode ?: lastMoving, modeByHand = manualMode != null, onMode = { showModePicker = true })
    }
    if (showModePicker) ModePicker(manualMode, onPick = { m ->
        App.manualMode.value = m; App.prefs.manualMode = m?.name ?: ""
        showModePicker = false
    }) { showModePicker = false }

    when (sheet) {
        Sheet.MENU -> MenuSheet(syncUi, activity.pendingInvite, onInviteUsed = { activity.pendingInvite = null }, initialTab = menuTab,
            onChanged = {
                controller?.setBuildingsVisible(App.prefs.buildings3d)
                controller?.animationsEnabled = App.prefs.fogAnimation
                controller?.animationsChanged()
                TrackingService.settingsChanged(context)
            },
            onDataCleared = { activity.recreate() },
        ) { sheet = Sheet.NONE }
        Sheet.NONE -> {}
    }

    UpdatePrompt()

    permissionProblem?.let { msg ->
        AlertDialog(
            onDismissRequest = { permissionProblem = null },
            containerColor = Fog.Surface, titleContentColor = Fog.Text, textContentColor = Fog.Text.copy(alpha = 0.85f),
            title = { Text("Standort benötigt") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = {
                    permissionProblem = null
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }) { Text("Einstellungen öffnen", color = Fog.Violet2) }
            },
            dismissButton = { TextButton(onClick = { permissionProblem = null }) { Text("Später", color = Fog.Muted) } },
        )
    }
}

/**
 * Everything on top of the map. Kept small on purpose: menu, start/stop and three map buttons
 * (compass = north up, 2D/3D, follow me). Statistics and GPS details live in Menü → Info.
 */
@Composable
internal fun MapControls(tracking: de.foggymap.app.TrackingUi, groupSize: Int, mapBearing: Float, northUp: Boolean, tilted: Boolean,
                         following: Boolean, onMenu: () -> Unit, onCompass: () -> Unit, onPerspective: () -> Unit,
                         onLocate: () -> Unit, onStartStop: () -> Unit,
                         mode: TravelMode = TravelMode.WALK, modeByHand: Boolean = false, onMode: () -> Unit = {}) {
    Box(Modifier.fillMaxSize()) {
        // Top scrim + bar
        Box(Modifier.fillMaxWidth().height(130.dp).background(Brush.verticalGradient(listOf(Fog.Bg.copy(alpha = 0.92f), Color.Transparent))))
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_logo), null, tint = Color.Unspecified, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(8.dp))
                LogoTitle(22)
                Spacer(Modifier.weight(1f))
                MenuButton(groupSize, onMenu)
            }
            // Only warnings stay on the map (no GPS signal, permission missing …).
            AnimatedVisibility(tracking.message != null, enter = fadeIn() + slideInVertically(), exit = fadeOut() + slideOutVertically()) {
                WarningChip(tracking.message ?: "")
            }
            RevealBanner(tracking)
        }

        // Bottom: start/stop in the middle, map buttons on the right.
        // The start button gets the space left of the map buttons, so nothing overlaps on narrow phones.
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f).padding(start = 46.dp), contentAlignment = Alignment.BottomCenter) {
                StartStopButton(tracking.active, Modifier, onStartStop)
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeButton(mode, modeByHand, onMode)
                CompassButton(mapBearing, northUp, onCompass)
                PerspectiveButton(tilted, onPerspective)
                LocateButton(following, onLocate)
            }
        }
    }
}

/** Opens the one menu (Info · Erkunden · Koop · App). Shows the group size when you are in a group. */
@Composable
internal fun MenuButton(groupSize: Int, onClick: () -> Unit) {
    Row(Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(Fog.Surface.copy(alpha = 0.9f))
        .border(1.dp, Fog.Line, RoundedCornerShape(22.dp)).clickable(onClick = onClick).padding(start = 14.dp, end = if (groupSize > 0) 6.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(rememberVectorPainter(Icons.Filled.Menu), "Menü", tint = Fog.Lavender, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text("Menü", color = Fog.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (groupSize > 0) {
            Spacer(Modifier.width(8.dp))
            Row(Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(Fog.button).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_group), null, tint = Color.White, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("$groupSize", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** One button: fix the map on your position, viewing direction up. Filled while it is active. */
@Composable
internal fun LocateButton(active: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(54.dp).clip(CircleShape)
        .background(if (active) Fog.button else Brush.linearGradient(listOf(Fog.Surface.copy(alpha = 0.92f), Fog.Surface.copy(alpha = 0.92f))))
        .border(1.dp, if (active) Color.White.copy(alpha = 0.25f) else Fog.Line, CircleShape)
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_navigation), if (active) "Karte folgt dir" else "Auf meinen Standort",
            tint = if (active) Color.White else Fog.Lavender, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun MapButton(active: Boolean, description: String, size: Int = 46, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(size.dp).clip(CircleShape)
        .background(if (active) Fog.button else Brush.linearGradient(listOf(Fog.Surface.copy(alpha = 0.92f), Fog.Surface.copy(alpha = 0.92f))))
        .border(1.dp, if (active) Color.White.copy(alpha = 0.25f) else Fog.Line, CircleShape)
        .semantics { contentDescription = description }
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) { content() }
}

/**
 * Small compass: the needle always points to north on the map. Tap = north up (also while the map
 * follows you); tap again = your viewing direction up. Highlighted while north is locked.
 */
@Composable
internal fun CompassButton(mapBearing: Float, northUp: Boolean, onClick: () -> Unit) {
    MapButton(northUp, if (northUp) "Norden oben – tippen für Blickrichtung oben" else "Karte nach Norden ausrichten", onClick = onClick) {
        val north = if (northUp) Color.White else Color(0xFFFF6B6B)
        val south = if (northUp) Color.White.copy(alpha = 0.45f) else Fog.Muted
        androidx.compose.foundation.Canvas(Modifier.size(26.dp).graphicsLayer { rotationZ = -mapBearing }) {
            val cx = size.width / 2; val cy = size.height / 2; val w = size.width * 0.2f; val h = size.height * 0.46f
            drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(cx, cy - h); lineTo(cx + w, cy); lineTo(cx - w, cy); close() }, north)
            drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(cx, cy + h); lineTo(cx + w, cy); lineTo(cx - w, cy); close() }, south)
            drawCircle(Fog.Bg, radius = w * 0.45f, center = androidx.compose.ui.geometry.Offset(cx, cy))
        }
    }
}

/** How you move (walking, bike, car, train): shows the current one, "A" = recognised automatically. Tap = choose. */
@Composable
internal fun ModeButton(mode: TravelMode, byHand: Boolean, onClick: () -> Unit) {
    MapButton(byHand, "Fortbewegung: ${modeLabel(mode)}${if (byHand) " (gewählt)" else " (automatisch)"}", onClick = onClick) {
        Box {
            Icon(painterResource(modeIcon(mode)), null, tint = if (byHand) Color.White else Fog.Lavender, modifier = Modifier.size(22.dp))
            if (!byHand) Text("A", color = Fog.Bg, fontSize = 8.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 5.dp, y = 4.dp).clip(CircleShape).background(Fog.Lavender).padding(horizontal = 3.dp))
        }
    }
}

/** Choose how you move – or let FoggyMap recognise it. Valid until exploring ends. */
@Composable
internal fun ModePicker(current: TravelMode?, onPick: (TravelMode?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Fog.Surface, titleContentColor = Fog.Text, textContentColor = Fog.Text,
        title = { Text("Wie bist du unterwegs?") },
        text = { ModeOptions(current, onPick) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig", color = Fog.Violet2) } },
    )
}

@Composable
internal fun ModeOptions(current: TravelMode?, onPick: (TravelMode?) -> Unit) {
    val options = listOf<Triple<TravelMode?, String, String>>(
        Triple(null, "Automatisch", "Erkennt Gehen, Rad, Auto und Zug selbst"),
        Triple(TravelMode.WALK, "Zu Fuß", "Gehen und Laufen"),
        Triple(TravelMode.BIKE, "Fahrrad", ""),
        Triple(TravelMode.CAR, "Auto", "Nur Straßen, auf denen Autos fahren"),
        Triple(TravelMode.TRAIN, "Zug", "Unterwegs wird nichts aufgedeckt"),
    )
            Column {
                for ((m, title, sub) in options) {
                    val sel = m == current
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (sel) Fog.SurfaceHigh else Color.Transparent)
                        .border(1.dp, if (sel) Fog.Violet else Color.Transparent, RoundedCornerShape(14.dp))
                        .clickable { onPick(m) }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(if (m == null) R.drawable.ic_auto_mode else modeIcon(m)), null, tint = Fog.Lavender, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Fog.Text)
                            if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = Fog.Muted)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Text("Gilt bis zum Ende der Erkundung.", fontSize = 11.sp, color = Fog.Muted, modifier = Modifier.padding(top = 4.dp))
            }
}

/** Switches between the tilted 3D view and the flat 2D map. Shows what a tap switches to. */
@Composable
internal fun PerspectiveButton(tilted: Boolean, onClick: () -> Unit) {
    MapButton(false, if (tilted) "Zur 2D-Ansicht wechseln" else "Zur 3D-Ansicht wechseln", onClick = onClick) {
        Text(if (tilted) "2D" else "3D", color = Fog.Lavender, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/** Start exploring (big button) – while it runs only a small "Beenden" pill with a recording dot. */
@Composable
internal fun StartStopButton(active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // Both states have exactly the same size and place, so the button never jumps.
    val shape = RoundedCornerShape(18.dp)
    if (!active) {
        PrimaryButton("Erkundung starten", modifier.widthIn(max = 240.dp).fillMaxWidth(), icon = rememberVectorPainter(Icons.Filled.PlayArrow), onClick = onClick)
    } else {
        Row(modifier.widthIn(max = 240.dp).fillMaxWidth().height(54.dp).clip(shape).background(Fog.Surface.copy(alpha = 0.95f))
            .border(1.dp, Fog.Danger.copy(alpha = 0.45f), shape).clickable(onClick = onClick).padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_stop), null, tint = Fog.Danger, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Erkundung beenden", color = Fog.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = 0.5.sp)
        }
    }
}

/** A problem worth seeing on the map (no permission, no signal …). */
@Composable
internal fun WarningChip(text: String) {
    Text(text, color = Fog.Amber, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(12.dp))
        .background(Fog.Surface.copy(alpha = 0.9f)).padding(horizontal = 12.dp, vertical = 6.dp))
}

/** Short banner whenever a new street section is uncovered. */
@Composable
internal fun RevealBanner(t: de.foggymap.app.TrackingUi) {
    var shown by remember { mutableStateOf<String?>(null) }
    var lastCount by remember { mutableStateOf(t.sessionSegments) }
    LaunchedEffect(t.sessionSegments) {
        if (t.sessionSegments > lastCount) {
            shown = t.lastRevealed
            lastCount = t.sessionSegments
            kotlinx.coroutines.delay(3200)
            shown = null
        } else lastCount = t.sessionSegments
    }
    AnimatedVisibility(shown != null, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
        Row(Modifier.padding(top = 10.dp).clip(RoundedCornerShape(18.dp)).background(Fog.button)
            .padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_road), null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Neuer Abschnitt entdeckt", color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp, letterSpacing = 0.8.sp)
                Text(shown ?: "", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

fun modeLabel(m: TravelMode) = when (m) {
    TravelMode.STILL -> "steht"
    TravelMode.WALK -> "zu Fuß"
    TravelMode.BIKE -> "Rad"
    TravelMode.CAR -> "Auto"
    TravelMode.TRAIN -> "Zug"
}

fun modeIcon(m: TravelMode?) = when (m) {
    TravelMode.CAR -> R.drawable.ic_car
    TravelMode.BIKE -> R.drawable.ic_bike
    TravelMode.TRAIN -> R.drawable.ic_train
    else -> R.drawable.ic_walk
}

fun hasPermission(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
fun lastKnown(c: Context): android.location.Location? = try {
    val lm = c.getSystemService(LocationManager::class.java)
    listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
} catch (_: Exception) { null }
