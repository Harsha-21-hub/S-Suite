package com.sdrive.shared.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.sdrive.shared.AppState
import com.sdrive.shared.Kind
import com.sdrive.shared.R
import com.sdrive.shared.Thumbs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlin.math.abs

/**
 * Nothing-style palette (same for watch, phone and the PC web page):
 * pure black, white type, grey surfaces, white as the primary accent and one red for "live"/warning states.
 * Only colours changed here - every size, spacing and layout is exactly as before.
 */
object Pal {
    val bg = Color(0xFF000000)
    val surface = Color(0xFF141414)
    val surface2 = Color(0xFF242424)
    val text = Color(0xFFFFFFFF)
    val dim = Color(0xFF8C8C8C)
    val accent = Color(0xFFFFFFFF)
    val accentDim = Color(0xFF2E2E2E)
    val audio = Color(0xFFFFFFFF)
    val video = Color(0xFFFFFFFF)
    val image = Color(0xFFFFFFFF)
    val warn = Color(0xFFD71921)
    val danger = Color(0xFFD71921)
    val red = Color(0xFFD71921)
}

/** Nothing-style dot-matrix font (Doto, SIL OFL), used only for short titles such as "S DRIVE". */
val DotFont: FontFamily = FontFamily(Font(R.font.sd_dot))

val LocalIsWatch = staticCompositionLocalOf { false }

