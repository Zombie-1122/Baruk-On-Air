package com.mytv.player

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), color = Dim, fontSize = 12.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SettingsTabs(labels: List<String>, current: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        labels.forEachIndexed { i, l ->
            val shape = RoundedCornerShape(8.dp)
            Column(
                Modifier
                    .weight(1f)
                    .clip(shape)
                    .tvFocus(shape)
                    .clickable { onSelect(i) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    l.uppercase(),
                    modifier = Modifier.padding(vertical = 14.dp),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.6.sp,
                    color = if (i == current) Txt else Dim,
                )
                Box(Modifier.fillMaxWidth().height(2.dp).background(if (i == current) Accent else Color.Transparent))
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: MainVm, onBack: () -> Unit, onPlaylists: () -> Unit) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }

    ScreenFrame("Settings", onBack, busy = vm.epgBusy || vm.checking) {
        SettingsTabs(listOf("Playlist account", "Application", "About"), tab) { tab = it }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (tab) {
                0 -> {
                    Card(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Label("Playlists")
                        Text(
                            "${vm.playlists.size} playlist(s), ${vm.channels.size} items",
                            color = Dim,
                            fontSize = 13.sp,
                        )
                        Pill("Manage playlists", true, onPlaylists)
                    }
                    Card(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Label("Programme guide (EPG)")
                        Text(
                            vm.epgStatus ?: "The guide loads from your IPTV provider automatically.",
                            color = Dim,
                            fontSize = 13.sp,
                        )
                        Pill(
                            if (vm.epgBusy) "Updating..." else "Update guide",
                            false,
                            { if (!vm.epgBusy) vm.refreshEpgNow() },
                        )
                    }
                }
                1 -> {
                    Card(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Label("Appearance")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill("Dark", Pal.dark, { vm.chooseDark(true) })
                            Pill("Light", !Pal.dark, { vm.chooseDark(false) })
                        }
                    }
                    Card(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Label("Player")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for ((id, label) in listOf("exo" to "Built-in", "external" to "External app")) {
                                Pill(label, vm.engine == id, { vm.chooseEngine(id) })
                            }
                        }
                        Text(
                            "Built-in plays HLS, DASH, TS and MP4, including ClearKey / Widevine channels from your playlist. External app opens VLC, MX Player or any player you have installed.",
                            color = Dim,
                            fontSize = 13.sp,
                        )
                    }
                    Card(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Label("App update")
                        Text("BarukOnAir - build ${BuildConfig.VERSION_CODE}", fontWeight = FontWeight.SemiBold)
                        val u = vm.update
                        if (u != null) {
                            Text("New update available: ${u.tag}", color = Accent, fontWeight = FontWeight.SemiBold)
                            if (u.notes.isNotBlank()) Text(u.notes, fontSize = 13.sp)
                            Pill("Download update", true, {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.apkUrl)))
                            })
                        } else {
                            vm.updateStatus?.let { Text(it, color = Dim, fontSize = 13.sp) }
                        }
                        Pill(if (vm.checking) "Checking..." else "Check for update", false, { if (!vm.checking) vm.checkUpdate() })
                    }
                }
                else -> AboutTab()
            }
        }
    }
}
