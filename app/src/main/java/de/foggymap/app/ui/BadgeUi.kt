package de.foggymap.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.foggymap.app.App
import de.foggymap.app.core.Badges
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// =================================================================== the medal

/** A badge as a medal: star burst, golden ring, coloured disc, the symbol in the middle. [shine]: 0..1 = light sweep. */
@Composable
fun BadgeMedal(badge: Badges.Badge, size: Dp, modifier: Modifier = Modifier, shine: Float = -1f, glow: Float = 0f) {
    val color = Color(badge.color); val accent = Color(badge.accent)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val r = this.size.minDimension / 2
            val c = center
            if (glow > 0f) drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.55f * glow), Color.Transparent), c, r * 1.25f), r * 1.25f, c)
            // star burst
            val star = Path()
            val points = 16
            for (i in 0 until points * 2) {
                val a = PI * i / points - PI / 2
                val rr = if (i % 2 == 0) r * 0.98f else r * 0.80f
                val x = c.x + (rr * cos(a)).toFloat(); val y = c.y + (rr * sin(a)).toFloat()
                if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
            }
            star.close()
            drawPath(star, Brush.radialGradient(listOf(accent, accent.copy(alpha = 0.85f), lerp(accent, Color.Black, 0.35f)), c, r))
            // golden ring
            drawCircle(Brush.sweepGradient(listOf(lerp(accent, Color.White, 0.55f), accent, lerp(accent, Color.Black, 0.3f), accent,
                lerp(accent, Color.White, 0.55f)), c), r * 0.74f, c)
            // coloured disc
            val disc = r * 0.64f
            drawCircle(Brush.radialGradient(listOf(lerp(color, Color.White, 0.35f), color, lerp(color, Color.Black, 0.45f)),
                Offset(c.x - disc * 0.3f, c.y - disc * 0.35f), disc * 1.6f), disc, c)
            drawCircle(Color.White.copy(alpha = 0.22f), disc * 0.88f, c, style = Stroke(width = r * 0.025f))
            // light sweep across the disc
            if (shine in 0f..1f) {
                val clip = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, disc)) }
                clipPath(clip) {
                    val x = c.x - disc * 1.6f + shine * disc * 3.2f
                    rotate(25f, c) {
                        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
                            x - disc * 0.25f, x + disc * 0.25f), Offset(x - disc * 0.25f, c.y - disc * 2), Size(disc * 0.5f, disc * 4))
                    }
                }
            }
        }
        Text(badge.symbol, color = Color.White, fontWeight = FontWeight.Black, fontSize = (size.value * 0.34f).sp,
            style = TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 4f), 10f)))
    }
}

private fun lerp(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t,
    a.blue + (b.blue - a.blue) * t, a.alpha + (b.alpha - a.alpha) * t)

// =================================================================== the big moment

/** Shows the celebration for every newly earned badge, one after another (above everything). */
@Composable
fun BadgeCelebrationHost() {
    val pending by App.badges.celebrate.collectAsState()
    val badge = pending.firstOrNull() ?: return
    androidx.compose.runtime.key(badge.id) { BadgeCelebration(badge) { App.badges.celebrated(badge.id) } }
}

private class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var rot: Float, val vr: Float,
                       val color: Color, val w: Float, val h: Float, val round: Boolean)

/**
 * Full-screen "Neues Abzeichen" moment: the screen darkens, light rays turn, the medal spins in and
 * lands with a bounce (and a buzz), confetti bursts out, a light sweep runs over the medal, then the
 * title and the button appear. [static] = final frame only (screenshot test).
 */
