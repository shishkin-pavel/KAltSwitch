package com.shish.kaltswitch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shish.kaltswitch.model.AppEntry
import com.shish.kaltswitch.model.World
import com.shish.kaltswitch.model.snapshot

@Composable
fun App(world: World) {
    val snapshot = remember(world) { world.snapshot() }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Section("Apps with windows (${snapshot.withWindows.size})") {
            items(snapshot.withWindows) { entry -> AppRow(entry) }
        }
        Spacer(Modifier.height(8.dp))
        Section("Windowless (${snapshot.windowless.size})") {
            items(snapshot.windowless) { entry -> AppRow(entry) }
        }
    }
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
private fun AppRow(entry: AppEntry) {
    Column {
        Text(
            "• ${entry.app.name}",
            color = Color(0xFFE0E0E0),
            style = MaterialTheme.typography.bodyMedium,
        )
        entry.windows.forEach { w ->
            Text(
                "    └─ ${w.title.ifBlank { "(untitled)" }}",
                color = Color(0xFF9E9E9E),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
