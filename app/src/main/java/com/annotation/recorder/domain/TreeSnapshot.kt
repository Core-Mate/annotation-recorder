package com.annotation.recorder.domain

data class TreeSnapshot(
    val id: String,
    val tsElapsedNanos: Long,
    val rootWindowId: Int,
    val root: TreeNodeSnapshot?
)
