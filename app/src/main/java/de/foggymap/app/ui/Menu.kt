package de.foggymap.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.foggymap.app.App
import de.foggymap.app.BuildConfig
import de.foggymap.app.R
import de.foggymap.app.core.Invite
import de.foggymap.app.core.LatLon
import de.foggymap.app.core.RevealMode
import de.foggymap.app.data.SyncUi
import de.foggymap.app.data.TileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MenuTab(val label: String) { INFO("Info"), EXPLORE("Erkunden"), COOP("Koop"), APP("App") }

/** One place for everything: progress and GPS state, how exploring works, the group, and the app itself. */
@Composable
fun MenuSheet(sync: SyncUi, pendingInvite: String?, onInviteUsed: () -> Unit, initialTab: MenuTab,
              onChanged: () -> Unit, onDataCleared: () -> Unit, onDismiss: () -> Unit) {
    FogSheet(onDismiss) { MenuContent(sync, pendingInvite, onInviteUsed, initialTab, onChanged, onDataCleared) }
}

@Composable
internal fun MenuContent(sync: SyncUi, pendingInvite: String?, onInviteUsed: () -> Unit, initialTab: MenuTab,
                         onChanged: () -> Unit, onDataCleared: () -> Unit) {
    var tab by remember { mutableStateOf(initialTab) }
    // All texts light on the dark menu, no matter which container hosts it.
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Fog.Text) { Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoTitle(22)
            Spacer(Modifier.weight(1f))
            Text("Version ${BuildConfig.VERSION_NAME}", color = Fog.Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(14.dp))
        TabBar(tab, sync) { tab = it }
        Spacer(Modifier.height(6.dp))
        // Opening the menu also looks for new badges (at most once a minute).
        LaunchedEffect(Unit) { App.badges.refresh() }
        when (tab) {
            MenuTab.INFO -> InfoTab(sync)
            MenuTab.EXPLORE -> ExploreTab(onChanged)
            MenuTab.COOP -> CoopTab(sync, pendingInvite, onInviteUsed)
            MenuTab.APP -> AppTab(onDataCleared)
        }
    } }
}

