package com.annotation.recorder.domain

data class SessionMeta(
    val sessionId: String,
    val appVersion: String,
    val deviceModel: String,
    val sdkInt: Int,
    val recordingMode: String,
    val pipelineVersion: String,
    var semanticStatus: String = "disabled",
    var videoPath: String? = null,
    val startedAtEpochMs: Long,
    var endedAtEpochMs: Long? = null
)
