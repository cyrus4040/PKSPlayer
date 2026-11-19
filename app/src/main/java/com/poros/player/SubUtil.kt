package com.poros.player

import androidx.media3.common.MimeTypes
import java.util.Locale

/** Helpers for external subtitles: format detection and timing shift (subtitle delay). */
object SubUtil {
    fun kind(name: String): String = when {
        name.endsWith(".vtt", true) -> "vtt"
        name.endsWith(".ass", true) || name.endsWith(".ssa", true) -> "ass"
        else -> "srt"
    }

    fun mime(kind: String): String = when (kind) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass" -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    fun shift(text: String, kind: String, delayMs: Long): String {
        val clean = text.removePrefix("\uFEFF")
        if (delayMs == 0L) return clean
        return if (kind == "ass") shiftAss(clean, delayMs) else shiftSrtVtt(clean, delayMs)
    }

    private val rx = Regex("(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})([.,])(\\d{3})")

    private fun shiftSrtVtt(text: String, delay: Long): String =
        text.lineSequence().joinToString("\n") { line ->
            if (line.contains("-->")) rx.replace(line) { m ->
                val h = m.groupValues[1].toLongOrNull() ?: 0L
                val mi = m.groupValues[2].toLong()
                val s = m.groupValues[3].toLong()
                val ms = m.groupValues[5].toLong()
                var total = ((h * 60 + mi) * 60 + s) * 1000 + ms + delay
                if (total < 0) total = 0
                val nh = total / 3_600_000
                val nm = (total % 3_600_000) / 60_000
                val ns = (total % 60_000) / 1000
                val nms = total % 1000
                String.format(Locale.US, "%02d:%02d:%02d%s%03d", nh, nm, ns, m.groupValues[4], nms)
            } else line
        }

    private val assRx = Regex("(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2})")

    private fun shiftAssTime(t: String, delay: Long): String =
        assRx.replace(t.trim()) { m ->
            val h = m.groupValues[1].toLong()
            val mi = m.groupValues[2].toLong()
            val s = m.groupValues[3].toLong()
            val cs = m.groupValues[4].toLong()
            var total = ((h * 60 + mi) * 60 + s) * 1000 + cs * 10 + delay
            if (total < 0) total = 0
            val nh = total / 3_600_000
            val nm = (total % 3_600_000) / 60_000
            val ns = (total % 60_000) / 1000
            val ncs = (total % 1000) / 10
            String.format(Locale.US, "%d:%02d:%02d.%02d", nh, nm, ns, ncs)
        }

    private fun shiftAss(text: String, delay: Long): String =
        text.lineSequence().joinToString("\n") { line ->
            if (line.startsWith("Dialogue:")) {
                val p = line.split(",", limit = 10).toMutableList()
                if (p.size >= 3) {
                    p[1] = shiftAssTime(p[1], delay)
                    p[2] = shiftAssTime(p[2], delay)
                }
                p.joinToString(",")
            } else line
        }
}
