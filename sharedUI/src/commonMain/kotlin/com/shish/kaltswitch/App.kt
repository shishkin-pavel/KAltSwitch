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
import com.shish.kaltswitch.model.AppActivationPolicy
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
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!axTrusted) AxBanner(onGrantAxClick)
        LazyColumn(
            Modifier.padding(start = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            sectionHeader("Apps with windows (${snapshot.withWindows.size})")
            items(snapshot.withWindows) { entry ->
                AppRow(entry, activeAppPid, activeWindowId)
            }
            item { Spacer(Modifier.height(12.dp)) }
            sectionHeader("Windowless (${snapshot.windowless.size})")
            items(snapshot.windowless) { entry ->
                AppRow(entry, activeAppPid, activeWindowId)
            }
        }
    }
}

private fun LazyListScope.sectionHeader(title: String) {
    item {
        Text(
            title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
        )
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
private fun AppRow(entry: AppEntry, activeAppPid: Int?, activeWindowId: WindowId?) {
    val app = entry.app
    val isActiveApp = app.pid == activeAppPid
    val appColor = if (isActiveApp) Color(0xFFFFFFFF) else Color(0xFFE0E0E0)
    val pictogram = appPictogram(app, isActiveApp)
    val tags = buildList {
        add(policyTag(app.activationPolicy))
        if (app.isHidden) add("hidden")
        if (!app.isFinishedLaunching) add("launching")
        app.bundleId?.let { add(it) }
    }.joinToString(" · ")
    Column {
        Text(
            "$pictogram ${app.name}  ${tagText(tags)}",
            color = appColor,
            fontWeight = if (isActiveApp) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyMedium,
        )
        entry.windows.forEach { w ->
            WindowRow(w, isActiveApp && w.id == activeWindowId)
        }
    }
}

@Composable
private fun WindowRow(w: Window, isActive: Boolean) {
    val color = if (isActive) Color(0xFFFFFFFF) else Color(0xFF9E9E9E)
    val pictogram = windowPictogram(w, isActive)
    val tags = buildList {
        if (!w.role.isNullOrBlank()) add(w.role.removePrefix("AX"))
        if (!w.subrole.isNullOrBlank()) add(w.subrole.removePrefix("AX"))
        if (w.isMain) add("main")
        if (w.width != null && w.height != null) {
            add("${w.width.toInt()}×${w.height.toInt()}")
        }
    }.joinToString(" · ")
    Text(
        "    $pictogram ${w.title.ifBlank { "(untitled)" }}  ${tagText(tags)}",
        color = color,
        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun appPictogram(app: com.shish.kaltswitch.model.App, isActive: Boolean): String = when {
    isActive -> "▶"
    app.isHidden -> "◌"
    !app.isFinishedLaunching -> "…"
    app.activationPolicy == AppActivationPolicy.Accessory -> "◇"
    else -> "•"
}

private fun windowPictogram(w: Window, isActive: Boolean): String = when {
    isActive -> "└▶"
    w.isMinimized -> "└⎽"
    w.isFullscreen -> "└⤢"
    else -> "└─"
}

private fun policyTag(p: AppActivationPolicy): String = when (p) {
    AppActivationPolicy.Regular -> "regular"
    AppActivationPolicy.Accessory -> "accessory"
    AppActivationPolicy.Prohibited -> "prohibited"
}

private fun tagText(s: String): String = if (s.isBlank()) "" else "  · $s"
