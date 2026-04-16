package com.annotation.recorder.domain

data class TreeNodeSnapshot(
    val nodeId: String,
    val className: String?,
    val text: String?,
    val contentDesc: String?,
    val viewIdRes: String?,
    val bounds: String,
    val clickable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val focused: Boolean,
    val children: List<TreeNodeSnapshot>
)
