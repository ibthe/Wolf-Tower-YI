package com.wolftower.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Палитра: тёмная тема YouTube (фон #0F0F0F, красный #FF0000) и градиент Instagram для акцентов.
 * Красный отвечает за действия и YouTube, градиент: за кнопку питания, логотип и Instagram.
 */
object WT {
    val BgTop = Color(0xFF1A0E16) // тёмный фон с едва заметным оттенком градиента Instagram
    val Bg = Color(0xFF0F0F0F)
    val Surface = Color(0xFF1C1C1C)
    val SurfaceHigh = Color(0xFF272727)
    val Outline = Color(0xFF3A3A3A)

    // YouTube
    val YtRed = Color(0xFFFF0000)
    val YtRedSoft = Color(0xFFFF4E45) // красный для текста и свечения на тёмном фоне
    val YtRedDeep = Color(0xFFCC0000)

    // Instagram (классический градиент логотипа) и синий его кнопок
    val IgYellow = Color(0xFFFEDA75)
    val IgOrange = Color(0xFFFA7E1E)
    val IgPink = Color(0xFFD62976)
    val IgPurple = Color(0xFF962FBF)
    val IgBlue = Color(0xFF4F5BD5)
    val IgAction = Color(0xFF0095F6)

    val Accent = YtRed
    val AccentGlow = YtRedSoft
    val AccentDeep = Color(0xFF4A1015) // тёмно-красная подложка выбранной плашки
    val OnAccent = Color.White
    val Ok = IgAction
    val Amber = Color(0xFFFFB020)
    val Text = Color(0xFFF1F1F1)
    val TextDim = Color(0xFFAAAAAA)

    val IgStops = listOf(IgYellow, IgOrange, IgPink, IgPurple, IgBlue)

    fun igBrush(): Brush = Brush.linearGradient(IgStops)

    /** Кольцо вокруг кнопки: градиент Instagram по кругу, концы совпадают, шва нет. */
    fun igSweep(): Brush = Brush.sweepGradient(IgStops + IgStops.reversed().drop(1))
}

@Composable
fun WolfTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = WT.Accent,
        onPrimary = WT.OnAccent,
        secondary = WT.IgPurple,
        onSecondary = Color.White,
        tertiary = WT.IgAction,
        background = WT.Bg,
        onBackground = WT.Text,
        surface = WT.Surface,
        onSurface = WT.Text,
        surfaceVariant = WT.SurfaceHigh,
        onSurfaceVariant = WT.TextDim,
        outline = WT.Outline,
        error = Color(0xFFFF8A80),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

// ---------------------------------------------------------------- значки (рисуются на Canvas)

@Composable
fun CopyGlyph(color: Color, iconSize: Dp = 20.dp, cover: Color = WT.SurfaceHigh) {
    Canvas(Modifier.size(iconSize)) {
        val s = this.size.minDimension
        val stroke = Stroke(width = s * 0.09f)
        val r = CornerRadius(s * 0.14f, s * 0.14f)
        drawRoundRect(color, topLeft = Offset(s * 0.30f, s * 0.08f), size = Size(s * 0.56f, s * 0.60f), cornerRadius = r, style = stroke)
        drawRoundRect(cover, topLeft = Offset(s * 0.14f, s * 0.28f), size = Size(s * 0.56f, s * 0.64f), cornerRadius = r)
        drawRoundRect(color, topLeft = Offset(s * 0.14f, s * 0.28f), size = Size(s * 0.56f, s * 0.64f), cornerRadius = r, style = stroke)
    }
}

@Composable
fun CheckGlyph(color: Color, iconSize: Dp = 20.dp) {
    Canvas(Modifier.size(iconSize)) {
        val s = this.size.minDimension
        val w = s * 0.12f
        drawLine(color, Offset(s * 0.20f, s * 0.53f), Offset(s * 0.42f, s * 0.75f), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(color, Offset(s * 0.42f, s * 0.75f), Offset(s * 0.81f, s * 0.28f), strokeWidth = w, cap = StrokeCap.Round)
    }
}

@Composable
fun PowerGlyph(color: Color, iconSize: Dp = 56.dp) {
    Canvas(Modifier.size(iconSize)) {
        val s = this.size.minDimension
        val w = s * 0.09f
        drawArc(
            color,
            startAngle = -55f,
            sweepAngle = 290f,
            useCenter = false,
            topLeft = Offset(s * 0.16f, s * 0.20f),
            size = Size(s * 0.68f, s * 0.68f),
            style = Stroke(width = w, cap = StrokeCap.Round),
        )
        drawLine(color, Offset(s * 0.5f, s * 0.08f), Offset(s * 0.5f, s * 0.48f), strokeWidth = w, cap = StrokeCap.Round)
    }
}

@Composable
fun KeyGlyph(color: Color, iconSize: Dp = 22.dp) {
    Canvas(Modifier.size(iconSize)) {
        val s = this.size.minDimension
        val w = s * 0.10f
        drawCircle(color, radius = s * 0.21f, center = Offset(s * 0.33f, s * 0.35f), style = Stroke(width = w))
        drawLine(color, Offset(s * 0.48f, s * 0.50f), Offset(s * 0.88f, s * 0.90f), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(color, Offset(s * 0.70f, s * 0.72f), Offset(s * 0.82f, s * 0.60f), strokeWidth = w, cap = StrokeCap.Round)
    }
}

@Composable
fun ShareGlyph(color: Color, iconSize: Dp = 18.dp) {
    Canvas(Modifier.size(iconSize)) {
        val s = this.size.minDimension
        val a = Offset(s * 0.78f, s * 0.20f)
        val b = Offset(s * 0.22f, s * 0.50f)
        val c = Offset(s * 0.78f, s * 0.80f)
        val w = s * 0.07f
        drawLine(color, a, b, strokeWidth = w)
        drawLine(color, b, c, strokeWidth = w)
        drawCircle(color, radius = s * 0.13f, center = a)
        drawCircle(color, radius = s * 0.13f, center = b)
        drawCircle(color, radius = s * 0.13f, center = c)
    }
}

@Composable
fun ChevronGlyph(color: Color, iconSize: Dp = 20.dp, open: Boolean = false) {
    Canvas(Modifier.size(iconSize).rotate(if (open) 180f else 0f)) {
        val s = this.size.minDimension
        val w = s * 0.10f
        drawLine(color, Offset(s * 0.22f, s * 0.38f), Offset(s * 0.50f, s * 0.66f), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(color, Offset(s * 0.50f, s * 0.66f), Offset(s * 0.78f, s * 0.38f), strokeWidth = w, cap = StrokeCap.Round)
    }
}

// ---------------------------------------------------------------- компоненты

@Composable
fun WtCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    var m = modifier.fillMaxWidth().clip(shape).background(WT.Surface).border(1.dp, WT.Outline, shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        content()
    }
}

/** Кнопка «копировать»: после нажатия значок меняется на галочку, подпись на «скопировано», через 2 с возвращается. */
@Composable
fun CopyPill(label: String, doneLabel: String, onCopy: () -> Unit, modifier: Modifier = Modifier) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }
    val bg by animateColorAsState(targetValue = if (copied) WT.Ok else WT.Surface, label = "copyBg")
    val fg by animateColorAsState(targetValue = if (copied) Color.White else WT.Text, label = "copyFg")
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(1.dp, if (copied) WT.Ok else WT.Outline, RoundedCornerShape(14.dp))
            .clickable {
                onCopy()
                copied = true
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (copied) CheckGlyph(fg, 18.dp) else CopyGlyph(fg, 18.dp, cover = WT.Surface)
        Text(if (copied) doneLabel else label, color = fg, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    }
}

@Composable
fun SharePill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(WT.Surface)
            .border(1.dp, WT.Outline, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ShareGlyph(WT.Text, 18.dp)
        Text(label, color = WT.Text, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    }
}

@Composable
fun WtButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
) {
    val shape = RoundedCornerShape(14.dp)
    if (primary) {
        Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = WT.Accent,
                contentColor = WT.OnAccent,
                disabledContainerColor = WT.SurfaceHigh,
                disabledContentColor = WT.TextDim,
            ),
        ) { Text(text, fontWeight = FontWeight.SemiBold) }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = shape,
            border = BorderStroke(1.dp, WT.Outline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = WT.Text, disabledContentColor = WT.TextDim),
        ) { Text(text) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WtTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = WT.YtRedSoft,
            unfocusedBorderColor = WT.Outline,
            cursorColor = WT.YtRedSoft,
            focusedLabelColor = WT.YtRedSoft,
            unfocusedLabelColor = WT.TextDim,
            focusedTextColor = WT.Text,
            unfocusedTextColor = WT.Text,
        ),
    )
}

