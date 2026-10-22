package com.example.weatherapp

import org.junit.Assert.*
import org.junit.Test

class GuideRefreshMotionTest {
    @Test fun exactFinalFrameIsFiniteDespiteFloatingPointPhaseOvershoot() {
        assertTrue((1f-.58f)/.42f>1f) // The device regression's original NaN trigger.
        val final=GuideRefreshMotion.frame(1f,33f)
        assertEquals(0f,final.pull,0f);assertEquals(1f,final.refreshBlend,0f)
    }
    @Test fun allFramesAreBoundedForShortAndLongPulls() {
        listOf(0f,37.8f,62f).forEach { initial ->
            (0..2000).forEach { i ->
                val f=GuideRefreshMotion.frame(i/2000f,initial)
                assertTrue(f.pull.isFinite() && f.pull in 0f..62f)
                assertTrue(f.refreshBlend.isFinite() && f.refreshBlend in 0f..1f)
            }
        }
    }
    @Test fun phasesJoinContinuouslyAndReleaseOnlyAfterRefreshHold() {
        assertEquals(17f,GuideRefreshMotion.frame(0f,17f).pull,0f)
        assertEquals(48f,GuideRefreshMotion.frame(.25f,17f).pull,.001f)
        assertEquals(48f,GuideRefreshMotion.frame(.58f,17f).pull,.001f)
        assertEquals(0f,GuideRefreshMotion.frame(.58f,17f).refreshBlend,.001f)
        var previous=48f
        (580..1000).forEach { i -> val p=GuideRefreshMotion.frame(i/1000f,17f).pull;assertTrue(p<=previous+.001f);previous=p }
    }
    @Test fun outOfRangeAndNonFiniteInputsCannotPoisonTheScene() {
        listOf(-.1f,1.000001f,Float.NaN,Float.POSITIVE_INFINITY).forEach { f ->
            val frame=GuideRefreshMotion.frame(f,Float.NaN)
            assertTrue(frame.pull.isFinite());assertTrue(frame.refreshBlend.isFinite())
        }
    }
}
