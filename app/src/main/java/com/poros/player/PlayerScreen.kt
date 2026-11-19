package com.poros.player

import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.util.TypedValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

val SUB_COLORS = listOf(Color.White, Color(0xFFFFEB3B), Color(0xFFB4FF1A), Color(0xFF00E5FF))

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(act: PlayerActivity) {
    val player = act.player
    val st = act.st
    val resizeModes = remember {
        listOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
            AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch",
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Crop"
        )
    }

    var showControls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var title by remember { mutableStateOf("") }
    var resizeIdx by remember { mutableIntStateOf(0) }
    var speed by remember { mutableFloatStateOf(1f) }
    var hud by remember { mutableStateOf<String?>(null) }
    var seeking by remember { mutableStateOf(false) }
    var seekValue by remember { mutableFloatStateOf(0f) }
    var landscape by remember { mutableStateOf(true) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var aPos by remember { mutableStateOf<Long?>(null) }
    var bPos by remember { mutableStateOf<Long?>(null) }
    var sleepMinutes by remember { mutableIntStateOf(0) }
    var fastHold by remember { mutableStateOf(false) }

    val audio = remember { act.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var vol by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol) }
    var bright by remember { mutableFloatStateOf(0.5f) }

    val subPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) act.loadSubtitle(uri, act.displayName(uri), 0L)
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { isPlaying = p }
            override fun onMediaMetadataChanged(m: MediaMetadata) { title = m.title?.toString() ?: "" }
        }
        player.addListener(l)
        title = player.mediaMetadata.title?.toString() ?: ""
        onDispose { player.removeListener(l) }
    }

    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            val a = aPos
            val b = bPos
            if (a != null && b != null && position >= b) player.seekTo(a)
            delay(250)
        }
    }
    LaunchedEffect(showControls, isPlaying, locked, dialog) {
        if (showControls && isPlaying && dialog == null) { delay(3500); showControls = false }
    }
    LaunchedEffect(hud) { if (hud != null) { delay(900); hud = null } }
    LaunchedEffect(sleepMinutes) {
        if (sleepMinutes > 0) {
            delay(sleepMinutes * 60_000L)
            player.pause()
            sleepMinutes = 0
            act.toast("Sleep timer: paused")
        }
    }

    fun seekBy(deltaMs: Long) {
        val dur = player.duration.coerceAtLeast(0L)
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, dur))
    }
    fun setSpeed(s: Float) { speed = s; player.setPlaybackSpeed(s) }
    fun stepFrame(dir: Int) {
        player.pause()
        player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
        val fps = player.videoFormat?.frameRate?.takeIf { it > 0f } ?: 30f
        val step = (1000f / fps).roundToInt().coerceAtLeast(1)
        player.seekTo((player.currentPosition + dir * step).coerceAtLeast(0L))
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.player = player
                    act.playerView = this
                }
            },
            update = { pv ->
                pv.resizeMode = resizeModes[resizeIdx].first
                pv.subtitleView?.apply {
                    setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, st.float("subsize", 22f))
                    val c = SUB_COLORS[st.int("subcolor", 0).coerceIn(0, SUB_COLORS.lastIndex)]
                    val bg = if (st.bool("subbg", false)) 0x99000000.toInt() else Color.Transparent.toArgb()
                    setStyle(
                        CaptionStyleCompat(
                            c.toArgb(), bg, Color.Transparent.toArgb(),
                            CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.Black.toArgb(), null
                        )
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (!act.inPip) {
            // ---------------- gesture layer ----------------
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(locked) {
                        detectTapGestures(
                            onPress = {
                                tryAwaitRelease()
                                if (fastHold) { fastHold = false; player.setPlaybackSpeed(speed) }
                            },
                            onLongPress = {
                                if (!locked) { fastHold = true; player.setPlaybackSpeed(2f); hud = "2x" }
                            },
                            onTap = { showControls = !showControls },
                            onDoubleTap = { off ->
                                if (!locked) {
                                    val third = size.width / 3f
                                    when {
                                        off.x < third -> { seekBy(-10_000); hud = "-10s" }
                                        off.x > third * 2 -> { seekBy(10_000); hud = "+10s" }
                                        else -> if (player.isPlaying) player.pause() else player.play()
                                    }
                                }
                            }
                        )
                    }
                    .pointerInput(locked) {
                        if (locked) return@pointerInput
                        var leftSide = true
                        detectVerticalDragGestures(
                            onDragStart = { o -> leftSide = o.x < size.width / 2f },
                            onVerticalDrag = { change, dy ->
                                change.consume()
                                val d = -dy / size.height * 1.5f
                                if (leftSide) {
                                    bright = (bright + d).coerceIn(0.02f, 1f)
                                    val lp = act.window.attributes
                                    lp.screenBrightness = bright
                                    act.window.attributes = lp
                                    hud = "Brightness ${(bright * 100).roundToInt()}%"
                                } else {
                                    vol = (vol + d).coerceIn(0f, 1f)
                                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, (vol * maxVol).roundToInt(), 0)
                                    hud = "Volume ${(vol * 100).roundToInt()}%"
                                }
                            }
                        )
                    }
                    .pointerInput(locked) {
                        if (locked) return@pointerInput
                        var target = 0L
                        detectHorizontalDragGestures(
                            onDragStart = { target = player.currentPosition },
                            onHorizontalDrag = { change, dx ->
                                change.consume()
                                val dur = player.duration.coerceAtLeast(0L)
                                target = (target + dx * 100).toLong().coerceIn(0L, dur)
                                hud = fmt(target)
                            },
                            onDragEnd = { player.seekTo(target) }
                        )
                    }
            )

            hud?.let {
                Text(
                    it, color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                        .background(Color(0xAA000000), RoundedCornerShape(12.dp))
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.titleLarge
                )
            }

            if (aPos != null) {
                Text(
                    "A-B: ${fmt(aPos!!)} → ${bPos?.let { fmt(it) } ?: "?"}",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            AnimatedVisibility(showControls && locked, modifier = Modifier.align(Alignment.CenterStart)) {
                IconButton(
                    onClick = { locked = false },
                    modifier = Modifier.padding(24.dp).background(Color(0x99000000), RoundedCornerShape(50))
                ) { Icon(Icons.Default.Lock, contentDescription = "Unlock", tint = MaterialTheme.colorScheme.primary) }
            }

            // ---------------- top bar ----------------
            AnimatedVisibility(showControls && !locked, modifier = Modifier.align(Alignment.TopCenter)) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                        .statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { act.finish() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Text(title, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { dialog = "speed" }) {
                        Text("${speed}x", color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = { dialog = "decoder" }) {
                        Text(listOf("HW", "HW+", "SW")[st.decoder.coerceIn(0, 2)], color = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = {
                        landscape = !landscape
                        act.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    }) { Icon(Icons.Default.ScreenRotation, contentDescription = "Rotate", tint = Color.White) }
                }
            }

            // ---------------- bottom controls ----------------
            AnimatedVisibility(showControls && !locked, modifier = Modifier.align(Alignment.BottomCenter)) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                        .navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    // tools row
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        ToolButton(Icons.Default.Subtitles, "Subtitles") { dialog = "sub" }
                        ToolButton(Icons.Default.Audiotrack, "Audio track") { dialog = "audio" }
                        ToolButton(Icons.Default.Equalizer, "Equalizer & boost") { dialog = "fx" }
                        ToolButton(Icons.Default.Speed, "Speed") { dialog = "speed" }
                        ToolButton(
                            Icons.Default.Repeat, "A-B repeat",
                            tint = if (aPos != null) MaterialTheme.colorScheme.primary else Color.White
                        ) {
                            when {
                                aPos == null -> { aPos = player.currentPosition; hud = "A set" }
                                bPos == null -> { bPos = player.currentPosition; hud = "B set • looping" }
                                else -> { aPos = null; bPos = null; hud = "A-B off" }
                            }
                        }
                        ToolButton(
                            Icons.Default.Timer, "Sleep timer",
                            tint = if (sleepMinutes > 0) MaterialTheme.colorScheme.primary else Color.White
                        ) { dialog = "sleep" }
                        ToolButton(Icons.Default.BookmarkAdd, "Add bookmark") {
                            val id = player.currentMediaItem?.mediaId
                            if (id != null) { st.addBookmark(id, player.currentPosition); hud = "Bookmarked ${fmt(player.currentPosition)}" }
                        }
                        ToolButton(Icons.Default.Bookmarks, "Bookmarks") { dialog = "bm" }
                        ToolButton(Icons.Default.PhotoCamera, "Screenshot") { act.screenshot() }
                        ToolButton(Icons.Default.PictureInPictureAlt, "Picture-in-Picture") { act.enterPip() }
                        ToolButton(Icons.Default.AspectRatio, "Aspect ratio") {
                            resizeIdx = (resizeIdx + 1) % resizeModes.size
                            hud = resizeModes[resizeIdx].second
                        }
                        ToolButton(Icons.Default.Memory, "Decoder") { dialog = "decoder" }
                    }
                    // seek bar
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fmt(if (seeking) seekValue.toLong() else position), color = Color.White,
                            style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = if (seeking) seekValue else position.toFloat().coerceAtMost(maxOf(duration, 1L).toFloat()),
                            onValueChange = { seeking = true; seekValue = it },
                            onValueChangeFinished = { player.seekTo(seekValue.toLong()); seeking = false },
                            valueRange = 0f..maxOf(duration, 1L).toFloat(),
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        )
                        Text(fmt(duration), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                    // transport row
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { locked = true; showControls = false }) {
                            Icon(Icons.Default.LockOpen, contentDescription = "Lock", tint = Color.White)
                        }
                        IconButton(onClick = { player.seekToPreviousMediaItem() }) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = Color.White)
                        }
                        if (!isPlaying) IconButton(onClick = { stepFrame(-1) }) {
                            Icon(Icons.Default.NavigateBefore, contentDescription = "Previous frame", tint = Color.White)
                        }
                        IconButton(onClick = { seekBy(-10_000) }) {
                            Icon(Icons.Default.Replay10, contentDescription = "-10s", tint = Color.White)
                        }
                        FilledIconButton(onClick = { if (isPlaying) player.pause() else player.play() }) {
                            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play/Pause")
                        }
                        IconButton(onClick = { seekBy(10_000) }) {
                            Icon(Icons.Default.Forward10, contentDescription = "+10s", tint = Color.White)
                        }
                        if (!isPlaying) IconButton(onClick = { stepFrame(1) }) {
                            Icon(Icons.Default.NavigateNext, contentDescription = "Next frame", tint = Color.White)
                        }
                        IconButton(onClick = { player.seekToNextMediaItem() }) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White)
                        }
                    }
                }
            }

            // ---------------- dialogs ----------------
            val close = { dialog = null }
            when (dialog) {
                "speed" -> SpeedDialog(speed, { setSpeed(it) }, close)
                "audio" -> TracksDialog(player, androidx.media3.common.C.TRACK_TYPE_AUDIO, "Audio track", false, close)
                "sub" -> SubtitleDialog(act, st, { subPicker.launch(arrayOf("*/*")) },
                    onTracks = { dialog = "subtracks" }, onDismiss = close)
                "subtracks" -> TracksDialog(player, androidx.media3.common.C.TRACK_TYPE_TEXT, "Subtitle track", true, close)
                "fx" -> AudioFxDialog(act.fx, close)
                "sleep" -> SleepDialog(sleepMinutes, { sleepMinutes = it }, close)
                "decoder" -> DecoderDialog(st.decoder, { act.restartWithDecoder(it) }, close)
                "bm" -> BookmarksDialog(player, st, close)
            }
        }
    }
}

@Composable
fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) { Icon(icon, contentDescription = desc, tint = tint) }
}
