package com.example.weatherapp

import com.google.gson.JsonParser

/** Public release metadata only. Never send a weather key or GitHub credential. */
object AppUpdatePolicy {
    const val API_URL = "https://api.github.com/repos/SIAKNG/yunsong-weather/releases/latest"
    private const val DOWNLOAD_ROOT = "https://github.com/SIAKNG/yunsong-weather/releases/download"
    internal const val MAX_APK_BYTES = 128L * 1024 * 1024
    data class Apk(val url: String, val size: Long, val sha256: String?, val versionCode: Int?)
    enum class Status { NEW_VERSION, CURRENT, UNAVAILABLE, FAILED }
    data class Result(val status: Status, val version: String = "", val apk: Apk? = null) {
        val label: String get() = when (status) {
            Status.NEW_VERSION -> "有新版本 $version"
            Status.CURRENT -> "已是最新版本"
            Status.UNAVAILABLE -> "暂无法检查"
            Status.FAILED -> "检查未完成"
        }
    }

    private fun versionParts(value: String): List<Int>? {
        val match = Regex("^v?(\\d{1,5})\\.(\\d{1,5})(?:\\.(\\d{1,5}))?(?:-userapi)?$").matchEntire(value) ?: return null
        return (1..3).map { match.groupValues[it].ifEmpty { "0" }.toInt() }
    }

    internal fun sameVersion(first: String, second: String): Boolean =
        versionParts(first)?.let { it == versionParts(second) } == true

    fun evaluate(httpStatus: Int, json: String, installedName: String, installedCode: Int): Result {
        if (httpStatus == 404) return Result(Status.UNAVAILABLE)
        if (httpStatus != 200) return Result(Status.FAILED)
        return runCatching {
            val release = JsonParser.parseString(json).asJsonObject
            require(!release.get("draft").asBoolean && !release.get("prerelease").asBoolean)
            val tag = release.get("tag_name").asString
            val version = tag.removePrefix("v").removeSuffix("-userapi")
            val apkName = "yunsong-weather-$version.apk"
            val apkUrl = "$DOWNLOAD_ROOT/$tag/$apkName"
            val asset = release.getAsJsonArray("assets").map { it.asJsonObject }.single {
                it.get("name")?.asString == apkName &&
                    it.get("state")?.asString == "uploaded" &&
                    it.get("browser_download_url")?.asString == apkUrl
            }
            val size = asset.get("size").asLong
            require(size in 1..MAX_APK_BYTES)
            val digest = asset.get("digest")?.takeUnless { it.isJsonNull }?.asString?.let {
                require(it.matches(Regex("sha256:[a-fA-F0-9]{64}")))
                it.removePrefix("sha256:")
            }
            val remote = requireNotNull(versionParts(tag))
            val installed = requireNotNull(versionParts(installedName))
            val comparison = remote.zip(installed).map { (r, i) -> r.compareTo(i) }.firstOrNull { it != 0 } ?: 0
            // Allows maintenance builds to keep the user-facing version 1.1.
            val code = Regex("<!-- yunsong-version-code: (\\d{1,9}) -->")
                .find(release.get("body")?.asString.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            val newer = when {
                code == null -> comparison > 0
                else -> comparison >= 0 && code > installedCode
            }
            Result(if (newer) Status.NEW_VERSION else Status.CURRENT, version, if (newer) Apk(apkUrl, size, digest, code) else null)
        }.getOrElse { Result(Status.FAILED) }
    }
}
