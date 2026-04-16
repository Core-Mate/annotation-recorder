package com.annotation.recorder.domain

data class CoordinateEstimate(
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
    val source: String = "estimated"
)
