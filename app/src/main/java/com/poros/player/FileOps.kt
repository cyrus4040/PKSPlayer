package com.poros.player

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ------------------------------------------------------------------ selection

class SelState {
    var keys by mutableStateOf<Set<String>>(emptySet())
        private set

    /** The list currently on screen; used by "select all". */
    var visible: List<VideoItem> = emptyList()

    val active: Boolean get() = keys.isNotEmpty()
    val allSelected: Boolean
        get() = visible.isNotEmpty() && keys.containsAll(visible.map { it.key })

    fun toggle(k: String) { keys = if (k in keys) keys - k else keys + k }
    fun clear() { keys = emptySet() }
    fun selectAll() { keys = visible.map { it.key }.toSet() }
}

class Actions(
    val sel: SelState,
    val share: (List<VideoItem>) -> Unit,
    val delete: (List<VideoItem>) -> Unit,
    val rename: (VideoItem) -> Unit,
    val move: (List<VideoItem>) -> Unit,
    val copy: (List<VideoItem>) -> Unit
)

// ------------------------------------------------------------------ folders

data class FolderTarget(val name: String, val rel: String)

private fun VideoItem.dirRef(): String =
    if (Build.VERSION.SDK_INT >= 29) relPath else (File(path).parent ?: "")

@Suppress("DEPRECATION")
private fun newFolderRef(name: String): String =
    if (Build.VERSION.SDK_INT >= 29) "Movies/$name/"
    else File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), name).absolutePath

fun folderTargets(videos: List<VideoItem>, st: Settings): List<FolderTarget> {
    val m = linkedMapOf<String, FolderTarget>()
    videos.forEach { v ->
        val rel = v.dirRef()
        if (rel.isNotEmpty() && !m.containsKey(rel)) m[rel] = FolderTarget(v.folder, rel)
    }
    st.createdFolders().forEach { n ->
        val rel = newFolderRef(n)
        if (!m.containsKey(rel)) m[rel] = FolderTarget(n, rel)
    }
    return m.values.sortedBy { it.name.lowercase() }
}

private fun uniqueFile(dir: File, name: String): File {
    var f = File(dir, name)
    if (!f.exists()) return f
    val base = name.substringBeforeLast('.', name)
    val ext = name.substringAfterLast('.', "")
    var i = 1
    while (f.exists()) {
        f = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
        i++
    }
    return f
}

// ------------------------------------------------------------------ operations

