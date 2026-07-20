package com.minimixer.app

import android.content.Context
import android.graphics.Color

/** Thème de couleur global de la console. */
object Mix {

    data class T(val name: String, val hex: String)

    val themes = listOf(
        T("Vert néon", "#9FE870"),
        T("Ambre vintage", "#FFB84B"),
        T("Cyan", "#4BD8FF"),
        T("Synthwave", "#C77BFF")
    )

    var index = 0
        private set

    val accent: Int
        get() = Color.parseColor(themes[index].hex)

    fun load(ctx: Context) {
        index = ctx.getSharedPreferences("theme", Context.MODE_PRIVATE)
            .getInt("i", 0).coerceIn(0, themes.size - 1)
    }

    fun next(ctx: Context): T {
        index = (index + 1) % themes.size
        ctx.getSharedPreferences("theme", Context.MODE_PRIVATE)
            .edit().putInt("i", index).apply()
        return themes[index]
    }
}