@Composable
private fun TabBar(selected: MenuTab, sync: SyncUi, onSelect: (MenuTab) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Fog.Surface).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (t in MenuTab.entries) {
            val active = t == selected
            val icon = when (t) {
                MenuTab.INFO -> rememberVectorPainter(Icons.Filled.Info)
                MenuTab.EXPLORE -> painterResource(R.drawable.ic_compass)
                MenuTab.COOP -> painterResource(R.drawable.ic_group)
                MenuTab.APP -> rememberVectorPainter(Icons.Filled.Settings)
            }
            // Icon above the label: four tabs fit on any phone width. The cell grows with large system fonts
            // and the group size sits beside the icon, so nothing is cut off.
            Column(Modifier.weight(1f).heightIn(min = 58.dp).clip(RoundedCornerShape(14.dp))
                .then(if (active) Modifier.background(Fog.button) else Modifier)
                .clickable { onSelect(t) }.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = if (active) Color.White else Fog.Lavender, modifier = Modifier.size(20.dp))
                    if (t == MenuTab.COOP && sync.inGroup) {
                        Spacer(Modifier.width(4.dp))
                        Text("${maxOf(sync.members.size, 1)}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                .background(if (active) Color.White.copy(alpha = 0.3f) else Fog.Violet).padding(horizontal = 5.dp))
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(t.label, color = if (active) Color.White else Fog.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

// =================================================================== Erkunden

@Composable
private fun ExploreTab(onChanged: () -> Unit) {
    val prefs = App.prefs
    var mode by remember { mutableStateOf(prefs.revealMode) }
    var width by remember { mutableFloatStateOf(prefs.stripWidth.toFloat()) }
    var radius by remember { mutableFloatStateOf(prefs.revealRadius.toFloat()) }
    var b3d by remember { mutableStateOf(prefs.buildings3d) }
    var anim by remember { mutableStateOf(prefs.fogAnimation) }

    SectionTitle("Was wird aufgedeckt?")
    ModeCard(RevealMode.BLOCKS, mode, "Häuserblöcke", "Die Straße bis zur nächsten Kreuzung und die Blöcke links und rechts davon. Ideal in der Stadt.") {
        mode = it; prefs.revealMode = it; onChanged()
    }
    ModeCard(RevealMode.STREET, mode, "Straßenzug", "Die Straße und ein Streifen links und rechts – nie über die nächste Straße hinweg. Ideal im Dorf.") {
        mode = it; prefs.revealMode = it; onChanged()
    }
    ModeCard(RevealMode.RADIUS, mode, "Umkreis", "Alles in einem Radius um deinen Weg – sofort, auch ohne Kartendaten.") {
        mode = it; prefs.revealMode = it; onChanged()
    }
    AnimatedVisibility(mode == RevealMode.STREET) {
        ValueSlider("Breite je Straßenseite", width, 15f..100f, 16, "m") { width = it }
    }
    AnimatedVisibility(mode == RevealMode.RADIUS) {
        ValueSlider("Radius um deinen Weg", radius, 20f..250f, 22, "m") { radius = it }
    }
    // Save when the slider rests for a moment (not on every pixel).
    LaunchedEffect(width) { kotlinx.coroutines.delay(400); if (prefs.stripWidth != width.toInt()) { prefs.stripWidth = width.toInt(); onChanged() } }
    LaunchedEffect(radius) { kotlinx.coroutines.delay(400); if (prefs.revealRadius != radius.toInt()) { prefs.revealRadius = radius.toInt(); onChanged() } }
    Spacer(Modifier.height(4.dp))
    Text("Gilt ab sofort für neue Wege. Bereits Aufgedecktes bleibt sichtbar. Auf jeder Straßenseite ohne Häuserblock (See, Feld, Ortsrand) wird ein Streifen sichtbar.",
        color = Fog.Muted, fontSize = 11.sp, lineHeight = 15.sp)

    SectionTitle("Ansicht")
    SettingsCard {
        SwitchRow("3D-Gebäude", "Häuser mit beleuchteten Fenstern", b3d) { b3d = it; prefs.buildings3d = it; onChanged() }
        SwitchRow("Animationen", "Nebel löst sich auf, Häuser wachsen", anim) { anim = it; prefs.fogAnimation = it; onChanged() }
    }

    SectionTitle("Offline-Karte")
    OfflineCard()
}

@Composable
private fun ModeCard(value: RevealMode, selected: RevealMode, title: String, text: String, onSelect: (RevealMode) -> Unit) {
    val active = value == selected
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp))
        .background(if (active) Fog.SurfaceHigh else Fog.Surface)
        .border(1.5.dp, if (active) Fog.Violet else Fog.Line, RoundedCornerShape(18.dp))
        .clickable { onSelect(value) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        ModeSketch(value, active, Modifier.size(58.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Fog.Text)
            Spacer(Modifier.height(2.dp))
            Text(text, fontSize = 12.sp, color = Fog.Muted, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(22.dp).clip(CircleShape).background(if (active) Fog.Violet else Color.Transparent)
            .border(1.5.dp, if (active) Fog.Violet else Fog.Muted, CircleShape), contentAlignment = Alignment.Center) {
            if (active) Icon(rememberVectorPainter(Icons.Filled.Check), null, tint = Color.White, modifier = Modifier.size(14.dp))
        }
    }
}

/** Tiny map: a street crossing four blocks, the uncovered part lit up. */
@Composable
private fun ModeSketch(mode: RevealMode, active: Boolean, modifier: Modifier) {
    val lit = if (active) Fog.Lavender else Fog.Violet2.copy(alpha = 0.7f)
    Canvas(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF0B0D1A))) {
        val w = size.width; val h = size.height
        val street = w * 0.08f
        val cx = w / 2; val cy = h / 2
        val gap = street * 1.3f
        val fog = Color(0xFF1C1E3A)
        val cr = CornerRadius(w * 0.06f)
        // four blocks around the crossing
        val blocks = listOf(
            Offset(gap * 0.5f, gap * 0.5f) to Offset(cx - gap, cy - gap), Offset(cx + gap, gap * 0.5f) to Offset(w - gap * 0.5f, cy - gap),
            Offset(gap * 0.5f, cy + gap) to Offset(cx - gap, h - gap * 0.5f), Offset(cx + gap, cy + gap) to Offset(w - gap * 0.5f, h - gap * 0.5f))
        for ((a, b) in blocks) drawRoundRect(fog, a, Size(b.x - a.x, b.y - a.y), cr)
        when (mode) {
            RevealMode.BLOCKS -> for ((a, b) in blocks.take(2)) drawRoundRect(lit.copy(alpha = 0.55f), a, Size(b.x - a.x, b.y - a.y), cr)
            RevealMode.STREET -> {
                val d = w * 0.14f
                drawRoundRect(lit.copy(alpha = 0.55f), Offset(cx - gap - d, gap * 0.5f), Size(d, cy - gap * 1.5f), cr)
                drawRoundRect(lit.copy(alpha = 0.55f), Offset(cx + gap, gap * 0.5f), Size(d, cy - gap * 1.5f), cr)
            }
            RevealMode.RADIUS -> {
                for (k in 0..3) drawCircle(lit.copy(alpha = 0.32f), radius = w * 0.2f, center = Offset(cx, h * (0.12f + k * 0.12f)))
            }
        }
        // streets
        drawLine(Color(0xFF3A3F66), Offset(0f, cy), Offset(w, cy), strokeWidth = street)
        drawLine(Color(0xFF3A3F66), Offset(cx, 0f), Offset(cx, h), strokeWidth = street)
        // the walked part
        drawLine(Color(0xFFF7B860), Offset(cx, h * 0.06f), Offset(cx, cy), strokeWidth = street * 0.8f, cap = StrokeCap.Round)
    }
}

@Composable
private fun ValueSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, unit: String, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(16.dp)).background(Fog.Surface).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, color = Fog.Text, modifier = Modifier.weight(1f))
            Text("${value.toInt()} $unit", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Fog.Lavender)
        }
        Slider(value, onChange, valueRange = range, steps = steps, colors = SliderDefaults.colors(
            thumbColor = Fog.Lavender, activeTrackColor = Fog.Violet, inactiveTrackColor = Fog.SurfaceHigh,
            activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent))
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Fog.Surface).padding(horizontal = 14.dp, vertical = 4.dp)) { content() }
}

