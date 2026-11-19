package com.poros.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage

@Composable
fun Center(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

// ---------------------------------------------------------------- video rows

@Composable
fun VideoList(list: List<VideoItem>, st: Settings, loader: ImageLoader, act: Actions, inVault: Boolean = false) {
    val ctx = LocalContext.current
    SideEffect { act.sel.visible = list }
    if (list.isEmpty()) {
        Center { Text("No videos here", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(list, key = { _, v -> v.key }) { index, v ->
            VideoRow(v, st, loader, act, inVault) { openPlayer(ctx, list, index) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoRow(v: VideoItem, st: Settings, loader: ImageLoader, act: Actions, inVault: Boolean, onPlay: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var addDialog by remember { mutableStateOf(false) }
    val sel = act.sel
    val selected by remember(v.key) { derivedStateOf { v.key in sel.keys } }
    val selecting by remember { derivedStateOf { sel.active } }
    val primary = MaterialTheme.colorScheme.primary
    val rowBg by animateColorAsState(
        if (selected) primary.copy(alpha = 0.22f) else Color.Transparent, label = "rowBg"
    )
    val fav = v.key in st.favorites()
    val prog = st.progress(v.key)
    val frac = if (prog != null && prog.second > 0) (prog.first.toFloat() / prog.second).coerceIn(0f, 1f) else 0f

    Row(
        Modifier.fillMaxWidth()
            .background(rowBg)
            .combinedClickable(
                onClick = { if (selecting) sel.toggle(v.key) else onPlay() },
                onLongClick = { sel.toggle(v.key) }
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(width = 120.dp, height = 72.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF222222))
                .border(if (selected) 2.5.dp else 0.dp, primary, RoundedCornerShape(10.dp))
                .clickable { sel.toggle(v.key) }
        ) {
            AsyncImage(
                model = v.uri, imageLoader = loader, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
            if (selected) Box(Modifier.fillMaxSize().background(primary.copy(alpha = 0.30f)))
            Text(
                fmt(v.durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp)
            )
            if (frac > 0.01f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x55FFFFFF))) {
                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(primary))
                }
            }
            if (selecting) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(5.dp).size(22.dp)
                        .clip(CircleShape)
                        .background(if (selected) primary else Color(0x66000000))
                        .border(1.5.dp, if (selected) primary else Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) Icon(
                        Icons.Default.Check, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                v.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium,
                color = if (selected) primary else Color.Unspecified
            )
            Text(
                "${fmtSize(v.sizeBytes)} • ${v.folder}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (fav) Icon(Icons.Default.Favorite, contentDescription = null, tint = primary,
            modifier = Modifier.size(18.dp))
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Play") },
                    leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                    onClick = { menu = false; onPlay() })
                DropdownMenuItem(
                    text = { Text(if (selected) "Deselect" else "Select") },
                    leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
                    onClick = { menu = false; sel.toggle(v.key) })
                DropdownMenuItem(
                    text = { Text("Share") },
                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                    onClick = { menu = false; act.share(listOf(v)) })
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null) },
                    onClick = { menu = false; act.rename(v) })
                DropdownMenuItem(
                    text = { Text("Move to folder") },
                    leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null) },
                    onClick = { menu = false; act.move(listOf(v)) })
                DropdownMenuItem(
                    text = { Text("Copy to folder") },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                    onClick = { menu = false; act.copy(listOf(v)) })
                DropdownMenuItem(
                    text = { Text(if (fav) "Remove from favorites" else "Add to favorites") },
                    onClick = { menu = false; st.toggleFavorite(v.key) })
                DropdownMenuItem(text = { Text("Add to playlist") }, onClick = { menu = false; addDialog = true })
                DropdownMenuItem(
                    text = { Text(if (inVault) "Remove from vault" else "Hide in Private Vault") },
                    onClick = { menu = false; st.setHidden(v.key, !inVault) })
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null,
                        tint = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; act.delete(listOf(v)) })
            }
        }
    }
    if (addDialog) AddToPlaylistDialog(st, v.key) { addDialog = false }
}

