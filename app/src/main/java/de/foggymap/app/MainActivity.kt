package de.foggymap.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.foggymap.app.tracking.TrackingService
import de.foggymap.app.ui.FoggyRoot
import de.foggymap.app.ui.FoggyTheme
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.offline.OfflineManager

class MainActivity : ComponentActivity() {
    /** Invitation text shared into the app (Teilen → FoggyMap). */
    var pendingInvite by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        // Keep viewed map tiles for offline use (default would be 50 MB).
        runCatching {
            OfflineManager.getInstance(this).setMaximumAmbientCacheSize(300L * 1024 * 1024, object : OfflineManager.FileSourceCallback {
                override fun onSuccess() {}
                override fun onError(message: String) {}
            })
        }
        handleIntent(intent)
        setContent { FoggyTheme { FoggyRoot(this) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Resume an exploration that was running when the app/process was closed.
        if (App.prefs.trackingWanted && !App.tracking.value.active && hasFineLocation(this)) TrackingService.start(this)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT)?.let { pendingInvite = it }
        // Automated emulator test (debug build only): skip onboarding and start exploring.
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("e2e_autostart", false) == true) {
            App.prefs.onboardingDone = true
            intent.getStringExtra("e2e_server")?.let { server ->
                App.prefs.nickname = "Emulator"
                App.scope.launch { App.sync.createGroup("E2E-Test", server) }
            }
            if (hasFineLocation(this)) TrackingService.start(this)
        }
    }

    companion object {
        fun hasFineLocation(c: Context) =
            c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}