@Composable
private fun OfflineCard() {
    val prefs = App.prefs
    val area by App.tiles.prefetch.collectAsState()
    var cacheMb by remember { mutableStateOf(0.0) }
    LaunchedEffect(area.running, area.done) { cacheMb = withContext(Dispatchers.IO) { App.tiles.cacheSizeBytes() / 1_048_576.0 } }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Fog.Surface).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_cloud), null, tint = Fog.Cyan, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("${cacheMb.de(1)} MB Kartendaten gespeichert", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Text("Beim Erkunden lädt FoggyMap die Umgebung im Hintergrund (WLAN: ganze Stadt ~8 km, mobil: ~3 km). Danach klappt alles auch ohne Netz.",
            color = Fog.Muted, fontSize = 12.sp, lineHeight = 16.sp)
        Spacer(Modifier.height(10.dp))
        if (area.running) {
            LinearProgressIndicator(progress = { if (area.total == 0) 0f else area.done.toFloat() / area.total },
                color = Fog.Cyan, trackColor = Fog.SurfaceHigh, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Text("${area.radiusKm}-km-Umkreis: ${area.done} von ${area.total} Kacheln · ${(area.bytes / 1_048_576.0).de(1)} MB",
                color = Fog.Muted, fontSize = 12.sp)
        } else {
            val here = App.location.collectAsState().value
            val start = here?.let { LatLon(it.latitude, it.longitude) } ?: prefs.lastLat.takeIf { !it.isNaN() }?.let { LatLon(it, prefs.lastLon) }
            PrimaryButton("Ganze Stadt herunterladen (12 km)", Modifier.fillMaxWidth(), secondary = true, enabled = start != null) {
                start?.let { App.ensureArea(it, TileRepository.CITY_RADIUS) }
            }
            area.message?.let { Spacer(Modifier.height(6.dp)); Text(it, color = Fog.Amber, fontSize = 12.sp) }
            if (area.total > 0 && area.message == null) { Spacer(Modifier.height(6.dp)); Text("Fertig: ${area.done} Kacheln geladen.", color = Fog.Success, fontSize = 12.sp) }
        }
    }
}

// =================================================================== App