@Composable
fun WtSwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = WT.Text, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Text(subtitle, color = WT.TextDim, fontSize = 12.sp)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

/**
 * Большая кнопка включения. Когда защита работает: красный диск YouTube, вокруг кольцо с градиентом
 * Instagram и расходящиеся волны (красная и пурпурная).
 */
@Composable
fun PowerButton(running: Boolean, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "pulseValue",
    )
    val top by animateColorAsState(targetValue = if (running) Color(0xFFFF4E45) else Color(0xFF2E2E2E), label = "btnTop")
    val bottom by animateColorAsState(targetValue = if (running) WT.YtRedDeep else Color(0xFF1A1A1A), label = "btnBottom")
    val glyph by animateColorAsState(
        targetValue = if (running) Color.White else if (enabled) WT.Text else WT.TextDim,
        label = "btnGlyph",
    )
    Box(Modifier.size(236.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val base = this.size.minDimension * 0.28f
            val reach = this.size.minDimension * 0.20f
            if (running) {
                val p = pulse
                drawCircle(WT.YtRed.copy(alpha = 0.30f * (1f - p)), radius = base + reach * p, center = center)
                val q = (p + 0.5f) % 1f
                drawCircle(WT.IgPurple.copy(alpha = 0.30f * (1f - q)), radius = base + reach * q, center = center)
                drawCircle(WT.IgPink.copy(alpha = 0.10f), radius = base + 20.dp.toPx(), center = center)
                drawCircle(
                    WT.igSweep(),
                    radius = base + 12.dp.toPx(),
                    center = center,
                    style = Stroke(width = 3.dp.toPx()),
                )
            } else {
                drawCircle(
                    WT.Outline,
                    radius = base + 12.dp.toPx(),
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
        Box(
            Modifier
                .size(132.dp)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(top, bottom)))
                .clickable(enabled = enabled && !busy, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(48.dp), color = glyph, strokeWidth = 3.dp)
            } else {
                PowerGlyph(glyph, 60.dp)
            }
        }
    }
}