class FileOps(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val onChanged: () -> Unit
) {
    var launcher: (IntentSender) -> Unit = {}
    var busy by mutableStateOf<String?>(null)
    private var pending: (() -> Unit)? = null

    fun onPermissionResult(ok: Boolean) {
        val p = pending
        pending = null
        if (ok) p?.invoke() else toast("Cancelled")
    }

    private fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show()
    private fun finish(m: String) { toast(m); onChanged() }

    // ---- share ----
    fun share(items: List<VideoItem>) {
        if (items.isEmpty()) return
        val i = if (items.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, items[0].uri)
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(items.map { it.uri }))
        }
        i.type = "video/*"
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---- delete ----
    fun delete(items: List<VideoItem>) {
        if (items.isEmpty()) return
        val uris = items.map { it.uri }
        val msg = "Deleted ${items.size}"
        if (Build.VERSION.SDK_INT >= 30) {
            pending = { finish(msg) }
            launcher(MediaStore.createDeleteRequest(ctx.contentResolver, uris).intentSender)
        } else {
            deleteLegacy(uris, msg)
        }
    }

    private fun deleteLegacy(uris: List<Uri>, msg: String) {
        try {
            uris.forEach { ctx.contentResolver.delete(it, null, null) }
            finish(msg)
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException) {
                pending = { deleteLegacy(uris, msg) }
                launcher(e.userAction.actionIntent.intentSender)
            } else toast("Permission denied")
        } catch (e: Exception) {
            toast("Failed: ${e.message}")
        }
    }

    // ---- write access helper (rename / move) ----
    private fun writeOp(uris: List<Uri>, okMsg: String, block: () -> Unit) {
        try {
            block()
            finish(okMsg)
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= 30) {
                pending = {
                    try { block(); finish(okMsg) } catch (x: Exception) { toast("Failed: ${x.message}") }
                }
                launcher(MediaStore.createWriteRequest(ctx.contentResolver, uris).intentSender)
            } else if (Build.VERSION.SDK_INT == 29 && e is RecoverableSecurityException) {
                pending = { writeOp(uris, okMsg, block) }
                launcher(e.userAction.actionIntent.intentSender)
            } else toast("Permission denied")
        } catch (e: Exception) {
            toast("Failed: ${e.message}")
        }
    }

    // ---- rename ----
    fun rename(item: VideoItem, input: String) {
        var name = input.trim().replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
        if (name.isEmpty()) { toast("Invalid name"); return }
        val ext = item.title.substringAfterLast('.', "")
        if (ext.isNotEmpty() && !name.endsWith(".$ext", ignoreCase = true)) name = "$name.$ext"
        if (name == item.title) return
        if (Build.VERSION.SDK_INT < 29) { renameLegacy(item, name); return }
        val cv = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }
        writeOp(listOf(item.uri), "Renamed") { ctx.contentResolver.update(item.uri, cv, null, null) }
    }

    @Suppress("DEPRECATION")
    private fun renameLegacy(item: VideoItem, name: String) {
        try {
            val src = File(item.path)
            val dst = File(src.parentFile, name)
            if (dst.exists()) { toast("A file with that name already exists"); return }
            if (!src.renameTo(dst)) { toast("Rename failed"); return }
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DATA, dst.absolutePath)
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
            }
            ctx.contentResolver.update(item.uri, cv, null, null)
            MediaScannerConnection.scanFile(ctx, arrayOf(dst.absolutePath), null, null)
            finish("Renamed")
        } catch (e: Exception) {
            toast("Failed: ${e.message}")
        }
    }

    // ---- create folder ----
    fun newFolder(st: Settings, raw: String): FolderTarget? {
        val name = raw.trim().replace(Regex("[\\\\/:*?\"<>|]"), "").trim('.', ' ')
        if (name.isEmpty()) { toast("Invalid folder name"); return null }
        st.addCreatedFolder(name)
        val rel = newFolderRef(name)
        if (Build.VERSION.SDK_INT < 29) {
            try { File(rel).mkdirs() } catch (e: Exception) { }
        }
        return FolderTarget(name, rel)
    }

    // ---- move ----
    fun move(items: List<VideoItem>, target: FolderTarget) {
        if (items.isEmpty()) return
        val msg = "Moved ${items.size} to ${target.name}"
        if (Build.VERSION.SDK_INT >= 29) {
            writeOp(items.map { it.uri }, msg) {
                items.forEach { v ->
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, target.rel)
                    }
                    ctx.contentResolver.update(v.uri, cv, null, null)
                }
            }
        } else {
            moveLegacy(items, target, msg)
        }
    }

    @Suppress("DEPRECATION")
    private fun moveLegacy(items: List<VideoItem>, target: FolderTarget, msg: String) {
        try {
            val dir = File(target.rel)
            dir.mkdirs()
            items.forEach { v ->
                val src = File(v.path)
                if (src.parentFile?.absolutePath == dir.absolutePath) return@forEach
                val dst = uniqueFile(dir, src.name)
                if (!src.renameTo(dst)) { src.copyTo(dst); src.delete() }
                val cv = ContentValues().apply { put(MediaStore.Video.Media.DATA, dst.absolutePath) }
                ctx.contentResolver.update(v.uri, cv, null, null)
                MediaScannerConnection.scanFile(ctx, arrayOf(dst.absolutePath), null, null)
            }
            finish(msg)
        } catch (e: Exception) {
            toast("Failed: ${e.message}")
        }
    }

    // ---- copy ----
    fun copy(items: List<VideoItem>, target: FolderTarget) {
        if (items.isEmpty()) return
        busy = "Copying ${items.size} video(s)…"
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                try { items.forEach { copyOne(it, target) }; null }
                catch (e: Exception) { e.message ?: "error" }
            }
            busy = null
            if (err == null) finish("Copied ${items.size} to ${target.name}") else toast("Copy failed: $err")
        }
    }

    @Suppress("DEPRECATION")
    private fun copyOne(v: VideoItem, target: FolderTarget) {
        val cr = ctx.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, v.title)
                put(MediaStore.MediaColumns.MIME_TYPE, cr.getType(v.uri) ?: "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, target.rel)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val dst = cr.insert(collection, cv) ?: throw IllegalStateException("Cannot create file")
            try {
                val input = cr.openInputStream(v.uri) ?: throw IllegalStateException("Cannot read file")
                val output = cr.openOutputStream(dst) ?: throw IllegalStateException("Cannot write file")
                input.use { i -> output.use { o -> i.copyTo(o) } }
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                cr.update(dst, done, null, null)
            } catch (e: Exception) {
                cr.delete(dst, null, null)
                throw e
            }
        } else {
            val dir = File(target.rel)
            dir.mkdirs()
            val src = File(v.path)
            val dst = uniqueFile(dir, src.name)
            src.copyTo(dst)
            MediaScannerConnection.scanFile(ctx, arrayOf(dst.absolutePath), null, null)
        }
    }
}

