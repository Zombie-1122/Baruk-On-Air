package com.mytv.player

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), color = Dim, fontSize = 12.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
fun SettingsScreen(vm: MainVm, onBack: () -> Unit, onPlaylists: () -> Unit) {
    val ctx = LocalContext.current
    var repoText by remember { mutableStateOf(vm.repo) }

    ScreenFrame("Settings", onBack, busy = vm.epgBusy || vm.checking) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- playlists + guide ----
            Card(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Label("Playlists and guide")
                Text(
                    "${vm.playlists.size} playlist(s), ${vm.channels.size} items",
                    color = Dim,
                    fontSize = 13.sp,
                )
                Text(vm.epgStatus ?: "Guide (EPG) loads from your IPTV provider automatically.", color = Dim, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Manage playlists", true, onPlaylists)
                    Pill(if (vm.epgBusy) "Updating..." else "Update guide", false, { if (!vm.epgBusy) vm.refreshEpgNow() })
                }
            }

            // ---- player ----
            Card(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Label("Player")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((id, label) in listOf("exo" to "Built-in", "external" to "External app")) {
                        Pill(label, vm.engine == id, { vm.chooseEngine(id) })
                    }
                }
                Text(
                    "Built-in is fast and light. External app opens VLC, MX Player or any player you have installed - handy for streams the built-in player can't decode.",
                    color = Dim,
                    fontSize = 13.sp,
                )
            }

            // ---- update ----
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
                OutlinedTextField(
                    value = repoText,
                    onValueChange = { repoText = it },
                    label = { Text("Update source: GitHub owner/repo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Pill("Save source", false, { vm.saveRepoSlug(repoText) })
            }
        }
    }
}
