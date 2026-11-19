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
    LaunchedEffect(granted) {
        if (granted) {
            loading = true
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

    LaunchedEffect(nav) {
        if (nav != 3) vaultUnlocked = false
        folder = null; playlist = null
    }

    BackHandler(showAbout || folder != null || playlist != null || searching) {
        when {
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
            TopAppBar(
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
                nav == 0 -> VideosScreen(visible, st, loader, query)
                nav == 1 -> FoldersScreen(visible, st, loader, folder) { folder = it }
                nav == 2 -> PlaylistsScreen(videos, st, loader, playlist) { playlist = it }
                nav == 3 -> VaultScreen(videos, st, loader, vaultUnlocked) { vaultUnlocked = true }
                else -> SettingsScreen(st) { showAbout = true }
            }
        }
    }

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
