package de.foggymap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.foggymap.app.App
import de.foggymap.app.R
import de.foggymap.app.data.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Menü → Info: what exploring costs in battery, and how good GPS is right now. */
@Composable
internal fun BatteryGpsCard() {
    val tour by Diagnostics.tour.collectAsState()
    val prefs = App.prefs
    SectionTitle("Akku & GPS")
    GlassCard(Modifier.fillMaxWidth()) {
        val t = tour
        if (t != null) {
            val minutes = t.optLong("minutes")
            val rate = if (t.has("rate")) "≈ ${t.optDouble("rate").de(1)} %/h" else "wird noch gemessen"
            InfoRow(R.drawable.ic_battery, "Diese Tour", "−${t.optDouble("drainPct").de(1)} % in $minutes min · $rate")
            val a50 = t.opt("acc50"); val a90 = t.opt("acc90")
            if (a50 is Number && a90 is Number) {
                val vague = if (t.optInt("fixes") > 0) t.optInt("vague") * 100 / t.optInt("fixes") else 0
                InfoRow(R.drawable.ic_compass, "GPS-Genauigkeit", "meist ±${a50.toInt()} m, 90 % besser als ±${a90.toInt()} m" +
                    if (vague > 0) " · $vague % zu ungenau" else "")
            }
        }
        val avg = if (prefs.batteryHours >= 0.15) "${(prefs.batteryPct / prefs.batteryHours).de(1)} %/h (${prefs.batteryHours.de(1)} h gemessen)" else "noch keine Messung"
        InfoRow(R.drawable.ic_battery, "Durchschnitt", avg)
        if (prefs.batteryLiveHours >= 0.15)
            InfoRow(R.drawable.ic_group, "Mit Live-Standort", "${(prefs.batteryLivePct / prefs.batteryLiveHours).de(1)} %/h (${prefs.batteryLiveHours.de(1)} h)")
        Spacer(Modifier.height(6.dp))
        Text("Gemessen wird das ganze Handy, solange die Erkundung läuft und es nicht lädt. Bildschirm an kostet am meisten.",
            color = Fog.Muted, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun InfoRow(icon: Int, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, tint = Fog.Lavender, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 13.sp, color = Fog.Muted, modifier = Modifier.width(118.dp))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
}

/** Menü → App: describe the problem, look at the report, share it. */
@Composable
internal fun ReportCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var withLog by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    SectionTitle("Hilfe")
    if (!open) {
        PrimaryButton("Problem melden", Modifier.fillMaxWidth(), secondary = true) { open = true }
        Spacer(Modifier.height(6.dp))
        Text("Erstellt einen Bericht für die Fehlersuche: Gerät, Einstellungen, GPS-Qualität, Akku, Abstürze und Protokoll – ohne Orte, " +
            "Straßennamen, Namen oder Gruppencode. Du siehst ihn vor dem Teilen.", color = Fog.Muted, fontSize = 11.sp, lineHeight = 15.sp)
        return
    }
    GlassCard(Modifier.fillMaxWidth()) {
        OutlinedTextField(text, { text = it.take(1000) }, label = { Text("Was ist passiert? (z. B. „Fenster flackern beim Drehen“)") },
            colors = fogTextFieldColors(), modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp))
        SwitchRow("Protokoll anhängen", "Die letzten Meldungen der App, bereinigt", withLog) { withLog = it; preview = null }
        Row {
            TextButton(onClick = {
                busy = true
                scope.launch { preview = withContext(Dispatchers.IO) { Diagnostics.buildReport(context, text, withLog, Diagnostics.pendingProblem(context)) }; busy = false }
            }, enabled = !busy) { Text(if (preview == null) "Bericht ansehen" else "Neu erstellen", color = Fog.Violet2) }
        }
        preview?.let { r ->
            Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(12.dp)).background(Fog.Bg)
                .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(10.dp)) {
                Text(r, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp, color = Fog.Text.copy(alpha = 0.85f))
            }
            Spacer(Modifier.height(8.dp))
        }
        PrimaryButton(if (busy) "…" else "Bericht teilen", Modifier.fillMaxWidth(), enabled = !busy) {
            busy = true
            scope.launch {
                val report = preview ?: withContext(Dispatchers.IO) { Diagnostics.buildReport(context, text, withLog, Diagnostics.pendingProblem(context)) }
                busy = false
                runCatching { context.startActivity(Diagnostics.shareIntent(context, report)) }
                withContext(Dispatchers.IO) { Diagnostics.clearCrash(context) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Teilen z. B. per WhatsApp an dich selbst oder direkt in den Chat mit Claude hochladen.", color = Fog.Muted, fontSize = 11.sp)
    }
}

/** On start: FoggyMap crashed or froze last time → offer to share a report (once per incident). */
@Composable
fun CrashPrompt() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<Diagnostics.Problem?>(null) }
    LaunchedEffect(Unit) { problem = withContext(Dispatchers.IO) { runCatching { Diagnostics.pendingProblem(context) }.getOrNull() } }
    val p = problem ?: return
    val whenText = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date(p.timeMs))
    AlertDialog(
        onDismissRequest = { problem = null; scope.launch(Dispatchers.IO) { Diagnostics.acknowledge(context) } },
        containerColor = Fog.Surface, titleContentColor = Fog.Text, textContentColor = Fog.Text.copy(alpha = 0.85f),
        title = { Text("FoggyMap wurde unerwartet beendet") },
        text = { Text("$whenText: ${p.what}. Magst du einen Bericht teilen? Er hilft beim Beheben und enthält keine Orte, Straßennamen oder Codes.") },
        confirmButton = {
            TextButton(onClick = {
                problem = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) { Diagnostics.buildReport(context, "Automatisch nach: ${p.what} ($whenText)", true, p) }
                    runCatching { context.startActivity(Diagnostics.shareIntent(context, r)) }
                    withContext(Dispatchers.IO) { Diagnostics.clearCrash(context) }
                }
            }) { Text("Bericht teilen", color = Fog.Violet2) }
        },
        dismissButton = { TextButton(onClick = { problem = null; scope.launch(Dispatchers.IO) { Diagnostics.acknowledge(context) } }) { Text("Nein danke", color = Fog.Muted) } },
    )
}