// ---------------- icons (Material paths, built at runtime: no icon library needed) ----------------
object Ic {
    private fun v(name: String, path: String): ImageVector = try {
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.White)).build()
    } catch (_: Throwable) {
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(pathData = addPathNodes("M6,6h12v12H6z"), fill = SolidColor(Color.White)).build()
    }

    val play by lazy { v("play", "M8,5v14l11,-7z") }
    val pause by lazy { v("pause", "M6,19h4V5H6v14zm8,-14v14h4V5h-4z") }
    val next by lazy { v("next", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z") }
    val prev by lazy { v("prev", "M6,6h2v12H6zm3.5,6l8.5,6V6z") }
    val rewind by lazy { v("rew", "M11,18V6l-8.5,6 8.5,6zm0.5,-6l8.5,6V6l-8.5,6z") }
    val forward by lazy { v("fwd", "M4,18l8.5,-6L4,6v12zm9,-12v12l8.5,-6L13,6z") }
    val volume by lazy { v("vol", "M3,9v6h4l5,5V4L7,9H3zm13.5,3c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z") }
    val folder by lazy { v("folder", "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z") }
    val file by lazy { v("file", "M6,2c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6H6zm7,7V3.5L18.5,9H13z") }
    val music by lazy { v("music", "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z") }
    val movie by lazy { v("movie", "M18,4l2,4h-3l-2,-4h-2l2,4h-3l-2,-4H8l2,4H7L5,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V4h-4z") }
    val image by lazy { v("image", "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z") }
    val lock by lazy { v("lock", "M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zm-6,9c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zm3.1,-9H8.9V6c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2z") }
    val unlock by lazy { v("unlock", "M12,17c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zm6,-9h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6h1.9c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zm0,12H6V10h12v10z") }
    val repeat by lazy { v("repeat", "M7,7h10v3l4,-4 -4,-4v3H5v6h2V7zm10,10H7v-3l-4,4 4,4v-3h12v-6h-2v4z") }
    val shuffle by lazy { v("shuffle", "M10.59,9.17L5.41,4 4,5.41l5.17,5.17 1.42,-1.41zM14.5,4l2.04,2.04L4,18.59 5.41,20 17.96,7.46 20,9.5V4h-5.5zm0.33,9.41l-1.41,1.41 3.13,3.13L14.5,20H20v-5.5l-2.04,2.04 -3.13,-3.13z") }
    val more by lazy { v("more", "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zm0,2c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zm0,6c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z") }
    val send by lazy { v("send", "M2.01,21L23,12 2.01,3 2,10l15,2 -15,2z") }
    val back by lazy { v("back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z") }
    val up by lazy { v("up", "M4,12l1.41,1.41L11,7.83V20h2V7.83l5.58,5.59L20,12l-8,-8 -8,8z") }
    val down by lazy { v("down", "M20,12l-1.41,-1.41L13,16.17V4h-2v12.17l-5.58,-5.59L4,12l8,8 8,-8z") }
    val close by lazy { v("close", "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z") }
    val check by lazy { v("check", "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z") }
    val add by lazy { v("add", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z") }
    val delete by lazy { v("delete", "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z") }
    val copy by lazy { v("copy", "M16,1H4c-1.1,0 -2,0.9 -2,2v14h2V3h12V1zm3,4H8c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2zm0,16H8V7h11v14z") }
    val cut by lazy { v("cut", "M9.64,7.64c0.23,-0.5 0.36,-1.05 0.36,-1.64 0,-2.21 -1.79,-4 -4,-4S2,3.79 2,6s1.79,4 4,4c0.59,0 1.14,-0.13 1.64,-0.36L10,12l-2.36,2.36C7.14,14.13 6.59,14 6,14c-2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4c0,-0.59 -0.13,-1.14 -0.36,-1.64L12,14l7,7h3v-1L9.64,7.64zM6,8c-1.1,0 -2,-0.89 -2,-2s0.9,-2 2,-2 2,0.89 2,2 -0.9,2 -2,2zm0,12c-1.1,0 -2,-0.89 -2,-2s0.9,-2 2,-2 2,0.89 2,2 -0.9,2 -2,2zM19,3l-6,6 2,2 7,-7V3z") }
    val edit by lazy { v("edit", "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z") }
    val share by lazy { v("share", "M18,16.08c-0.76,0 -1.44,0.3 -1.96,0.77L8.91,12.7c0.05,-0.23 0.09,-0.46 0.09,-0.7s-0.04,-0.47 -0.09,-0.7l7.05,-4.11c0.54,0.5 1.25,0.81 2.04,0.81 1.66,0 3,-1.34 3,-3s-1.34,-3 -3,-3 -3,1.34 -3,3c0,0.24 0.04,0.47 0.09,0.7L8.04,9.81C7.5,9.31 6.79,9 6,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3c0.79,0 1.5,-0.31 2.04,-0.81l7.12,4.16c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.61 1.31,2.92 2.92,2.92s2.92,-1.31 2.92,-2.92 -1.31,-2.92 -2.92,-2.92z") }
    val sort by lazy { v("sort", "M3,18h6v-2H3v2zM3,6v2h18V6H3zm0,7h12v-2H3v2z") }
    val selectAll by lazy { v("selall", "M19,3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.11,0 2,-0.9 2,-2V5c0,-1.1 -0.89,-2 -2,-2zm-9,14l-5,-5 1.41,-1.41L10,14.17l7.59,-7.59L19,8l-9,9z") }
    val wifi by lazy { v("wifi", "M1,9l2,2c4.97,-4.97 13.03,-4.97 18,0l2,-2C16.93,2.93 7.08,2.93 1,9zm8,8l3,3 3,-3c-1.65,-1.66 -4.34,-1.66 -6,0zm-4,-4l2,2c2.76,-2.76 7.24,-2.76 10,0l2,-2C15.14,9.14 8.87,9.14 5,13z") }
    val bluetooth by lazy { v("bt", "M17.71,7.71L12,2h-1v7.59L6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 11,14.41V22h1l5.71,-5.71 -4.3,-4.29 4.3,-4.29zM13,5.83l1.88,1.88L13,9.59V5.83zm1.88,10.46L13,18.17v-3.76l1.88,1.88z") }
    val phone by lazy { v("phone", "M17,1.01L7,1c-1.1,0 -2,0.9 -2,2v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V3c0,-1.1 -0.9,-1.99 -2,-1.99zM17,19H7V5h10v14z") }
    val watch by lazy { v("watch", "M20,12c0,-2.54 -1.19,-4.81 -3.04,-6.27L16,0H8l-0.95,5.73C5.19,7.19 4,9.45 4,12s1.19,4.81 3.05,6.27L8,24h8l0.96,-5.73C18.81,16.81 20,14.54 20,12zM6,12c0,-3.31 2.69,-6 6,-6s6,2.69 6,6 -2.69,6 -6,6 -6,-2.69 -6,-6z") }
    val computer by lazy { v("pc", "M20,18c1.1,0 1.99,-0.9 1.99,-2L22,6c0,-1.1 -0.9,-2 -2,-2H4c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2H0v2h24v-2h-4zM4,6h16v10H4V6z") }
    val aspect by lazy { v("aspect", "M3,5v4h2V5h4V3H5c-1.1,0 -2,0.9 -2,2zm2,10H3v4c0,1.1 0.9,2 2,2h4v-2H5v-4zm14,4h-4v2h4c1.1,0 2,-0.9 2,-2v-4h-2v4zm0,-16h-4v2h4v4h2V5c0,-1.1 -0.9,-2 -2,-2z") }
    val subtitles by lazy { v("subs", "M20,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2zM4,12h4v2H4v-2zm10,6H4v-2h10v2zm6,0h-4v-2h4v2zm0,-4H10v-2h10v2z") }
    val terminal by lazy { v("term", "M20,4H4C2.89,4 2,4.9 2,6v12c0,1.1 0.89,2 2,2h16c1.1,0 2,-0.9 2,-2V6C22,4.9 21.11,4 20,4zM20,18H4V8h16V18zM18,17h-6v-2h6V17zM7.5,17l-1.41,-1.41L8.67,13l-2.59,-2.59L7.5,9l4,4L7.5,17z") }
    val help by lazy { v("help", "M11,18h2v-2h-2v2zm1,-16C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zm0,18c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8zm0,-14c-2.21,0 -4,1.79 -4,4h2c0,-1.1 0.9,-2 2,-2s2,0.9 2,2c0,2 -3,1.75 -3,5h2c0,-2.25 3,-2.5 3,-5 0,-2.21 -1.79,-4 -4,-4z") }
    val battery by lazy { v("bat", "M15.67,4H14V2h-4v2H8.33C7.6,4 7,4.6 7,5.33v15.33C7,21.4 7.6,22 8.33,22h7.33c0.74,0 1.34,-0.6 1.34,-1.33V5.33C17,4.6 16.4,4 15.67,4z") }
    val download by lazy { v("dl", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z") }
    val doc by lazy { v("doc", "M14,2H6c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6zm2,16H8v-2h8v2zm0,-4H8v-2h8v2zm-3,-5V3.5L18.5,9H13z") }
    val apps by lazy { v("apps", "M4,8h4V4H4v4zm6,12h4v-4h-4v4zm-6,0h4v-4H4v4zm0,-6h4v-4H4v4zm6,0h4v-4h-4v4zm6,-10v4h4V4h-4zm-6,4h4V4h-4v4zm6,6h4v-4h-4v4zm0,6h4v-4h-4v4z") }
    val archive by lazy { v("zip", "M20.54,5.23l-1.39,-1.68C18.88,3.21 18.47,3 18,3H6c-0.47,0 -0.88,0.21 -1.16,0.55L3.46,5.23C3.17,5.57 3,6.02 3,6.5V19c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V6.5c0,-0.48 -0.17,-0.93 -0.46,-1.27zM12,17.5L6.5,12H10v-2h4v2h3.5L12,17.5zM5.12,5l0.81,-1h12l0.94,1H5.12z") }
}

// ---------------- primitives ----------------

@Composable
fun Txt(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 14.sp,
    color: Color = Pal.text,
    weight: FontWeight = FontWeight.Normal,
    lines: Int = 1,
    align: TextAlign = TextAlign.Start,
    font: FontFamily? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = size, fontWeight = weight, textAlign = align, fontFamily = font),
        maxLines = lines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun Ico(v: ImageVector, tint: Color = Pal.text, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    Image(rememberVectorPainter(v), contentDescription = null, modifier = modifier.size(size), colorFilter = ColorFilter.tint(tint))
}

@Composable
fun RoundBtn(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    bg: Color = Pal.surface2,
    tint: Color = Pal.text,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) { Ico(icon, tint, size * 0.55f) }
}

/** Full-width pill button used for list actions. */
@Composable
fun Chip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    sub: String? = null,
    bg: Color = Pal.surface,
    fg: Color = Pal.text,
    iconTint: Color = Pal.accent,
    selected: Boolean = false,
) {
    val watch = LocalIsWatch.current
    Row(
        modifier
            .curved()
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (watch) 24.dp else 14.dp))
            .background(if (selected) Pal.accentDim else bg)
            .clickable(onClick = onClick)
            .padding(horizontal = if (watch) 12.dp else 16.dp, vertical = if (watch) 9.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Ico(icon, iconTint, if (watch) 18.dp else 22.dp)
            Spacer(Modifier.width(10.dp))
        }
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Txt(text, size = if (watch) 13.sp else 15.sp, color = fg, weight = FontWeight.Medium, lines = 2)
            if (sub != null) Txt(sub, size = if (watch) 11.sp else 12.sp, color = Pal.dim, lines = 2)
        }
        if (selected) Ico(Ic.check, Pal.accent, 16.dp)
    }
}

