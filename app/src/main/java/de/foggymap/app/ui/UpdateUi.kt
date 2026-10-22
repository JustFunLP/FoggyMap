package de.foggymap.app.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.foggymap.app.App
import de.foggymap.app.data.Updater
import kotlinx.coroutines.launch

/** Checks GitHub for a newer FoggyMap on start / resume and walks the user through the update. */
@Composable
fun UpdatePrompt() {
    val state by App.updater.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(Unit) { App.updater.check() }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                val s = App.updater.state.value
                val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()
                if (s is Updater.State.NeedsPermission && allowed) scope.launch { App.updater.install() }
                else scope.launch { App.updater.check() }
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    when (val s = state) {
        is Updater.State.Available -> AlertDialog(
            onDismissRequest = { App.updater.dismiss() },
            containerColor = Fog.Surface, titleContentColor = Fog.Text, textContentColor = Fog.Text.copy(alpha = 0.85f),
            title = { Text("Update verfügbar") },
            text = { Text("FoggyMap ${s.versionName} ist da. Jetzt herunterladen und installieren? Deine Erkundung bleibt vollständig erhalten.") },
            confirmButton = { TextButton(onClick = { scope.launch { App.updater.install() } }) { Text("Installieren", color = Fog.Violet2) } },
            dismissButton = { TextButton(onClick = { App.updater.dismiss() }) { Text("Später", color = Fog.Muted) } },
        )
        is Updater.State.Downloading, Updater.State.Installing -> AlertDialog(
            onDismissRequest = {},
            containerColor = Fog.Surface, titleContentColor = Fog.Text, textContentColor = Fog.Text.copy(alpha = 0.85f),
            title = { Text("Update wird installiert") },
            text = {
                Column {
                    val pct = (s as? Updater.State.Downloading)?.percent
                    Text(if (pct != null) "Lade herunter … $pct %" else "Android installiert die neue Version …")
                    Spacer(Modifier.height(12.dp))
                    AccentProgress((pct ?: 100) / 100f)
                }
            },
            confirmButton = {},
        )
        is Updater.State.NeedsPermission -> AlertDialog(
            onDismissRequest = { App.updater.dismiss() },
            containerColor = Fog.Surface, titleContentColor = Fog.Text, textContentColor = Fog.Text.copy(alpha = 0.85f),
            title = { Text("Einmalige Erlaubnis") },
            text = { Text("Damit sich FoggyMap selbst aktualisieren kann, erlaube im nächsten Bildschirm „Apps aus dieser Quelle zulassen“. Danach geht es automatisch weiter.") },
            confirmButton = { TextButton(onClick = { context.startActivity(App.updater.permissionIntent()) }) { Text("Erlauben", color = Fog.Violet2) } },
            dismissButton = { TextButton(onClick = { App.updater.dismiss() }) { Text("Später", color = Fog.Muted) } },
        )
        is Updater.State.Failed -> if (s.fromInstall) AlertDialog(
            onDismissRequest = { App.updater.dismiss() },
            containerColor = Fog.Surface, titleContentColor = Fog.Text, textContentColor = Fog.Text.copy(alpha = 0.85f),
            title = { Text("Update nicht möglich") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = { App.updater.dismiss() }) { Text("OK", color = Fog.Violet2) } },
        )
        else -> {}
    }
}
