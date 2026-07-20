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
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class MainActivity : AppCompatActivity() {

    private lateinit var audio: AudioManager
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var virt: Virtualizer? = null
    private var loud: LoudnessEnhancer? = null
    private var reverb: PresetReverb? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager

        setupStreamFaders()
        setupEq()
        setupAppSessions()

        findViewById<TextView>(R.id.btnFilters).setOnClickListener { showFiltersDialog() }
        findViewById<TextView>(R.id.btnApps).setOnClickListener { setupAppSessions(forcePrompt = true) }
    }

    override fun onResume() {
        super.onResume()
        setupAppSessions()
    }

    // ---------- Fader vertical générique ----------
    private fun addFader(container: LinearLayout, label: String, max: Int, current: Int, onChange: (Int) -> Unit) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val slider = VerticalSeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(180))
            this.max = if (max > 0) max else 1
            progress = current.coerceIn(0, this.max)
            onUserChange = onChange
        }
        val text = TextView(this).apply {
            this.text = label
            setTextColor(Color.parseColor("#9FE870"))
            textSize = 11f
            gravity = Gravity.CENTER
        }
        col.addView(slider)
        col.addView(text)
        container.addView(col)
    }

    // ---------- Volumes des canaux audio ----------
    private fun setupStreamFaders() {
        val row = findViewById<LinearLayout>(R.id.streamRow)
        row.removeAllViews()
        val streams = listOf(
            "MÉDIA" to AudioManager.STREAM_MUSIC,
            "APPEL" to AudioManager.STREAM_VOICE_CALL,
            "SONNERIE" to AudioManager.STREAM_RING,
            "NOTIF" to AudioManager.STREAM_NOTIFICATION,
            "ALARME" to AudioManager.STREAM_ALARM
        )
        for ((label, stream) in streams) {
            addFader(row, label, audio.getStreamMaxVolume(stream), audio.getStreamVolume(stream)) { v ->
                try {
                    audio.setStreamVolume(stream, v, 0)
                } catch (_: SecurityException) {
                    toast("Autorisation « Ne pas déranger » requise")
                }
            }
        }
    }

    // ---------- Égaliseur BASS / MÉDIUM / AIGU ----------
    private fun setupEq() {
        val row = findViewById<LinearLayout>(R.id.eqRow)
        row.removeAllViews()
        try {
            eq = Equalizer(0, 0).apply { enabled = true }
        } catch (e: Exception) {
            findViewById<TextView>(R.id.eqTitle).text = getString(R.string.eq_unavailable)
            return
        }
        val e = eq ?: return
        val bands = e.numberOfBands.toInt()
        if (bands <= 0) return
        val minL = e.bandLevelRange[0].toInt()
        val maxL = e.bandLevelRange[1].toInt()

        val groups = listOf(
            "BASS" to (0 until bands / 3).toList(),
            "MÉDIUM" to (bands / 3 until (2 * bands) / 3).toList(),
            "AIGU" to ((2 * bands) / 3 until bands).toList()
        ).map { (n, l) -> n to l.ifEmpty { listOf(0) } }

        for ((name, idxs) in groups) {
            val cur = try { e.getBandLevel(idxs.first().toShort()).toInt() - minL } catch (_: Exception) { (maxL - minL) / 2 }
            addFader(row, name, maxL - minL, cur) { v ->
                val level = (minL + v).toShort()
                idxs.forEach { i -> runCatching { e.setBandLevel(i.toShort(), level) } }
            }
        }
    }

    // ---------- Menu des filtres ----------
    private fun showFiltersDialog() {
        val pad = dp(20)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun addSwitch(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
            val sw = SwitchCompat(this).apply {
                text = label
                isChecked = checked
                textSize = 15f
                setPadding(0, dp(8), 0, dp(8))
                setOnCheckedChangeListener { _, on -> onToggle(on) }
            }
            box.addView(sw)
        }

        addSwitch("Bass Boost 🔊", bass?.enabled == true) { on ->
            if (on) {
                bass = runCatching { BassBoost(0, 0).apply { setStrength(800); enabled = true } }.getOrNull()
                if (bass == null) toast("Bass Boost indisponible")
            } else { runCatching { bass?.release() }; bass = null }
        }
        addSwitch("Son 3D (Virtualizer) 🎧", virt?.enabled == true) { on ->
            if (on) {
                virt = runCatching { Virtualizer(0, 0).apply { setStrength(800); enabled = true } }.getOrNull()
                if (virt == null) toast("Virtualizer indisponible")
            } else { runCatching { virt?.release() }; virt = null }
        }
        addSwitch("Loudness (volume boosté) ⚡", loud != null) { on ->
            if (on) {
                loud = runCatching { LoudnessEnhancer(0).apply { setTargetGain(600); enabled = true } }.getOrNull()
                if (loud == null) toast("Loudness indisponible")
            } else { runCatching { loud?.release() }; loud = null }
        }

        val label = TextView(this).apply {
            text = "Réverbération"
            setPadding(0, dp(12), 0, dp(4))
        }
        box.addView(label)

        val presets = listOf("OFF", "Petite pièce", "Grande pièce", "Salle moyenne", "Grande salle", "Plate")
        val presetVals = listOf(
            PresetReverb.PRESET_NONE, PresetReverb.PRESET_SMALLROOM, PresetReverb.PRESET_LARGEROOM,
            PresetReverb.PRESET_MEDIUMHALL, PresetReverb.PRESET_LARGEHALL, PresetReverb.PRESET_PLATE
        )
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, presets)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (pos == 0) { runCatching { reverb?.release() }; reverb = null; return }
                    if (reverb == null) reverb = runCatching { PresetReverb(0, 0) }.getOrNull()
                    val r = reverb ?: run { toast("Réverb indisponible"); return }
                    runCatching { r.preset = presetVals[pos]; r.enabled = true }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        box.addView(spinner)

        AlertDialog.Builder(this)
            .setTitle("🎛 Filtres")
            .setView(box)
            .setPositiveButton("OK", null)
            .show()
    }

    // ---------- Apps ouvertes (sessions média) ----------
    private fun setupAppSessions(forcePrompt: Boolean = false) {
        val row = findViewById<LinearLayout>(R.id.appRow)
        val hint = findViewById<TextView>(R.id.appHint)
        row.removeAllViews()

        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        if (!enabled) {
            hint.text = getString(R.string.apps_need_permission)
            if (forcePrompt) startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            return
        }
        try {
            val msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val comp = ComponentName(this, MediaNotifListener::class.java)
            val controllers = msm.getActiveSessions(comp)
            if (controllers.isEmpty()) {
                hint.text = getString(R.string.apps_none)
                return
            }
            hint.text = ""
            for (c in controllers) {
                val name = try {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(c.packageName, 0)).toString()
                } catch (_: Exception) { c.packageName }
                val info = c.playbackInfo
                val max = info?.maxVolume ?: 15
                val cur = info?.currentVolume ?: 0
                addFader(row, name.take(9).uppercase(), max, cur) { v ->
                    runCatching { c.setVolumeTo(v, 0) }
                }
            }
        } catch (_: SecurityException) {
            hint.text = getString(R.string.apps_need_permission)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        listOf(eq, bass, virt, reverb, loud).forEach { runCatching { it?.release() } }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
