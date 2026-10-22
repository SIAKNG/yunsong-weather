package com.example.weatherapp

/** 展示层唯一的基础单位转换：温度=℃、风速=km/h、气压=hPa、能见度=km。 */
object WeatherUnitConverter {
    fun temperature(celsius: Double, unit: String): Double =
        if (unit == WeatherSettings.TEMP_F) celsius * 9.0 / 5.0 + 32.0 else celsius

    fun wind(kmh: Double, unit: String): Double = when (unit) {
        WeatherSettings.WIND_MS -> kmh / 3.6
        WeatherSettings.WIND_MPH -> kmh / 1.609344
        else -> kmh
    }

    fun pressure(hpa: Double, unit: String): Double = when (unit) {
        WeatherSettings.PRESSURE_MMHG -> hpa * 0.750062
        WeatherSettings.PRESSURE_INHG -> hpa * 0.02953
        else -> hpa
    }

    fun visibility(kilometres: Double, unit: String): Double = when (unit) {
        WeatherSettings.VIS_MI -> kilometres * 0.621371
        WeatherSettings.VIS_M -> kilometres * 1000.0
        else -> kilometres
    }
}
