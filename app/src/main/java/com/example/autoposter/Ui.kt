package com.example.autoposter

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun platformColor(p: Platform): Color {
    val dark = LocalIsDark.current
    return when (p) {
        Platform.YOUTUBE -> if (dark) Color(0xFFFF7B7B) else Color(0xFFC4302B)
        Platform.INSTAGRAM -> if (dark) Color(0xFFFF9AD5) else Color(0xFFB4237A)
        Platform.TIKTOK -> if (dark) Color(0xFF5EEAD4) else Color(0xFF0B7A67)
    }
}

@Composable
fun statusColor(s: Status): Color {
    val dark = LocalIsDark.current
    return when (s) {
        Status.QUEUED -> if (dark) Color(0xFFB7A5FF) else Color(0xFF5B4DE2)
        Status.UPLOADING -> if (dark) Color(0xFFFFD27A) else Color(0xFF8A5A00)
        Status.DONE -> if (dark) Color(0xFF73DCCB) else Color(0xFF0B7A67)
        Status.FAILED -> if (dark) Color(0xFFFF7A8A) else Color(0xFFBA1A2A)
        Status.NEEDS_AUTH -> if (dark) Color(0xFFFFA36B) else Color(0xFFA14A00)
        Status.CANCELED -> if (dark) Color(0xFF9AA0B8) else Color(0xFF5E6483)
    }
}

fun fmtTime(ms: Long): String = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ms))
fun fmtSize(b: Long): String = when {
    b < 0 -> ""
    b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "${b shr 20} MB"
    else -> "${b shr 10} KB"
}
fun fmtCount(n: Long): String = when { n < 0 -> "–"; n >= 1_000_000 -> "%.1fM".format(n / 1e6); n >= 10_000 -> "${n / 1000}K"; else -> n.toString() }

@Composable
fun PageList(content: LazyListScope.() -> Unit) = LazyColumn(
    Modifier.fillMaxSize(),
    contentPadding = PaddingValues(18.dp, 12.dp, 18.dp, 28.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
    content = content
)

@Composable
fun SectionTitle(title: String, subtitle: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun StudioCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth()
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
}

@Composable
fun Pill(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.16f)) {
        Text(text, Modifier.padding(horizontal = 9.dp, vertical = 3.dp), color = color,
            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.secondary) {
    Card(modifier, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(13.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun Metric(label: String, value: String) {
    Surface(color = Color(0x33202030), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(value, color = Color.White, fontWeight = FontWeight.Bold)
            Text(label, color = Color(0xFFCECEE0), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun BarChart(values: List<Int>, labels: List<String>, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val max = (values.maxOrNull() ?: 1).coerceAtLeast(1)
            val bw = size.width / values.size.coerceAtLeast(1)
            values.forEachIndexed { i, v ->
                val h = (size.height * v / max).coerceAtLeast(if (v > 0) 6f else 3f)
                drawRoundRect(
                    if (v > 0) color else color.copy(alpha = 0.18f),
                    topLeft = Offset(i * bw + bw * 0.15f, size.height - h), size = Size(bw * 0.7f, h), cornerRadius = CornerRadius(6f, 6f)
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Muted(labels.firstOrNull().orEmpty()); Muted("max ${values.maxOrNull() ?: 0}/day"); Muted(labels.lastOrNull().orEmpty())
        }
    }
}
