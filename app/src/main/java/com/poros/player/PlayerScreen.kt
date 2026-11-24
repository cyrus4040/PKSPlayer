package com.poros.player

import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
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
    var showRemain by remember { mutableStateOf(st.bool("remain", false)) }   // right-side time: total <-> remaining
    var seeking by remember { mutableStateOf(false) }
    var seekValue by remember { mutableFloatStateOf(0f) }
    var seekFrom by remember { mutableLongStateOf(0L) }
    var landscape by remember { mutableStateOf(true) }
    var autoLandscape by remember { mutableStateOf<Boolean?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var aPos by remember { mutableStateOf<Long?>(null) }
    var bPos by remember { mutableStateOf<Long?>(null) }
    var sleepMinutes by remember { mutableIntStateOf(0) }
    var fastHold by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var fxOpen by remember { mutableStateOf(false) }
    var fx by remember { mutableStateOf(FxState()) }
    var compare by remember { mutableStateOf(false) }
    var hudText by remember { mutableStateOf("") }
    var coach by remember { mutableStateOf(!st.bool("coach_v1", false)) }   // first-run "where is what" overlay

    val audio = remember { act.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var vol by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol) }
    var bright by remember { mutableFloatStateOf(0.5f) }

    val subPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) act.loadSubtitle(uri, act.displayName(uri), 0L)
    }

    // Portrait video -> portrait player, landscape video -> landscape player (re-applied only when it flips)
    fun autoRotate(vs: VideoSize) {
        if (vs.width <= 0 || vs.height <= 0) return
        var w = vs.width * vs.pixelWidthHeightRatio
        var h = vs.height.toFloat()
        if (vs.unappliedRotationDegrees == 90 || vs.unappliedRotationDegrees == 270) { val t = w; w = h; h = t }
        val land = w >= h
        if (autoLandscape == land) return
        autoLandscape = land
        landscape = land
        act.requestedOrientation = if (land) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { isPlaying = p }
            override fun onMediaMetadataChanged(m: MediaMetadata) { title = m.title?.toString() ?: "" }
            override fun onVideoSizeChanged(videoSize: VideoSize) { autoRotate(videoSize) }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                zoom = 1f; panX = 0f; panY = 0f
            }
        }
        player.addListener(l)
        autoRotate(player.videoSize)
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
    LaunchedEffect(showControls, isPlaying, locked, dialog, seeking, moreOpen, coach) {
        if (showControls && isPlaying && dialog == null && !seeking && !coach) { delay(3500); showControls = false }
    }
    LaunchedEffect(showControls) { if (!showControls) moreOpen = false }
    LaunchedEffect(hud) {
        val h = hud
        if (h != null) { hudText = h; delay(900); hud = null }
    }
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
                (LayoutInflater.from(ctx).inflate(R.layout.pk_player_view, null, false) as PlayerView).apply {
                    useController = false
                    // let a pinch-zoomed video draw outside the (letter-boxed) video frame
                    clipChildren = false
                    (findViewById<View>(androidx.media3.ui.R.id.exo_content_frame) as? ViewGroup)?.clipChildren = false
                    this.player = player
                    act.playerView = this
                }
            },
            update = { pv ->
                pv.resizeMode = resizeModes[resizeIdx].first
                // live preset preview: applied instantly to the video surface, playback is untouched
                pv.videoSurfaceView?.let { v: View ->
                    VideoFx.apply(v, if (compare) FxState() else fx)
                    v.scaleX = zoom; v.scaleY = zoom
                    v.translationX = panX; v.translationY = panY
                }
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
            modifier = Modifier.fillMaxSize().then(
                if (resizeIdx == 0) Modifier.windowInsetsPadding(WindowInsets.displayCutout) else Modifier
            )
        )

        if (!act.inPip) {
            // ---------------- gesture layer ----------------
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(locked) {
                        if (locked) return@pointerInput
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            var multi = false
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pressed = event.changes.count { it.pressed }
                                if (pressed >= 2) multi = true
                                if (multi) {
                                    if (pressed >= 2) {
                                        val z = event.calculateZoom()
                                        val pan = event.calculatePan()
                                        val c = event.calculateCentroid(useCurrent = true)
                                        val newZoom = (zoom * z).coerceIn(1f, 4f)
                                        val f = newZoom / zoom
                                        val cx = size.width / 2f
                                        val cy = size.height / 2f
                                        val mx = size.width * (newZoom - 1f) / 2f
                                        val my = size.height * (newZoom - 1f) / 2f
                                        panX = ((c.x - cx) * (1f - f) + panX * f + pan.x).coerceIn(-mx, mx)
                                        panY = ((c.y - cy) * (1f - f) + panY * f + pan.y).coerceIn(-my, my)
                                        zoom = newZoom
                                        hud = "Zoom ${(zoom * 100).roundToInt()}%"
                                    }
                                    event.changes.forEach { it.consume() }   // keep tap / volume / seek gestures out
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
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
                        var edgeIgnore = false
                        // swipes that start at the very top (notification shade) or bottom (gesture bar)
                        // belong to the system, so volume / brightness must not react to them
                        val edgeTop = 90.dp.toPx()
                        val edgeBottom = 48.dp.toPx()
                        detectVerticalDragGestures(
                            onDragStart = { o ->
                                leftSide = o.x < size.width / 2f
                                edgeIgnore = o.y < edgeTop || o.y > size.height - edgeBottom
                            },
                            onDragCancel = { edgeIgnore = false },
                            onDragEnd = { edgeIgnore = false },
                            onVerticalDrag = { change, dy ->
                                if (edgeIgnore) return@detectVerticalDragGestures
                                change.consume()
                                val d = -dy / size.height * 1.5f
                                if (leftSide) {
                                    bright = (bright + d).coerceIn(0.02f, 1f)
                                    val lp = act.window.attributes
                                    lp.screenBrightness = bright
                                    act.window.attributes = lp
                                    hud = "Brightness ${(bright * 100).roundToInt()}%"
                                } else {
                                    // Sound boost (up to 200 %) only in HW+ mode; HW / SW stay at 100 %
                                    val boostOk = st.decoder == 1
                                    val maxV = if (boostOk) 2f else 1f
                                    vol = (vol + d * 1.4f).coerceIn(0f, maxV)
                                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, (vol.coerceAtMost(1f) * maxVol).roundToInt(), 0)
                                    act.fx.updateVolBoost(if (vol > 1f) (vol * 100f).roundToInt() else 100)
                                    hud = if (!boostOk && vol >= 1f) "Volume 100% • HW+ for sound boost"
                                    else "Volume ${(vol * 100).roundToInt()}%"
                                }
                            }
                        )
                    }
                    .pointerInput(locked) {
                        if (locked) return@pointerInput
                        var target = 0L
                        var edgeIgnore = false
                        val edgeSide = 28.dp.toPx()
                        detectHorizontalDragGestures(
                            onDragStart = { o ->
                                target = player.currentPosition
                                edgeIgnore = o.x < edgeSide || o.x > size.width - edgeSide
                            },
                            onDragCancel = { edgeIgnore = false },
                            onHorizontalDrag = { change, dx ->
                                if (edgeIgnore) return@detectHorizontalDragGestures
                                change.consume()
                                val dur = player.duration.coerceAtLeast(0L)
                                target = (target + dx * 100).toLong().coerceIn(0L, dur)
                                hud = fmt(target)
                            },
                            onDragEnd = { if (!edgeIgnore) player.seekTo(target); edgeIgnore = false }
                        )
                    }
            )

            val cutoutPad = Modifier.windowInsetsPadding(WindowInsets.displayCutout)
            val ease = FastOutSlowInEasing

            // ---------------- HUD (volume / brightness / seek text) ----------------
            AnimatedVisibility(
                visible = hud != null,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp),
                enter = fadeIn(tween(150)) + scaleIn(tween(150, easing = ease), initialScale = 0.9f),
                exit = fadeOut(tween(300)) + scaleOut(tween(300, easing = ease), targetScale = 0.9f)
            ) {
                Text(
                    hudText, color = Color.White,
                    modifier = Modifier
                        .background(Color(0xAA000000), RoundedCornerShape(12.dp))
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.titleLarge
                )
            }

            if (aPos != null) {
                Text(
                    "A-B: ${fmt(aPos!!)} → ${bPos?.let { fmt(it) } ?: "?"}",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).then(cutoutPad).statusBarsPadding().padding(12.dp),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            // ---------------- unlock button (only while locked) ----------------
            AnimatedVisibility(
                visible = showControls && locked,
                modifier = Modifier.align(Alignment.CenterStart),
                enter = fadeIn(tween(250, easing = ease)) + scaleIn(tween(250, easing = ease), initialScale = 0.8f),
                exit = fadeOut(tween(250, easing = ease)) + scaleOut(tween(250, easing = ease), targetScale = 0.8f)
            ) {
                IconButton(
                    onClick = { locked = false },
                    modifier = cutoutPad.padding(24.dp).background(Color(0x99000000), RoundedCornerShape(50))
                ) { Icon(Icons.Default.Lock, contentDescription = "Unlock", tint = MaterialTheme.colorScheme.primary) }
            }

            // ---------------- top bar ----------------
            AnimatedVisibility(
                visible = showControls && !locked,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(tween(250, easing = ease)) + slideInVertically(tween(300, easing = ease)) { -it / 2 },
                exit = fadeOut(tween(200, easing = ease)) + slideOutVertically(tween(250, easing = ease)) { -it / 2 }
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                        .then(cutoutPad)
                        .statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { act.finish() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Text(title, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { dialog = "audio" }) {
                        Icon(Icons.Default.Audiotrack, contentDescription = "Audio track", tint = Color.White)
                    }
                    IconButton(onClick = { dialog = "sub" }) {
                        Icon(Icons.Default.Subtitles, contentDescription = "Subtitles", tint = Color.White)
                    }
                    TextButton(onClick = { dialog = "decoder" }) {
                        Text(listOf("HW", "HW+", "SW")[st.decoder.coerceIn(0, 2)], color = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { fxOpen = true; showControls = false }) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = "Presets",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // ---------------- centre: -10s / play-pause / +10s ----------------
            AnimatedVisibility(
                visible = showControls && !locked && !seeking,
                modifier = Modifier.align(Alignment.Center),
                enter = fadeIn(tween(250, easing = ease)) + scaleIn(tween(300, easing = ease), initialScale = 0.85f),
                exit = fadeOut(tween(200, easing = ease)) + scaleOut(tween(250, easing = ease), targetScale = 0.85f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(44.dp)
                ) {
                    IconButton(onClick = { seekBy(-10_000) }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Default.Replay10, contentDescription = "-10s", tint = Color.White,
                            modifier = Modifier.size(36.dp))
                    }
                    FilledIconButton(
                        onClick = { if (isPlaying) player.pause() else player.play() },
                        modifier = Modifier.size(68.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0x55000000),
                            contentColor = Color.White
                        )
                    ) {
                        Crossfade(targetState = isPlaying, animationSpec = tween(200), label = "centrePlayPause") { playing ->
                            Icon(
                                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (playing) "Pause" else "Play", modifier = Modifier.size(42.dp)
                            )
                        }
                    }
                    IconButton(onClick = { seekBy(10_000) }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Default.Forward10, contentDescription = "+10s", tint = Color.White,
                            modifier = Modifier.size(36.dp))
                    }
                }
            }

            // ---------------- live time while dragging the seek bar ----------------
            AnimatedVisibility(
                visible = seeking,
                modifier = Modifier.align(Alignment.Center),
                enter = fadeIn(tween(120)) + scaleIn(tween(150, easing = ease), initialScale = 0.9f),
                exit = fadeOut(tween(200))
            ) {
                val delta = seekValue.toLong() - seekFrom
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(Color(0x77000000), RoundedCornerShape(18.dp))
                        .padding(horizontal = 28.dp, vertical = 14.dp)
                ) {
                    Text(fmt(seekValue.toLong()), color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "[${if (delta >= 0) "+" else "-"}${fmt(kotlin.math.abs(delta))}]",
                        color = MaterialTheme.colorScheme.primary, fontSize = 22.sp
                    )
                }
            }

            // ---------------- thin progress line (only while controls are hidden) ----------------
            AnimatedVisibility(
                visible = !showControls,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(tween(250, easing = ease)),
                exit = fadeOut(tween(150, easing = ease))
            ) {
                val frac = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0x33FFFFFF))) {
                    Box(
                        Modifier.fillMaxWidth(frac).fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }

            // ---------------- bottom panel ----------------
            AnimatedVisibility(
                visible = showControls && !locked,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(tween(250, easing = ease)) + slideInVertically(tween(300, easing = ease)) { it / 2 },
                exit = fadeOut(tween(200, easing = ease)) + slideOutVertically(tween(250, easing = ease)) { it / 2 }
            ) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                        .then(cutoutPad)
                        .navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    // tools row: 4 icons + arrow, the arrow opens the rest
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState())) {
                            ToolButton(
                                Icons.Default.Speed, "Speed",
                                tint = if (speed != 1f) MaterialTheme.colorScheme.primary else Color.White
                            ) { dialog = "speed" }
                            ToolButton(Icons.Default.Equalizer, "Equalizer & boost") { dialog = "fx" }
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
                            AnimatedVisibility(
                                visible = moreOpen,
                                enter = expandHorizontally(tween(300, easing = ease)) + fadeIn(tween(300)),
                                exit = shrinkHorizontally(tween(250, easing = ease)) + fadeOut(tween(200))
                            ) {
                                Row {
                                    ToolButton(Icons.Default.ScreenRotation, "Rotate") {
                                        landscape = !landscape
                                        autoLandscape = landscape
                                        act.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                    }
                                    ToolButton(Icons.Default.BookmarkAdd, "Add bookmark") {
                                        val id = player.currentMediaItem?.mediaId
                                        if (id != null) { st.addBookmark(id, player.currentPosition); hud = "Bookmarked ${fmt(player.currentPosition)}" }
                                    }
                                    ToolButton(Icons.Default.Bookmarks, "Bookmarks") { dialog = "bm" }
                                    ToolButton(Icons.Default.PhotoCamera, "Screenshot") { act.screenshot() }
                                    ToolButton(Icons.Default.NavigateBefore, "Previous frame") { stepFrame(-1) }
                                    ToolButton(Icons.Default.NavigateNext, "Next frame") { stepFrame(1) }
                                }
                            }
                        }
                        val arrowRotation by animateFloatAsState(
                            targetValue = if (moreOpen) 180f else 0f,
                            animationSpec = tween(300, easing = ease),
                            label = "moreArrow"
                        )
                        IconButton(onClick = { moreOpen = !moreOpen }) {
                            Icon(
                                Icons.Default.ChevronRight, contentDescription = "More",
                                tint = Color.White, modifier = Modifier.rotate(arrowRotation)
                            )
                        }
                    }
                    // seek bar
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fmt(if (seeking) seekValue.toLong() else position), color = Color.White,
                            style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = if (seeking) seekValue else position.toFloat().coerceAtMost(maxOf(duration, 1L).toFloat()),
                            onValueChange = { if (!seeking) { seekFrom = position; seeking = true }; seekValue = it },
                            onValueChangeFinished = { player.seekTo(seekValue.toLong()); seeking = false },
                            valueRange = 0f..maxOf(duration, 1L).toFloat(),
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        )
                        val shownPos = if (seeking) seekValue.toLong() else position
                        Text(
                            if (showRemain) "-" + fmt((duration - shownPos).coerceAtLeast(0L)) else fmt(duration),
                            color = Color.White, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .clickable { showRemain = !showRemain; st.set("remain", showRemain) }
                                .padding(horizontal = 4.dp, vertical = 6.dp)
                        )
                    }
                    // bottom row: lock | prev, play/pause, next | zoom, PiP
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { locked = true; showControls = false }) {
                            Icon(Icons.Default.LockOpen, contentDescription = "Lock", tint = Color.White)
                        }
                        Row(
                            Modifier.weight(1f),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { player.seekToPreviousMediaItem() }) {
                                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = Color.White)
                            }
                            FilledIconButton(
                                onClick = { if (isPlaying) player.pause() else player.play() },
                                modifier = Modifier.padding(horizontal = 16.dp).size(56.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = Color(0xFF00C853), contentColor = Color.White
                                )
                            ) {
                                Crossfade(targetState = isPlaying, animationSpec = tween(200), label = "playpause") { playing ->
                                    Icon(
                                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = "Play/Pause", modifier = Modifier.size(34.dp)
                                    )
                                }
                            }
                            IconButton(onClick = { player.seekToNextMediaItem() }) {
                                Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White)
                            }
                        }
                        Row {
                            IconButton(onClick = {
                                resizeIdx = (resizeIdx + 1) % resizeModes.size
                                zoom = 1f; panX = 0f; panY = 0f
                                hud = resizeModes[resizeIdx].second
                            }) { Icon(Icons.Default.AspectRatio, contentDescription = "Zoom / aspect ratio", tint = Color.White) }
                            IconButton(onClick = { act.enterPip() }) {
                                Icon(Icons.Default.PictureInPictureAlt, contentDescription = "Picture-in-Picture", tint = Color.White)
                            }
                        }
                    }
                }
            }

            // ---------------- presets panel ----------------
            AnimatedVisibility(
                visible = fxOpen,
                modifier = Modifier.align(Alignment.CenterEnd),
                enter = fadeIn(tween(200)) + slideInHorizontally(tween(300, easing = ease)) { it },
                exit = fadeOut(tween(200)) + slideOutHorizontally(tween(250, easing = ease)) { it }
            ) {
                PresetPanel(
                    st = st,
                    state = fx,
                    onChange = { fx = it },
                    onCompare = { compare = it },
                    onClose = { fxOpen = false; compare = false },
                    modifier = Modifier.fillMaxHeight().widthIn(max = 340.dp).fillMaxWidth(0.9f)
                )
            }

            // ---------------- first-run: where Subtitles / Presets are ----------------
            if (coach) PlayerCoach(onDone = { coach = false; st.set("coach_v1", true) })

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
