package com.minimixer.app

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var audio: AudioManager
    private lateinit var led: LedMeterView
    private lateinit var multi: MultiAudio
    private val handler = Handler(Looper.getMainLooper())

    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var virt: Virtualizer? = null
    private var loud: LoudnessEnhancer? = null
    private var reverb: PresetReverb? = null

    private var appCount = 0
    private var sessionPkgs: Set<String> = emptySet()
    private var sessionListener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    private val shizukuReq = 42
    private val permListener = Shizuku.OnRequestPermissionResultListener { _, grant ->
        if (grant == PackageManager.PERMISSION_GRANTED) enableMulti()
        else toast("Permission Shizuku refusée")
    }

    private val meterTick = object : Runnable {
        override fun run() {
            val maxV = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val base = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxV
            val playing = audio.isMusicActive || appCount > 0
            led.level = if (playing)
                (base * (0.45f + 0.55f * Math.random().toFloat())).coerceAtLeast(0.12f)
            else
                led.level * 0.7f
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        led = findViewById(R.id.ledMeter)
        multi = MultiAudio(this)
        multi.restoreLeftover()
        runCatching { Shizuku.addRequestPermissionResultListener(permListener) }

        setupKnobs()
        setupMaster()
        updateDualUi()

        findViewById<TextView>(R.id.btnApps).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<TextView>(R.id.btnRefresh).setOnClickListener {
            rebindListener()
            setupChannels()
        }
        findViewById<TextView>(R.id.btnDual).setOnClickListener { onDualClicked() }
    }

    override fun onResume() {
        super.onResume()
        rebindListener()
        setupChannels()
        registerSessionListener()
        syncMaster()
        handler.post(meterTick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(meterTick)
        unregisterSessionListener()
    }

    override fun onDestroy() {
        // Restaure le comportement audio normal quand on ferme l'app
        if (multi.active) multi.disableAsync()
        runCatching { Shizuku.removeRequestPermissionResultListener(permListener) }
        listOf(eq, bass, virt, reverb, loud).forEach { runCatching { it?.release() } }
        super.onDestroy()
    }

    // ---------- Mode multi-sources (DUAL) ----------
    private fun onDualClicked() {
        if (multi.active) {
            Thread {
                multi.disable()
                runOnUiThread {
                    updateDualUi()
                    toast("Multi-sources désactivé, focus audio normal")
                }
            }.start()
            return
        }
        if (!ShizukuShell.running()) {
            showShizukuHelp()
            return
        }
        if (!ShizukuShell.hasPermission()) {
            runCatching { Shizuku.requestPermission(shizukuReq) }
            return
        }
        enableMulti()
    }

    private fun enableMulti() {
        val pkgs = sessionPkgs
        Thread {
            val ok = multi.enable(pkgs)
            runOnUiThread {
                updateDualUi()
                toast(
                    if (ok) "Multi-sources ACTIVÉ 🎧 (YouTube + Spotify possibles)"
                    else "Échec : vérifie que Shizuku est démarré"
                )
            }
        }.start()
    }

    private fun updateDualUi() {
        val b = findViewById<TextView>(R.id.btnDual)
        if (multi.active) {
            b.setBackgroundResource(R.drawable.dual_on)
            b.setTextColor(Color.parseColor("#0B0B0D"))
        } else {
            b.setBackgroundResource(R.drawable.dual_off)
            b.setTextColor(Color.parseColor("#9FE870"))
        }
    }

    private fun showShizukuHelp() {
        AlertDialog.Builder(this)
            .setTitle("Mode multi-sources (DUAL)")
            .setMessage(getString(R.string.shizuku_help))
            .setPositiveButton("Télécharger Shizuku") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
            }
            .setNegativeButton("OK", null)
            .show()
    }

    // ---------- Listener notifications / sessions ----------
    private fun listenerEnabled(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true

    private fun rebindListener() {
        if (!listenerEnabled()) return
        runCatching {
            NotificationListenerService.requestRebind(
                ComponentName(this, MediaNotifListener::class.java)
            )
        }
    }

    private fun registerSessionListener() {
        if (!listenerEnabled()) return
        runCatching {
            val msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val l = MediaSessionManager.OnActiveSessionsChangedListener { setupChannels() }
            msm.addOnActiveSessionsChangedListener(l, ComponentName(this, MediaNotifListener::class.java))
            sessionListener = l
        }
    }

    private fun unregisterSessionListener() {
        val l = sessionListener ?: return
        runCatching {
            (getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager)
                .removeOnActiveSessionsChangedListener(l)
        }
        sessionListener = null
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
            big.addView(TextView(this).apply {
                text = getString(R.string.eq_unavailable)
                setTextColor(Color.parseColor("#6E6E78"))
                textSize = 12f
            })
        }

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

    // ---------- Tranches ----------
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

    private fun appLabel(pkg: String): String {
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()
        return (label ?: pkg.substringAfterLast('.')).take(9).uppercase()
    }

    private fun setupChannels() {
        val row = findViewById<LinearLayout>(R.id.channelRow)
        val hint = findViewById<TextView>(R.id.appHint)
        row.removeAllViews()

        data class Strip(val label: String, val max: Int, val cur: Int, val cb: (Int) -> Unit)

        val strips = mutableListOf<Strip>()
        val listenerOn = listenerEnabled()
        val pkgs = mutableSetOf<String>()

        if (listenerOn) {
            try {
                val msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
                val controllers = msm.getActiveSessions(ComponentName(this, MediaNotifListener::class.java))
                for (c in controllers) {
                    pkgs.add(c.packageName)
                    val info = c.playbackInfo
                    strips.add(Strip(appLabel(c.packageName), info?.maxVolume ?: 15, info?.currentVolume ?: 0) { v ->
                        runCatching { c.setVolumeTo(v, 0) }
                    })
                }
            } catch (_: SecurityException) {
            }
        }
        appCount = strips.size
        sessionPkgs = pkgs

        // Si le mode DUAL est actif, on couvre aussi les nouvelles apps détectées
        if (multi.active && pkgs.isNotEmpty()) {
            Thread { multi.enable(pkgs) }.start()
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

    // ---------- Master ----------
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

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