/** Small selectable tag (speed, aspect, track options...). */
@Composable
fun Tag(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val watch = LocalIsWatch.current
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Pal.accent else Pal.surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = if (watch) 9.dp else 14.dp, vertical = if (watch) 6.dp else 8.dp),
        contentAlignment = Alignment.Center,
    ) { Txt(text, size = if (watch) 12.sp else 14.sp, color = if (selected) Pal.bg else Pal.text, weight = FontWeight.Medium) }
}

@Composable
fun TagRow(content: @Composable () -> Unit) {
    Row(
        Modifier.curved().fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
fun Bar(fraction: Float, modifier: Modifier = Modifier, color: Color = Pal.accent, height: Dp = 4.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color))
    }
}

@Composable
fun SectionTitle(text: String) {
    Txt(text.uppercase(), Modifier.curved().fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        size = 11.sp, color = Pal.dim, weight = FontWeight.Bold, align = TextAlign.Center)
}

/**
 * Watch-style "fisheye": items shrink and fade towards the top/bottom edge of the round screen.
 * Only reads layout position in the draw phase, so it costs no recomposition while scrolling.
 */
@Composable
fun Modifier.curved(): Modifier {
    if (!LocalIsWatch.current) return this
    val h = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val y = remember { mutableFloatStateOf(h / 2f) }
    return this
        .onGloballyPositioned { y.floatValue = it.boundsInRoot().center.y }
        .graphicsLayer {
            val d = ((y.floatValue - h / 2f) / (h / 2f)).coerceIn(-1f, 1f)
            val s = 1f - 0.22f * d * d
            scaleX = s; scaleY = s
            alpha = 1f - 0.55f * d * d * d * d
        }
}

