package de.foggymap.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.foggymap.app.App
import de.foggymap.app.data.TileRepository
import de.foggymap.app.core.LatLon
import de.foggymap.app.BuildConfig
import de.foggymap.app.Coverage
import de.foggymap.app.R
import de.foggymap.app.core.Invite
import de.foggymap.app.core.TravelMode
import de.foggymap.app.data.BatteryMode
import de.foggymap.app.data.RevealDatabase
import de.foggymap.app.data.SyncUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FogSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Fog.Bg,
        contentColor = Fog.Text,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Fog.Muted.copy(alpha = 0.5f)) },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).navigationBarsPadding().padding(bottom = 24.dp),
            content = content)
    }
}

// =================================================================== statistics

@Composable
fun StatsSheet(counts: RevealDatabase.Counts?, coverage: Coverage?, sync: SyncUi, onDismiss: () -> Unit) {
    val prefs = App.prefs
    val c = counts ?: RevealDatabase.Counts(0, 0.0, 0, 0, 0, 0, 0.0, 0)
    FogSheet(onDismiss) {
        Text("Deine Statistiken", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${(coverage?.percent ?: 0.0).de(1)} %", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Fog.Text)
                Spacer(Modifier.width(10.dp))
                Text("deiner Umgebung erkundet", color = Fog.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
            }
            Spacer(Modifier.height(8.dp))
            AccentProgress(((coverage?.percent ?: 0.0) / 100).toFloat(), height = 8.dp)
            Spacer(Modifier.height(8.dp))
            Text(
                if (coverage == null) "Sobald du losgehst, lädt FoggyMap die Straßen deiner Umgebung (Kacheln von ca. 1 km²)."
                else "${(coverage.exploredMeters / 1000).de(1)} von ${(coverage.totalMeters / 1000).de(1)} Straßenkilometern in den geladenen Kartenkacheln.",
                color = Fog.Muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        SectionTitle("Aufgedeckt")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("${c.roadKm.de(1)} km", "Straßen erkundet", painterResource(R.drawable.ic_road), Modifier.weight(1f))
            StatTile(c.roads.de(), "Straßenabschnitte", painterResource(R.drawable.ic_compass), Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(c.blocks.de(), "Häuserblöcke", painterResource(R.drawable.ic_blocks), Modifier.weight(1f))
            StatTile(c.buildings.de(), "Gebäude sichtbar", painterResource(R.drawable.ic_building), Modifier.weight(1f))
        }
        if (sync.inGroup) {
            Spacer(Modifier.height(10.dp))
            Text("Davon selbst erkundet: ${c.ownRoads.de()} Abschnitte · ${c.ownRoadKm.de(1)} km", color = Fog.Muted, fontSize = 12.sp)
        }
        SectionTitle("Unterwegs")
        val walk = prefs.distance(TravelMode.WALK); val bike = prefs.distance(TravelMode.BIKE); val car = prefs.distance(TravelMode.CAR)
        val total = walk + bike + car
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("${(total / 1000).de(1)} km", "zurückgelegt", rememberVectorPainter(Icons.Filled.LocationOn), Modifier.weight(1f))
            StatTile(formatDuration(prefs.trackedSeconds), "Erkundungszeit", painterResource(R.drawable.ic_clock), Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        ModeRow(painterResource(R.drawable.ic_walk), "Zu Fuß", walk, total)
        ModeRow(painterResource(R.drawable.ic_bike), "Fahrrad", bike, total)
        ModeRow(painterResource(R.drawable.ic_car), "Auto", car, total)
        if (sync.inGroup && sync.members.isNotEmpty()) {
            SectionTitle("Gruppe „${sync.groupName}“")
            sync.members.sortedByDescending { it.km }.forEachIndexed { i, m -> MemberRow(i, m.name, m.km, m.roads, m.isMe, m.live != null) }
        }
    }
}

@Composable
private fun ModeRow(icon: Painter, label: String, meters: Double, total: Double) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Fog.Lavender, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 13.sp, modifier = Modifier.width(70.dp))
        Box(Modifier.weight(1f)) { AccentProgress(if (total > 0) (meters / total).toFloat() else 0f, height = 6.dp) }
        Spacer(Modifier.width(10.dp))
        Text("${(meters / 1000).de(1)} km", fontSize = 13.sp, color = Fog.Muted, modifier = Modifier.width(64.dp))
    }
}