@Composable
fun BadgeCelebration(badge: Badges.Badge, static: Boolean = false, onDone: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val appear = remember { Animatable(if (static) 1f else 0f) }
    val scale = remember { Animatable(if (static) 1f else 0f) }
    val flip = remember { Animatable(if (static) 0f else 720f) }
    val shine = remember { Animatable(if (static) 0.45f else -1f) }
    var showText by remember { mutableStateOf(static) }
    var showButton by remember { mutableStateOf(static) }
    var frame by remember { mutableLongStateOf(0L) }
    val particles = remember { ArrayList<Particle>() }
    val accent = Color(badge.accent); val color = Color(badge.color)
    val palette = listOf(color, accent, Color(0xFF22D3EE), Color(0xFFF472B6), Color.White, lerp(color, Color.White, 0.5f))

    val rays = rememberInfiniteTransition(label = "rays")
    val rayTurn by rays.animateFloat(0f, 360f, infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "turn")
    val pulse by rays.animateFloat(0.7f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "pulse")

    LaunchedEffect(badge.id) {
        if (static) return@LaunchedEffect
        appear.animateTo(1f, tween(400))
        launch { flip.animateTo(0f, tween(1400, easing = FastOutSlowInEasing)) }
        scale.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 160f))
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        // confetti burst from the medal
        val rnd = Random(badge.id.hashCode())
        repeat(140) {
            val a = rnd.nextDouble(0.0, 2 * PI); val v = rnd.nextDouble(500.0, 1500.0).toFloat()
            particles += Particle(0f, 0f, (cos(a) * v).toFloat(), (sin(a) * v).toFloat() - 500f, rnd.nextFloat() * 360f,
                rnd.nextFloat() * 720f - 360f, palette[rnd.nextInt(palette.size)], 6f + rnd.nextFloat() * 8f, 4f + rnd.nextFloat() * 6f, rnd.nextBoolean())
        }
        showText = true
        launch {
            var last = 0L
            val start = System.nanoTime()
            while (isActive && System.nanoTime() - start < 6_000_000_000L) {
                androidx.compose.runtime.withFrameNanos { now ->
                    val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                    last = now
                    for (p in particles) {
                        p.vy += 1400f * dt; p.vx *= (1f - 0.9f * dt); p.vy *= (1f - 0.4f * dt)
                        p.x += p.vx * dt; p.y += p.vy * dt; p.rot += p.vr * dt
                    }
                    frame = now
                }
            }
            particles.clear(); frame = -1
        }
        delay(450)
        shine.snapTo(0f); shine.animateTo(1f, tween(900))
        showButton = true
        while (isActive) { delay(2600); shine.snapTo(0f); shine.animateTo(1f, tween(900)) }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF05060F).copy(alpha = 0.9f * appear.value))
        .clickable(remember { MutableInteractionSource() }, indication = null) { if (showButton) onDone() },
        contentAlignment = Alignment.Center) {
        // turning light rays behind the medal
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = appear.value }) {
            val c = Offset(size.width / 2, size.height * 0.40f)
            val r = size.maxDimension
            rotate(rayTurn, c) {
                for (i in 0 until 14) {
                    val a0 = 2 * PI * i / 14; val a1 = a0 + PI / 22
                    val path = Path().apply {
                        moveTo(c.x, c.y)
                        lineTo(c.x + (r * cos(a0)).toFloat(), c.y + (r * sin(a0)).toFloat())
                        lineTo(c.x + (r * cos(a1)).toFloat(), c.y + (r * sin(a1)).toFloat()); close()
                    }
                    drawPath(path, Brush.radialGradient(listOf(accent.copy(alpha = 0.22f), Color.Transparent), c, r * 0.6f))
                }
            }
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.45f * pulse), Color.Transparent), c, size.width * 0.55f), size.width * 0.55f, c)
            // confetti
            if (frame != 0L) for (p in particles) {
                val pos = Offset(c.x + p.x, c.y + p.y)
                if (pos.y > size.height + 40) continue
                rotate(p.rot, pos) {
                    if (p.round) drawCircle(p.color, p.w / 2, pos)
                    else drawRect(p.color, Offset(pos.x - p.w / 2, pos.y - p.h / 2), Size(p.w, p.h))
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(10.dp))
            Text("NEUES ABZEICHEN", color = Fog.Lavender.copy(alpha = appear.value), fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
            Spacer(Modifier.height(26.dp))
            BadgeMedal(badge, 196.dp, Modifier.graphicsLayer {
                scaleX = scale.value; scaleY = scale.value
                rotationY = flip.value
                cameraDistance = 14f * density
            }, shine = shine.value, glow = pulse)
            Spacer(Modifier.height(30.dp))
            AnimatedVisibility(showText, enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { it / 2 }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(badge.title, fontSize = 38.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
                        style = TextStyle(brush = Brush.linearGradient(listOf(lerp(accent, Color.White, 0.4f), accent, lerp(color, Color.White, 0.3f)))))
                    Spacer(Modifier.height(10.dp))
                    Text(badge.text, color = Fog.Text.copy(alpha = 0.85f), fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(30.dp))
            AnimatedVisibility(showButton, enter = fadeIn(tween(400))) {
                PrimaryButton("Yeah!", Modifier.width(200.dp)) { onDone() }
            }
        }
    }
}

