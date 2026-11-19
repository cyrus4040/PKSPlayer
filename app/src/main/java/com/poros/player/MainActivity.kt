package com.poros.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val st = Settings.get(this)
        setContent {
            PkTheme(st.theme) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    val st = remember { Settings.get(ctx) }

    var unlocked by remember { mutableStateOf(!(st.appLock && st.hasPin)) }
    if (!unlocked) {
        LockScreen("PK'S Player is locked", onUnlocked = { unlocked = true })
        return
    }

    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
    else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(perm)
        else if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    var videos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(granted, reloadTick) {
        if (granted) {
            if (videos.isEmpty()) loading = true
            videos = withContext(Dispatchers.IO) { loadVideos(ctx) }
            loading = false
        }
    }
    val loader = remember { ImageLoader.Builder(ctx).components { add(VideoFrameDecoder.Factory()) }.build() }

    var nav by remember { mutableIntStateOf(0) }
    var folder by remember { mutableStateOf<String?>(null) }
    var playlist by remember { mutableStateOf<String?>(null) }
    var showAbout by remember { mutableStateOf(false) }
    var showStream by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var vaultUnlocked by remember { mutableStateOf(false) }

    val sel = remember { SelState() }
    val ops = rememberFileOps { sel.clear(); reloadTick++ }
    var deleteItems by remember { mutableStateOf<List<VideoItem>?>(null) }
    var renameItem by remember { mutableStateOf<VideoItem?>(null) }
    var moveItems by remember { mutableStateOf<List<VideoItem>?>(null) }
    var copyItems by remember { mutableStateOf<List<VideoItem>?>(null) }
    var newFolderDialog by remember { mutableStateOf(false) }
    val act = remember {
        Actions(
            sel = sel,
            share = { ops.share(it) },
            delete = { deleteItems = it },
            rename = { renameItem = it },
            move = { moveItems = it },
            copy = { copyItems = it }
        )
    }

    LaunchedEffect(nav) {
        if (nav != 3) vaultUnlocked = false
        folder = null; playlist = null
        sel.clear()
    }
    LaunchedEffect(folder, playlist) { sel.clear() }

    BackHandler(showAbout || folder != null || playlist != null || searching || sel.active) {
        when {
            sel.active -> sel.clear()
            showAbout -> showAbout = false
            searching -> { searching = false; query = "" }
            folder != null -> folder = null
            playlist != null -> playlist = null
        }
    }

    if (showAbout) {
        AboutScreen { showAbout = false }
        return
    }

    val hidden = st.hidden()
    val visible = remember(videos, hidden) { videos.filter { it.key !in hidden } }

    val titles = listOf("Videos", "Folders", "Playlists", "Private Vault", "Settings")
    Scaffold(
        topBar = {
            if (sel.active) {
                val picked = videos.filter { it.key in sel.keys }
                SelectionBar(
                    count = sel.keys.size,
                    onClose = { sel.clear() },
                    onSelectAll = { if (sel.allSelected) sel.clear() else sel.selectAll() },
                    onShare = { ops.share(picked) },
                    onDelete = { deleteItems = picked },
                    onRename = { picked.firstOrNull()?.let { renameItem = it } },
                    onMove = { moveItems = picked },
                    onCopy = { copyItems = picked }
                )
            } else TopAppBar(
                title = {
                    if (searching && nav == 0) {
                        OutlinedTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            placeholder = { Text("Search videos") }, modifier = Modifier.fillMaxWidth()
                        )
                    } else Text(folder ?: playlist ?: titles[nav])
                },
                navigationIcon = {
                    if (folder != null || playlist != null)
                        IconButton(onClick = { folder = null; playlist = null }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                },
                actions = {
                    if (nav == 0) IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = { showStream = true }) {
                        Icon(Icons.Default.Language, contentDescription = "Network stream")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                val icons = listOf(Icons.Default.VideoLibrary, Icons.Default.Folder, Icons.Default.PlaylistPlay,
                    Icons.Default.Lock, Icons.Default.Settings)
                titles.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = nav == i, onClick = { nav = i },
                        icon = { Icon(icons[i], contentDescription = t) },
                        label = { Text(if (i == 3) "Vault" else t, maxLines = 1) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                !granted && nav <= 2 -> Center {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Video access permission is needed")
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { launcher.launch(perm) }) { Text("Allow") }
                    }
                }
                loading -> Center { CircularProgressIndicator() }
                nav == 0 -> VideosScreen(visible, st, loader, query, act)
                nav == 1 -> FoldersScreen(visible, st, loader, folder, act, { newFolderDialog = true }) { folder = it }
                nav == 2 -> PlaylistsScreen(videos, st, loader, playlist, act) { playlist = it }
                nav == 3 -> VaultScreen(videos, st, loader, vaultUnlocked, act) { vaultUnlocked = true }
                else -> SettingsScreen(st) { showAbout = true }
            }
        }
    }

    deleteItems?.let { l ->
        DeleteConfirmDialog(
            count = l.size,
            onConfirm = { deleteItems = null; ops.delete(l) },
            onDismiss = { deleteItems = null }
        )
    }
    renameItem?.let { v ->
        RenameDialog(
            item = v,
            onConfirm = { renameItem = null; ops.rename(v, it) },
            onDismiss = { renameItem = null }
        )
    }
    moveItems?.let { l ->
        FolderPickerDialog(
            title = "Move to folder",
            targets = folderTargets(visible, st),
            onPick = { moveItems = null; ops.move(l, it) },
            onCreate = { name -> ops.newFolder(st, name)?.let { moveItems = null; ops.move(l, it) } },
            onDismiss = { moveItems = null }
        )
    }
    copyItems?.let { l ->
        FolderPickerDialog(
            title = "Copy to folder",
            targets = folderTargets(visible, st),
            onPick = { copyItems = null; ops.copy(l, it) },
            onCreate = { name -> ops.newFolder(st, name)?.let { copyItems = null; ops.copy(l, it) } },
            onDismiss = { copyItems = null }
        )
    }
    if (newFolderDialog) NewFolderDialog(
        onCreate = { name ->
            if (ops.newFolder(st, name) != null) newFolderDialog = false
        },
        onDismiss = { newFolderDialog = false }
    )
    ops.busy?.let { BusyDialog(it) }

    if (showStream) StreamDialog(
        onDismiss = { showStream = false },
        onPlay = { showStream = false; openStream(ctx, it) }
    )
}

@Composable
fun StreamDialog(onDismiss: () -> Unit, onPlay: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Network stream") },
        text = {
            OutlinedTextField(
                value = url, onValueChange = { url = it }, singleLine = true,
                label = { Text("URL (HLS .m3u8, DASH .mpd, mp4...)") }
            )
        },
        confirmButton = { TextButton(onClick = { if (url.isNotBlank()) onPlay(url.trim()) }) { Text("Play") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
