package com.minimixer.app

import android.content.Context

/**
 * Mode multi-sources : dit au système d'ignorer les demandes de focus audio
 * des apps média TANT QUE Mini Mixer est actif, puis restaure tout.
 */
class MultiAudio(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("multi_audio", Context.MODE_PRIVATE)
    private val touched = mutableSetOf<String>()
    private val mutedPkgs = mutableSetOf<String>()

    var active = false
        private set

    val defaultTargets = listOf(
        "com.google.android.youtube",
        "com.google.android.apps.youtube.music",
        "com.spotify.music",
        "com.soundcloud.android",
        "deezer.android.app",
        "com.amazon.mp3",
        "com.netflix.mediaclient",
        "com.zhiliaoapp.musically"
    )

    /** Filet de sécurité : si l'app avait été tuée sans restaurer, on remet tout. */
    fun restoreLeftover() {
        val leftover = prefs.getStringSet("touched", emptySet()) ?: emptySet()
        val leftoverMuted = prefs.getStringSet("muted", emptySet()) ?: emptySet()
        if (leftover.isEmpty() && leftoverMuted.isEmpty()) return
        Thread {
            leftover.forEach { ShizukuShell.exec("cmd appops set $it TAKE_AUDIO_FOCUS allow") }
            leftoverMuted.forEach { ShizukuShell.exec("cmd appops set $it PLAY_AUDIO allow") }
        }.start()
        prefs.edit().remove("touched").remove("muted").apply()
    }

    /** Mute/unmute réel d'une app (à appeler hors du thread UI). */
    fun setPlayMuted(pkg: String, muted: Boolean): Boolean {
        val mode = if (muted) "ignore" else "allow"
        val ok = ShizukuShell.exec("cmd appops set $pkg PLAY_AUDIO $mode")
        if (ok) {
            if (muted) mutedPkgs.add(pkg) else mutedPkgs.remove(pkg)
            prefs.edit().putStringSet("muted", HashSet(mutedPkgs)).apply()
        }
        return ok
    }

    /** À appeler hors du thread UI. */
    fun unmuteAll() {
        mutedPkgs.forEach { ShizukuShell.exec("cmd appops set $it PLAY_AUDIO allow") }
        mutedPkgs.clear()
        prefs.edit().remove("muted").apply()
    }

    /** À appeler hors du thread UI. */
    fun enable(pkgs: Collection<String>): Boolean {
        var okAtLeastOne = false
        for (p in (pkgs + defaultTargets).toSet()) {
            if (p == ctx.packageName) continue
            val installed = runCatching { ctx.packageManager.getApplicationInfo(p, 0) }.isSuccess
            if (!installed) continue
            if (ShizukuShell.exec("cmd appops set $p TAKE_AUDIO_FOCUS ignore")) {
                touched.add(p)
                okAtLeastOne = true
            }
        }
        active = touched.isNotEmpty()
        prefs.edit().putStringSet("touched", HashSet(touched)).apply()
        return okAtLeastOne
    }

    /** À appeler hors du thread UI. */
    fun disable() {
        touched.forEach { ShizukuShell.exec("cmd appops set $it TAKE_AUDIO_FOCUS allow") }
        touched.clear()
        unmuteAll()
        active = false
        prefs.edit().remove("touched").apply()
    }

    fun disableAsync() = Thread { disable() }.start()
}
