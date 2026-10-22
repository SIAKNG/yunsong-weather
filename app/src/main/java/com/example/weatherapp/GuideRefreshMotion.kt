package com.example.weatherapp

import kotlin.math.pow

/** Bounded phase math: float division can otherwise produce 1.0000001 at the final frame. */
internal object GuideRefreshMotion {
    data class Frame(val pull: Float, val refreshBlend: Float)
    private fun ease(value: Float): Float = 1f-(1f-value.coerceIn(0f,1f)).pow(2.8f)
    fun frame(fraction: Float, initialPull: Float): Frame {
        val f=fraction.takeIf { it.isFinite() }?.coerceIn(0f,1f) ?: 0f
        val initial=initialPull.takeIf { it.isFinite() }?.coerceIn(0f,62f) ?: 0f
        val pull=when {
            f<.25f -> initial+(48f-initial)*ease(f/.25f)
            f<.58f -> 48f
            else -> 48f*(1f-ease((f-.58f)/.42f))
        }
        return Frame(pull,((f-.58f)/.42f).coerceIn(0f,1f))
    }
}
