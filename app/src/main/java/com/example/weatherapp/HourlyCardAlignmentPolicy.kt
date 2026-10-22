package com.example.weatherapp

/** Pure policy for keeping the 72-hour card on the first-screen bottom edge. */
object HourlyCardAlignmentPolicy {
    fun targetSpacerHeight(currentHeight: Int, availableSpace: Int, visualLift: Int = 0): Int? {
        val target = currentHeight + availableSpace - visualLift
        return target.takeIf { it >= 0 }
    }
}
