package de.foggymap.app.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

object Fog {
    val Bg = Color(0xFF090B18)
    val Surface = Color(0xFF14162C)
    val SurfaceHigh = Color(0xFF1D1F3D)
    val Line = Color(0x1FFFFFFF)
    val Violet = Color(0xFF8B5CF6)
    val Violet2 = Color(0xFFA78BFA)
    val Lavender = Color(0xFFC4B5FD)
    val Deep = Color(0xFF5B34C9)
    val Cyan = Color(0xFF22D3EE)
    val Text = Color(0xFFEEEAFF)
    val Muted = Color(0xFF9A97BF)
    val Danger = Color(0xFFF87171)
    val Success = Color(0xFF34D399)
    val Amber = Color(0xFFF7B860)
    val accent = Brush.horizontalGradient(listOf(Violet2, Violet, Color(0xFF6D3FE0)))
    val button = Brush.linearGradient(listOf(Color(0xFF9B6BFF), Color(0xFF6A3BE4)))
}

@Composable
fun FoggyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Fog.Violet, onPrimary = Color.White, secondary = Fog.Cyan, background = Fog.Bg, surface = Fog.Surface,
            onSurface = Fog.Text, onBackground = Fog.Text, surfaceVariant = Fog.SurfaceHigh, onSurfaceVariant = Fog.Muted,
            outline = Fog.Line, error = Fog.Danger,
        ),
        content = content,
    )
}

fun Double.de(decimals: Int = 1): String = String.format(Locale.GERMANY, "%.${decimals}f", this)
fun Int.de(): String = String.format(Locale.GERMANY, "%,d", this)

@Composable
fun LogoTitle(size: Int = 22) {
    Text(buildAnnotatedString {
        withStyle(SpanStyle(color = Color.White)) { append("Foggy") }
        withStyle(SpanStyle(color = Fog.Violet2)) { append("Map") }
    }, fontSize = size.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, corner: Dp = 24.dp, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(corner),
        color = Fog.Surface.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, Fog.Line),
        shadowElevation = 12.dp,
    ) { Column(Modifier.padding(padding), content = content) }
}

@Composable
fun RoundButton(painter: Painter, description: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(50.dp).clip(RoundedCornerShape(16.dp))
            .background(if (active) Fog.Violet.copy(alpha = 0.9f) else Fog.Surface.copy(alpha = 0.9f))
            .border(1.dp, if (active) Fog.Lavender.copy(alpha = 0.6f) else Fog.Line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(painter, description, tint = if (active) Color.White else Fog.Lavender, modifier = Modifier.size(24.dp)) }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, icon: Painter? = null, secondary: Boolean = false,
                  enabled: Boolean = true, onClick: () -> Unit) {
    val bg = if (secondary) Brush.linearGradient(listOf(Fog.SurfaceHigh, Fog.SurfaceHigh)) else Fog.button
    Row(
        modifier.height(54.dp).clip(RoundedCornerShape(18.dp)).background(bg)
            .border(1.dp, if (secondary) Fog.Line else Color.White.copy(alpha = 0.15f), RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)) }
        Text(text, color = if (enabled) Color.White else Fog.Muted, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = 0.5.sp)
    }
}

@Composable
fun AccentProgress(fraction: Float, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(Color.White.copy(alpha = 0.08f))) {
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) Box(Modifier.fillMaxWidth(maxOf(f, 0.015f)).height(height).clip(CircleShape).background(Fog.accent))
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text.uppercase(), color = Fog.Violet2, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
}

@Composable
fun StatTile(value: String, label: String, painter: Painter, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(18.dp)).background(Fog.SurfaceHigh).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Fog.Violet.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(painter, null, tint = Fog.Lavender, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(value, color = Fog.Text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(label, color = Fog.Muted, fontSize = 11.sp, lineHeight = 13.sp)
        }
    }
}

@Composable
fun InfoBox(text: String, painter: Painter, tint: Color = Fog.Cyan) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(tint.copy(alpha = 0.08f))
        .border(1.dp, tint.copy(alpha = 0.25f), RoundedCornerShape(16.dp)).padding(12.dp)) {
        Icon(painter, null, tint = tint, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Fog.Text.copy(alpha = 0.85f), fontSize = 12.5.sp, lineHeight = 17.sp)
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Fog.SurfaceHigh).padding(4.dp)) {
        options.forEachIndexed { i, o ->
            val sel = i == selected
            Box(Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (sel) Fog.button else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)))
                .clickable { onSelect(i) }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text(o, color = if (sel) Color.White else Fog.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun fogTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Fog.Violet2, unfocusedBorderColor = Fog.Line, focusedTextColor = Fog.Text, unfocusedTextColor = Fog.Text,
    cursorColor = Fog.Violet2, focusedLabelColor = Fog.Violet2, unfocusedLabelColor = Fog.Muted,
    focusedContainerColor = Fog.SurfaceHigh, unfocusedContainerColor = Fog.SurfaceHigh,
)

@Composable
fun fogSwitchColors() = SwitchDefaults.colors(
    checkedTrackColor = Fog.Violet, checkedThumbColor = Color.White, uncheckedTrackColor = Fog.SurfaceHigh,
    uncheckedThumbColor = Fog.Muted, uncheckedBorderColor = Fog.Line,
)
