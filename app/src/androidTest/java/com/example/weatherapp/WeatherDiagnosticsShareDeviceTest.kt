package com.example.weatherapp

import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherDiagnosticsShareDeviceTest {
    @Test fun attachesTheUtf8ReportWithOnlyReadAccess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expected = WeatherDiagnostics.report(context)
        val send = WeatherDiagnostics.shareIntent(context)
        @Suppress("DEPRECATION")
        val uri = requireNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        try {
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("text/plain", send.type)
            assertEquals("content", uri.scheme)
            assertEquals("${context.packageName}.diagnostic-reports", uri.authority)
            assertEquals(uri, send.clipData?.getItemAt(0)?.uri)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertFalse(send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
            assertTrue(uri.lastPathSegment.orEmpty().endsWith(".txt"))
            val resolver = context.contentResolver
            assertEquals("text/plain", resolver.getType(uri))
            val contents = requireNotNull(resolver.openInputStream(uri)).bufferedReader(Charsets.UTF_8).use { it.readText() }
            assertEquals(expected, contents)
            val chooser = Intent.createChooser(send, "分享诊断报告")
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }
}
