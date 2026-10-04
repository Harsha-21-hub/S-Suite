package com.hesi.slog

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.launch

/**
 * Nothing-style (dot-matrix) visuals: streak icons, avatar, milestone celebration.
 * Patterns: '#' = main dot, 'o' = accent dot, '.' = faint "off" dot (the matrix look).
 */
object DotGlyphs {
    // Outline glyphs: they stay crisp at small sizes (no faint "off" dots around them)
    /** Current streak: a teardrop flame with a white-hot core. */
    val FLAME = listOf("....#....", "...##....", "...#.#...", "..#...#..", ".#..o..#.", ".#.ooo.#.", "#..ooo..#", ".#..o..#.", "..#####..")

    /** Max streak: a crown with three jewels on the band. */
    val CROWN = listOf("o...o...o", "##.#.#.##", "#.#...#.#", "#.......#", "#.o.o.o.#", "#########")

    /** Monthly streak: a calendar page. */
    val MONTH = listOf(".#.....#.", "#########", "#.......#", "#.o.o.o.#", "#.......#", "#.o.o.o.#", "#.......#", "#########")

    /** Hollow block arrows for the calendar drag control. */
    val ARROW_UP = listOf("..#..", ".#.#.", "#...#", "##.##", ".#.#.", ".#.#.", ".###.")
    val ARROW_DOWN = ARROW_UP.reversed()

    /** 5x7 digits for the celebration number. */
    val DIGITS = mapOf(
        '0' to listOf(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."),
        '1' to listOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
        '2' to listOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
        '3' to listOf("####.", "....#", "....#", ".###.", "....#", "....#", "####."),
        '4' to listOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
        '5' to listOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
        '6' to listOf("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
        '7' to listOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
        '8' to listOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        '9' to listOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##..")
    )
}

/** Draws a dot pattern. [lit] 0..1 lights the dots up in a sweep (for animations). */
@Composable
fun DotIcon(
    pattern: List<String>,
    size: Dp,
    color: Color = Color.White,
    accent: Color = Accent,
    offColor: Color = Color.White.copy(alpha = 0.08f),
    lit: Float = 1f,
    modifier: Modifier = Modifier
) {
    val rows = pattern.size
    val cols = pattern.maxOf { it.length }
    Canvas(modifier = modifier.size(size * (cols.toFloat() / rows), size)) {
        val cell = min(this.size.width / cols, this.size.height / rows)
        val r = cell * 0.4f
        val total = rows * cols
        for (y in 0 until rows) for (x in 0 until cols) {
            val ch = pattern[y].getOrElse(x) { '.' }
            val order = (y * cols + x).toFloat() / total
            val on = ch != '.' && order <= lit
            val c = when {
                !on -> offColor
                ch == 'o' -> accent
                else -> color
            }
            if (c.alpha > 0f) drawCircle(c, r, Offset(x * cell + cell / 2, y * cell + cell / 2))
        }
    }
}

/** Icon + number that rolls up/down when it changes. Used for MONTH / MAX / CURRENT. */
@Composable
fun StreakStat(
    label: String,
    value: Int,
    glyph: List<String>,
    font: FontFamily,
    iconColor: Color,
    big: Boolean = true
) {
    Column(horizontalAlignment = if (big) Alignment.Start else Alignment.CenterHorizontally) {
        if (big) androidx.compose.material3.Text(label, color = Color.Gray, fontSize = 12.sp, fontFamily = font)
        Row(verticalAlignment = Alignment.CenterVertically) {
            DotIcon(
                glyph,
                size = if (big) 18.dp else 10.dp,
                color = iconColor,
                accent = if (iconColor == Accent) Color.White else Accent,
                offColor = Color.Transparent
            )
            Spacer(Modifier.width(if (big) 8.dp else 4.dp))
            AnimatedContent(
                targetState = value,
                transitionSpec = {
                    val up = targetState > initialState
                    (slideInVertically(tween(260)) { if (up) it else -it } + fadeIn(tween(260))) togetherWith
                            (slideOutVertically(tween(260)) { if (up) -it else it } + fadeOut(tween(200)))
                },
                label = "streak-$label"
            ) { v ->
                androidx.compose.material3.Text(
                    v.toString(),
                    color = Color.White,
                    fontSize = if (big) 22.sp else 10.sp,
                    fontFamily = font
                )
            }
        }
    }
}

/**
 * Nothing-style avatar: black disc, a ring of white dots, initials in the dot font and the
 * signature red dot.
 */
@Composable
fun NothingAvatar(initials: String, font: FontFamily, size: Dp = 42.dp, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = this.size.minDimension / 2
            drawCircle(Color.Black, radius)
            val dots = 28
            val ringR = radius - 2.dp.toPx()
            for (i in 0 until dots) {
                val a = 2 * PI * i / dots - PI / 2
                drawCircle(
                    Color.White.copy(alpha = if (i % 7 == 0) 0.95f else 0.55f),
                    radius = 1.1.dp.toPx(),
                    center = Offset(center.x + (ringR * cos(a)).toFloat(), center.y + (ringR * sin(a)).toFloat())
                )
            }
            // red "recording" dot, top right
            drawCircle(
                Accent,
                radius = 2.6.dp.toPx(),
                center = Offset(center.x + radius * 0.70f, center.y - radius * 0.70f)
            )
        }
        androidx.compose.material3.Text(
            text = initials,
            color = Color.White,
            fontFamily = font,
            fontSize = if (initials.length >= 3) 12.sp else 15.sp,
            letterSpacing = 1.sp
        )
    }
}