/** Scrolling list that works with touch AND the rotating bezel/crown. Round-screen padding on watch. */
@Composable
fun WList(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    val watch = LocalIsWatch.current
    val fr = remember { FocusRequester() }
    // Smooth bezel/crown scrolling: each bezel click is spread over a few frames (ease-out)
    // instead of jumping, which is what looked like stutter. Touch scrolling always wins.
    val steps = remember(state) { Channel<Float>(Channel.UNLIMITED) }
    LaunchedEffect(state) {
        while (isActive) {
            var remaining = steps.receive()
            try {
                state.scroll {
                    while (abs(remaining) > 0.5f) {
                        while (true) { remaining += steps.tryReceive().getOrNull() ?: break }
                        withFrameNanos { }
                        val step = if (abs(remaining) < 3f) remaining else remaining * 0.35f
                        val used = scrollBy(step)
                        remaining -= step
                        if (abs(used) < 0.01f) remaining = 0f // reached the top or bottom
                    }
                }
            } catch (e: CancellationException) {
                if (!isActive) throw e // screen closed; otherwise a touch drag took over
            }
        }
    }
    LazyColumn(
        state = state,
        modifier = modifier
            .fillMaxSize()
            .onRotaryScrollEvent {
                steps.trySend(it.verticalScrollPixels)
                true
            }
            .focusRequester(fr)
            .focusable(),
        contentPadding = if (watch) PaddingValues(start = 12.dp, end = 12.dp, top = 30.dp, bottom = 56.dp)
        else PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (watch) 5.dp else 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
    // Take bezel focus on start, after a dialog closes and when the app comes back
    // (never while a text box is open, or the keyboard would lose its text field).
    val dialogOpen = LocalUi.current.prompt != null
    LaunchedEffect(AppState.focusTick, AppState.resumeTick, dialogOpen) {
        if (!dialogOpen) try { fr.requestFocus() } catch (_: Exception) {}
    }
}

@Composable
fun FullBg(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Pal.bg)) { content() }
}

/**
 * Small thumbnail (image / video frame / album art) for the category lists, or the file-type icon.
 * Decoded at display size on a background thread and cached in memory.
 */
@Composable
fun Thumb(path: String, kind: Kind, icon: ImageVector, modifier: Modifier, shape: Shape, iconSize: Dp) {
    val px = if (LocalIsWatch.current) 96 else 160
    val bmp by produceState(Thumbs.cached(path, px), path, px) {
        if (value == null && Thumbs.canThumb(kind)) value = Thumbs.load(path, kind, px)
    }
    Box(modifier.clip(shape).background(Pal.surface2), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            val img = remember(b) { b.asImageBitmap() }
            Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else Ico(icon, Pal.text, iconSize)
    }
}
