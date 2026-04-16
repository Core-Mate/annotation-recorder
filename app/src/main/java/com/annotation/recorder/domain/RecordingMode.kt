package com.annotation.recorder.domain

enum class RecordingMode(val value: String) {
    MODE_1_TREE("mode_1_tree"),
    MODE_2_VIDEO_SEMANTIC("mode_2_video_semantic");

    companion object {
        fun fromValue(value: String?): RecordingMode {
            return entries.firstOrNull { it.value == value } ?: MODE_1_TREE
        }
    }
}