@Composable
private fun AppTab(onDataCleared: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = App.prefs
    var server by remember { mutableStateOf(prefs.server) }
    var confirmReset by remember { mutableStateOf(false) }
    var showServer by remember { mutableStateOf(prefs.server.isNotEmpty()) }

    SectionTitle("Updates")
    val update by App.updater.state.collectAsState()
    SettingsCard {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("FoggyMap ${BuildConfig.VERSION_NAME}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                val status = when (val u = update) {
                    de.foggymap.app.data.Updater.State.Checking -> "Suche …"
                    de.foggymap.app.data.Updater.State.UpToDate -> "Du hast die neueste Version."
                    is de.foggymap.app.data.Updater.State.Failed -> u.message
                    else -> "Updates kommen automatisch beim Öffnen der App."
                }
                Text(status, color = if (update is de.foggymap.app.data.Updater.State.Failed) Fog.Amber else Fog.Muted, fontSize = 11.sp)
            }
            if (App.updater.enabled) TextButton(onClick = { scope.launch { if (App.updater.check(force = true)) App.updater.install() } }) {
                Text("Prüfen", color = Fog.Violet2)
            }
        }
    }

    SectionTitle("Berechtigungen")
    val fine = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    val notif = Build.VERSION.SDK_INT < 33 || hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
    val pm = context.getSystemService(PowerManager::class.java)
    val unrestricted = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    SettingsCard {
        PermissionRow(rememberVectorPainter(Icons.Filled.LocationOn), "Genauer Standort", if (fine) "erteilt" else "fehlt", fine) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
        PermissionRow(rememberVectorPainter(Icons.Filled.Notifications), "Benachrichtigung", if (notif) "erteilt" else "fehlt (Aufzeichnung läuft trotzdem)", notif) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
        PermissionRow(painterResource(R.drawable.ic_battery), "Akku-Optimierung",
            if (unrestricted) "nicht eingeschränkt" else "aktiv – manche Hersteller stoppen die Aufzeichnung", unrestricted) {
            runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }
    Spacer(Modifier.height(8.dp))
    InfoBox("„Immer erlauben“ ist nicht nötig: Die Erkundung läuft als sichtbarer Dienst mit Benachrichtigung, auch bei ausgeschaltetem Bildschirm. " +
        "Bei Samsung, Xiaomi oder Huawei FoggyMap von der Akku-Optimierung ausnehmen.", rememberVectorPainter(Icons.Filled.Info))

    SectionTitle("Daten")
    SettingsCard {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { App.tiles.clearCache() }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_cloud), null, tint = Fog.Lavender, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text("Offline-Kartendaten löschen", fontSize = 14.sp, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { confirmReset = true }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(rememberVectorPainter(Icons.Filled.Delete), null, tint = Fog.Danger, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text("Alle Erkundungsdaten löschen", fontSize = 14.sp, color = Fog.Danger, modifier = Modifier.weight(1f))
        }
    }

    SectionTitle("Koop-Server")
    if (!showServer) {
        Text("Ohne Eintrag läuft Koop ohne Einrichtung über öffentliche Nostr-Relays (verschlüsselt).", color = Fog.Muted, fontSize = 12.sp)
        TextButton(onClick = { showServer = true }) { Text("Eigenen Server eintragen (für Fortgeschrittene)", color = Fog.Violet2) }
    } else {
        OutlinedTextField(server, { server = it.trim() }, label = { Text("Eigene Server-Adresse (optional)") }, singleLine = true,
            colors = fogTextFieldColors(), modifier = Modifier.fillMaxWidth(),
            supportingText = { Text(if (BuildConfig.DEFAULT_SERVER.isNotEmpty()) "Eingebaut: ${BuildConfig.DEFAULT_SERVER}" else "Kein Server eingebaut",
                color = Fog.Muted, fontSize = 11.sp) })
        TextButton(onClick = { if (server.isEmpty() || Invite.isValidServer(server)) { prefs.server = server; App.sync.refreshState() } }) {
            Text("Server übernehmen", color = Fog.Violet2)
        }
    }

    ReportCard()

    SectionTitle("Über")
    Text("Kartendaten © OpenStreetMap-Mitwirkende (ODbL) · Karten- und Straßendaten: OpenFreeMap / OpenMapTiles · " +
        "Kartendarstellung: MapLibre Native · Geometrie: JTS Topology Suite.", color = Fog.Muted, fontSize = 11.sp, lineHeight = 15.sp)
    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"))) }) {
        Text("OpenStreetMap-Lizenz", color = Fog.Violet2)
    }
    Text("Spieler-ID: ${App.badges.playerId}", color = Fog.Muted, fontSize = 11.sp)
    AdminSection()

    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false }, containerColor = Fog.Surface, titleContentColor = Fog.Text, textContentColor = Fog.Text,
        title = { Text("Wirklich alles löschen?") },
        text = { Text("Alle aufgedeckten Gebiete, Statistiken und gemerkten Orte auf diesem Handy werden gelöscht. Was du mit einer Koop-Gruppe geteilt hast, kommt bei der nächsten Synchronisierung zurück.") },
        confirmButton = { TextButton(onClick = {
            confirmReset = false
            scope.launch {
                withContext(Dispatchers.IO) { App.db.clear(); App.prefs.resetStatistics() }
                App.notifyDbChanged()
                onDataCleared()
            }
        }) { Text("Löschen", color = Fog.Danger) } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Abbrechen", color = Fog.Muted) } },
    )
}
