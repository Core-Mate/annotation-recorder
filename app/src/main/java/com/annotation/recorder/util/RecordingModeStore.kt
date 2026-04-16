package com.annotation.recorder.util

import android.content.Context
import com.annotation.recorder.domain.RecordingMode

object RecordingModeStore {
    private const val PREF_NAME = "recorder_prefs"
    private const val KEY_MODE = "recording_mode"

    fun get(context: Context): RecordingMode {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return RecordingMode.fromValue(prefs.getString(KEY_MODE, RecordingMode.MODE_1_TREE.value))
    }

    fun set(context: Context, mode: RecordingMode) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_MODE, mode.value).apply()
    }
}
