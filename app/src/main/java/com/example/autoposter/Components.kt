package com.example.autoposter

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Pulsing placeholder shown while real data is loading. */
@Composable
fun Skeleton(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(12.dp)) {
    val t = rememberInfiniteTransition(label = "skeleton")
    val a by t.animateFloat(
        initialValue = 0.35f, targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "skeleton-alpha"
    )
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = a * 0.14f)))
}

@Composable
fun SkeletonCard(lines: Int = 2) {
    StudioCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Skeleton(Modifier.size(44.dp), CircleShape)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Skeleton(Modifier.fillMaxWidth(0.55f).size(width = 160.dp, height = 14.dp))
                Skeleton(Modifier.fillMaxWidth(0.8f).size(width = 220.dp, height = 11.dp))
            }
        }
        repeat((lines - 1).coerceAtLeast(0)) { Skeleton(Modifier.fillMaxWidth().size(width = 300.dp, height = 12.dp)) }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier,
    actionLabel: String? = null, onAction: (() -> Unit)? = null
) {
    StudioCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(30.dp))
            }
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            if (actionLabel != null && onAction != null) FilledTonalButton(onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun ErrorState(message: String, retryLabel: String, onRetry: () -> Unit, modifier: Modifier = Modifier, title: String? = null) {
    StudioCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.errorContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(30.dp))
            }
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            OutlinedButton(onRetry) { Text(retryLabel) }
        }
    }
}

/** Inline notice. [warning] uses the accent container, otherwise the neutral variant surface. */
@Composable
fun Banner(text: String, modifier: Modifier = Modifier, warning: Boolean = true, action: String? = null, onAction: (() -> Unit)? = null) {
    val bg = if (warning) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (warning) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = bg, shape = RoundedCornerShape(16.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text, Modifier.weight(1f), color = fg, style = MaterialTheme.typography.bodyMedium)
            if (action != null && onAction != null) Button(onAction) { Text(action) }
        }
    }
}

@Composable
fun heroBrush(): Brush = if (LocalIsDark.current) Brush.linearGradient(listOf(Color(0xFF3B2F8F), Color(0xFF14405A)))
else Brush.linearGradient(listOf(Color(0xFF5B4DE2), Color(0xFF3B2FB5)))

/** Self-drawn simplified marks (not the official brand artwork). */
@Composable
fun PlatformLogo(platform: Platform, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val r = CornerRadius(s * 0.26f, s * 0.26f)
        when (platform) {
            Platform.YOUTUBE -> {
                drawRoundRect(Color(0xFFE62117), size = Size(s, s), cornerRadius = r)
                val p = Path().apply { moveTo(s * 0.40f, s * 0.30f); lineTo(s * 0.72f, s * 0.50f); lineTo(s * 0.40f, s * 0.70f); close() }
                drawPath(p, Color.White)
            }
            Platform.INSTAGRAM -> {
                drawRoundRect(
                    Brush.linearGradient(listOf(Color(0xFFF9A03F), Color(0xFFDD2A7B), Color(0xFF6B3FD1)), start = Offset(0f, s), end = Offset(s, 0f)),
                    size = Size(s, s), cornerRadius = r
                )
                val w = s * 0.07f
                drawRoundRect(Color.White, topLeft = Offset(s * 0.22f, s * 0.22f), size = Size(s * 0.56f, s * 0.56f),
                    cornerRadius = CornerRadius(s * 0.16f, s * 0.16f), style = Stroke(w))
                drawCircle(Color.White, radius = s * 0.13f, center = Offset(s * 0.5f, s * 0.5f), style = Stroke(w))
                drawCircle(Color.White, radius = s * 0.04f, center = Offset(s * 0.67f, s * 0.33f))
            }
            Platform.TIKTOK -> {
                drawRoundRect(Color(0xFF111111), size = Size(s, s), cornerRadius = r)
                fun note(color: Color, dx: Float) {
                    val w = s * 0.085f
                    drawLine(color, Offset(s * 0.56f + dx, s * 0.24f), Offset(s * 0.56f + dx, s * 0.66f), strokeWidth = w, cap = StrokeCap.Round)
                    drawCircle(color, radius = s * 0.12f, center = Offset(s * 0.44f + dx, s * 0.68f))
                    val flag = Path().apply {
                        moveTo(s * 0.56f + dx, s * 0.24f)
                        cubicTo(s * 0.60f + dx, s * 0.36f, s * 0.68f + dx, s * 0.40f, s * 0.76f + dx, s * 0.40f)
                    }
                    drawPath(flag, color, style = Stroke(w, cap = StrokeCap.Round))
                }
                note(Color(0xFF25F4EE), -s * 0.025f)
                note(Color(0xFFFE2C55), s * 0.025f)
                note(Color.White, 0f)
            }
        }
    }
}

@Composable
fun BrandMark(size: Dp = 64.dp, modifier: Modifier = Modifier) {
    val a = MaterialTheme.colorScheme.primary
    val b = MaterialTheme.colorScheme.secondary
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        drawRoundRect(Brush.linearGradient(listOf(a, b), start = Offset(0f, 0f), end = Offset(s, s)), size = Size(s, s), cornerRadius = CornerRadius(s * 0.3f, s * 0.3f))
        val p = Path().apply { moveTo(s * 0.38f, s * 0.28f); lineTo(s * 0.74f, s * 0.5f); lineTo(s * 0.38f, s * 0.72f); close() }
        drawPath(p, Color.White)
    }
}

@Composable
fun Avatar(initial: String, size: Dp = 48.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        Text(initial.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun SettingsRow(icon: ImageVector, title: String, subtitle: String? = null, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val base = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
    Row(
        (if (onClick != null) base.clickable(onClick = onClick) else base).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChecked)
    }
}

