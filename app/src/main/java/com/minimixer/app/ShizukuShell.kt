package com.minimixer.app

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Exécution de commandes shell via Shizuku (niveau ADB, sans root). */
object ShizukuShell {

    fun running(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean =
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)

    /** À appeler hors du thread UI. Retourne true si la commande a réussi. */
    fun exec(cmd: String): Boolean {
        return try {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            m.isAccessible = true
            val process = m.invoke(null, arrayOf("sh", "-c", cmd), null, null)
            val waitFor = process.javaClass.getMethod("waitFor")
            (waitFor.invoke(process) as Int) == 0
        } catch (_: Exception) {
            false
        }
    }
}
