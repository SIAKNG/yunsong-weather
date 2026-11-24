package com.example.weatherapp

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

internal object WeatherDiagnosticMail {
    const val RECIPIENT = "siakngsong@outlook.com"
    const val SUBJECT = "云松天气问题诊断"
    const val BODY = "你好，随信附上云松天气的诊断日志。\n\n问题出现时的操作和现象（可补充）：\n"

    data class Client(val label: String, val component: ComponentName)

    @Suppress("DEPRECATION")
    fun clients(context: Context): List<Client> {
        val manager = context.packageManager
        val mailto = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$RECIPIENT"))
        val flags = PackageManager.MATCH_DEFAULT_ONLY
        val mailActivities = manager.queryIntentActivities(mailto, flags)
        val mailPackages = mailActivities.groupBy { it.activityInfo.packageName }
        val send = Intent(Intent.ACTION_SEND).setType(WeatherDiagnostics.MIME_TYPE)
        return manager.queryIntentActivities(send, flags)
            .filter { it.activityInfo.exported && it.activityInfo.packageName in mailPackages }
            .groupBy { it.activityInfo.packageName }
            .map { (name, candidates) ->
                val composeNames = mailPackages.getValue(name).map { it.activityInfo.name }.toSet()
                val target = candidates.firstOrNull { it.activityInfo.name in composeNames } ?: candidates.first()
                Client(target.activityInfo.applicationInfo.loadLabel(manager).toString(), ComponentName(name, target.activityInfo.name))
            }.sortedBy { it.label }
    }

    @Suppress("DEPRECATION")
    fun preferredClient(context: Context, clients: List<Client>): Client? {
        val mailto = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$RECIPIENT"))
        val preferredPackage = context.packageManager
            .resolveActivity(mailto, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        return clients.firstOrNull { it.component.packageName == preferredPackage } ?: clients.singleOrNull()
    }

    fun composeIntent(context: Context, client: Client): Intent {
        val directory = File(context.cacheDir, "diagnostic-shares")
        check(directory.isDirectory || directory.mkdirs() || directory.isDirectory)
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1_000L
        directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        val prefix = WeatherDiagnostics.archiveName().removeSuffix(".zip") + "-"
        val file = File.createTempFile(prefix, ".zip", directory)
        try {
            file.outputStream().use { WeatherDiagnostics.writeArchive(context, it) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", file)
            return Intent(Intent.ACTION_SEND).apply {
                component = client.component
                type = WeatherDiagnostics.MIME_TYPE
                putExtra(Intent.EXTRA_EMAIL, arrayOf(RECIPIENT))
                putExtra(Intent.EXTRA_SUBJECT, SUBJECT)
                putExtra(Intent.EXTRA_TEXT, BODY)
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("云松天气诊断包", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }
}
