package com.annotation.recorder.domain

data class EventSnapshot(
    val id: String,
    val tsElapsedNanos: Long,
    val type: String,
    val packageName: String?,
    val className: String?,
    val textSummary: String?,
    val sourceViewId: String?,
    val bounds: String?,
    val coord: CoordinateEstimate,
    val snapshotId: String
)
