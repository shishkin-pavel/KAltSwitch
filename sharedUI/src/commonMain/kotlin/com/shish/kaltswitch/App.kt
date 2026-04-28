package com.shish.kaltswitch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shish.kaltswitch.model.AppEntry
import com.shish.kaltswitch.model.Window
import com.shish.kaltswitch.model.WindowId
import com.shish.kaltswitch.model.World
import com.shish.kaltswitch.model.snapshot

@Composable
fun App(
    world: World,
    axTrusted: Boolean = true,
    activeAppPid: Int? = null,
    activeWindowId: WindowId? = null,
    onGrantAxClick: () -> Unit = {},
) {
    val snapshot = remember(world) { world.snapshot() }
    val activeAppName = snapshot.all.firstOrNull { it.app.pid == activeAppPid }?.app?.name
    val activeWindowTitle = snapshot.all
        .firstOrNull { it.app.pid == activeAppPid }
        ?.windows
        ?.firstOrNull { it.id == activeWindowId }
        ?.title

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!axTrusted) AxBanner(onGrantAxClick)
        ActiveIndicator(activeAppName, activeWindowTitle)
        Section("Apps with windows (${snapshot.withWindows.size})") {
            items(snapshot.withWindows) { entry ->
                AppRow(entry, activeAppPid, activeWindowId)
            }
        }
        Spacer(Modifier.height(8.dp))
        Section("Windowless (${snapshot.windowless.size})") {
            items(snapshot.windowless) { entry ->
                AppRow(entry, activeAppPid, activeWindowId)
            }
        }
    }
}

@Composable
private fun AxBanner(onGrantClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF3A2C0A))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "⚠️  Accessibility permission not granted. Without it we can't see other apps' windows.",
            color = Color(0xFFFFC107),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = onGrantClick,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFC107), contentColor = Color.Black),
        ) { Text("Grant…") }
    }
}

@Composable
private fun ActiveIndicator(appName: String?, windowTitle: String?) {
    val text = when {
        appName == null -> "● Active: (none)"
        windowTitle.isNullOrBlank() -> "● Active: $appName"
        else -> "● Active: $appName — $windowTitle"
    }
    Text(
        text,
        color = Color(0xFF80CBC4),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun Section(title: String, content: LazyListScope.() -> Unit) {
    Text(
        title,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.titleMedium,
    )
    LazyColumn(
        Modifier.padding(start = 8.dp, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

@Composable
private fun AppRow(entry: AppEntry, activeAppPid: Int?, activeWindowId: WindowId?) {
    val isActiveApp = entry.app.pid == activeAppPid
    val appColor = if (isActiveApp) Color(0xFFFFFFFF) else Color(0xFFE0E0E0)
    val appPrefix = if (isActiveApp) "▶ " else "• "
    Column {
        Text(
            "$appPrefix${entry.app.name}",
            color = appColor,
            fontWeight = if (isActiveApp) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyMedium,
        )
        entry.windows.forEach { w -> WindowRow(w, isActiveApp && w.id == activeWindowId) }
    }
}

@Composable
private fun WindowRow(w: Window, isActive: Boolean) {
    val color = if (isActive) Color(0xFFFFFFFF) else Color(0xFF9E9E9E)
    val prefix = if (isActive) "    └▶ " else "    └─ "
    Text(
        "$prefix${w.title.ifBlank { "(untitled)" }}",
        color = color,
        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
        style = MaterialTheme.typography.bodySmall,
    )
}
