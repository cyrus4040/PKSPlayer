package com.poros.player

import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Audio boost (LoudnessEnhancer) + Equalizer, attached to the ExoPlayer audio session. */
class AudioFx(private val st: Settings) {
    private var enhancer: LoudnessEnhancer? = null
    private var eq: Equalizer? = null

    var boost by mutableIntStateOf(st.int("boost", 0))
        private set
    var eqOn by mutableStateOf(st.bool("eqon", false))
        private set
    var available by mutableStateOf(false)
        private set
    val levels = mutableStateListOf<Int>()
    val freqs = mutableStateListOf<String>()
    var minLevel = -1500
    var maxLevel = 1500

    fun attach(session: Int) {
        release()
        if (session == 0) return
        try { enhancer = LoudnessEnhancer(session) } catch (e: Throwable) { enhancer = null }
        try {
            val e = Equalizer(0, session)
            eq = e
            val n = e.numberOfBands.toInt()
            minLevel = e.bandLevelRange[0].toInt()
            maxLevel = e.bandLevelRange[1].toInt()
            val saved = st.str("eq").split(",").mapNotNull { it.toIntOrNull() }
            levels.clear(); freqs.clear()
            for (b in 0 until n) {
                levels.add(saved.getOrElse(b) { 0 }.coerceIn(minLevel, maxLevel))
                val hz = e.getCenterFreq(b.toShort()) / 1000
                freqs.add(if (hz >= 1000) "${hz / 1000}kHz" else "${hz}Hz")
            }
            available = true
        } catch (e: Throwable) {
            eq = null; available = false
        }
        push()
    }

    private fun push() {
        try {
            enhancer?.setEnabled(boost > 0)
            enhancer?.setTargetGain(boost * 15)
        } catch (e: Throwable) { }
        try {
            val q = eq
            if (q != null) {
                levels.forEachIndexed { i, l -> q.setBandLevel(i.toShort(), l.toShort()) }
                q.setEnabled(eqOn)
            }
        } catch (e: Throwable) { }
    }

    fun setBoost(v: Int) { boost = v; st.set("boost", v); push() }
    fun setEqOn(on: Boolean) { eqOn = on; st.set("eqon", on); push() }
    fun setLevel(i: Int, v: Int) {
        if (i in levels.indices) { levels[i] = v; st.set("eq", levels.joinToString(",")); push() }
    }
    fun resetEq() {
        for (i in levels.indices) levels[i] = 0
        st.set("eq", levels.joinToString(",")); push()
    }

    fun release() {
        try { enhancer?.release() } catch (e: Throwable) { }
        try { eq?.release() } catch (e: Throwable) { }
        enhancer = null; eq = null
    }
}
