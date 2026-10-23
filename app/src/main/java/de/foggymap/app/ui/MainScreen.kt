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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.withContext

enum class Sheet { NONE, STATS, GROUP, SETTINGS }

@Composable
fun FoggyRoot(activity: MainActivity) {
    var onboarded by remember { mutableStateOf(App.prefs.onboardingDone) }
    if (!onboarded) OnboardingScreen { App.prefs.onboardingDone = true; onboarded = true }
    else MainScreen(activity)
}

@SuppressLint("MissingPermission")
@Composable
fun MainScreen(activity: MainActivity) {
    val context = LocalContext.current
    val tracking by App.tracking.collectAsState()
    val coverage by App.coverage.collectAsState()
    val syncUi by App.sync.state.collectAsState()
    val dbVersion by App.dbVersion.collectAsState()
    val location by App.location.collectAsState()
    val counts by produceState<RevealDatabase.Counts?>(null, dbVersion) { value = withContext(Dispatchers.IO) { App.db.counts() } }

    var controller by remember { mutableStateOf<FogMapController?>(null) }
    /** Map fixed on you with your viewing direction up (the location button). */
    var following by remember { mutableStateOf(false) }
    /** The user moved the map away by hand – do not grab it back automatically. */
    var releasedByUser by remember { mutableStateOf(false) }
    val perspective = App.prefs.perspective
    var sheet by remember { mutableStateOf(if (activity.pendingInvite != null) Sheet.GROUP else Sheet.NONE) }
    var permissionProblem by remember { mutableStateOf<String?>(null) }
    var locationEnabled by remember { mutableStateOf(false) }

    // ------------------------------------------------------------------ permissions → start
    fun startTracking() {
        TrackingService.start(context)
        releasedByUser = false
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { startTracking() }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        when {
            MainActivity.hasFineLocation(context) -> {
                locationEnabled = controller?.enableLocation() == true
                if (Build.VERSION.SDK_INT >= 33 && !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS))
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else startTracking()
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
            else startTracking()
        } else locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    // ------------------------------------------------------------------ map wiring
    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        c.animationsEnabled = App.prefs.fogAnimation
        c.onTrackingDismissed = { following = false; releasedByUser = true }
        if (MainActivity.hasFineLocation(context)) locationEnabled = c.enableLocation()
        val last = App.location.value ?: if (MainActivity.hasFineLocation(context)) lastKnown(context) else null
        last?.let { App.location.value = it }
        val start = last?.let { LatLon(it.latitude, it.longitude) }
            ?: App.prefs.lastLat.takeIf { !it.isNaN() }?.let { LatLon(it, App.prefs.lastLon) }
            ?: withContext(Dispatchers.IO) { App.db.lastPosition() }?.let { LatLon(it.first, it.second) }
        if (start != null) c.moveTo(start, 16.6, perspective, animate = false)
        else c.moveTo(LatLon(51.1657, 10.4515), 5.0, false, animate = false)
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
        val listener = android.location.LocationListener { loc -> if (!App.tracking.value.active) App.location.value = loc }
        var registered = false
        fun startUiLocation() {
            if (tracking.active || !following || registered || !MainActivity.hasFineLocation(context)) return
            val providers = buildList {
                if (Build.VERSION.SDK_INT >= 31 && lm.allProviders.contains(LocationManager.FUSED_PROVIDER)) add(LocationManager.FUSED_PROVIDER)
                else { add(LocationManager.GPS_PROVIDER); add(LocationManager.NETWORK_PROVIDER) }
            }
            for (pr in providers) runCatching { lm.requestLocationUpdates(pr, 1000L, 0f, listener, android.os.Looper.getMainLooper()); registered = true }
        }
        fun stopUiLocation() { if (registered) { runCatching { lm.removeUpdates(listener) }; registered = false } }
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> { compass.start(); startUiLocation() }
                Lifecycle.Event.ON_PAUSE -> { compass.stop(); stopUiLocation() }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs); compass.stop(); stopUiLocation() }
    }
    LaunchedEffect(controller, syncUi.members) { controller?.showFriends(syncUi.members) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, currentController) {
        val c = currentController
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> { c?.startAmbient(); App.sync.requestSync(1_000) }
                Lifecycle.Event.ON_PAUSE -> c?.stopAmbient()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)   // replays ON_RESUME if we are already resumed
        onDispose { lifecycle.removeObserver(obs); c?.stopAmbient() }
    }

    val density = LocalDensity.current
    val topInset = WindowInsets.statusBars.getTop(density) + with(density) { 70.dp.roundToPx() }

    Box(Modifier.fillMaxSize().background(Fog.Bg)) {
        FogMapView(Modifier.fillMaxSize(), topInset) { controller = it }

        // Top scrim + bar
        Box(Modifier.fillMaxWidth().height(150.dp).background(Brush.verticalGradient(listOf(Fog.Bg.copy(alpha = 0.92f), Color.Transparent))))
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_logo), null, tint = Color.Unspecified, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(8.dp))
                LogoTitle(22)
                Spacer(Modifier.weight(1f))
                TopChip(painterResource(R.drawable.ic_group), if (syncUi.inGroup) "${maxOf(syncUi.members.size, 1)}" else "Koop",
                    active = syncUi.inGroup) { sheet = Sheet.GROUP }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(42.dp).clip(CircleShape).background(Fog.Surface.copy(alpha = 0.85f)).clickable { sheet = Sheet.SETTINGS },
                    contentAlignment = Alignment.Center) {
                    Icon(rememberVectorPainter(Icons.Filled.Settings), "Einstellungen", tint = Fog.Lavender, modifier = Modifier.size(22.dp))
                }
            }
            AnimatedVisibility(tracking.active || tracking.message != null, enter = fadeIn() + slideInVertically(), exit = fadeOut() + slideOutVertically()) {
                StatusChips(tracking)
            }
            RevealBanner(tracking)
        }

        // Bottom: the location button sits right above the card.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.End) {
                LocateButton(following) {
                    releasedByUser = false
                    if (!MainActivity.hasFineLocation(context)) {
                        locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        return@LocateButton
                    }
                    following = true
                    controller?.startFollowing(App.prefs.perspective)
                }
            }
            BottomCard(coverage?.percent, counts, tracking, onExpand = { sheet = Sheet.STATS }, onStart = { onStartClicked() })
        }
    }

    when (sheet) {
        Sheet.STATS -> StatsSheet(counts, coverage, syncUi) { sheet = Sheet.NONE }
        Sheet.GROUP -> GroupSheet(syncUi, activity.pendingInvite, onInviteUsed = { activity.pendingInvite = null }) { sheet = Sheet.NONE }
        Sheet.SETTINGS -> SettingsSheet(
            onChanged = {
                controller?.setBuildingsVisible(App.prefs.buildings3d)
                controller?.setPerspective(App.prefs.perspective)
                controller?.animationsEnabled = App.prefs.fogAnimation
                if (!App.prefs.fogAnimation) controller?.stopAmbient() else controller?.startAmbient()
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

/** One button: fix the map on your position, viewing direction up. Filled while it is active. */
@Composable
private fun LocateButton(active: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(54.dp).clip(CircleShape)
        .background(if (active) Fog.button else Brush.linearGradient(listOf(Fog.Surface.copy(alpha = 0.92f), Fog.Surface.copy(alpha = 0.92f))))
        .border(1.dp, if (active) Color.White.copy(alpha = 0.25f) else Fog.Line, CircleShape)
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_navigation), if (active) "Karte folgt dir" else "Auf meinen Standort",
            tint = if (active) Color.White else Fog.Lavender, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun TopChip(painter: androidx.compose.ui.graphics.painter.Painter, text: String, active: Boolean, onClick: () -> Unit) {
    Row(Modifier.height(42.dp).clip(RoundedCornerShape(21.dp))
        .background(if (active) Fog.Violet.copy(alpha = 0.85f) else Fog.Surface.copy(alpha = 0.85f))
        .clickable(onClick = onClick).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painter, null, tint = if (active) Color.White else Fog.Lavender, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Chip(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(Fog.Surface.copy(alpha = 0.88f))
        .border(1.dp, Fog.Line, RoundedCornerShape(16.dp)).padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically, content = content)
}

/** Compact status line: GPS quality · travel mode · GPS pause · loading. */
@Composable
private fun StatusChips(t: de.foggymap.app.TrackingUi) {
    val acc = t.accuracy
    val dot = when {
        !t.active -> Fog.Muted
        acc == null -> Fog.Amber
        acc <= 15f -> Fog.Success
        acc <= 35f -> Fog.Amber
        else -> Fog.Danger
    }
    Column(Modifier.padding(top = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (t.active) Chip {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(7.dp))
                Text(if (acc == null) "GPS …" else "±${acc.toInt()} m", color = Fog.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            if (t.active && acc != null) Chip {
                Icon(painterResource(modeIcon(t.mode)), null, tint = Fog.Lavender, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(modeLabel(t.mode), color = Fog.Text, fontSize = 12.sp)
            }
            if (t.active && t.powerProfile.startsWith("GPS-Pause")) Chip {
                Icon(painterResource(R.drawable.ic_battery), null, tint = Fog.Success, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Pause", color = Fog.Text, fontSize = 12.sp)
            }
            if (t.loadingMap) Chip {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(12.dp), color = Fog.Cyan, strokeWidth = 1.6.dp)
                Spacer(Modifier.width(6.dp))
                Text("lade Straßen", color = Fog.Text, fontSize = 12.sp)
            }
        }
        t.message?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = Fog.Amber, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp))
                .background(Fog.Surface.copy(alpha = 0.9f)).padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}

/** Short banner whenever a new street section is uncovered. */
@Composable
private fun RevealBanner(t: de.foggymap.app.TrackingUi) {
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

private fun modeIcon(m: TravelMode) = when (m) {
    TravelMode.CAR -> R.drawable.ic_car
    TravelMode.BIKE -> R.drawable.ic_bike
    else -> R.drawable.ic_walk
}

fun modeLabel(m: TravelMode) = when (m) {
    TravelMode.STILL -> "steht"
    TravelMode.WALK -> "zu Fuß"
    TravelMode.BIKE -> "Rad"
    TravelMode.CAR -> "Auto"
}

@Composable
private fun ProgressRing(percent: Float, modifier: Modifier = Modifier) {
    val sweep = (percent / 100f).coerceIn(0f, 1f) * 360f
    Box(modifier, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            val inset = 3.dp.toPx()
            val arcSize = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(Color.White.copy(alpha = 0.08f), 0f, 360f, false, topLeft, arcSize, style = stroke)
            drawArc(Brush.sweepGradient(listOf(Fog.Cyan, Fog.Violet2, Fog.Violet, Fog.Cyan)), -90f, maxOf(sweep, 4f), false, topLeft, arcSize, style = stroke)
        }
        Text("${percent.toDouble().de(1)}%", color = Fog.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BottomCard(percent: Double?, counts: RevealDatabase.Counts?, t: de.foggymap.app.TrackingUi, onExpand: () -> Unit, onStart: () -> Unit) {
    val animated by animateFloatAsState((percent ?: 0.0).toFloat(), tween(900), label = "pct")
    GlassCard(Modifier.fillMaxWidth(), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onExpand)) {
            ProgressRing(animated, Modifier.size(66.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (percent == null) "Starte die Erkundung" else "deiner Umgebung erkundet", color = Fog.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InlineStat(R.drawable.ic_road, "${(counts?.roadKm ?: 0.0).de(1)} km")
                    InlineStat(R.drawable.ic_blocks, (counts?.blocks ?: 0).de())
                    InlineStat(R.drawable.ic_building, (counts?.buildings ?: 0).de())
                }
            }
            Box(Modifier.size(36.dp).clip(CircleShape).background(Fog.SurfaceHigh), contentAlignment = Alignment.Center) {
                Icon(rememberVectorPainter(Icons.Filled.KeyboardArrowUp), "Statistiken", tint = Fog.Lavender)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (!t.active) {
            PrimaryButton("Erkundung starten", Modifier.fillMaxWidth(), icon = rememberVectorPainter(Icons.Filled.PlayArrow), onClick = onStart)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Fog.Success))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("DIESE TOUR", color = Fog.Violet2, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text("${t.sessionSegments.de()} Abschnitte · ${(t.sessionMeters / 1000).de(2)} km", color = Fog.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(Modifier.height(42.dp).clip(RoundedCornerShape(14.dp)).background(Fog.SurfaceHigh)
                    .border(1.dp, Fog.Danger.copy(alpha = 0.35f), RoundedCornerShape(14.dp)).clickable(onClick = onStart)
                    .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_stop), null, tint = Fog.Danger, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Beenden", color = Fog.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun InlineStat(icon: Int, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, tint = Fog.Lavender, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(value, color = Fog.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

fun hasPermission(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
fun lastKnown(c: Context): android.location.Location? = try {
    val lm = c.getSystemService(LocationManager::class.java)
    listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
} catch (_: Exception) { null }