/** Выбор стратегии: простая плашка (своя, чтобы не зависеть от версии Material). */
@Composable
fun WtChip(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val bg = if (selected) WT.AccentDeep else WT.Surface
    val fg = if (selected) Color.White else if (enabled) WT.Text else WT.TextDim
    Box(
        Modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, if (selected) WT.Accent else WT.Outline, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) { Text(text, color = fg, fontSize = 13.sp) }
}

// ---------------------------------------------------------------- сервисы

/** Значок YouTube: красная плашка с белым треугольником. */
@Composable
fun YouTubeGlyph(iconSize: Dp = 34.dp) {
    Canvas(Modifier.size(iconSize)) {
        val w = this.size.width
        val h = this.size.height
        val bh = h * 0.70f
        val top = (h - bh) / 2f
        drawRoundRect(WT.YtRed, topLeft = Offset(0f, top), size = Size(w, bh), cornerRadius = CornerRadius(bh * 0.30f, bh * 0.30f))
        val tri = Path().apply {
            moveTo(w * 0.40f, h * 0.36f)
            lineTo(w * 0.40f, h * 0.64f)
            lineTo(w * 0.66f, h * 0.50f)
            close()
        }
        drawPath(tri, Color.White)
    }
}

/** Значок Instagram: квадрат с градиентом, белая рамка, объектив и точка. */
@Composable
fun InstagramGlyph(iconSize: Dp = 34.dp) {
    Canvas(Modifier.size(iconSize)) {
        val s = this.size.minDimension
        drawRoundRect(WT.igBrush(), topLeft = Offset.Zero, size = Size(s, s), cornerRadius = CornerRadius(s * 0.28f, s * 0.28f))
        val w = s * 0.075f
        drawRoundRect(
            Color.White,
            topLeft = Offset(s * 0.20f, s * 0.20f),
            size = Size(s * 0.60f, s * 0.60f),
            cornerRadius = CornerRadius(s * 0.18f, s * 0.18f),
            style = Stroke(width = w),
        )
        drawCircle(Color.White, radius = s * 0.14f, center = Offset(s * 0.5f, s * 0.5f), style = Stroke(width = w))
        drawCircle(Color.White, radius = s * 0.04f, center = Offset(s * 0.69f, s * 0.31f))
    }
}

/** Значок сервиса по id из profiles.json. */
@Composable
fun ServiceGlyph(id: String, iconSize: Dp = 34.dp) {
    when (id) {
        "youtube" -> YouTubeGlyph(iconSize)
        "instagram" -> InstagramGlyph(iconSize)
        else -> Box(Modifier.size(iconSize).clip(RoundedCornerShape(8.dp)).background(WT.SurfaceHigh))
    }
}

/** Цвет переключателя сервиса: красный у YouTube, розовый у Instagram. */
fun serviceAccent(id: String): Color = when (id) {
    "instagram" -> WT.IgPink
    else -> WT.YtRed
}

/** Строка сервиса: значок, название, подпись (стратегия) и переключатель. */
@Composable
fun ServiceRow(
    id: String,
    name: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ServiceGlyph(id, 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, color = WT.Text, fontWeight = FontWeight.Medium, fontSize = 16.sp)
            Text(subtitle, color = WT.TextDim, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = serviceAccent(id),
                uncheckedThumbColor = WT.TextDim,
                uncheckedTrackColor = WT.SurfaceHigh,
                uncheckedBorderColor = WT.Outline,
            ),
        )
    }
}
