package com.example.weatherapp

import org.junit.Assert.*
import org.junit.Test

class FeelsLikeTrackTest {
    private fun marker(delta: Double, width: Float = 160f, range: Double = 6.0) =
        IndicatorTrackGeometry.feelsLikeMarkerX(4f, width - 4f, 10f, 5f, 8f, delta, range)

    @Test fun oneDegreeHasAWholeFilledSegmentOutsideTheGap() {
        for (width in listOf(100f,160f,300f)) {
            assertTrue(marker(1.0,width) - width/2 >= 18f)
            assertTrue(width/2 - marker(-1.0,width) >= 18f)
        }
    }
    @Test fun fractionalNonzeroValuesStayOnTheirCorrectFilledSide() {
        for (delta in listOf(.01,.2,.5,1.0)) {
            assertTrue(marker(delta) >= 98f)
            assertTrue(marker(-delta) <= 62f)
        }
    }
    @Test fun zeroAndMissingValuesStayCentered() {
        assertEquals(80f,marker(0.0),0f)
        assertEquals(80f,marker(Double.NaN),0f)
    }
    @Test fun movementIsSymmetricMonotoneAndClampedInsideEndpoints() {
        var previous = 80f
        for (delta in listOf(.1,.5,1.0,2.0,3.0,6.0,100.0)) {
            val value = marker(delta)
            assertTrue(value >= previous)
            assertTrue(value <= 151f)
            assertEquals(160f,value+marker(-delta),.001f)
            previous = value
        }
    }
    @Test fun fahrenheitAndCelsiusAgreeForEquivalentDifferences() {
        assertEquals(marker(1.0),marker(1.8,range=10.8),.001f)
        assertEquals(marker(-3.0),marker(-5.4,range=10.8),.001f)
    }
}
