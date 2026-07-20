package com.minimixer.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
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
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
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
    private var retries = 0
    private val levelReader = AudioLevelReader()
    private var visOk = false
    private val eqKnobs = mutableListOf<KnobView>()
    private var boostKnob: KnobView? = null
    private var threeDKnob: KnobView? = null
    private var reverbKnob: KnobView? = null
    private var loudKnob: KnobView? = null
    private var sessionPkgs: Set<String> = emptySet()
    private var sessionListener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    private val shizukuReq = 42
    private val permListener = Shizuku.OnRequestPermissionResultListener { _, grant ->
        if (grant == PackageManager.PERMISSION_GRANTED) enableMulti()
        else toast("Permission Shizuku refusée")
    }

    private val meterTick = object : Runnable {
        override fun run() {
            val target = if (visOk) {
                levelReader.level
            } else {
                val maxV = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val base = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxV
                if (audio.isMusicActive || appCount > 0)
                    base * (0.45f + 0.55f * Math.random().toFloat())
                else 0f
            }
            // Lissage : montée rapide, retombée douce, comme un vrai vumètre
            val k = if (target > led.level) 0.55f else 0.28f
            led.level = led.level + (target - led.level) * k
            handler.postDelayed(this, 60)
        }
    }

    private fun ensureVisualizer() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            visOk = levelReader.start()
        } else {
            val prefs = getSharedPreferences("perm", MODE_PRIVATE)
            if (!prefs.getBoolean("asked_mic", false)) {
                prefs.edit().putBoolean("asked_mic", true).apply()
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 7)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            visOk = levelReader.start()
            toast("Vumètre branché sur le vrai signal 📊")
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
        setupPresets()
        updateDualUi()

        findViewById<TextView>(R.id.btnApps).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<TextView>(R.id.btnRefresh).setOnClickListener {
            retries = 0
            forceRebind()
            setupChannels()
            toast("Reconnexion aux apps…")
        }
        findViewById<TextView>(R.id.btnDual).setOnClickListener { onDualClicked() }
    }

    override fun onResume() {
        super.onResume()
        retries = 0
        MediaNotifListener.onConnected = {
            runOnUiThread {
                retries = 0
                setupChannels()
            }
        }
        rebindListener()
        setupChannels()
        registerSessionListener()
        syncMaster()
        ensureVisualizer()
        handler.post(meterTick)
    }

    override fun onPause() {
        super.onPause()
        clearCtrlCallbacks()
        MediaNotifListener.onConnected = null
        handler.removeCallbacks(meterTick)
        levelReader.stop()
        visOk = false
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

    /** Re-branchement musclé : bascule le composant off/on puis rebind. */
    private fun forceRebind() {
        if (!listenerEnabled()) return
        val comp = ComponentName(this, MediaNotifListener::class.java)
        runCatching {
            packageManager.setComponentEnabledSetting(
                comp,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            packageManager.setComponentEnabledSetting(
                comp,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }
        runCatching { NotificationListenerService.requestRebind(comp) }
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
    private fun addKnob(parent: LinearLayout, label: String, sizeDp: Int, max: Int, initial: Int, defaultVal: Int = 0, onChange: (Int) -> Unit): KnobView {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val k = KnobView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            this.max = max.coerceAtLeast(1)
            value = initial
            defaultValue = defaultVal
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
        return k
    }

    private fun setupKnobs() {
        val big = findViewById<LinearLayout>(R.id.knobRowBig)
        val small = findViewById<LinearLayout>(R.id.knobRowSmall)
        big.removeAllViews()
        small.removeAllViews()
        eqKnobs.clear()

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
                eqKnobs.add(addKnob(big, name, 78, maxL - minL, cur, (maxL - minL) / 2) { v ->
                    val level = (minL + v).toShort()
                    idxs.forEach { i -> runCatching { e.setBandLevel(i.toShort(), level) } }
                })
            }
        } else {
            big.addView(TextView(this).apply {
                text = getString(R.string.eq_unavailable)
                setTextColor(Color.parseColor("#6E6E78"))
                textSize = 12f
            })
        }

        boostKnob = addKnob(small, "BOOST", 58, 1000, 0) { v ->
            if (v <= 0) {
                runCatching { bass?.release() }; bass = null
            } else {
                if (bass == null) bass = runCatching { BassBoost(0, 0).apply { enabled = true } }.getOrNull()
                if (bass == null) toast("Bass Boost indisponible")
                runCatching { bass?.setStrength(v.toShort()) }
            }
        }
        threeDKnob = addKnob(small, "3D", 58, 1000, 0) { v ->
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
        reverbKnob = addKnob(small, "REVERB", 58, presets.size - 1, 0) { v ->
            if (v <= 0) {
                runCatching { reverb?.release() }; reverb = null
            } else {
                if (reverb == null) reverb = runCatching { PresetReverb(0, 0) }.getOrNull()
                if (reverb == null) toast("Réverb indisponible")
                runCatching { reverb?.preset = presets[v]; reverb?.enabled = true }
            }
        }
        loudKnob = addKnob(small, "LOUD", 58, 1200, 0) { v ->
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
    private val ctrlCallbacks = mutableListOf<Pair<MediaController, MediaController.Callback>>()

    private fun clearCtrlCallbacks() {
        ctrlCallbacks.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        ctrlCallbacks.clear()
    }

    private fun applyArt(iv: ImageView, md: MediaMetadata?) {
        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (bmp != null) {
            iv.setImageBitmap(bmp)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
        } else {
            iv.setImageResource(R.drawable.art_placeholder)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
        }
    }

    private fun addStrip(parent: LinearLayout, ch: String, label: String, maxV: Int, current: Int, controller: MediaController?, cb: (Int) -> Unit) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Pochette d'album
        if (controller != null) {
            val art = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { bottomMargin = dp(4) }
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) {
                        o.setRoundRect(0, 0, v.width, v.height, dp(8).toFloat())
                    }
                }
                clipToOutline = true
                setBackgroundColor(Color.parseColor("#1A1A1F"))
            }
            applyArt(art, controller.metadata)
            col.addView(art)

            val cbArt = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    runOnUiThread { applyArt(art, metadata) }
                }
            }
            runCatching { controller.registerCallback(cbArt) }
            ctrlCallbacks.add(controller to cbArt)
        }

        val chText = TextView(this).apply {
            text = ch
            setTextColor(Color.parseColor("#9FE870"))
            textSize = 11f
            gravity = Gravity.CENTER
            letterSpacing = 0.15f
        }
        val slider = FaderView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(190))
            this.max = maxV
            value = current
            this.onChange = cb
        }
        col.addView(chText)
        col.addView(slider)

        // Transport : ⏮ ▶/⏸ ⏭
        if (controller != null) {
            fun tbtn(txt: String, size: Float): TextView = TextView(this).apply {
                text = txt
                textSize = size
                setTextColor(Color.parseColor("#9FE870"))
                setPadding(dp(6), dp(2), dp(6), dp(2))
            }
            val prev = tbtn("⏮", 15f)
            val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
            val play = tbtn(if (playing) "⏸" else "▶", 19f)
            val next = tbtn("⏭", 15f)
            chText.setTextColor(Color.parseColor(if (playing) "#9FE870" else "#6E6E78"))

            prev.setOnClickListener { runCatching { controller.transportControls.skipToPrevious() } }
            next.setOnClickListener { runCatching { controller.transportControls.skipToNext() } }
            play.setOnClickListener {
                runCatching {
                    if (controller.playbackState?.state == PlaybackState.STATE_PLAYING)
                        controller.transportControls.pause()
                    else
                        controller.transportControls.play()
                }
            }

            val cbState = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    runOnUiThread {
                        val isPlaying = state?.state == PlaybackState.STATE_PLAYING
                        play.text = if (isPlaying) "⏸" else "▶"
                        chText.setTextColor(Color.parseColor(if (isPlaying) "#9FE870" else "#6E6E78"))
                    }
                }
            }
            runCatching { controller.registerCallback(cbState) }
            ctrlCallbacks.add(controller to cbState)

            val transport = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            transport.addView(prev)
            transport.addView(play)
            transport.addView(next)
            col.addView(transport)
        }

        val name = TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#B9B9C2"))
            textSize = 10f
            gravity = Gravity.CENTER
            maxLines = 1
        }
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
        clearCtrlCallbacks()

        data class Strip(
            val label: String,
            val max: Int,
            val cur: Int,
            val controller: MediaController? = null,
            val cb: (Int) -> Unit
        )

        val strips = mutableListOf<Strip>()
        val listenerOn = listenerEnabled()
        val pkgs = mutableSetOf<String>()
        var sessionError = false

        if (listenerOn) {
            try {
                val msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
                val controllers = msm.getActiveSessions(ComponentName(this, MediaNotifListener::class.java))
                for (c in controllers) {
                    pkgs.add(c.packageName)
                    val info = c.playbackInfo
                    strips.add(Strip(appLabel(c.packageName), info?.maxVolume ?: 15, info?.currentVolume ?: 0, c) { v ->
                        runCatching { c.setVolumeTo(v, 0) }
                    })
                }
            } catch (_: Exception) {
                sessionError = true
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
            addStrip(row, "CH${i + 1}", s.label, s.max, s.cur, s.controller, s.cb)
        }

        // Le service met parfois 1 à 3 s à se rebrancher : on réessaie tout seul
        if (listenerOn && (sessionError || (appCount == 0 && audio.isMusicActive)) && retries < 5) {
            retries++
            handler.postDelayed({ setupChannels() }, 600L * retries)
        }
    }

    // ---------- Presets ----------
    private fun fire(k: KnobView?, v: Int?) {
        if (k == null || v == null) return
        k.value = v
        k.onChange?.invoke(k.value)
    }

    private fun currentPreset(): String {
        val eq = eqKnobs.joinToString(",") { it.value.toString() }
        return listOf(
            eq,
            (boostKnob?.value ?: 0).toString(),
            (threeDKnob?.value ?: 0).toString(),
            (reverbKnob?.value ?: 0).toString(),
            (loudKnob?.value ?: 0).toString()
        ).joinToString(";")
    }

    private fun applyPreset(data: String) {
        val parts = data.split(";")
        runCatching {
            val eqVals = parts[0].split(",").mapNotNull { it.toIntOrNull() }
            eqKnobs.forEachIndexed { i, k -> eqVals.getOrNull(i)?.let { fire(k, it) } }
            fire(boostKnob, parts.getOrNull(1)?.toIntOrNull())
            fire(threeDKnob, parts.getOrNull(2)?.toIntOrNull())
            fire(reverbKnob, parts.getOrNull(3)?.toIntOrNull())
            fire(loudKnob, parts.getOrNull(4)?.toIntOrNull())
        }
    }

    private fun setupPresets() {
        val rowP = findViewById<LinearLayout>(R.id.presetRow)
        rowP.removeAllViews()
        val prefs = getSharedPreferences("presets", MODE_PRIVATE)
        for (i in 1..3) {
            val key = "p$i"
            val exists = prefs.contains(key)
            val btn = TextView(this).apply {
                text = "P$i"
                textSize = 13f
                letterSpacing = 0.1f
                setTextColor(Color.parseColor(if (exists) "#9FE870" else "#6E6E78"))
                setBackgroundResource(if (exists) R.drawable.dual_off else R.drawable.preset_empty)
                setPadding(dp(18), dp(6), dp(18), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(6)
                    marginEnd = dp(6)
                }
            }
            btn.setOnClickListener {
                val data = prefs.getString(key, null)
                if (data == null) {
                    toast("Appui long sur P$i pour enregistrer tes réglages")
                } else {
                    applyPreset(data)
                    btn.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    toast("Preset P$i chargé 🎚")
                }
            }
            btn.setOnLongClickListener {
                prefs.edit().putString(key, currentPreset()).apply()
                btn.setTextColor(Color.parseColor("#9FE870"))
                btn.setBackgroundResource(R.drawable.dual_off)
                btn.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                toast("Preset P$i enregistré 💾")
                true
            }
            rowP.addView(btn)
        }
    }

    // ---------- Master ----------
    private var masterFader: FaderView? = null

    private fun setupMaster() {
        val holder = findViewById<android.widget.FrameLayout>(R.id.masterHolder)
        holder.removeAllViews()
        masterFader = FaderView(this, horizontal = true).apply {
            max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            value = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            onChange = { v -> runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) } }
        }
        holder.addView(
            masterFader,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun syncMaster() {
        masterFader?.value = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
