package com.example.weatherapp

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Deliberately never returns exception text, URLs, coordinates or server response bodies. */
internal object WeatherPrivacy {
    fun requestFailure(error: Exception): String {
        val status = Regex("^HTTP (\\d{3})(?:\\D|$)").find(error.message.orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
        return when (status) {
            401 -> "接口凭证未通过验证，请检查填写的信息"
            403 -> "接口访问受限，请检查服务权限与额度"
            429 -> "接口调用频率或额度受限，请稍后重试"
            in 500..599 -> "天气服务暂时不可用，请稍后重试"
            else -> when (error) {
                is SocketTimeoutException -> "连接超时，请稍后重试"
                is UnknownHostException -> "无法连接天气服务，请检查网络"
                is SSLException -> "安全连接失败，请检查网络与系统时间"
                is IOException -> "天气请求未完成，请检查网络或接口配置"
                else -> "天气数据暂时不可用，请稍后重试"
            }
        }
    }
}
