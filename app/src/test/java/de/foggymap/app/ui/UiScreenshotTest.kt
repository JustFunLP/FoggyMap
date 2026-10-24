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
import androidx.compose.ui.res.painterResource
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { FoggyTheme { content() } }
        compose.waitForIdle()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        // Draw the window straight into a bitmap (captureToImage waits for a redraw Robolectric never sends).
        val root = compose.activity.window.decorView.rootView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
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

    @Test fun menuInfo() {
        de.foggymap.app.App.badges.earned.value = listOf(de.foggymap.app.core.Badges.CATALOG[0])
        de.foggymap.app.data.Diagnostics.tour.value = org.json.JSONObject("{\"minutes\":52,\"drainPct\":4.1,\"rate\":4.7," +
            "\"fixes\":980,\"vague\":12,\"acc50\":5.0,\"acc90\":11.0}")
        de.foggymap.app.App.tracking.value = TrackingUi(active = true, accuracy = 6f, mode = TravelMode.WALK, sessionSegments = 7,
            sessionMeters = 1840.0, powerProfile = "GPS gedrosselt (kurzer Halt)")
        shot("menu-info") { Sheet { MenuContent(group, null, {}, MenuTab.INFO, {}, {}) } }
    }

    private fun mapBackground() = Brush.verticalGradient(listOf(Color(0xFF0B0D1A), Color(0xFF1B1D3A), Color(0xFF0B0D1A)))

    @Test @Config(qualifiers = "w400dp-h860dp-xhdpi")
    fun hud() = shot("hud") {
        val t = TrackingUi(active = true, accuracy = 6f, mode = TravelMode.WALK, sessionSegments = 7, sessionMeters = 1840.0)
        Box(Modifier.fillMaxSize().background(mapBackground())) {
            MapControls(t, 3, mapBearing = 35f, northUp = false, tilted = true, following = true, {}, {}, {}, {}, {},
                mode = de.foggymap.app.core.TravelMode.BIKE, modeByHand = false)
        }
    }

    @Test @Config(qualifiers = "w400dp-h860dp-xhdpi")
    fun hudStart() = shot("hud-start") {
        Box(Modifier.fillMaxSize().background(mapBackground())) {
            MapControls(TrackingUi(), 0, mapBearing = 0f, northUp = true, tilted = false, following = false, {}, {}, {}, {}, {})
        }
    }

    @Test @Config(qualifiers = "w360dp-h740dp-xhdpi")
    fun hudStartNarrow() = shot("hud-start-schmal") {
        Box(Modifier.fillMaxSize().background(mapBackground())) {
            MapControls(TrackingUi(), 0, mapBearing = 0f, northUp = false, tilted = true, following = true, {}, {}, {}, {}, {})
        }
    }

    /** All figure pictures (walking, bike, car, train; both colours) and the cones on one sheet. */
    @Test fun figureSheet() {
        val imgs = Figures.all(2).values.toList()
        val cw = 132; val ch = 100; val cols = 9
        val rows = (imgs.size + cols - 1) / cols
        val sheet = Bitmap.createBitmap(cols * cw, rows * ch, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(sheet)
        c.drawColor(0xFF14162C.toInt())
        imgs.forEachIndexed { i, b -> c.drawBitmap(b, ((i % cols) * cw).toFloat(), ((i / cols) * ch + (ch - b.height)).toFloat().coerceAtLeast(0f), null) }
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, "figuren.png")).use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The problem report as text, to check it holds no place, name or code. */
    @Test fun problemReport() {
        val app = de.foggymap.app.App
        app.prefs.groupCode = "ABCDEFGHJKMN"
        try {
            val report = kotlinx.coroutines.runBlocking {
                de.foggymap.app.data.Diagnostics.buildReport(compose.activity, "Fenster flackern beim Drehen", true, null)
            }
            File("build/screenshots").apply { mkdirs() }.resolve("bericht.txt").writeText(report)
            org.junit.Assert.assertTrue(report.contains("FoggyMap-Problembericht"))
            org.junit.Assert.assertFalse("no group code in the report", report.contains("ABCDEFGHJKMN"))
        } finally { app.prefs.groupCode = "" }
    }

    @Test @Config(qualifiers = "w400dp-h860dp-xhdpi")
    fun modePicker() = shot("fortbewegung") {
        Sheet { ModeOptions(de.foggymap.app.core.TravelMode.TRAIN) {} }
    }

    /** Large system font (130 %): the tab bar must not cut anything off. */
    @Test fun menuLargeFont() {
        org.robolectric.RuntimeEnvironment.setFontScale(1.3f)
        try { shot("menu-grosse-schrift") { Sheet { MenuContent(group, null, {}, MenuTab.INFO, {}, {}) } } }
        finally { org.robolectric.RuntimeEnvironment.setFontScale(1f) }
    }
}
