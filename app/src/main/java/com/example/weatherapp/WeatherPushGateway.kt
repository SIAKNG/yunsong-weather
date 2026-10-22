package com.example.weatherapp

import android.content.Context

/**
 * 第二阶段 OPPO PUSH 的客户端边界。
 *
 * 当前发布前版本没有登记 OPPO Registration ID，也不会发送网络请求；因此本机
 * Worker 保持启用。未来接入实现时只允许共享彩云或和风发送下列最小订阅数据，
 * “我的彩云 Token”永远不会被读取或上传。
 */
data class WeatherPushRegistration(
    val registrationId: String,
    val latitude: Double,
    val longitude: Double,
    val dataSource: String,
    val rainEnabled: Boolean,
    val disasterEnabled: Boolean,
    val dailyEnabled: Boolean
)

interface WeatherPushRegistrar {
    /** 实现必须将 Registration ID 加密后传给自有后端，而非直接传第三方天气服务。 */
    fun sync(context: Context, registration: WeatherPushRegistration)

    /** 云端确认接管风险提醒后返回 true，防止云端和本机双重投递。 */
    fun isCloudRiskDeliveryActive(context: Context): Boolean = false
}

private object DisabledWeatherPushRegistrar : WeatherPushRegistrar {
    override fun sync(context: Context, registration: WeatherPushRegistration) = Unit
}

object WeatherPushGateway {
    @Volatile private var registrar: WeatherPushRegistrar = DisabledWeatherPushRegistrar

    /** 供第二阶段 OPPO PUSH 客户端实现安装；未配置前保持纯本机模式。 */
    fun install(registrar: WeatherPushRegistrar) {
        this.registrar = registrar
    }

    fun isCloudRiskDeliveryActive(context: Context): Boolean {
        if (WeatherSettings.usesOwnCaiyunToken(context)) return false
        return registrar.isCloudRiskDeliveryActive(context)
    }

    fun syncCurrentCity(context: Context, city: CityPage) {
        if ((!WeatherSettings.rainReminder(context) && !WeatherSettings.disasterReminder(context)) ||
            WeatherSettings.usesOwnCaiyunToken(context)
        ) return
        // Registration ID 由将来的 OPPO SDK 实现提供。空 ID 在默认实现中不会离开设备。
        registrar.sync(
            context,
            WeatherPushRegistration(
                registrationId = "",
                latitude = city.lat,
                longitude = city.lng,
                dataSource = WeatherSettings.dataSource(context),
                rainEnabled = WeatherSettings.rainReminder(context),
                disasterEnabled = WeatherSettings.disasterReminder(context),
                dailyEnabled = WeatherSettings.dailyForecastReminder(context)
            )
        )
    }
}