@Composable
fun AddToPlaylistDialog(st: Settings, key: String, onDismiss: () -> Unit) {
    var newName by remember { mutableStateOf("") }
    val pls = st.playlists()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column {
                pls.keys.forEach { n ->
                    TextButton(onClick = { st.addToPlaylist(n, key); onDismiss() }) { Text(n) }
                }
                OutlinedTextField(newName, { newName = it }, label = { Text("New playlist") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = newName.trim()
                if (n.isNotEmpty()) { st.createPlaylist(n); st.addToPlaylist(n, key); onDismiss() }
            }) { Text("Create & add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

// ---------------------------------------------------------------- screens

@Composable
fun VideosScreen(videos: List<VideoItem>, st: Settings, loader: ImageLoader, query: String, act: Actions) {
    var chip by remember { mutableIntStateOf(0) }
    val chips = listOf("All", "Recent", "Favorites", "Continue", "Long (1h+)", "Large (500MB+)")
    val favs = st.favorites()
    val recent = st.recent()
    val filtered = remember(videos, chip, query, favs, recent) {
        var l = when (chip) {
            1 -> recent.mapNotNull { k -> videos.firstOrNull { it.key == k } }
            2 -> videos.filter { it.key in favs }
            3 -> videos.filter {
                val p = st.progress(it.key)
                p != null && p.second > 0 && p.first > 3000 && p.first < p.second - 10_000
            }
            4 -> videos.filter { it.durationMs >= 3_600_000 }
            5 -> videos.filter { it.sizeBytes >= 500L * 1024 * 1024 }
            else -> videos
        }
        if (query.isNotBlank()) l = l.filter { it.title.contains(query, ignoreCase = true) }
        l
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp)) {
            chips.forEachIndexed { i, c ->
                FilterChip(selected = chip == i, onClick = { chip = i }, label = { Text(c) },
                    modifier = Modifier.padding(end = 8.dp))
            }
        }
        VideoList(filtered, st, loader, act)
    }
}

@Composable
fun FoldersScreen(
    videos: List<VideoItem>, st: Settings, loader: ImageLoader, folder: String?,
    act: Actions, onNewFolder: () -> Unit, onOpen: (String) -> Unit
) {
    if (folder != null) {
        VideoList(videos.filter { it.folder == folder }, st, loader, act)
        return
    }
    val created = st.createdFolders()
    val groups = remember(videos, created) {
        val m = videos.groupBy { it.folder }.toMutableMap()
        created.forEach { if (!m.containsKey(it)) m[it] = emptyList() }
        m.toList().sortedBy { it.first.lowercase() }
    }
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onNewFolder, modifier = Modifier.padding(8.dp)) {
            Icon(Icons.Default.CreateNewFolder, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Create folder")
        }
        if (groups.isEmpty()) {
            Center { Text("No folders found") }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(groups) { _, (name, items) ->
                    Row(Modifier.fillMaxWidth().clickable { onOpen(name) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(name, fontWeight = FontWeight.Medium)
                            Text("${items.size} videos • ${fmtSize(items.sumOf { it.sizeBytes })}",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistsScreen(videos: List<VideoItem>, st: Settings, loader: ImageLoader, open: String?, act: Actions, onOpen: (String?) -> Unit) {
    val ctx = LocalContext.current
    val pls = st.playlists()
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    if (open != null) {
        val items = (pls[open] ?: emptyList()).mapNotNull { k -> videos.firstOrNull { it.key == k } }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(12.dp)) {
                Button(onClick = { if (items.isNotEmpty()) openPlayer(ctx, items, 0) }) { Text("Play all") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { st.deletePlaylist(open); onOpen(null) }) { Text("Delete playlist") }
            }
            VideoList(items, st, loader, act)
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = { creating = true }, modifier = Modifier.padding(8.dp)) {
            Icon(Icons.Default.Add, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("New playlist")
        }
        if (pls.isEmpty()) Center { Text("No playlists yet") }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(pls.toList()) { _, (name, list) ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(name) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PlaylistPlay, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(name, fontWeight = FontWeight.Medium)
                        Text("${list.size} videos", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (creating) AlertDialog(
        onDismissRequest = { creating = false },
        title = { Text("New playlist") },
        text = { OutlinedTextField(newName, { newName = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = {
            TextButton(onClick = {
                if (newName.isNotBlank()) st.createPlaylist(newName.trim())
                newName = ""; creating = false
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } }
    )
}

@Composable
fun VaultScreen(videos: List<VideoItem>, st: Settings, loader: ImageLoader, unlocked: Boolean, act: Actions, onUnlocked: () -> Unit) {
    var pinSet by remember { mutableStateOf(st.hasPin) }
    when {
        !pinSet -> SetPinPanel("Set a PIN for Private Vault", onDone = { st.setPin(it); pinSet = true })
        !unlocked -> LockScreen("Private Vault", onUnlocked)
        else -> {
            val hidden = st.hidden()
            val list = videos.filter { it.key in hidden }
            Column(Modifier.fillMaxSize()) {
                Text(
                    "Videos hidden from your library. Tap the ⋮ menu → Remove from vault to restore a video.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
                VideoList(list, st, loader, act, inVault = true)
            }
        }
    }
}

@Composable
fun SettingsScreen(st: Settings, onAbout: () -> Unit) {
    val ctx = LocalContext.current
    var pinDialog by remember { mutableStateOf(false) }
    if (pinDialog) {
        SetPinPanel("Set / change PIN", onDone = { st.setPin(it); pinDialog = false }, onCancel = { pinDialog = false })
        return
    }
    val subColors = listOf(Color.White, Color(0xFFFFEB3B), Color(0xFFB4FF1A), Color(0xFF00E5FF))

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("Theme")
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            AppTheme.values().forEach { t ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(end = 14.dp)) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(t.primary)
                            .border(if (st.theme == t.name) 3.dp else 0.dp, Color.White, CircleShape)
                            .clickable { st.set("theme", t.name) }
                    )
                    Text(t.label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        SectionTitle("Default decoder")
        Row {
            listOf("HW", "HW+", "SW").forEachIndexed { i, n ->
                FilterChip(selected = st.decoder == i, onClick = { st.set("decoder", i) }, label = { Text(n) },
                    modifier = Modifier.padding(end = 8.dp))
            }
        }
        Text("HW: hardware only • HW+: hardware with software fallback • SW: software preferred",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionTitle("Playback")
        SwitchRow("Resume watching", st.resume) { st.set("resume", it) }
        SwitchRow("Background playback", st.bgPlay) { st.set("bgplay", it) }
        SwitchRow("Auto Picture-in-Picture", st.autoPip) { st.set("autopip", it) }

        SectionTitle("Subtitle defaults")
        Text("Size ${st.float("subsize", 22f).toInt()}sp")
        Slider(value = st.float("subsize", 22f), onValueChange = { st.set("subsize", it) }, valueRange = 14f..40f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            subColors.forEachIndexed { i, c ->
                Box(
                    Modifier.padding(end = 12.dp).size(32.dp).clip(CircleShape).background(c)
                        .border(if (st.int("subcolor", 0) == i) 3.dp else 1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        .clickable { st.set("subcolor", i) }
                )
            }
        }
        SwitchRow("Subtitle background", st.bool("subbg", false)) { st.set("subbg", it) }

        SectionTitle("Security")
        SwitchRow("App lock", st.appLock) {
            if (it && !st.hasPin) pinDialog = true
            st.set("applock", it)
        }
        SwitchRow("Fingerprint unlock", st.bio) { st.set("bio", it) }
        OutlinedButton(onClick = { pinDialog = true }) { Text(if (st.hasPin) "Change PIN" else "Set PIN") }

        SectionTitle("Data")
        OutlinedButton(onClick = { st.clearHistory() }) { Text("Clear watch history") }

        SectionTitle("About")
        OutlinedButton(onClick = onAbout, modifier = Modifier.fillMaxWidth()) { Text("Contact & Support") }
        Spacer(Modifier.height(8.dp))
        Text("PK'S Player 1.0 • Ad-free", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionTitle(t: String) {
    Spacer(Modifier.height(18.dp))
    Text(t, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val uri = LocalUriHandler.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contact & Support") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(20.dp).verticalScroll(rememberScrollState())) {
            Text("Contact & Support", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                "For any questions, suggestions, feedback, or support regarding the app, " +
                    "feel free to contact the developer through the following social media platforms."
            )
            Spacer(Modifier.height(16.dp))
            Text("Developer: Poros", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            LinkButton("Facebook") { uri.openUri("https://www.facebook.com/share/1FFQNQECUg/") }
            LinkButton("Instagram") { uri.openUri("https://www.instagram.com/p_o_r_o_s/") }
            LinkButton("Telegram") { uri.openUri("https://t.me/cyrus4040") }
            Spacer(Modifier.height(16.dp))
            Text("Thank you for using our application. Your feedback and support are always appreciated!")
        }
    }
}

@Composable
fun LinkButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text(label) }
}
