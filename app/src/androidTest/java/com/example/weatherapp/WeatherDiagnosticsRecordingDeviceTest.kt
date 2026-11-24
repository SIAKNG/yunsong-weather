package com.example.weatherapp

import androidx.test.core.app.ActivityScenario
import android.os.SystemClock
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException
import java.io.ByteArrayOutputStream
import org.json.JSONObject

class WeatherDiagnosticsRecordingDeviceTest {
    @Test fun recordsOnlyWhileEnabledAndExportsCrashWithoutSensitiveInput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        WeatherDiagnostics.clear(context)
        try {
            WeatherDiagnostics.record(context, WeatherDiagnostics.Event.LOCATION_RECEIVED)
            assertTrue(WeatherDiagnostics.entries(context).isEmpty())
            WeatherDiagnostics.setEnabled(context, true)
            WeatherDiagnostics.record(context, WeatherDiagnostics.Event.LOCATION_TIMEOUT)
            WeatherDiagnostics.mark(context, WeatherDiagnostics.Issue.DISPLAY)
            val first = requireNotNull(WeatherDiagnosticMonitor.requestStart(WeatherDiagnosticMonitor.Source.CAIYUN, "forecast"))
            WeatherDiagnosticMonitor.requestFinished(first, 429)
            val privateInput = "example-token=private-987654&lat=39.123456"
            val second = requireNotNull(WeatherDiagnosticMonitor.requestStart(WeatherDiagnosticMonitor.Source.QWEATHER))
            WeatherDiagnosticMonitor.requestFinished(second, null, UnknownHostException(privateInput))
            WeatherDiagnostics.saveCrash(context, IllegalStateException(privateInput), true)
            WeatherDiagnostics.setEnabled(context, false)
            assertFalse(WeatherDiagnostics.enabled(context))
            WeatherDiagnostics.mark(context, WeatherDiagnostics.Issue.OTHER)

            val output = ByteArrayOutputStream()
            WeatherDiagnostics.writeArchive(context, output)
            val files = WeatherDiagnosticsShareDeviceTest.readArchive(output.toByteArray().inputStream())
            val events = files.getValue("timeline.jsonl").lineSequence().filter { it.isNotBlank() }.map { JSONObject(it) }.toList()
            val rows = events.filter { it.getString("category") == "天气请求" }.map { it.getJSONObject("data") }
            assertEquals(listOf("start", "finish", "start", "finish"), rows.map { it.getString("phase") })
            assertEquals(rows[0].getString("request_id"), rows[1].getString("request_id"))
            assertEquals(rows[2].getString("request_id"), rows[3].getString("request_id"))
            assertEquals(429, rows[1].getInt("http_status"))
            assertEquals("dns", rows[3].getString("failure"))
            assertTrue(events.any { it.getString("message") == "界面显示或交互" })
            assertFalse(events.any { it.getString("message") == "其他问题" })
            val crash = JSONObject(files.getValue("crashes.json")).getJSONObject("last_java_crash")
            assertEquals("java.lang.IllegalStateException", crash.getJSONArray("exceptions").getJSONObject(0).getString("type"))
            assertFalse(files.values.any { it.contains("private-987654") || it.contains("39.123456") })
            val manifest = JSONObject(files.getValue("manifest.json"))
            assertEquals(events.size, manifest.getInt("event_count"))
            assertTrue(manifest.getLong("latest_session_ended_at_ms") >= manifest.getLong("latest_session_started_at_ms"))
        } finally {
            WeatherDiagnostics.clear(context)
        }
        assertTrue(WeatherDiagnostics.entries(context).isEmpty())
    }
    @Test fun settingsEntryAndWindowFramesAreCollected() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        WeatherDiagnostics.clear(context)
        try {
            WeatherDiagnostics.setEnabled(context, true)
            ActivityScenario.launch(WeatherSettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val entry = activity.findViewById<android.widget.TextView>(R.id.btnProblemDiagnostics)
                    assertEquals("问题诊断与日志", entry.text.toString())
                    val decor = activity.window.decorView
                    decor.postOnAnimation(object : Runnable {
                        private var remaining = 20
                        override fun run() {
                            if (remaining-- <= 0) return
                            activity.findViewById<View>(R.id.settingsRoot).invalidate()
                            decor.postOnAnimation(this)
                        }
                    })
                }
                // The metrics callback dispatches after the corresponding window frame has completed.
                for (attempt in 0 until 20) {
                    scenario.onActivity { WeatherDiagnosticMonitor.flushCurrentWindow() }
                    if (WeatherDiagnostics.entries(context).any { it.getString("category") == "流畅度" }) break
                    SystemClock.sleep(100L)
                }
                assertTrue(WeatherDiagnostics.entries(context).any { it.getString("message") == "进入 天气设置" })
                assertTrue(WeatherDiagnostics.entries(context).any { it.optJSONObject("data")?.optInt("sample_count", 0) ?: 0 > 0 })
            }
        } finally {
            WeatherDiagnostics.clear(context)
        }
    }
}
