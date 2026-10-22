package com.example.weatherapp

/** 将各天气源的原始空气质量字段收敛成可追溯的同源时间线。 */
object AirQualityTimelineFactory {

    fun caiyun(result: Result, receivedAtMillis: Long = System.currentTimeMillis()): AirQualityTimeline {
        val realtime = result.realtime?.let { value ->
            AirQualityPoint(
                timeMillis = receivedAtMillis,
                aqi = value.airQuality?.aqi?.chn ?: value.aqi?.chn,
                pm25 = value.airQuality?.pm25 ?: value.pm25,
                pm10 = value.airQuality?.pm10,
                no2 = value.airQuality?.no2,
                o3 = value.airQuality?.o3,
                co = value.airQuality?.co,
                so2 = value.airQuality?.so2,
                source = AirQualitySource.CAIYUN,
                updatedAtMillis = receivedAtMillis
            )
        }
        val quality = result.hourly?.airQuality
        val hourly = quality?.let { source -> source.aqi.orEmpty().mapNotNull { aqiItem ->
            val datetime = aqiItem.datetime ?: return@mapNotNull null
            val time = TimeUtil.parse(datetime) ?: return@mapNotNull null
            AirQualityPoint(
                timeMillis = time,
                aqi = aqiItem.value?.chn,
                pm25 = source.pm25.valueAt(datetime),
                pm10 = source.pm10.valueAt(datetime),
                no2 = source.no2.valueAt(datetime),
                o3 = source.o3.valueAt(datetime),
                co = source.co.valueAt(datetime),
                so2 = source.so2.valueAt(datetime),
                source = AirQualitySource.CAIYUN,
                updatedAtMillis = receivedAtMillis
            )
        } }.orEmpty().filter { it.aqi != null || it.pm25 != null }.sortedBy { it.timeMillis }
        return AirQualityTimeline(AirQualitySource.CAIYUN, receivedAtMillis, realtime, hourly)
    }

    fun qweather(bundle: QWeatherBundle, receivedAtMillis: Long = System.currentTimeMillis()): AirQualityTimeline {
        val realtime = bundle.aqi?.let {
            AirQualityPoint(
                timeMillis = it.updateTimeMillis.takeIf { value -> value > 0L } ?: receivedAtMillis,
                aqi = it.aqi,
                pm25 = it.pm25,
                pm10 = it.pm10,
                no2 = it.no2,
                o3 = it.o3,
                co = it.co,
                so2 = it.so2,
                source = AirQualitySource.QWEATHER,
                updatedAtMillis = receivedAtMillis
            )
        }
        // 兼容升级前没有 aqiHourly 字段的本地 JSON（Gson 可产生运行时 null）。
        val hourly = (bundle.aqiHourly as List<QAirQualityHour>?).orEmpty().map {
            AirQualityPoint(
                timeMillis = it.timeMillis,
                aqi = it.aqi,
                pm25 = it.pm25,
                pm10 = it.pm10,
                no2 = it.no2,
                o3 = it.o3,
                co = it.co,
                so2 = it.so2,
                source = AirQualitySource.QWEATHER,
                updatedAtMillis = receivedAtMillis
            )
        }.filter { it.aqi != null || it.pm25 != null }.sortedBy { it.timeMillis }
        return AirQualityTimeline(AirQualitySource.QWEATHER, receivedAtMillis, realtime, hourly)
    }

    /**
     * 彩云的 AQI、PM2.5 与已有污染物绝不被和风覆盖；和风只按同一整点补齐彩云未返回的
     * PM10、NO₂、O₃、CO、SO₂ 等字段。这样首页、污染物格和详情曲线读取的仍是一条
     * 可追溯的彩云主时间线。
     */
    fun mergeCaiyunWithQWeather(
        caiyun: AirQualityTimeline,
        qweather: AirQualityTimeline
    ): AirQualityTimeline {
        fun hourKey(time: Long) = time / 3_600_000L
        fun merge(primary: AirQualityPoint?, supplemental: AirQualityPoint?): AirQualityPoint? {
            if (primary == null) return supplemental?.copy(
                source = AirQualitySource.CAIYUN,
                fieldSources = mapOf(
                    "aqi" to AirQualitySource.QWEATHER,
                    "pm25" to AirQualitySource.QWEATHER,
                    "pm10" to AirQualitySource.QWEATHER,
                    "no2" to AirQualitySource.QWEATHER,
                    "o3" to AirQualitySource.QWEATHER,
                    "co" to AirQualitySource.QWEATHER,
                    "so2" to AirQualitySource.QWEATHER
                )
            )
            if (supplemental == null) return primary
            val added = primary.fieldSources.toMutableMap()
            fun fill(name: String, primaryValue: Double?, fallback: Double?): Double? =
                primaryValue ?: fallback?.also { added[name] = AirQualitySource.QWEATHER }
            return primary.copy(
                // 彩云已有的 AQI/PM2.5 永远优先；只有确实没有才补齐。
                aqi = fill("aqi", primary.aqi, supplemental.aqi),
                pm25 = fill("pm25", primary.pm25, supplemental.pm25),
                pm10 = fill("pm10", primary.pm10, supplemental.pm10),
                no2 = fill("no2", primary.no2, supplemental.no2),
                o3 = fill("o3", primary.o3, supplemental.o3),
                co = fill("co", primary.co, supplemental.co),
                so2 = fill("so2", primary.so2, supplemental.so2),
                fieldSources = added
            )
        }

        val qByHour = qweather.hourly.associateBy { hourKey(it.timeMillis) }
        val cByHour = caiyun.hourly.associateBy { hourKey(it.timeMillis) }
        val mergedHours = (cByHour.keys + qByHour.keys).sorted().mapNotNull { hour ->
            merge(cByHour[hour], qByHour[hour])
        }
        return caiyun.copy(
            realtime = merge(caiyun.realtime, qweather.realtime),
            hourly = mergedHours
        )
    }

    private fun List<HourlyValue>?.valueAt(datetime: String): Double? =
        this?.firstOrNull { it.datetime == datetime }?.value
}
