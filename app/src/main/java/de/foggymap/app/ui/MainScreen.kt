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
    var follow by remember { mutableStateOf(FogMapController.Follow.FOLLOW) }
    var perspective by remember { mutableStateOf(App.prefs.perspective) }
    var sheet by remember { mutableStateOf(if (activity.pendingInvite != null) Sheet.GROUP else Sheet.NONE) }
    var permissionProblem by remember { mutableStateOf<String?>(null) }
    var locationEnabled by remember { mutableStateOf(false) }

    // ------------------------------------------------------------------ permissions → start
    fun startTracking() {
        TrackingService.start(context)
        follow = FogMapController.Follow.FOLLOW
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
        c.onTrackingDismissed = { follow = FogMapController.Follow.FREE }
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
    DisposableEffect(controller) { onDispose { controller?.dispose() } }
    if (de.foggymap.app.BuildConfig.DEBUG) LaunchedEffect(controller) {
        while (true) { kotlinx.coroutines.delay(10_000); controller?.let { android.util.Log.i("FoggyMap", "ui: " + it.debugState()) } }
    }
    LaunchedEffect(controller, location, locationEnabled) { location?.let { controller?.updateLocation(it) } }
    LaunchedEffect(controller, tracking.active, follow, locationEnabled, location != null) {
        val c = controller ?: return@LaunchedEffect
        if (tracking.active && location != null && locationEnabled) c.setFollow(follow, perspective)
    }
    LaunchedEffect(controller, syncUi.members) { controller?.showFriends(syncUi.members) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, controller) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> { controller?.startAmbient(); App.sync.requestSync(1_000) }
                Lifecycle.Event.ON_PAUSE -> controller?.stopAmbient()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); controller?.stopAmbient() }
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
                StatusPill(tracking)
            }
        }

        // Right-hand map controls (as in the design: layers · locate · navigation)
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundButton(painterResource(R.drawable.ic_layers), "3D-Ansicht", active = perspective) {
                perspective = !perspective
                App.prefs.perspective = perspective
                controller?.setPerspective(perspective)
            }
            RoundButton(painterResource(R.drawable.ic_crosshair), "Auf mich zentrieren", active = follow == FogMapController.Follow.NORTH) {
                val loc = location
                if (loc == null) { onStartClicked(); return@RoundButton }
                follow = FogMapController.Follow.NORTH
                if (locationEnabled) controller?.setFollow(follow, perspective)
                else controller?.moveTo(LatLon(loc.latitude, loc.longitude), 17.0, perspective)
            }
            RoundButton(painterResource(R.drawable.ic_navigation), "Mitfahren", active = follow == FogMapController.Follow.FOLLOW && tracking.active) {
                follow = FogMapController.Follow.FOLLOW
                if (locationEnabled) controller?.setFollow(follow, perspective)
            }
        }

        // Bottom card
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)) {
            BottomCard(coverage?.percent, counts, tracking.active, onExpand = { sheet = Sheet.STATS }, onStart = { onStartClicked() })
        }
    }

    when (sheet) {
        Sheet.STATS -> StatsSheet(counts, coverage, syncUi) { sheet = Sheet.NONE }
        Sheet.GROUP -> GroupSheet(syncUi, activity.pendingInvite, onInviteUsed = { activity.pendingInvite = null }) { sheet = Sheet.NONE }
        Sheet.SETTINGS -> SettingsSheet(
            onChanged = {
                controller?.setBuildingsVisible(App.prefs.buildings3d)
                controller?.animationsEnabled = App.prefs.fogAnimation
                if (!App.prefs.fogAnimation) controller?.stopAmbient() else controller?.startAmbient()
                TrackingService.settingsChanged(context)
            },
            onDataCleared = { activity.recreate() },
        ) { sheet = Sheet.NONE }
        Sheet.NONE -> {}
    }

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
private fun StatusPill(t: de.foggymap.app.TrackingUi) {
    val acc = t.accuracy
    val dot = when {
        !t.active -> Fog.Muted
        acc == null -> Fog.Amber
        acc <= 15f -> Fog.Success
        acc <= 35f -> Fog.Amber
        else -> Fog.Danger
    }
    val text = buildList {
        if (t.active) add(if (acc == null) "Suche GPS …" else "GPS ±${acc.toInt()} m")
        if (t.active && acc != null) add(modeLabel(t.mode))
        if (t.powerProfile.isNotEmpty() && t.active) add(t.powerProfile)
        if (t.loadingMap) add("lade Straßen …")
    }.joinToString(" · ")
    Column(Modifier.padding(top = 8.dp)) {
        Row(Modifier.clip(RoundedCornerShape(14.dp)).background(Fog.Surface.copy(alpha = 0.88f)).padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
            Text(text.ifEmpty { "Bereit" }, color = Fog.Text, fontSize = 12.sp)
        }
        t.message?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = Fog.Amber, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp))
                .background(Fog.Surface.copy(alpha = 0.88f)).padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}

fun modeLabel(m: TravelMode) = when (m) {
    TravelMode.STILL -> "steht"
    TravelMode.WALK -> "zu Fuß"
    TravelMode.BIKE -> "Rad"
    TravelMode.CAR -> "Auto"
}

@Composable
private fun BottomCard(percent: Double?, counts: RevealDatabase.Counts?, active: Boolean, onExpand: () -> Unit, onStart: () -> Unit) {
    val animated by animateFloatAsState((percent ?: 0.0).toFloat(), tween(900), label = "pct")
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onExpand)) {
            Column(Modifier.weight(1f)) {
                Text("${animated.toDouble().de(1)} %", color = Fog.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(if (percent == null) "Starte die Erkundung – der Nebel wartet" else "deiner Umgebung erkundet",
                    color = Fog.Muted, fontSize = 13.sp)
            }
            Box(Modifier.size(40.dp).clip(CircleShape).background(Fog.SurfaceHigh), contentAlignment = Alignment.Center) {
                Icon(rememberVectorPainter(Icons.Filled.KeyboardArrowUp), "Statistiken", tint = Fog.Lavender)
            }
        }
        Spacer(Modifier.height(10.dp))
        AccentProgress(animated / 100f)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MiniStat("${(counts?.roadKm ?: 0.0).de(1)} km", "Straßen")
            MiniStat((counts?.roads ?: 0).de(), "Abschnitte")
            MiniStat((counts?.blocks ?: 0).de(), "Blöcke")
            MiniStat((counts?.buildings ?: 0).de(), "Gebäude")
        }
        Spacer(Modifier.height(14.dp))
        PrimaryButton(
            if (active) "Erkundung beenden" else "Erkundung starten",
            Modifier.fillMaxWidth(),
            icon = if (active) painterResource(R.drawable.ic_stop) else rememberVectorPainter(Icons.Filled.PlayArrow),
            secondary = active, onClick = onStart,
        )
    }
}

@Composable
private fun MiniStat(value: String, label: String) {
    Column {
        Text(value, color = Fog.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = Fog.Muted, fontSize = 11.sp)
    }
}

fun hasPermission(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
fun lastKnown(c: Context): android.location.Location? = try {
    val lm = c.getSystemService(LocationManager::class.java)
    listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
} catch (_: Exception) { null }
