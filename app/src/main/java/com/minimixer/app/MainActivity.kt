package com.minimixer.app

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.media.session.MediaSessionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var audio: AudioManager
    private lateinit var led: LedMeterView
    private val handler = Handler(Looper.getMainLooper())

    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var virt: Virtualizer? = null
    private var loud: LoudnessEnhancer? = null
    private var reverb: PresetReverb? = null

    private val meterTick = object : Runnable {
        override fun run() {
            val maxV = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val base = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxV
            led.level = if (audio.isMusicActive)
                base * (0.55f + 0.45f * Math.random().toFloat())
            else 0f
            handler.postDelayed(this, 120)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        led = findViewById(R.id.ledMeter)

        setupKnobs()
        setupMaster()

        findViewById<TextView>(R.id.btnApps).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        setupChannels()
        syncMaster()
        handler.post(meterTick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(meterTick)
    }

    // ---------- Potentiomètres ----------
    private fun addKnob(parent: LinearLayout, label: String, sizeDp: Int, max: Int, initial: Int, onChange: (Int) -> Unit) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val k = KnobView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            this.max = max.coerceAtLeast(1)
            value = initial
            this.onChange = onChange
        }
        val t = TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#B9B9C2"))
            textSize = 10f
            gravity = Gravity.CENTER
            letterSpacing = 0.1f
        }
        col.addView(k)
        col.addView(t)
        parent.addView(col)
    }

    private fun setupKnobs() {
        val big = findViewById<LinearLayout>(R.id.knobRowBig)
        val small = findViewById<LinearLayout>(R.id.knobRowSmall)
        big.removeAllViews()
        small.removeAllViews()

        // --- Égaliseur global : BASS / MÉDIUM / AIGU ---
        try {
            eq = Equalizer(0, 0).apply { enabled = true }
        } catch (_: Exception) {
        }
        val e = eq
        if (e != null && e.numberOfBands > 0) {
            val bands = e.numberOfBands.toInt()
            val minL = e.bandLevelRange[0].toInt()
            val maxL = e.bandLevelRange[1].toInt()
            val groups = listOf(
                "BASS" to (0 until bands / 3).toList(),
                "MED" to (bands / 3 until (2 * bands) / 3).toList(),
                "AIGU" to ((2 * bands) / 3 until bands).toList()
            ).map { (n, l) -> n to l.ifEmpty { listOf(0) } }
            for ((name, idxs) in groups) {
                val cur = runCatching { e.getBandLevel(idxs.first().toShort()).toInt() - minL }
                    .getOrDefault((maxL - minL) / 2)
                addKnob(big, name, 78, maxL - minL, cur) { v ->
                    val level = (minL + v).toShort()
                    idxs.forEach { i -> runCatching { e.setBandLevel(i.toShort(), level) } }
                }
            }
        } else {
            val t = TextView(this).apply {
                text = getString(R.string.eq_unavailable)
                setTextColor(Color.parseColor("#6E6E78"))
                textSize = 12f
            }
            big.addView(t)
        }

        // --- Filtres : BOOST / 3D / REVERB / LOUD ---
        addKnob(small, "BOOST", 58, 1000, 0) { v ->
            if (v <= 0) {
                runCatching { bass?.release() }; bass = null
            } else {
                if (bass == null) bass = runCatching { BassBoost(0, 0).apply { enabled = true } }.getOrNull()
                if (bass == null) toast("Bass Boost indisponible")
                runCatching { bass?.setStrength(v.toShort()) }
            }
        }
        addKnob(small, "3D", 58, 1000, 0) { v ->
            if (v <= 0) {
                runCatching { virt?.release() }; virt = null
            } else {
                if (virt == null) virt = runCatching { Virtualizer(0, 0).apply { enabled = true } }.getOrNull()
                if (virt == null) toast("Virtualizer indisponible")
                runCatching { virt?.setStrength(v.toShort()) }
            }
        }
        val presets = listOf(
            PresetReverb.PRESET_NONE, PresetReverb.PRESET_SMALLROOM, PresetReverb.PRESET_LARGEROOM,
            PresetReverb.PRESET_MEDIUMHALL, PresetReverb.PRESET_LARGEHALL, PresetReverb.PRESET_PLATE
        )
        addKnob(small, "REVERB", 58, presets.size - 1, 0) { v ->
            if (v <= 0) {
                runCatching { reverb?.release() }; reverb = null
            } else {
                if (reverb == null) reverb = runCatching { PresetReverb(0, 0) }.getOrNull()
                if (reverb == null) toast("Réverb indisponible")
                runCatching { reverb?.preset = presets[v]; reverb?.enabled = true }
            }
        }
        addKnob(small, "LOUD", 58, 1200, 0) { v ->
            if (v <= 0) {
                runCatching { loud?.release() }; loud = null
            } else {
                if (loud == null) loud = runCatching { LoudnessEnhancer(0).apply { enabled = true } }.getOrNull()
                if (loud == null) toast("Loudness indisponible")
                runCatching { loud?.setTargetGain(v) }
            }
        }
    }

    // ---------- Tranches CH1..CH4 ----------
    private fun addStrip(parent: LinearLayout, ch: String, label: String, max: Int, current: Int, onChange: (Int) -> Unit) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val chText = TextView(this).apply {
            text = ch
            setTextColor(Color.parseColor("#9FE870"))
            textSize = 11f
            gravity = Gravity.CENTER
            letterSpacing = 0.15f
        }
        val slider = VerticalSeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(200))
            this.max = max.coerceAtLeast(1)
            progress = current.coerceIn(0, this.max)
            progressDrawable = ContextCompat.getDrawable(this@MainActivity, R.drawable.fader_track)
            thumb = ContextCompat.getDrawable(this@MainActivity, R.drawable.fader_thumb)
            splitTrack = false
            onUserChange = onChange
        }
        val name = TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#B9B9C2"))
            textSize = 10f
            gravity = Gravity.CENTER
            maxLines = 1
        }
        col.addView(chText)
        col.addView(slider)
        col.addView(name)
        parent.addView(col)
    }

    private fun setupChannels() {
        val row = findViewById<LinearLayout>(R.id.channelRow)
        val hint = findViewById<TextView>(R.id.appHint)
        row.removeAllViews()

        data class Strip(val label: String, val max: Int, val cur: Int, val cb: (Int) -> Unit)

        val strips = mutableListOf<Strip>()
        val listenerOn = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true

        if (listenerOn) {
            try {
                val msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
                val controllers = msm.getActiveSessions(ComponentName(this, MediaNotifListener::class.java))
                for (c in controllers) {
                    val name = runCatching {
                        packageManager.getApplicationLabel(packageManager.getApplicationInfo(c.packageName, 0)).toString()
                    }.getOrDefault(c.packageName)
                    val info = c.playbackInfo
                    strips.add(Strip(name.take(9).uppercase(), info?.maxVolume ?: 15, info?.currentVolume ?: 0) { v ->
                        runCatching { c.setVolumeTo(v, 0) }
                    })
                }
            } catch (_: SecurityException) {
            }
        }

        if (strips.isEmpty()) {
            hint.text = if (listenerOn) getString(R.string.apps_none_short) else getString(R.string.apps_grant_short)
            val fallback = listOf(
                "MÉDIA" to AudioManager.STREAM_MUSIC,
                "SONNERIE" to AudioManager.STREAM_RING,
                "ALARME" to AudioManager.STREAM_ALARM
            )
            for ((label, stream) in fallback) {
                strips.add(Strip(label, audio.getStreamMaxVolume(stream), audio.getStreamVolume(stream)) { v ->
                    try {
                        audio.setStreamVolume(stream, v, 0)
                    } catch (_: SecurityException) {
                        toast("Autorisation « Ne pas déranger » requise")
                    }
                })
            }
        } else {
            hint.text = ""
        }

        strips.take(4).forEachIndexed { i, s ->
            addStrip(row, "CH${i + 1}", s.label, s.max, s.cur, s.cb)
        }
    }

    // ---------- Crossfader master ----------
    private fun setupMaster() {
        val seek = findViewById<SeekBar>(R.id.masterSeek)
        seek.max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                if (fromUser) runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun syncMaster() {
        findViewById<SeekBar>(R.id.masterSeek).progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    override fun onDestroy() {
        super.onDestroy()
        listOf(eq, bass, virt, reverb, loud).forEach { runCatching { it?.release() } }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
