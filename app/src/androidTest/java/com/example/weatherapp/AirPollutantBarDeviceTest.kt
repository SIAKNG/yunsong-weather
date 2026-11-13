package com.example.weatherapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AirPollutantBarDeviceTest {
    @Test fun concentrationFillsRemainVisibleInBothThemes() = onGrid { grid ->
        val point = AirQualityPoint(
            timeMillis = 1, pm25 = 38.0, pm10 = 250.0, no2 = 35.0,
            o3 = 110.0, co = 0.6, so2 = 4.0
        )
        for (dark in listOf(false, true)) {
            grid.setData(point, dark)
            DetailCardEdges.apply(grid)
            for ((index, bar) in bars(grid).withIndex()) {
                val first = pixel(bar, 4)
                val second = pixel(bar, 160)
                assertEquals("opaque fill: dark=$dark pollutant=$index", 255, Color.alpha(first))
                assertTrue("distinct fill: dark=$dark pollutant=$index", chroma(first) > 45)
                if (index == 3) assertTrue("full PM10 bar", chroma(second) > 45)
                else assertTrue("neutral remaining track", chroma(second) < 5)
            }
        }
    }

    @Test fun missingPollutantsLeaveOnlyTheTrack() = onGrid { grid ->
        for (dark in listOf(false, true)) {
            grid.setData(AirQualityPoint(timeMillis = 1), dark)
            DetailCardEdges.apply(grid)
            for (bar in bars(grid)) {
                val left = pixel(bar, 4)
                val right = pixel(bar, 160)
                assertEquals(left, right)
                assertEquals(if (dark) 65 else 255, Color.alpha(left))
                assertTrue(chroma(left) < 5)
            }
        }
    }

    private fun onGrid(run: (AirPollutantGridView) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                run(AirPollutantGridView(instrumentation.targetContext))
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it }
    }

    private fun bars(grid: AirPollutantGridView): List<View> = buildList {
        for (i in 0 until grid.childCount) {
            val row = grid.getChildAt(i) as ViewGroup
            for (j in 0 until row.childCount) {
                val tile = row.getChildAt(j) as ViewGroup
                add(tile.getChildAt(tile.childCount - 1))
            }
        }
        assertEquals(6, size)
    }

    private fun pixel(bar: View, x: Int): Int {
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(16, View.MeasureSpec.EXACTLY)
        )
        bar.layout(0, 0, 180, 16)
        val bitmap = Bitmap.createBitmap(180, 16, Bitmap.Config.ARGB_8888)
        bar.draw(Canvas(bitmap))
        return bitmap.getPixel(x, 8).also { bitmap.recycle() }
    }

    private fun chroma(color: Int): Int {
        val channels = listOf(Color.red(color), Color.green(color), Color.blue(color))
        return channels.max() - channels.min()
    }
}