@Composable
fun rememberFileOps(onChanged: () -> Unit): FileOps {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(onChanged)
    val ops = remember { FileOps(ctx, scope) { latest.value() } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        ops.onPermissionResult(it.resultCode == Activity.RESULT_OK)
    }
    ops.launcher = { sender -> launcher.launch(IntentSenderRequest.Builder(sender).build()) }
    return ops
}

// ------------------------------------------------------------------ selection bar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit
) {
    var more by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    TopAppBar(
        title = { Text("$count selected", fontWeight = FontWeight.Bold) },
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Cancel selection") }
        },
        actions = {
            IconButton(onClick = onSelectAll) { Icon(Icons.Default.SelectAll, contentDescription = "Select all") }
            IconButton(onClick = onShare) { Icon(Icons.Default.Share, contentDescription = "Share") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") }, enabled = count == 1,
                        leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null) },
                        onClick = { more = false; onRename() })
                    DropdownMenuItem(
                        text = { Text("Move to folder") },
                        leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null) },
                        onClick = { more = false; onMove() })
                    DropdownMenuItem(
                        text = { Text("Copy to folder") },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        onClick = { more = false; onCopy() })
                    DropdownMenuItem(
                        text = { Text("Select all") },
                        leadingIcon = { Icon(Icons.Default.SelectAll, contentDescription = null) },
                        onClick = { more = false; onSelectAll() })
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = cs.primary,
            titleContentColor = cs.onPrimary,
            navigationIconContentColor = cs.onPrimary,
            actionIconContentColor = cs.onPrimary
        )
    )
}

// ------------------------------------------------------------------ dialogs

@Composable
fun DeleteConfirmDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) "Delete video?" else "Delete $count videos?") },
        text = { Text("The file will be permanently deleted from your device.") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun RenameDialog(item: VideoItem, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val ext = item.title.substringAfterLast('.', "")
    val base = if (ext.isNotEmpty()) item.title.removeSuffix(".$ext") else item.title
    var name by remember(item.key) { mutableStateOf(base) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text("Name") },
                suffix = { if (ext.isNotEmpty()) Text(".$ext") }
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name) }) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun NewFolderDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create folder") },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text("Folder name") }
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onCreate(name) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun FolderPickerDialog(
    title: String,
    targets: List<FolderTarget>,
    onPick: (FolderTarget) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it }, singleLine = true,
                    label = { Text("New folder name") },
                    leadingIcon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                if (targets.isEmpty()) {
                    Text("No folders yet. Create one above.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(targets, key = { it.rel }) { t ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onPick(t) }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Folder, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = newName.isNotBlank(), onClick = { onCreate(newName) }) { Text("Create & use") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun BusyDialog(message: String) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Spacer(Modifier.width(16.dp))
                Text(message)
            }
        }
    )
}
