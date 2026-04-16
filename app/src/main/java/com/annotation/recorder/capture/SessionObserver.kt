package com.annotation.recorder.capture

fun interface SessionObserver {
    fun onSessionStateChanged(state: SessionState, sessionId: String?, lastOutputPath: String?)
}
