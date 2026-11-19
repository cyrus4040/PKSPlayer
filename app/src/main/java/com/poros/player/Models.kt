package com.poros.player

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.util.Locale

data class VideoItem(
    val uri: Uri,
    val title: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val folder: String,
    val path: String = "",
    val relPath: String = ""
) {
    val key: String get() = uri.toString()
}

fun loadVideos(context: Context): List<VideoItem> {
    val collection = if (Build.VERSION.SDK_INT >= 29)
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    @Suppress("DEPRECATION")
    val projection = mutableListOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Video.Media.DATA
    ).apply {
        if (Build.VERSION.SDK_INT >= 29) add(MediaStore.MediaColumns.RELATIVE_PATH)
    }.toTypedArray()
    val out = mutableListOf<VideoItem>()
    context.contentResolver.query(
        collection, projection, null, null,
        "${MediaStore.Video.Media.DATE_ADDED} DESC"
    )?.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
        val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
        val bucketCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
        @Suppress("DEPRECATION")
        val dataCol = c.getColumnIndex(MediaStore.Video.Media.DATA)
        val relCol = if (Build.VERSION.SDK_INT >= 29) c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
        while (c.moveToNext()) {
            out += VideoItem(
                uri = ContentUris.withAppendedId(collection, c.getLong(idCol)),
                title = c.getString(nameCol) ?: "Video",
                durationMs = c.getLong(durCol),
                sizeBytes = c.getLong(sizeCol),
                folder = c.getString(bucketCol) ?: "Unknown",
                path = if (dataCol >= 0) c.getString(dataCol) ?: "" else "",
                relPath = if (relCol >= 0) c.getString(relCol) ?: "" else ""
            )
        }
    }
    return out
}

fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%02d:%02d", m, sec)
}

fun fmtSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format(Locale.US, "%.2f GB", mb / 1024)
    else String.format(Locale.US, "%.1f MB", mb)
}

fun openPlayer(ctx: Context, list: List<VideoItem>, index: Int) {
    val i = Intent(ctx, PlayerActivity::class.java)
        .putStringArrayListExtra("uris", ArrayList(list.map { it.uri.toString() }))
        .putStringArrayListExtra("titles", ArrayList(list.map { it.title }))
        .putExtra("index", index)
    ctx.startActivity(i)
}

fun openStream(ctx: Context, url: String) {
    val i = Intent(ctx, PlayerActivity::class.java)
        .putStringArrayListExtra("uris", arrayListOf(url))
        .putStringArrayListExtra("titles", arrayListOf(url.substringAfterLast('/').ifBlank { url }))
        .putExtra("index", 0)
    ctx.startActivity(i)
}
