package de.foggymap.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import de.foggymap.app.R
import de.foggymap.app.TrackingUi
import de.foggymap.app.core.TravelMode
import de.foggymap.app.data.GroupMember
import de.foggymap.app.data.RevealDatabase
import de.foggymap.app.data.SyncUi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** Renders the real Compose screens without a device, so the look can be checked in CI. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h1900dp-xhdpi")
class UiScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { FoggyTheme { content() } }
        compose.waitForIdle()
        val bmp = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun Sheet(content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize().background(Fog.Bg).padding(horizontal = 18.dp, vertical = 16.dp)) { content() }
    }

    private val group = SyncUi(server = "https://example.org", groupCode = "ABCDEFGHJKMN", groupName = "Büro", status = SyncUi.Status.OK,
        lastSync = System.currentTimeMillis() - 60_000, groupKm = 23.4, groupRoads = 212, members = listOf(
            GroupMember("a", "Tim", 120, 12.1, 0, null, true), GroupMember("b", "Lukas", 70, 7.3, 0, null, false),
            GroupMember("c", "Marie", 40, 4.0, 0, null, false)))

    @Test fun menuExplore() = shot("menu-erkunden") { Sheet { MenuContent(SyncUi(), null, {}, MenuTab.EXPLORE, {}, {}) } }
    @Test fun menuCoop() = shot("menu-koop") { Sheet { MenuContent(SyncUi(), null, {}, MenuTab.COOP, {}, {}) } }
    @Test fun menuCoopInGroup() = shot("menu-koop-gruppe") { Sheet { MenuContent(group, null, {}, MenuTab.COOP, {}, {}) } }
    @Test fun menuApp() = shot("menu-app") { Sheet { MenuContent(SyncUi(), null, {}, MenuTab.APP, {}, {}) } }

    @Test fun hud() = shot("hud") {
        val t = TrackingUi(active = true, accuracy = 6f, mode = TravelMode.WALK, sessionSegments = 7, sessionMeters = 1840.0)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B0D1A), Color(0xFF1B1D3A), Color(0xFF0B0D1A))))) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_logo), null, tint = Color.Unspecified, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(8.dp))
                    LogoTitle(22)
                    Spacer(Modifier.weight(1f))
                    MenuButton(3) {}
                }
                StatusChips(t)
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                    LocateButton(true) {}
                }
                BottomCard(1.4, RevealDatabase.Counts(42, 9.6, 57, 824, 3, 42, 9.6, 900), t, {}, {})
            }
        }
    }
}
