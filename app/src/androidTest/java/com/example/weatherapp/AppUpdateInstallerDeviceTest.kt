package com.example.weatherapp

import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppUpdateInstallerDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val url = "https://github.com/SIAKNG/yunsong-weather/releases/download/v1.5/yunsong-weather-1.5.apk"

    @Suppress("DEPRECATION")
    @Test fun signedDownloadIsVerifiedBeforeOpeningTheAndroidInstaller() = runBlocking {
        val signedApk = File(context.applicationInfo.sourceDir)
        val bytes = signedApk.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val installer = AppUpdateInstaller(context) { uri -> testConnection(uri, signedApk) }
        val original = installer.installedPackage()
        val parcel = android.os.Parcel.obtain()
        val older = try {
            original.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            android.content.pm.PackageInfo.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }.apply {
            if (Build.VERSION.SDK_INT >= 28) setLongVersionCode(BuildConfig.VERSION_CODE.toLong() - 1)
            else versionCode = BuildConfig.VERSION_CODE - 1
        }
        val candidate = AppUpdatePolicy.Apk(url, bytes.size.toLong(), digest, BuildConfig.VERSION_CODE)
        var lastProgress = 0
        val downloaded = installer.download("1.5", candidate, older) { lastProgress = it }
        try {
            assertEquals(100, lastProgress)
            assertEquals(bytes.size.toLong(), downloaded.length())
            val intent = AppUpdateInstaller.installationIntent(context, downloaded)
            assertEquals(Intent.ACTION_INSTALL_PACKAGE, intent.action)
            assertEquals("content", intent.data?.scheme)
            assertEquals(context.packageName + ".app-updates", intent.data?.authority)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertTrue(runCatching { installer.verifyPackage(downloaded, "1.5", BuildConfig.VERSION_CODE) }.isFailure)
            assertTrue(runCatching { installer.verifyPackage(downloaded, "9.9", BuildConfig.VERSION_CODE, older) }.isFailure)
            assertTrue(runCatching {
                installer.download("1.5", candidate.copy(sha256 = "0".repeat(64)), older) {}
            }.isFailure)
        } finally {
            downloaded.delete()
        }
    }

    @Test fun downloadRedirectsStayOnGitHubAssetHosts() {
        assertTrue(AppUpdateInstaller.isAllowedAssetHost(URI("https://release-assets.githubusercontent.com/path?signature=ok")))
        assertFalse(AppUpdateInstaller.isAllowedAssetHost(URI("http://github.com/path")))
        assertFalse(AppUpdateInstaller.isAllowedAssetHost(URI("https://github.com.example.com/path")))
        assertFalse(AppUpdateInstaller.isAllowedAssetHost(URI("https://user@github.com/path")))
    }

    private fun testConnection(uri: URI, apk: File): HttpURLConnection =
        object : HttpURLConnection(uri.toURL()) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getResponseCode(): Int = if (uri.host == "github.com") 302 else 200
            override fun getHeaderField(name: String): String? =
                if (name == "Location") "https://release-assets.githubusercontent.com/android-test" else null
            override fun getInputStream(): InputStream = apk.inputStream()
        }
}