@Composable
private fun MemberRow(rank: Int, name: String, km: Double, roads: Int, me: Boolean, live: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(if (me) Fog.Violet else Fog.SurfaceHigh), contentAlignment = Alignment.Center) {
            if (rank == 0) Icon(rememberVectorPainter(Icons.Filled.Star), null, tint = Fog.Amber, modifier = Modifier.size(18.dp))
            else Text(name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(if (me) "$name (Du)" else name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("${roads.de()} Abschnitte" + if (live) " · teilt Live-Standort" else "", color = Fog.Muted, fontSize = 11.sp)
        }
        Text("${km.de(1)} km", fontSize = 14.sp, color = Fog.Lavender, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600; val m = (seconds % 3600) / 60
    return if (h > 0) "$h h ${m} min" else "$m min"
}

// =================================================================== group / coop

@Composable
internal fun CoopTab(sync: SyncUi, pendingInvite: String?, onInviteUsed: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var joinText by remember { mutableStateOf(pendingInvite ?: "") }
    var groupName by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf(App.prefs.nickname) }
    var serverField by remember { mutableStateOf(App.prefs.server) }
    var confirmLive by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var shareLive by remember { mutableStateOf(App.prefs.shareLiveLocation) }
    LaunchedEffect(pendingInvite) { if (pendingInvite != null) { joinText = pendingInvite; onInviteUsed() } }

    Column {
        Text("Gemeinsam mit Freunden dieselbe Karte aufdecken. Jeder sieht, was die anderen entdeckt haben.", color = Fog.Muted, fontSize = 13.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(14.dp))

        if (!sync.inGroup) {
            OutlinedTextField(nickname, { nickname = it.take(24) }, label = { Text("Dein Anzeigename in der Gruppe") }, singleLine = true,
                colors = fogTextFieldColors(), modifier = Modifier.fillMaxWidth())
            if (BuildConfig.DEFAULT_SERVER.isEmpty() || serverField.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(serverField, { serverField = it.trim() }, label = { Text("Server-Adresse (https://…)") }, singleLine = true,
                    colors = fogTextFieldColors(), modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(if (BuildConfig.DEFAULT_SERVER.isEmpty())
                        "Einmalig nötig für den Gruppenersteller – siehe Anleitung „Koop-Server“. Beim Beitreten per Einladung wird sie automatisch übernommen."
                        else "Leer lassen für den eingebauten Server.", color = Fog.Muted, fontSize = 11.sp) })
            }
            SectionTitle("Neue Gruppe")
            OutlinedTextField(groupName, { groupName = it.take(24) }, label = { Text("Gruppenname (z. B. Büro)") }, singleLine = true,
                colors = fogTextFieldColors(), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            PrimaryButton(if (busy) "Bitte warten …" else "Gruppe erstellen", Modifier.fillMaxWidth(), enabled = !busy) {
                App.prefs.nickname = nickname
                busy = true; error = null
                scope.launch {
                    App.sync.createGroup(groupName, serverField).onFailure { error = it.message }
                    busy = false
                }
            }
            SectionTitle("Einer Gruppe beitreten")
            OutlinedTextField(joinText, { joinText = it }, label = { Text("Einladung einfügen oder Code eingeben") },
                colors = fogTextFieldColors(), modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Einfügen", Modifier.weight(1f), icon = painterResource(R.drawable.ic_copy), secondary = true) {
                    clipboard.getText()?.text?.let { joinText = it }
                }
                PrimaryButton(if (busy) "…" else "Beitreten", Modifier.weight(1f), enabled = !busy && joinText.isNotBlank()) {
                    App.prefs.nickname = nickname
                    busy = true; error = null
                    scope.launch {
                        App.sync.joinGroup(joinText, serverField).onFailure { error = it.message }
                        busy = false
                    }
                }
            }
        } else {
            GlassCard(Modifier.fillMaxWidth()) {
                Text(sync.groupName.ifEmpty { "Deine Gruppe" }, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Fog.SurfaceHigh)
                        .border(1.dp, Fog.Violet.copy(alpha = 0.4f), RoundedCornerShape(14.dp)).padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center) {
                        Text(Invite.pretty(sync.groupCode), fontSize = 22.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp, color = Fog.Text)
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.size(50.dp).clip(RoundedCornerShape(14.dp)).background(Fog.button).clickable {
                        val text = Invite.shareText(sync.groupName.ifEmpty { "FoggyMap" }, sync.groupCode, sync.server)
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, text), "Einladung teilen"))
                    }, contentAlignment = Alignment.Center) {
                        Icon(rememberVectorPainter(Icons.Filled.Share), "Teilen", tint = Color.White)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Teile diese Einladung mit deinen Freunden. Sie installieren dieselbe FoggyMap-APK und fügen die Nachricht unter „Beitreten“ ein.",
                    color = Fog.Muted, fontSize = 12.sp, lineHeight = 16.sp)
                TextButton(onClick = { clipboard.setText(AnnotatedString(Invite.shareText(sync.groupName, sync.groupCode, sync.server))) }) {
                    Text("Einladung kopieren", color = Fog.Violet2)
                }
            }
            Spacer(Modifier.height(12.dp))
            SyncStatusRow(sync) { scope.launch { App.sync.syncNow() } }
            SectionTitle("Mitglieder")
            if (sync.members.isEmpty()) Text("Noch keine Daten – Mitglieder erscheinen nach ihrer ersten Synchronisierung.", color = Fog.Muted, fontSize = 12.sp)
            sync.members.sortedByDescending { it.km }.forEachIndexed { i, m -> MemberRow(i, m.name, m.km, m.roads, m.isMe, m.live != null) }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                Text("Gemeinsamer Fortschritt", color = Fog.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("${sync.groupKm.de(1)} km · ${sync.groupRoads.de()} Abschnitte", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            SectionTitle("Live-Standort")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Live-Standort mit der Gruppe teilen", fontSize = 14.sp)
                    Text("Aus = deine Position wird nie übertragen. An = während der Erkundung etwa minütlich, verfällt nach 10 Minuten.",
                        color = Fog.Muted, fontSize = 11.sp, lineHeight = 14.sp)
                }
                Switch(shareLive, { on ->
                    if (on) {
                        confirmLive = true
                    } else {
                        shareLive = false; App.prefs.shareLiveLocation = false; scope.launch { App.sync.clearLive() }
                    }
                }, colors = fogSwitchColors())
            }
            Spacer(Modifier.height(14.dp))
            TextButton(onClick = { confirmLeave = true }) { Text("Gruppe verlassen", color = Fog.Danger) }
        }

        error?.let {
            Spacer(Modifier.height(10.dp))
            InfoBox(it, rememberVectorPainter(Icons.Filled.Warning), Fog.Danger)
        }
        SectionTitle("Was wird geteilt?")
        InfoBox("Geteilt werden nur die aufgedeckten Flächen (Straßenabschnitte, Häuserblöcke, Gebäude), deine Kilometer und dein Anzeigename. " +
            "Dein GPS-Verlauf wird nie übertragen. Hinweis: Aus den aufgedeckten Flächen ist erkennbar, wo du unterwegs warst. " +
            "Wer den Gruppencode kennt, kann beitreten – teile ihn nur mit Menschen, denen du vertraust.",
            rememberVectorPainter(Icons.Filled.Lock))
    }

    if (confirmLive) AlertDialog(
        onDismissRequest = { confirmLive = false }, containerColor = Fog.Surface, titleContentColor = Fog.Text, textContentColor = Fog.Text,
        title = { Text("Live-Standort teilen?") },
        text = { Text("Während die Erkundung läuft, sehen alle Gruppenmitglieder deine aktuelle Position (auf ca. 1 m gerundet, Aktualisierung etwa jede Minute). Du kannst das jederzeit wieder ausschalten.") },
        confirmButton = { TextButton(onClick = { confirmLive = false; shareLive = true; App.prefs.shareLiveLocation = true }) { Text("Teilen", color = Fog.Violet2) } },
        dismissButton = { TextButton(onClick = { confirmLive = false }) { Text("Abbrechen", color = Fog.Muted) } },
    )
    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false }, containerColor = Fog.Surface, titleContentColor = Fog.Text, textContentColor = Fog.Text,
        title = { Text("Gruppe verlassen?") },
        text = { Text("Alles bereits Aufgedeckte (auch von der Gruppe) bleibt auf deinem Handy erhalten. Neue Funde werden nicht mehr geteilt.") },
        confirmButton = { TextButton(onClick = { confirmLeave = false; shareLive = false; App.sync.leaveGroup() }) { Text("Verlassen", color = Fog.Danger) } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Abbrechen", color = Fog.Muted) } },
    )
}

