package com.minimixer.app

import android.content.Context

/**
 * Mode multi-sources : dit au système d'ignorer les demandes de focus audio
 * des apps média TANT QUE Mini Mixer est actif, puis restaure tout.
 */
class MultiAudio(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("multi_audio", Context.MODE_PRIVATE)
    private val touched = mutableSetOf<String>()

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
        if (leftover.isEmpty()) return
        Thread {
            leftover.forEach { ShizukuShell.exec("cmd appops set $it TAKE_AUDIO_FOCUS allow") }
        }.start()
        prefs.edit().remove("touched").apply()
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
        active = false
        prefs.edit().remove("touched").apply()
    }

    fun disableAsync() = Thread { disable() }.start()
}
