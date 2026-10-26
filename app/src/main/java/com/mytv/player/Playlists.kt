package com.mytv.player

import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PlaylistsScreen(vm: MainVm, onBack: () -> Unit) {
    val snack = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<Playlist?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Playlist?>(null) }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snack.showSnackbar(it)
            vm.message = null
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFile(uri)
    }

    ScreenFrame("Playlists", onBack, busy = vm.busy || vm.epgBusy, snack = snack) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Add link", true, { editing = null; showDialog = true })
                Pill("Import file", false, { picker.launch(arrayOf("*/*")) })
            }

            Text("AUTO-UPDATE ON APP START", modifier = Modifier.padding(top = 20.dp, bottom = 8.dp), color = Dim, fontSize = 12.sp, letterSpacing = 0.8.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, h) in listOf("Off" to 0, "Every start" to 1, "12 h" to 12, "24 h" to 24)) {
                    Pill(label, vm.refreshHours == h, { vm.setRefreshHours(h) })
                }
            }

            Text("YOUR PLAYLISTS", modifier = Modifier.padding(top = 20.dp, bottom = 8.dp), color = Dim, fontSize = 12.sp, letterSpacing = 0.8.sp)
            if (vm.playlists.isEmpty()) {
                Text("None yet. Tap Add link and paste your M3U URL or Xtream login.", color = Dim)
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.playlists, key = { it.id }) { pl ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Panel)
                            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(pl.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                            Text(
                                if (pl.url != null) "Online playlist" else "Local file",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = Dim,
                                fontSize = 12.sp,
                            )
                            Text(
                                if (pl.updated == 0L) "Not updated yet"
                                else "Updated " + DateUtils.getRelativeTimeSpanString(pl.updated, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                                color = Dim,
                                fontSize = 12.sp,
                            )
                        }
                        if (pl.url != null) {
                            IconBtn(Icons.Default.Refresh, "Update", { vm.refreshOne(pl) })
                            IconBtn(Icons.Default.Edit, "Edit", { editing = pl; showDialog = true })
                        }
                        IconBtn(Icons.Default.Delete, "Delete", { toDelete = pl })
                    }
                }
            }
        }
    }

    if (showDialog) {
        PlaylistDialog(
            initial = editing,
            onDismiss = { showDialog = false },
            onSave = { name, url, epgUrl ->
                vm.savePlaylist(editing, name, url, epgUrl)
                showDialog = false
            },
        )
    }

    toDelete?.let { pl ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete playlist?") },
            text = { Text(pl.name) },
            confirmButton = {
                TextButton(onClick = { vm.deletePlaylist(pl); toDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun PlaylistDialog(initial: Playlist?, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var xtream by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    var epgUrl by remember { mutableStateOf(initial?.epg ?: "") }
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }

    val valid = if (xtream) {
        server.isNotBlank() && user.isNotBlank() && pass.isNotBlank()
    } else {
        url.trim().startsWith("http://") || url.trim().startsWith("https://")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add playlist" else "Edit playlist") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (initial == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("M3U link", !xtream, { xtream = false })
                        Pill("Xtream", xtream, { xtream = true })
                    }
                }
                if (xtream) {
                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it },
                        label = { Text("Server (http://host:port)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text("Password") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("M3U link (http...)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = epgUrl,
                    onValueChange = { epgUrl = it },
                    label = { Text("EPG link (optional)") },
                    supportingText = { Text("Leave empty to load the guide from your provider automatically") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val finalUrl = if (xtream) {
                        var base = server.trim().trimEnd('/')
                        if (!base.startsWith("http")) base = "http://$base"
                        base + "/get.php?username=" + Uri.encode(user.trim()) +
                            "&password=" + Uri.encode(pass.trim()) + "&type=m3u_plus&output=ts"
                    } else {
                        url.trim()
                    }
                    onSave(name.trim().ifEmpty { "Playlist" }, finalUrl, epgUrl.trim())
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
