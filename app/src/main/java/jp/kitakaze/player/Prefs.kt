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
    const val KEY_FLOAT_ON = "float_on"
    const val KEY_FLOAT_STYLE = "float_style"
    const val KEY_FLOAT_X = "float_x"
    const val KEY_FLOAT_Y = "float_y"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences("player", Context.MODE_PRIVATE)
}
