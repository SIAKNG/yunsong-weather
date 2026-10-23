package com.example.weatherapp

import com.google.gson.JsonParser

/** Public release metadata only. Never send a weather key or GitHub credential. */
object AppUpdatePolicy {
    const val DOWNLOAD_PAGE = "https://github.com/SIAKNG/yunsong-weather/releases/latest"
    const val API_URL = "https://api.github.com/repos/SIAKNG/yunsong-weather/releases/latest"
    enum class Status { NEW_VERSION, CURRENT, UNAVAILABLE, FAILED }
    data class Result(val status: Status, val version: String = "") {
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

    fun evaluate(httpStatus: Int, json: String, installedName: String, installedCode: Int): Result {
        if (httpStatus == 404) return Result(Status.UNAVAILABLE)
        if (httpStatus != 200) return Result(Status.FAILED)
        return runCatching {
            val release = JsonParser.parseString(json).asJsonObject
            require(!release.get("draft").asBoolean && !release.get("prerelease").asBoolean)
            require(release.getAsJsonArray("assets").any { element ->
                val asset = element.asJsonObject
                asset.get("state")?.asString == "uploaded" &&
                    asset.get("name")?.asString?.endsWith(".apk", ignoreCase = true) == true &&
                    (asset.get("size")?.asLong ?: 0L) > 0L &&
                    asset.get("browser_download_url")?.asString?.startsWith(
                        "https://github.com/SIAKNG/yunsong-weather/releases/download/") == true
            })
            val tag = release.get("tag_name").asString
            val remote = requireNotNull(versionParts(tag))
            val installed = requireNotNull(versionParts(installedName))
            val comparison = remote.zip(installed).map { (r, i) -> r.compareTo(i) }.firstOrNull { it != 0 } ?: 0
            // Allows maintenance builds to keep the user-facing version 1.1.
            val code = Regex("<!-- yunsong-version-code: (\\d{1,9}) -->")
                .find(release.get("body")?.asString.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            val newer = comparison > 0 || (comparison == 0 && code != null && code > installedCode)
            Result(if (newer) Status.NEW_VERSION else Status.CURRENT, tag.removePrefix("v"))
        }.getOrElse { Result(Status.FAILED) }
    }
}
