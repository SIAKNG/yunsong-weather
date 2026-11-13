package com.example.weatherapp

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class AppUpdateFileProvider : FileProvider()

/** Fetches only the official release asset and validates it before handing a content URI to Android. */
internal class AppUpdateInstaller(
    private val context: Context,
    private val open: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection }
) {
    suspend fun download(
        version: String,
        apk: AppUpdatePolicy.Apk,
        installed: PackageInfo? = null,
        progress: (Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "app-updates")
        check(folder.isDirectory || folder.mkdirs())
        folder.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 48 * 60 * 60_000L }
            ?.forEach { it.delete() }
        val file = File.createTempFile("yunsong-update-", ".apk", folder)
        var verified = false
        try {
            val currentPackage = installed ?: installedPackage()
            val sha = MessageDigest.getInstance("SHA-256")
            val connection = openAsset(URI(apk.url))
            try {
                var bytes = 0L
                var shownProgress = -1
                val buffer = ByteArray(64 * 1024)
                connection.inputStream.buffered().use { input ->
                    file.outputStream().buffered().use { output ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            bytes += count
                            check(bytes <= apk.size)
                            output.write(buffer, 0, count)
                            sha.update(buffer, 0, count)
                            val next = (bytes * 100 / apk.size).toInt()
                            if (next != shownProgress) {
                                shownProgress = next
                                progress(next)
                            }
                        }
                    }
                }
                check(bytes == apk.size)
            } finally {
                connection.disconnect()
            }
            val expectedHash = apk.sha256
            if (expectedHash != null) {
                check(sha.digest().joinToString("") { "%02x".format(it) }.equals(expectedHash, ignoreCase = true))
            }
            coroutineContext.ensureActive()
            verifyPackage(file, version, apk.versionCode, currentPackage)
            verified = true
            file
        } finally {
            if (!verified) file.delete()
        }
    }

    private fun openAsset(initial: URI): HttpURLConnection {
        require(initial.host == "github.com" && initial.rawPath.startsWith("/SIAKNG/yunsong-weather/releases/download/"))
        var next = initial
        repeat(6) {
            require(isAllowedAssetHost(next))
            val connection = open(next)
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", "YunsongWeather/" + BuildConfig.VERSION_NAME)
                when (connection.responseCode) {
                    200 -> return connection
                    301, 302, 303, 307, 308 -> next = next.resolve(requireNotNull(connection.getHeaderField("Location")))
                    else -> error("Release asset download failed")
                }
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            connection.disconnect()
        }
        error("Too many release asset redirects")
    }

    @Suppress("DEPRECATION")
    internal fun installedPackage(): PackageInfo = context.packageManager.getPackageInfo(context.packageName, signingFlag)

    @Suppress("DEPRECATION")
    internal fun verifyPackage(
        file: File,
        version: String,
        declaredCode: Int?,
        installed: PackageInfo = installedPackage()
    ) {
        val downloaded = requireNotNull(context.packageManager.getPackageArchiveInfo(file.absolutePath, signingFlag))
        check(downloaded.packageName == installed.packageName)
        check(AppUpdatePolicy.sameVersion(version, downloaded.versionName.orEmpty()))
        val code = versionCode(downloaded)
        check(code > versionCode(installed) && (declaredCode == null || code == declaredCode.toLong()))
        val expected = signatures(installed)
        check(expected.isNotEmpty() && expected == signatures(downloaded))
    }

    private val signingFlag: Int get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<android.content.pm.Signature> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures).orEmpty().toSet()

    companion object {
        private val hosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")

        internal fun isAllowedAssetHost(uri: URI): Boolean =
            uri.scheme == "https" && uri.host in hosts && uri.userInfo == null &&
                (uri.port == -1 || uri.port == 443) && uri.rawFragment == null

        @Suppress("DEPRECATION")
        fun installationIntent(context: Context, file: File): Intent {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".app-updates", file)
            return Intent(Intent.ACTION_INSTALL_PACKAGE, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

internal class AppUpdateDownloadModel(application: Application) : AndroidViewModel(application) {
    sealed interface State {
        data object Idle : State
        data class Downloading(val version: String, val percent: Int) : State
        data class Ready(val version: String, val file: File) : State
        data object Failed : State
    }
    private val current = MutableStateFlow<State>(State.Idle)
    val state = current.asStateFlow()
    private var job: Job? = null
    private var autoInstall = false

    fun start(version: String, apk: AppUpdatePolicy.Apk) {
        if (job?.isActive == true) return
        autoInstall = false
        current.value = State.Downloading(version, 0)
        job = viewModelScope.launch {
            try {
                val file = AppUpdateInstaller(getApplication()).download(version, apk) {
                    if (current.value is State.Downloading) current.value = State.Downloading(version, it)
                }
                autoInstall = true
                current.value = State.Ready(version, file)
            } catch (cancelled: CancellationException) {
                current.value = State.Idle
                throw cancelled
            } catch (_: Exception) {
                current.value = State.Failed
            }
        }
    }

    fun cancel() {
        job?.cancel()
        current.value = State.Idle
    }

    fun takeAutoInstall(): Boolean = autoInstall.also { autoInstall = false }

    fun clearFailure() {
        if (current.value == State.Failed) current.value = State.Idle
    }

    fun clearMissingPackage() {
        current.value = State.Idle
    }
}
