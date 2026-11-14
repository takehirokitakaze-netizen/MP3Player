package jp.kitakaze.player

import android.content.Context
import android.content.SharedPreferences

/** 前回の続きから再生するための保存領域 */
object Prefs {
    const val KEY_FOLDER = "folder"
    const val KEY_CURRENT = "current_uri"
    const val KEY_TITLE = "current_title"
    const val KEY_POSITION = "position"
    const val KEY_SPEED = "speed"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences("player", Context.MODE_PRIVATE)
}
