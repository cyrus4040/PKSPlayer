package com.poros.player

import android.app.PictureInPictureParams
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Rational
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.ui.PlayerView
import androidx.media3.common.AudioAttributes
import java.io.File

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    lateinit var player: ExoPlayer
    lateinit var st: Settings
    lateinit var fx: AudioFx
    var playerView: PlayerView? = null

    var inPip by mutableStateOf(false)
    var subName by mutableStateOf<String?>(null)
    var subDelay by mutableLongStateOf(0L)
    var subUri: Uri? = null

    private var initialised = false

    /** Applies the HW / HW+ / SW decoder preference to video decoder selection. */
    private inner class PkRenderers(ctx: Context, val mode: Int) : DefaultRenderersFactory(ctx) {
        override fun buildVideoRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            eventHandler: Handler,
            eventListener: VideoRendererEventListener,
            allowedVideoJoiningTimeMs: Long,
            out: ArrayList<Renderer>
        ) {
            val picked = MediaCodecSelector { mime, secure, tunneling ->
                val all = mediaCodecSelector.getDecoderInfos(mime, secure, tunneling)
                when (mode) {
                    0 -> all.filter { !it.softwareOnly }.ifEmpty { all }
                    2 -> all.sortedBy { !it.softwareOnly }
                    else -> all
                }
            }
            super.buildVideoRenderers(
                context, extensionRendererMode, picked, mode != 0,
                eventHandler, eventListener, allowedVideoJoiningTimeMs, out
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        st = Settings.get(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // draw video behind the camera cut-out (like MX Player) - Fit mode keeps clear of it via insets in PlayerScreen
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val uris: List<String>
        val titles: List<String>
        var index = intent.getIntExtra("index", 0)
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            val d = intent.data!!
            uris = listOf(d.toString())
            titles = listOf(d.lastPathSegment ?: "Video")
            index = 0
        } else {
            uris = intent.getStringArrayListExtra("uris") ?: emptyList()
            titles = intent.getStringArrayListExtra("titles") ?: emptyList()
        }
        if (uris.isEmpty()) { finish(); return }
        index = index.coerceIn(0, uris.size - 1)

        // clean old subtitle cache
        cacheDir.listFiles()?.filter { it.name.startsWith("sub_") }?.forEach { it.delete() }

        val items = uris.mapIndexed { i, u ->
            MediaItem.Builder()
                .setUri(u)
                .setMediaId(u)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(titles.getOrElse(i) { "Video" }).build())
                .build()
        }

        fx = AudioFx(st)
        val exo = ExoPlayer.Builder(this, PkRenderers(this, st.decoder))
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player = exo
        PlayerHolder.player = exo
        initialised = true

        exo.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) { fx.attach(audioSessionId) }
        })
        fx.attach(exo.audioSessionId)

        val forced = intent.getLongExtra("startPos", -1L)
        val startPos = when {
            forced >= 0 -> forced
            st.resume -> st.progress(uris[index])?.first ?: 0L
            else -> 0L
        }
        exo.setMediaItems(items)
        exo.seekTo(index, startPos)
        exo.prepare()
        exo.playWhenReady = true

        if (st.bgPlay) {
            val si = Intent(this, BgService::class.java).putExtra("title", titles.getOrElse(index) { "PK'S Player" })
            try { androidx.core.content.ContextCompat.startForegroundService(this, si) } catch (e: Exception) { }
        }

        setContent {
            PkTheme(st.theme) { PlayerScreen(this) }
        }
    }

    // ---------------------------------------------------------------- progress

    fun saveProgress() {
        if (!initialised) return
        val id = player.currentMediaItem?.mediaId ?: return
        val dur = player.duration
        if (dur <= 0) return
        val pos = player.currentPosition
        st.saveProgress(id, if (pos >= dur - 3000) 0L else pos, dur)
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- decoder

    fun restartWithDecoder(mode: Int) {
        st.set("decoder", mode)
        saveProgress()
        val i = Intent(intent)
            .putExtra("index", player.currentMediaItemIndex)
            .putExtra("startPos", player.currentPosition)
        finish()
        startActivity(i)
    }

    // ---------------------------------------------------------------- subtitles

    fun displayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "subtitle.srt"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0) ?: name
            }
        } catch (e: Exception) { }
        return name
    }

    /** Loads (or reloads with a new delay) an external subtitle: SRT, VTT, ASS/SSA. */
    fun loadSubtitle(uri: Uri, name: String, delayMs: Long) {
        try {
            val kind = SubUtil.kind(name)
            val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() }?.toString(Charsets.UTF_8)
            if (raw == null) { toast("Cannot read subtitle"); return }
            val f = File(cacheDir, "sub_${System.nanoTime()}.$kind")
            f.writeText(SubUtil.shift(raw, kind, delayMs))

            val cur = player.currentMediaItem ?: return
            val idx = player.currentMediaItemIndex
            val pos = player.currentPosition
            val play = player.playWhenReady
            val sub = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(f))
                .setMimeType(SubUtil.mime(kind))
                .setLanguage("und")
                .setLabel(name)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            player.replaceMediaItem(idx, cur.buildUpon().setSubtitleConfigurations(listOf(sub)).build())
            player.seekTo(idx, pos)
            player.playWhenReady = play
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
            subUri = uri; subName = name; subDelay = delayMs
        } catch (e: Exception) {
            toast("Subtitle error: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- PiP

    fun enterPip() {
        if (Build.VERSION.SDK_INT < 26 || !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            toast("Picture-in-Picture not supported"); return
        }
        val w = player.videoSize.width
        val h = player.videoSize.height
        val r = if (w > 0 && h > 0) (w.toFloat() / h).coerceIn(0.5f, 2.3f) else 16f / 9f
        try {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational((r * 1000).toInt(), 1000)).build()
            )
        } catch (e: Exception) { toast("Cannot enter PiP") }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (initialised && st.autoPip && player.isPlaying) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    // ---------------------------------------------------------------- screenshot

    fun screenshot() {
        val tv = playerView?.videoSurfaceView as? TextureView
        if (tv != null) {
            val frame = tv.bitmap
            if (frame != null) saveBitmap(frame) else toast("Screenshot not available")
            return
        }
        val sv = playerView?.videoSurfaceView as? SurfaceView
        if (Build.VERSION.SDK_INT < 26 || sv == null || sv.width == 0 || sv.height == 0) {
            toast("Screenshot not available"); return
        }
        val bmp = Bitmap.createBitmap(sv.width, sv.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(sv, bmp, { result ->
            if (result == PixelCopy.SUCCESS) saveBitmap(bmp) else toast("Screenshot failed")
        }, Handler(Looper.getMainLooper()))
    }

    private fun saveBitmap(bmp: Bitmap) {
        try {
            val name = "PKS_${System.currentTimeMillis()}.png"
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PKSPlayer")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                if (uri != null) contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                toast("Screenshot saved to Pictures/PKSPlayer")
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                val f = File(dir, name)
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                toast("Saved: ${f.absolutePath}")
            }
        } catch (e: Exception) { toast("Screenshot failed") }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onStop() {
        super.onStop()
        if (!initialised) return
        saveProgress()
        if (inPip) { finish(); return }       // PiP window was closed
        if (!st.bgPlay) player.pause()
    }

    override fun onDestroy() {
        if (initialised) {
            saveProgress()
            fx.release()
            val mine = PlayerHolder.player === player
            player.release()
            if (mine) {
                PlayerHolder.player = null
                stopService(Intent(this, BgService::class.java))
            }
        }
        super.onDestroy()
    }
}
