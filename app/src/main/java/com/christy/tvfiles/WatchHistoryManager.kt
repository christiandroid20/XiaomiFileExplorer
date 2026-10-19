package com.christy.tvfiles

import android.content.Context

object WatchHistoryManager {
    private const val PREF_NAME = "mi_box_watch_history"

    fun markAsWatched(context: Context, filePath: String, watched: Boolean) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(filePath, watched).apply()
    }

    fun isWatched(context: Context, filePath: String): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(filePath, false)
    }
}