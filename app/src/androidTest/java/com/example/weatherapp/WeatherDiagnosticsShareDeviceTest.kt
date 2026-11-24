package com.example.weatherapp

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.util.zip.ZipInputStream

class WeatherDiagnosticsShareDeviceTest {
    @Test fun composesReadOnlyZipForAnExplicitMailClient() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = WeatherDiagnosticMail.Client("测试邮箱", ComponentName("example.mail", "example.mail.Compose"))
        val mail = WeatherDiagnosticMail.composeIntent(context, client)
        @Suppress("DEPRECATION")
        val attachment = requireNotNull(mail.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        try {
            assertEquals(Intent.ACTION_SEND, mail.action)
            assertEquals(client.component, mail.component)
            assertEquals("application/zip", mail.type)
            assertEquals(listOf("siakngsong@outlook.com"), mail.getStringArrayExtra(Intent.EXTRA_EMAIL)?.toList())
            assertEquals(WeatherDiagnosticMail.SUBJECT, mail.getStringExtra(Intent.EXTRA_SUBJECT))
            assertEquals(WeatherDiagnosticMail.BODY, mail.getStringExtra(Intent.EXTRA_TEXT))
            assertEquals("content", attachment.scheme)
            assertEquals("${context.packageName}.diagnostic-reports", attachment.authority)
            assertEquals(attachment, mail.clipData?.getItemAt(0)?.uri)
            assertTrue(mail.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertFalse(mail.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
            assertTrue(attachment.lastPathSegment.orEmpty().endsWith(".zip"))
            val resolver = context.contentResolver
            assertEquals("application/zip", resolver.getType(attachment))
            val files = readArchive(requireNotNull(resolver.openInputStream(attachment)))
            assertEquals(setOf("manifest.json", "environment.json", "timeline.jsonl", "crashes.json"), files.keys)
            assertEquals(1, JSONObject(files.getValue("manifest.json")).getInt("schema_version"))
        } finally {
            context.contentResolver.delete(attachment, null, null)
        }
    }

    @Test fun saveWritesAnOpenableZipIntoDownloads() {
        if (Build.VERSION.SDK_INT < 29) return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = WeatherDiagnostics.saveToDownloads(context)
        try {
            val resolver = context.contentResolver
            assertEquals("application/zip", resolver.getType(uri))
            val files = readArchive(requireNotNull(resolver.openInputStream(uri)))
            assertEquals("yunsong.diagnostics", JSONObject(files.getValue("manifest.json")).getString("schema"))
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    companion object {
        internal fun readArchive(input: InputStream): Map<String, String> = ZipInputStream(input).use { zip ->
            buildMap {
                while (true) {
                    val entry = zip.nextEntry ?: break
                    put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
    }
}