/** Full-screen dot-matrix celebration for a max-streak milestone. Tap anywhere to close. */
@Composable
fun MilestoneCelebration(
    days: Int,
    labels: List<String>,
    font: FontFamily,
    onDismiss: () -> Unit
) {
    val appear = remember(days) { Animatable(0f) }
    val digitsLit = remember(days) { Animatable(0f) }
    val burst = remember(days) { Animatable(0f) }
    LaunchedEffect(days) {
        appear.animateTo(1f, tween(350, easing = FastOutSlowInEasing))
        burst.snapTo(0f)
        kotlinx.coroutines.coroutineScope {
            launch { digitsLit.animateTo(1f, tween(900, easing = LinearEasing)) }
            launch { burst.animateTo(1f, tween(1400, easing = FastOutSlowInEasing)) }
        }
    }
    val spin = rememberInfiniteTransition(label = "ring")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "angle")
    val pulse by spin.animateFloat(0.85f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")

    val particles = remember(days) {
        val rnd = Random(days)
        List(56) { Triple(rnd.nextFloat() * 2 * PI.toFloat(), 0.55f + rnd.nextFloat() * 0.6f, rnd.nextInt(3)) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = appear.value }
            .background(Color.Black.copy(alpha = 0.92f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        // rotating dotted ring + bursting dots
        Canvas(
            modifier = Modifier
                .size(300.dp)
                .graphicsLayer { scaleX = 0.6f + 0.4f * appear.value; scaleY = scaleX }
        ) {
            val r = size.minDimension / 2 * 0.92f
            val n = 48
            for (i in 0 until n) {
                val a = Math.toRadians((angle + i * 360f / n).toDouble())
                val lit = i % 4 == 0
                drawCircle(
                    if (lit) Accent else Color.White.copy(alpha = 0.35f),
                    radius = (if (lit) 3.2f else 2f).dp.toPx() * if (lit) pulse else 1f,
                    center = Offset(center.x + (r * cos(a)).toFloat(), center.y + (r * sin(a)).toFloat())
                )
            }
            val b = burst.value
            if (b in 0.001f..0.999f) {
                particles.forEach { (a, speed, kind) ->
                    val d = r * speed * b * 1.25f
                    val c = when (kind) {
                        0 -> Accent
                        1 -> Color.White
                        else -> Color(0xFFFFB300)
                    }
                    drawCircle(
                        c.copy(alpha = (1f - b).coerceIn(0f, 1f)),
                        radius = 2.4.dp.toPx(),
                        center = Offset(center.x + d * cos(a), center.y + d * sin(a))
                    )
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DotIcon(DotGlyphs.CROWN, size = 34.dp, color = Color.White, lit = digitsLit.value)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                days.toString().forEach { ch ->
                    DotIcon(
                        DotGlyphs.DIGITS.getValue(ch),
                        size = 64.dp,
                        color = Color.White,
                        offColor = Color.White.copy(alpha = 0.06f),
                        lit = digitsLit.value
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            androidx.compose.material3.Text(
                "DAY STREAK",
                color = Accent,
                fontFamily = font,
                fontSize = 22.sp,
                letterSpacing = 4.sp
            )
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.Text(
                labels.joinToString("  ·  "),
                color = Color.LightGray,
                fontFamily = font,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(Modifier.height(6.dp))
            androidx.compose.material3.Text(
                celebrationLine(days),
                color = Color.White,
                fontFamily = font,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(Modifier.height(28.dp))
            androidx.compose.material3.Text(
                "TAP TO CONTINUE",
                color = Color.Gray,
                fontFamily = font,
                fontSize = 11.sp,
                modifier = Modifier.graphicsLayer { alpha = pulse }
            )
        }
    }
}

fun celebrationLine(days: Int): String = when {
    days >= 365 && days % 365 == 0 -> "${days / 365} YEAR${if (days >= 730) "S" else ""} UNBROKEN. LEGEND."
    days >= 300 -> "UNSTOPPABLE."
    days >= 200 -> "THIS IS WHO YOU ARE NOW."
    days >= 100 -> "TRIPLE DIGITS. RESPECT."
    days >= 50 -> "HALF A HUNDRED. KEEP GOING."
    days >= 10 -> "DOUBLE DIGITS. HABIT UNLOCKED."
    else -> "GREAT START. KEEP THE CHAIN."
}

/**
 * Calendar drag control:  ↑ (DRAG) ↓  - dot-matrix outline arrows and an oval pill with red text
 * and outline on a grey frosted (matte) fill. The arrow you can drag towards is lit and bobs gently
 * (collapsed -> down = show the month, expanded -> up = back to this week).
 */
@Composable
fun DragPill(expanded: Boolean, font: FontFamily, modifier: Modifier = Modifier) {
    val bob = rememberInfiniteTransition(label = "dragBob")
    val shift by bob.animateFloat(
        0f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "shift"
    )
    val upAlpha by animateFloatAsState(if (expanded) 1f else 0.3f, tween(250), label = "upA")
    val downAlpha by animateFloatAsState(if (expanded) 0.3f else 1f, tween(250), label = "downA")
    // grey frosted glass: translucent light grey, a touch brighter while expanded
    val fill by animateColorAsState(
        Color(0xFF9E9E9E).copy(alpha = if (expanded) 0.24f else 0.18f), tween(250), label = "pillFill"
    )
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        DotIcon(
            DotGlyphs.ARROW_UP, size = 16.dp, color = Accent, offColor = Color.Transparent,
            modifier = Modifier.graphicsLayer {
                alpha = upAlpha
                translationY = if (expanded) -3.dp.toPx() * shift else 0f
            }
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                // frosted / matte grey: slightly lighter at the top, no gloss
                .background(Brush.verticalGradient(listOf(fill.copy(alpha = fill.alpha + 0.06f), fill)))
                .border(1.5.dp, Accent, RoundedCornerShape(50))
                .padding(horizontal = 18.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Text(
                "DRAG",
                color = Accent,
                fontFamily = font,
                fontSize = 13.sp,
                letterSpacing = 2.sp
            )
        }
        DotIcon(
            DotGlyphs.ARROW_DOWN, size = 16.dp, color = Accent, offColor = Color.Transparent,
            modifier = Modifier.graphicsLayer {
                alpha = downAlpha
                translationY = if (!expanded) 3.dp.toPx() * shift else 0f
            }
        )
    }
}

/** Settings button: a Nothing-style dotted gear (ring of dots, 8 teeth, red centre). */
@Composable
fun NothingGear(size: Dp = 30.dp, rotation: Float = 0f, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size).graphicsLayer { rotationZ = rotation }) {
        val unit = this.size.minDimension / 104f   // drawn on a 104 x 104 grid, centre at 52
        val c = Offset(this.size.width / 2, this.size.height / 2)
        for (i in 0 until 16) {
            val a = 2 * PI * i / 16
            drawCircle(Color.White, 5.2f * unit, Offset(c.x + 28 * unit * cos(a).toFloat(), c.y + 28 * unit * sin(a).toFloat()))
        }
        for (t in 0 until 8) {
            val a = 2 * PI * t / 8 - PI / 2
            drawCircle(Color.White, 6.2f * unit, Offset(c.x + 44 * unit * cos(a).toFloat(), c.y + 44 * unit * sin(a).toFloat()))
        }
        drawCircle(Accent, 7.5f * unit, c)
    }
}