@Composable
private fun SyncStatusRow(sync: SyncUi, onSync: () -> Unit) {
    val (color, label) = when (sync.status) {
        SyncUi.Status.OK -> Fog.Success to "Synchronisiert"
        SyncUi.Status.SYNCING -> Fog.Cyan to "Synchronisiere …"
        SyncUi.Status.OFFLINE -> Fog.Amber to "Offline"
        SyncUi.Status.ERROR -> Fog.Danger to "Fehler"
        SyncUi.Status.IDLE -> Fog.Muted to "Bereit"
        SyncUi.Status.NOT_CONFIGURED -> Fog.Muted to "Nicht eingerichtet"
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Fog.SurfaceHigh).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            val detail = buildList {
                if (sync.lastSync > 0) add("zuletzt " + DateUtils.getRelativeTimeSpanString(sync.lastSync).toString())
                if (sync.pendingUploads > 0) add("${sync.pendingUploads} warten auf Upload")
                if (sync.message.isNotEmpty()) add(sync.message)
            }.joinToString(" · ")
            if (detail.isNotEmpty()) Text(detail, color = Fog.Muted, fontSize = 11.sp)
        }
        Box(Modifier.size(38.dp).clip(CircleShape).background(Fog.Violet.copy(alpha = 0.25f)).clickable(onClick = onSync), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_sync), "Jetzt synchronisieren", tint = Fog.Lavender, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            Text(subtitle, color = Fog.Muted, fontSize = 11.sp)
        }
        Switch(checked, onChange, colors = fogSwitchColors())
    }
}

@Composable
internal fun PermissionRow(icon: Painter, title: String, state: String, ok: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onFix).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Fog.Lavender, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            Text(state, color = if (ok) Fog.Success else Fog.Amber, fontSize = 11.sp)
        }
        Icon(rememberVectorPainter(if (ok) Icons.Filled.Check else Icons.Filled.Warning), null,
            tint = if (ok) Fog.Success else Fog.Amber, modifier = Modifier.size(18.dp))
    }
}
