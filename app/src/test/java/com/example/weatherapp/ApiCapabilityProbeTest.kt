package com.example.weatherapp

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CancellationException

class ApiCapabilityProbeTest {
    private val now=Instant.parse("2026-10-21T10:00:00Z").toEpochMilli()
    private val gson=Gson()
    private fun cy(version: String="2.6")=UserApiConfiguration.Config(caiyunToken="private_dummy_token",caiyunVersion=version)
    private fun q()=UserApiConfiguration.Config(source=WeatherSettings.SOURCE_QWEATHER,qweatherHost="test.qweatherapi.com",qweatherApiKey="private_dummy_key")
    private fun stamp(i: Int)=Instant.ofEpochMilli(now+i*3_600_000L).toString()
    private fun cyBody(count: Int=48,start: Int=0,uv: Boolean=true): String {
        fun values(key: String="value")=(start until start+count).map { mapOf("datetime" to stamp(it),key to 0) }
        val hourly=mutableMapOf<String,Any>("temperature" to values(),"apparent_temperature" to values(),"humidity" to values(),"pressure" to values(),"visibility" to values(),
            "wind" to values("speed"),"precipitation" to (start until start+count).map { mapOf("datetime" to stamp(it),"value" to 0,"probability" to 0) },
            "skycon" to (start until start+count).map { mapOf("datetime" to stamp(it),"value" to "CLEAR_DAY") },
            "air_quality" to (listOf("pm25","pm10","so2","no2","o3","co").associateWith { values() }+mapOf("aqi" to (start until start+count).map { mapOf("datetime" to stamp(it),"value" to mapOf("chn" to 0)) })))
        if(uv) hourly["life_index"]=mapOf("ultraviolet" to values("index"))
        fun date(i: Int)=LocalDate.parse("2026-10-21").plusDays(i.toLong()).toString()
        val daily=mapOf("temperature" to (0..6).map { mapOf("date" to date(it),"min" to 0,"max" to 1) },
            "skycon" to (0..6).map { mapOf("date" to date(it),"value" to "CLEAR_DAY") },
            "precipitation" to (0..6).map { mapOf("date" to date(it),"probability" to 0) },
            "astro" to (0..2).map { mapOf("date" to date(it),"sunrise" to mapOf("time" to "06:00"),"sunset" to mapOf("time" to "18:00")) })
        val realtime=mapOf("temperature" to 0,"skycon" to "CLEAR_DAY","apparent_temperature" to 0,"humidity" to 0,"pressure" to 0,"visibility" to 0,
            "wind" to mapOf("speed" to 0),"life_index" to mapOf("ultraviolet" to mapOf("index" to "0")),
            "air_quality" to (listOf("pm25","pm10","so2","no2","o3","co").associateWith { 0 }+mapOf("aqi" to mapOf("chn" to 0))))
        return gson.toJson(mapOf("status" to "ok","result" to mapOf("realtime" to realtime,"hourly" to hourly,"daily" to daily)))
    }
    private fun cyTransport(main: String=cyBody(), history: String=cyBody(48,-24), minutes: Int=120)=ApiProbeTransport { req ->
        ApiProbeReply(200,when {
            "begin=" in req.url -> history
            "/minutely?" in req.url -> gson.toJson(mapOf("status" to "ok","result" to mapOf("minutely" to mapOf("precipitation_2h" to List(minutes) { 0 })) ))
            "/daily?" in req.url -> cyBody()
            else -> main
        })
    }
    private fun row(r: ApiCapabilityReport,name: String)=r.rows.single { it.feature==name }
    private fun qTransport()=ApiProbeTransport { req ->
        fun pollutant()=listOf("pm2p5","pm10","so2","no2","o3","co").map { mapOf("code" to it,"concentration" to mapOf("value" to 0)) }
        val data: Any=when {
            "/weather/now?" in req.url -> mapOf("now" to mapOf("temp" to "0","icon" to "100","pressure" to "1010","windSpeed" to "0","humidity" to "0","vis" to "0"))
            "/weather/24h?" in req.url -> mapOf("hourly" to (0..23).map { mapOf("fxTime" to stamp(it),"temp" to "0","pop" to "0","windSpeed" to "0") })
            "/weather/v1/daily/" in req.url -> mapOf("days" to (0..6).map {
                mapOf("forecastStartTime" to "${LocalDate.parse("2026-10-21").plusDays(it.toLong())}T00:00:00+08:00","temperatureMax" to mapOf("value" to 1),"temperatureMin" to mapOf("value" to 0),
                    "daytime" to mapOf("condition" to mapOf("code" to "100"),"precipitation" to mapOf("probability" to 0)),"astro" to mapOf("sunrise" to "06:00","sunset" to "18:00")) })
            "/airquality/v1/hourly/" in req.url -> mapOf("hours" to (0..23).map { mapOf("forecastTime" to stamp(it),"indexes" to listOf(mapOf("code" to "cn-mee","aqi" to 0)),"pollutants" to pollutant()) })
            "/airquality/v1/current/" in req.url -> mapOf("indexes" to listOf(mapOf("code" to "cn-mee","aqi" to 0)),"pollutants" to pollutant())
            "/minutely/" in req.url -> mapOf("minutely" to (0..23).map { mapOf("fxTime" to Instant.ofEpochMilli(now+it*300_000L).toString(),"precip" to "0") })
            "/indices/" in req.url -> mapOf("daily" to listOf(mapOf("type" to "5","name" to "紫外线指数","level" to "1")))
            else -> mapOf("metadata" to mapOf("zeroResult" to true))
        }
        ApiProbeReply(200,gson.toJson(data))
    }
    @Test fun zeroValuesAreValidAndFullHourlyCoverageIsMeasured() {
        val r=ApiCapabilityProbe(cyTransport(),now).run(cy())
        assertEquals(4,r.requests)
        listOf("首页实况","六项实时指标","小时气温 / 48小时页","小时紫外线","分钟级降水卡","历史24小时 · 气温","小时污染物 · pm25").forEach { assertEquals(it,ApiSupport.SUPPORTED,row(r,it).support) }
    }
    @Test fun missingUvDoesNotBecomeAClaimOfPermissionDenial() {
        val r=ApiCapabilityProbe(cyTransport(main=cyBody(uv=false)),now).run(cy())
        assertEquals(ApiSupport.MISSING,row(r,"小时紫外线").support)
        assertEquals(ApiSupport.SUPPORTED,row(r,"小时气温 / 48小时页").support)
    }
    @Test fun shortForecastReportsPartialHoursAndDaysRatherThanAssumingFortyEight() {
        val r=ApiCapabilityProbe(cyTransport(main=cyBody(24)),now).run(cy())
        assertEquals(ApiSupport.PARTIAL,row(r,"小时气温 / 48小时页").support)
        assertTrue(row(r,"小时气温 / 48小时页").detail.contains("24/48"))
        assertEquals(ApiSupport.PARTIAL,row(r,"多日预报（App 请求15日）").support)
        assertEquals(ApiSupport.PARTIAL,row(r,"多日页逐小时范围").support)
        assertTrue(row(r,"多日预报（App 请求15日）").detail.contains("7/15"))
    }
    @Test fun forecastEchoedForBeginIsNotAcceptedAsHistoricalWeather() {
        val r=ApiCapabilityProbe(cyTransport(history=cyBody()),now).run(cy())
        assertEquals(ApiSupport.MISSING,row(r,"历史24小时 · 气温").support)
    }
    @Test fun malformedNumbersDatesAndDuplicateTimesDoNotInflateCoverage() {
        val body="""{"result":{"hourly":{"temperature":[{"datetime":"${stamp(0)}","value":0},{"datetime":"${stamp(0)}","value":1},{"datetime":"bad","value":2},{"datetime":"${stamp(1)}","value":"NaN"}]}}}"""
        val r=ApiCapabilityProbe(cyTransport(main=body),now).run(cy())
        assertTrue(row(r,"小时气温 / 48小时页").detail.contains("1/48"))
    }
    @Test fun authenticationFailuresPauseFurtherCallsAndNeverEchoSecrets() {
        val seen=mutableListOf<ApiProbeRequest>()
        val r=ApiCapabilityProbe(ApiProbeTransport { req -> seen+=req;ApiProbeReply(401,"""{"error":{"detail":"private_dummy_token"}}""") },now).run(cy())
        assertEquals(1,seen.size)
        assertEquals(ApiSupport.RESTRICTED,row(r,"首页实况").support)
        assertFalse(r.toString().contains("private_dummy_token"))
        assertFalse(seen.first().toString().contains("private_dummy_token"))
    }
    @Test fun businessAuthenticationCodeIsAlsoRecognized() {
        val r=ApiCapabilityProbe(ApiProbeTransport { ApiProbeReply(200,"""{"code":"401"}""") },now).run(q())
        assertEquals(1,r.requests);assertEquals(ApiSupport.RESTRICTED,row(r,"首页实况").support)
    }
    @Test fun forbiddenDoesNotBecomePermanentUnsupported() {
        val r=ApiCapabilityProbe(ApiProbeTransport { ApiProbeReply(403,"""{"error":{"type":"https://dev.qweather.com/docs/resource/error-code/#forbidden"}}""") },now).run(q())
        assertEquals(ApiSupport.RESTRICTED,row(r,"首页实况").support)
        assertFalse(r.rows.any { it.detail.contains("永久不支持") })
    }
    @Test fun rateLimitStopsRepeatedProbes() {
        val r=ApiCapabilityProbe(ApiProbeTransport { ApiProbeReply(429) },now).run(cy())
        assertEquals(1,r.requests)
        assertEquals(ApiSupport.FAILED,row(r,"分钟级降水卡").support)
    }
    @Test fun timeoutAndHtmlDoNotMasqueradeAsMissingFields() {
        listOf(ApiProbeReply(0,transportFailed=true),ApiProbeReply(200,"<html>temporary error</html>")).forEach { reply ->
            val r=ApiCapabilityProbe(ApiProbeTransport { reply },now).run(cy())
            assertEquals(ApiSupport.FAILED,row(r,"首页实况").support)
        }
    }
    @Test fun qweatherUsesAppRoutesAndNativeAirSchemaWithoutFallback() {
        val seen=mutableListOf<ApiProbeRequest>();val fake=qTransport()
        val r=ApiCapabilityProbe(ApiProbeTransport { seen+=it;fake.get(it) },now).run(q())
        assertEquals(9,r.requests)
        assertTrue(seen.all { it.headers==mapOf("X-QW-Api-Key" to "private_dummy_key") && it.url.startsWith("https://test.qweatherapi.com/") })
        assertEquals(ApiSupport.SUPPORTED,row(r,"小时 AQI（目标24小时）").support)
        assertEquals(ApiSupport.SUPPORTED,row(r,"六项污染物").support)
        assertEquals(ApiSupport.SUPPORTED,row(r,"小时污染物 · pm25").support)
        assertEquals(ApiSupport.PARTIAL,row(r,"小时气温 / 48小时页").support)
        assertTrue(row(r,"多日页逐小时范围").detail.contains("24/168"))
        assertEquals(ApiSupport.MISSING,row(r,"小时体感 / 48小时页").support)
        assertEquals(ApiSupport.SUPPORTED,row(r,"预警卡与通知数据").support)
        assertTrue(row(r,"预警卡与通知数据").detail.contains("无生效预警"))
        assertEquals(ApiSupport.LOCAL,row(r,"昨日/历史对比").support)
    }
    @Test fun versionAndHistoricalParametersMatchCurrentAppAndNeverLeakIntoReport() {
        val seen=mutableListOf<ApiProbeRequest>();val fake=cyTransport()
        val r=ApiCapabilityProbe(ApiProbeTransport { seen+=it;fake.get(it) },now).run(cy("2.7"))
        assertTrue(seen.first().url.contains("/v2.7/"));assertTrue(seen.first().url.contains("hourlysteps=360"))
        assertTrue(seen.drop(1).all { "/v2.6/" in it.url })
        assertTrue(seen.last().url.contains("begin=${(now-24*3_600_000L)/1000}"))
        assertFalse(r.toString().contains("private_dummy_token"))
    }
    @Test fun invalidHostIsRejectedBeforeAnyTransportCall() {
        var calls=0
        try { ApiCapabilityProbe(ApiProbeTransport { calls++;ApiProbeReply(200,"{}") },now).run(q().copy(qweatherHost="evil.example"));fail("Invalid host") }
        catch (_: IllegalArgumentException) { assertEquals(0,calls) }
    }
    @Test fun cancellationPreventsFollowingRequests() {
        lateinit var p: ApiCapabilityProbe;var calls=0
        p=ApiCapabilityProbe(ApiProbeTransport { calls++;p.cancel();ApiProbeReply(200,cyBody()) },now)
        try { p.run(cy());fail("Cancelled probe must not deliver a report") } catch (_: CancellationException) { assertEquals(1,calls) }
    }
    @Test fun optionalProvidersAreExplicitAndNeverUseBuiltIns() {
        val r=ApiCapabilityProbe(cyTransport(),now).run(cy())
        assertTrue(r.rows.any { it.provider=="和风" && it.support==ApiSupport.UNCONFIGURED })
        assertEquals(ApiSupport.UNCONFIGURED,row(r,"城市在线搜索").support)
    }
    @Test fun allConfiguredProvidersStayWithinFifteenRequests() {
        val fakeCy=cyTransport();val fakeQ=qTransport()
        val r=ApiCapabilityProbe(ApiProbeTransport { req -> when(req.provider) {
            "和风" -> fakeQ.get(req);"高德" -> ApiProbeReply(200,"""{"status":"1"}""");else -> fakeCy.get(req)
        } },now).run(cy().copy(qweatherHost=q().qweatherHost,qweatherApiKey=q().qweatherApiKey,amapKey="dummy"))
        assertEquals(15,r.requests)
        assertEquals(ApiSupport.SUPPORTED,row(r,"城市输入提示").support)
    }
}
