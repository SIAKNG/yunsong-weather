package com.example.weatherapp

import android.content.Context

object WeatherAccessFeedback {
    const val MISSING_API = "尚未配置天气 API，请先填写个人接口"
    fun needsApi(context: Context): Boolean = false // Open-Meteo is available without personal credentials.
    // Missing local configuration is not a network or remote authorization error.
    fun refreshResult(missingApi: Boolean, success: Boolean): String = when {
        missingApi -> MISSING_API
        success -> "刷新成功"
        else -> "刷新失败，请检查网络或天气源状态"
    }
}