// =================================================================== in the menu

/** Your badges (Menü → Info) and your player ID. */
@Composable
internal fun BadgesSection() {
    val earned by App.badges.earned.collectAsState()
    val clipboard = LocalClipboardManager.current
    SectionTitle("Abzeichen")
    if (earned.isEmpty()) {
        Text("Noch keine Abzeichen.", color = Fog.Muted, fontSize = 13.sp)
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            for (b in earned) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(84.dp)) {
                BadgeMedal(b, 72.dp)
                Spacer(Modifier.height(4.dp))
                Text(b.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2)
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Deine Spieler-ID: ", color = Fog.Muted, fontSize = 12.sp)
        Text(App.badges.playerId, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Fog.Lavender)
        TextButton(onClick = { clipboard.setText(AnnotatedString(App.badges.playerId)) }) { Text("kopieren", color = Fog.Violet2, fontSize = 12.sp) }
    }
}

/** Admin only (after entering the admin code): hand out badges and create new ones. */
@Composable
internal fun AdminSection() {
    val scope = rememberCoroutineScope()
    val isAdmin by App.badges.isAdmin.collectAsState()
    var askCode by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    if (!isAdmin) {
        TextButton(onClick = { askCode = !askCode }) { Text("Admin", color = Fog.Muted.copy(alpha = 0.6f), fontSize = 12.sp) }
        if (askCode) {
            OutlinedTextField(code, { code = it.take(24) }, label = { Text("Admin-Code") }, singleLine = true,
                colors = fogTextFieldColors(), modifier = Modifier.fillMaxWidth())
            TextButton(onClick = {
                busy = true
                scope.launch {
                    result = if (App.badges.unlockAdmin(code)) null else "Falscher Code."
                    busy = false; code = ""
                }
            }, enabled = !busy && code.length >= 16) { Text(if (busy) "Prüfe …" else "Freischalten", color = Fog.Violet2) }
            result?.let { Text(it, color = Fog.Danger, fontSize = 12.sp) }
        }
        return
    }

    SectionTitle("Admin: Abzeichen vergeben")
    val defined by App.badges.defined.collectAsState()
    val all = remember(defined) { App.badges.all() }
    var badgeId by remember { mutableStateOf(all.firstOrNull()?.id ?: "") }
    var target by remember { mutableStateOf(0) }    // 0 = everybody so far, 1 = player ID, 2 = group member
    var playerId by remember { mutableStateOf("") }
    val sync by App.sync.state.collectAsState()
    val members = sync.members.filter { !it.isMe }
    var member by remember { mutableStateOf<String?>(null) }
    var revoke by remember { mutableStateOf(false) }

    GlassCard(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (b in all) {
                val sel = b.id == badgeId
                Column(Modifier.clip(RoundedCornerShape(14.dp)).border(1.5.dp, if (sel) Fog.Violet else Color.Transparent, RoundedCornerShape(14.dp))
                    .clickable { badgeId = b.id }.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    BadgeMedal(b, 48.dp)
                    Text(b.title, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Segmented(listOf("Alle bisher", "Spieler-ID", "Gruppe"), target) { target = it }
        Spacer(Modifier.height(8.dp))
        when (target) {
            0 -> Text("Alle, die FoggyMap bis jetzt installiert haben. Wer die App später installiert, bekommt es nicht.",
                color = Fog.Muted, fontSize = 12.sp, lineHeight = 16.sp)
            1 -> OutlinedTextField(playerId, { playerId = it.take(12) }, label = { Text("Spieler-ID (z. B. AB3K-7QXZ)") }, singleLine = true,
                colors = fogTextFieldColors(), modifier = Modifier.fillMaxWidth())
            else -> if (members.isEmpty()) Text("Keine anderen Mitglieder in deiner Gruppe.", color = Fog.Muted, fontSize = 12.sp)
                else Column { for (m in members) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { member = m.key }
                    .background(if (member == m.key) Fog.SurfaceHigh else Color.Transparent).padding(10.dp)) { Text(m.name, fontSize = 14.sp) } }
        }
        SwitchRow("Zurücknehmen", "Statt zu verleihen, das Abzeichen wieder entziehen", revoke) { revoke = it }
        val ready = badgeId.isNotEmpty() && when (target) { 0 -> true; 1 -> Badges.normalizeId(playerId).length == 8; else -> member != null }
        PrimaryButton(if (busy) "…" else if (revoke) "Zurücknehmen" else "Verleihen", Modifier.fillMaxWidth(), enabled = ready && !busy) {
            busy = true; result = null
            val award = when (target) {
                0 -> Badges.Award(badgeId, "all", before = System.currentTimeMillis() / 1000, revoked = revoke)
                1 -> Badges.Award(badgeId, "id", id = Badges.normalizeId(playerId), revoked = revoke)
                else -> Badges.Award(badgeId, "pk", pk = member ?: "", revoked = revoke)
            }
            scope.launch {
                val n = App.badges.award(award)
                result = if (n > 0) "Erledigt – an $n Relays verteilt. Empfänger sehen es beim nächsten App-Start." else "Keine Verbindung zu den Relays."
                busy = false
            }
        }
        result?.let { Spacer(Modifier.height(6.dp)); Text(it, color = Fog.Muted, fontSize = 12.sp) }
    }

    SectionTitle("Admin: Neues Abzeichen")
    var title by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var symbol by remember { mutableStateOf("★") }
    var colorIdx by remember { mutableStateOf(1) }
    GlassCard(Modifier.fillMaxWidth()) {
        OutlinedTextField(title, { title = it.take(30) }, label = { Text("Name") }, singleLine = true, colors = fogTextFieldColors(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(text, { text = it.take(160) }, label = { Text("Beschreibung") }, colors = fogTextFieldColors(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(symbol, { symbol = it.take(2) }, label = { Text("Symbol (Buchstabe oder Emoji)") }, singleLine = true, colors = fogTextFieldColors(), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Badges.COLORS.forEachIndexed { i, c ->
                Box(Modifier.size(30.dp).clip(CircleShape).background(Color(c)).border(2.dp, if (i == colorIdx) Color.White else Color.Transparent, CircleShape)
                    .clickable { colorIdx = i })
            }
            Spacer(Modifier.weight(1f))
            if (title.isNotBlank()) BadgeMedal(Badges.Badge("preview", title, text, symbol.ifBlank { "★" }, Badges.COLORS[colorIdx], 0xFFF5C26B.toInt()), 52.dp)
        }
        Spacer(Modifier.height(8.dp))
        PrimaryButton("Abzeichen anlegen", Modifier.fillMaxWidth(), secondary = true, enabled = title.isNotBlank() && !busy) {
            busy = true
            val id = title.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("").trim('-').take(24).ifEmpty { "badge" }
            scope.launch {
                val n = App.badges.define(Badges.Badge(id, title.trim(), text.trim(), symbol.ifBlank { "★" }, Badges.COLORS[colorIdx], 0xFFF5C26B.toInt()))
                result = if (n > 0) "„${title.trim()}“ angelegt. Jetzt oben auswählen und verleihen." else "Keine Verbindung zu den Relays."
                busy = false; title = ""; text = ""
            }
        }
    }
    TextButton(onClick = { App.badges.lockAdmin() }) { Text("Admin abmelden", color = Fog.Muted, fontSize = 12.sp) }
}
