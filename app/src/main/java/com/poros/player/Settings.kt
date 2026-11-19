package com.poros.player

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Single shared, Compose-observable store backed by SharedPreferences. */
class Settings private constructor(ctx: Context) {
    private val sp = ctx.getSharedPreferences("pks_player", Context.MODE_PRIVATE)
    private val map = mutableStateMapOf<String, String>().apply {
        for ((k, v) in sp.all) if (v != null) put(k, v.toString())
    }

    fun str(k: String, d: String = ""): String = map[k] ?: d
    fun bool(k: String, d: Boolean = false): Boolean = map[k]?.toBooleanStrictOrNull() ?: d
    fun int(k: String, d: Int = 0): Int = map[k]?.toIntOrNull() ?: d
    fun float(k: String, d: Float = 0f): Float = map[k]?.toFloatOrNull() ?: d
    fun set(k: String, v: Any) {
        map[k] = v.toString()
        sp.edit().putString(k, v.toString()).apply()
    }

    // ---- simple settings ----
    val theme: String get() = str("theme", "LIME")
    val decoder: Int get() = int("decoder", 1)          // 0 = HW, 1 = HW+, 2 = SW
    val resume: Boolean get() = bool("resume", true)
    val bgPlay: Boolean get() = bool("bgplay", false)
    val autoPip: Boolean get() = bool("autopip", true)
    val appLock: Boolean get() = bool("applock", false)
    val bio: Boolean get() = bool("bio", true)

    // ---- PIN ----
    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("pks:$pin".toByteArray())
            .joinToString("") { "%02x".format(it) }
    val hasPin: Boolean get() = str("pin").isNotEmpty()
    fun setPin(pin: String) = set("pin", hash(pin))
    fun checkPin(pin: String): Boolean = str("pin") == hash(pin)

    // ---- json helpers ----
    private fun arr(key: String): JSONArray =
        try { JSONArray(str(key, "[]")) } catch (e: Exception) { JSONArray() }
    private fun obj(key: String): JSONObject =
        try { JSONObject(str(key, "{}")) } catch (e: Exception) { JSONObject() }
    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    // ---- favorites / hidden ----
    fun favorites(): Set<String> = arr("fav").strings().toSet()
    fun toggleFavorite(uri: String) {
        val s = favorites().toMutableSet()
        if (!s.add(uri)) s.remove(uri)
        set("fav", JSONArray(s.toList()).toString())
    }

    fun hidden(): Set<String> = arr("hidden").strings().toSet()
    fun setHidden(uri: String, hide: Boolean) {
        val s = hidden().toMutableSet()
        if (hide) s.add(uri) else s.remove(uri)
        set("hidden", JSONArray(s.toList()).toString())
    }

    // ---- history / resume ----
    fun progress(uri: String): Pair<Long, Long>? {
        val o = obj("hist").optJSONObject(uri) ?: return null
        return o.optLong("p") to o.optLong("d")
    }

    fun saveProgress(uri: String, pos: Long, dur: Long) {
        val h = obj("hist")
        h.put(uri, JSONObject().put("p", pos).put("d", dur).put("t", System.currentTimeMillis()))
        if (h.length() > 300) {
            val keys = h.keys().asSequence().toList().sortedBy { h.getJSONObject(it).optLong("t") }
            keys.take(h.length() - 300).forEach { h.remove(it) }
        }
        set("hist", h.toString())
    }

    fun recent(): List<String> {
        val h = obj("hist")
        return h.keys().asSequence().toList().sortedByDescending { h.getJSONObject(it).optLong("t") }
    }

    fun clearHistory() = set("hist", "{}")

    // ---- playlists ----
    fun playlists(): Map<String, List<String>> {
        val o = obj("pl")
        return o.keys().asSequence().toList().associateWith { o.getJSONArray(it).strings() }
    }
    fun createPlaylist(name: String) {
        val o = obj("pl")
        if (!o.has(name)) { o.put(name, JSONArray()); set("pl", o.toString()) }
    }
    fun addToPlaylist(name: String, uri: String) {
        val o = obj("pl")
        val a = o.optJSONArray(name) ?: JSONArray()
        if (!a.strings().contains(uri)) a.put(uri)
        o.put(name, a); set("pl", o.toString())
    }
    fun removeFromPlaylist(name: String, uri: String) {
        val o = obj("pl")
        val l = (o.optJSONArray(name) ?: JSONArray()).strings().filter { it != uri }
        o.put(name, JSONArray(l)); set("pl", o.toString())
    }
    fun deletePlaylist(name: String) {
        val o = obj("pl"); o.remove(name); set("pl", o.toString())
    }

    // ---- bookmarks ----
    fun bookmarks(uri: String): List<Long> {
        val a = obj("bm").optJSONArray(uri) ?: return emptyList()
        return (0 until a.length()).map { a.getLong(it) }
    }
    fun addBookmark(uri: String, pos: Long) {
        val o = obj("bm")
        val l = (bookmarks(uri) + pos).distinct().sorted()
        o.put(uri, JSONArray(l)); set("bm", o.toString())
    }
    fun removeBookmark(uri: String, pos: Long) {
        val o = obj("bm")
        o.put(uri, JSONArray(bookmarks(uri).filter { it != pos })); set("bm", o.toString())
    }

    // ---- custom video presets ----
    fun fxCustoms(): List<Pair<String, FxState>> {
        val a = arr("fxcustom")
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("n")
            if (name.isBlank()) return@mapNotNull null
            name to FxState(
                preset = FxPreset.fromId(o.optString("p")),
                intensity = o.optDouble("i", 1.0).toFloat(),
                brightness = o.optDouble("b", 0.0).toFloat(),
                contrast = o.optDouble("c", 0.0).toFloat(),
                saturation = o.optDouble("s", 0.0).toFloat(),
                warmth = o.optDouble("w", 0.0).toFloat()
            )
        }
    }
    fun saveFxCustom(name: String, f: FxState) {
        val list = fxCustoms().filter { it.first != name } + (name to f)
        writeFx(list)
    }
    fun deleteFxCustom(name: String) = writeFx(fxCustoms().filter { it.first != name })
    private fun writeFx(list: List<Pair<String, FxState>>) {
        val a = JSONArray()
        list.forEach { (n, f) ->
            a.put(
                JSONObject().put("n", n).put("p", f.preset?.id ?: "")
                    .put("i", f.intensity.toDouble()).put("b", f.brightness.toDouble())
                    .put("c", f.contrast.toDouble()).put("s", f.saturation.toDouble())
                    .put("w", f.warmth.toDouble())
            )
        }
        set("fxcustom", a.toString())
    }

    companion object {
        @Volatile private var inst: Settings? = null
        fun get(ctx: Context): Settings =
            inst ?: synchronized(this) {
                inst ?: Settings(ctx.applicationContext).also { inst = it }
            }
    }
}
