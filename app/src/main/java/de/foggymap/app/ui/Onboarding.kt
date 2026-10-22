package de.foggymap.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.foggymap.app.R

private class Page(val title: String, val text: String, val icon: @Composable () -> Painter)

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pages = listOf(
        Page("Die Welt liegt im Nebel", "Die ganze Karte ist zunächst verdeckt. Nur wo du selbst warst – zu Fuß, mit dem Rad oder im Auto – lichtet sich der Nebel.") { painterResource(R.drawable.ic_cloud) },
        Page("Straße für Straße", "Betrittst du eine Straße, wird der ganze Abschnitt bis zur nächsten Kreuzung sichtbar – samt der angrenzenden Häuserblöcke. Andere Straßen bleiben verborgen, bis du sie selbst betrittst.") { painterResource(R.drawable.ic_road) },
        Page("Gemeinsam erkunden", "Lade Freunde per Gruppencode ein und deckt zusammen dieselbe Karte auf. Geteilt werden nur aufgedeckte Flächen – nie dein GPS-Verlauf.") { painterResource(R.drawable.ic_group) },
        Page("Deine Daten, dein Akku", "Der Standort wird nur genutzt, während du die Erkundung startest – sichtbar an der Benachrichtigung. GPS pausiert automatisch im Stillstand. Alles wird auf deinem Handy gespeichert und funktioniert auch offline.") { rememberVectorPainter(Icons.Filled.Lock) },
    )
    var index by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF15123A), Fog.Bg, Fog.Bg)))) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_logo), null, tint = Color.Unspecified, modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(10.dp))
                LogoTitle(26)
            }
            Text("ENTDECKE DEINE WELT", color = Fog.Muted, fontSize = 11.sp, letterSpacing = 3.sp, modifier = Modifier.padding(start = 44.dp))
            Spacer(Modifier.weight(1f))
            AnimatedContent(index, transitionSpec = {
                (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
            }, label = "onboarding") { i ->
                val p = pages[i]
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.size(120.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Fog.Violet.copy(alpha = 0.55f), Color.Transparent))),
                        contentAlignment = Alignment.Center) {
                        Icon(p.icon(), null, tint = Fog.Lavender, modifier = Modifier.size(56.dp))
                    }
                    Spacer(Modifier.height(28.dp))
                    Text(p.title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Fog.Text, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(p.text, fontSize = 15.sp, color = Fog.Text.copy(alpha = 0.8f), textAlign = TextAlign.Center, lineHeight = 22.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                pages.indices.forEach { i ->
                    Box(Modifier.padding(4.dp).size(if (i == index) 22.dp else 8.dp, 8.dp).clip(CircleShape)
                        .background(if (i == index) Fog.Violet2 else Fog.Muted.copy(alpha = 0.4f)))
                }
            }
            Spacer(Modifier.height(20.dp))
            PrimaryButton(if (index < pages.lastIndex) "Weiter" else "Los geht's", Modifier.fillMaxWidth()) {
                if (index < pages.lastIndex) index++ else onDone()
            }
            if (index < pages.lastIndex) TextButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Überspringen", color = Fog.Muted)
            }
        }
    }
}
