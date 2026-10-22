package com.example.weatherapp

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

enum class ApiSupport(val label: String) {
    SUPPORTED("可用"), PARTIAL("部分可用"), MISSING("暂不支持"), RESTRICTED("需要处理"),
    FAILED("检测失败"), UNCONFIGURED("未填写"), LOCAL("独立功能")
}
data class ApiCapabilityRow(val provider: String, val feature: String, val support: ApiSupport, val detail: String)
data class ApiCapabilityReport(val rows: List<ApiCapabilityRow>, val checkedAt: Long, val requests: Int)

/** Never expose the URL, headers, response body or exception message in a report/log. */
class ApiProbeRequest(val provider: String, val name: String, val url: String, val headers: Map<String,String> = emptyMap()) {
    override fun toString() = "$provider / $name (凭证已隐藏)"
}
class ApiProbeReply(val code: Int, val body: String = "", val transportFailed: Boolean = false)
fun interface ApiProbeTransport { fun get(request: ApiProbeRequest): ApiProbeReply }

/** One bounded attempt per current App route. No redirects, automatic retries, caches or fallback data. */
class ApiCapabilityProbe(private val transport: ApiProbeTransport? = null, private val now: Long = System.currentTimeMillis()) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(5,TimeUnit.SECONDS)
        .readTimeout(7,TimeUnit.SECONDS).callTimeout(8,TimeUnit.SECONDS).build()
    @Volatile private var cancelled = false
    @Volatile private var activeCall: Call? = null
    private val started = System.nanoTime()
    private var requests = 0
    private val rows = mutableListOf<ApiCapabilityRow>()
    private val stopped = mutableMapOf<String,String>()
    private val hour = 3_600_000L
    private val currentHour = now / hour * hour
    private val china = ZoneOffset.ofHours(8)
    private val today = Instant.ofEpochMilli(now).atOffset(china).toLocalDate()
    private val dateFormat = DateTimeFormatter.ofPattern("MM-dd HH时").withZone(china)
    fun cancel() { cancelled = true; activeCall?.cancel() }
    private fun checkCancellation() { if(cancelled || Thread.currentThread().isInterrupted) throw CancellationException() }
    private data class Result(val root: JsonObject? = null, val failure: ApiSupport? = null, val reason: String = "")

    fun run(config: UserApiConfiguration.Config, progress: (Int,String)->Unit = {_,_->}): ApiCapabilityReport {
        require(UserApiConfiguration.validate(config) == null) { "接口配置无效" }
        if(config.caiyunToken.isBlank()) unconfigured("彩云", "未填写 Token，彩云实况/预报/历史能力未检测")
        else caiyun(config,progress)
        if(!config.hasQWeather()) unconfigured("和风", "Host 或 API Key 未填全；生活建议和预警也需要和风配置")
        else qweather(config,progress)
        if(config.amapKey.isBlank()) add("高德","城市在线搜索",ApiSupport.UNCONFIGURED,"可选项未填写；仍可使用本地城市表")
        else amap(config,progress)
        add("App 说明","晨昏与霞光时段",ApiSupport.LOCAL,"以日出日落和坐标本地推算，不是 API 单独返回的预测")
        add("App 说明","全国空气排名",ApiSupport.LOCAL,"使用独立官方排名源；不由彩云/和风密钥权限决定")
        checkCancellation()
        return ApiCapabilityReport(rows.toList(),now,requests)
    }
    private fun unconfigured(provider: String, detail: String) = add(provider,"所有接口能力",ApiSupport.UNCONFIGURED,detail)
    private fun add(provider: String, feature: String, state: ApiSupport, detail: String) { rows += ApiCapabilityRow(provider,feature,state,detail) }
    private fun request(req: ApiProbeRequest, progress: (Int,String)->Unit): Result {
        checkCancellation()
        stopped[req.provider]?.let { return Result(failure=ApiSupport.FAILED,reason="已暂停后续请求：$it") }
        if(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-started)>=50) return Result(failure=ApiSupport.FAILED,reason="检测时间上限已到，未继续请求；可稍后重试")
        requests++; progress(requests, "${req.provider} · ${req.name}")
        val reply = try { transport?.get(req) ?: network(req) } catch (_: Exception) {
            checkCancellation(); ApiProbeReply(0,transportFailed=true)
        }
        checkCancellation()
        if(reply.transportFailed || reply.code==0) return Result(failure=ApiSupport.FAILED,reason="网络/超时或响应过大，不能判断接口权限")
        val root = runCatching { JsonParser.parseString(reply.body).takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()
        val bodyCode = root.str("code").toIntOrNull() ?: root.str("error.status").toIntOrNull()
        val code = if(reply.code in 200..299 && bodyCode != null && bodyCode != 200) bodyCode else reply.code
        if(code !in 200..299) {
            val type=root.str("error.type").substringAfterLast('/').substringAfterLast('#').lowercase()
            val reason=when(code) {
                401 -> "认证失败，请核对密钥与 Host"
                402 -> "额度或套餐限制，请查看控制台"
                403 -> when(type) {
                    "invalid-host" -> "API Host 不匹配，请核对控制台"
                    "no-credit","overdue" -> "可用额度不足或账单受限"
                    "security-restriction" -> "访问限制未通过，请检查控制台"
                    "deprecated" -> "当前 App 路径已被服务方停用"
                    else -> "请求被拒绝；可能是权限、额度、Host 或访问限制"
                }
                404 -> "当前 App 路径未找到，不代表密钥所有接口均不支持"
                429 -> "请求频率/额度受限，已停止继续探测"
                400,204 -> "该测试地点/参数当前未返回数据"
                else -> "服务请求失败，不能判断是否支持"
            }
            if(code==401 || code==429 || type in listOf("invalid-host","no-credit","overdue","security-restriction","account-suspension")) stopped[req.provider]=reason
            return Result(failure=if(code in listOf(401,402,403,429)) ApiSupport.RESTRICTED else ApiSupport.FAILED,reason="HTTP/业务码 $code · $reason")
        }
        if(root == null) return Result(failure=ApiSupport.FAILED,reason="响应格式异常，未确认支持情况")
        if(root.str("status") in listOf("failed","error")) return Result(failure=ApiSupport.FAILED,reason="接口报告失败；请核对版本、权限与凭证，未确认支持情况")
        return Result(root)
    }
    private fun network(req: ApiProbeRequest): ApiProbeReply {
        val request=Request.Builder().url(req.url).apply { req.headers.forEach { (k,v)->header(k,v) } }.build()
        val call=client.newCall(request);activeCall=call
        try {
            checkCancellation()
            return call.execute().use { r ->
                val bytes=r.body()?.byteStream()?.use { it.readNBytesCompat(2_097_153) } ?: byteArrayOf()
                if(bytes.size>2_097_152) ApiProbeReply(0,transportFailed=true) else ApiProbeReply(r.code(),String(bytes,Charsets.UTF_8))
            }
        } finally { activeCall=null }
    }
    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(out.size()<limit) { val n=read(buffer,0,minOf(buffer.size,limit-out.size()));if(n<0) break;out.write(buffer,0,n) }
        return out.toByteArray()
    }
    private inline fun result(provider: String, feature: String, r: Result, block: (JsonObject)->Unit) {
        if(r.failure!=null) add(provider,feature,r.failure,r.reason)
        else block(r.root!!)
    }
    private fun fields(provider: String, feature: String, r: Result, names: List<Pair<String,Boolean>>, note: String="") = result(provider,feature,r) {
        val present=names.filter { it.second }.map { it.first };val missing=names.filterNot { it.second }.map { it.first }
        val state=when { present.isEmpty()->ApiSupport.MISSING;missing.isEmpty()->ApiSupport.SUPPORTED;else->ApiSupport.PARTIAL }
        add(provider,feature,state,listOf("${present.size}/${names.size}项",if(present.isNotEmpty()) "有：${present.joinToString("、")}" else "本次未返回有效字段",
            if(missing.isNotEmpty()) "缺：${missing.joinToString("、")}" else "",note).filter { it.isNotBlank() }.joinToString("；"))
    }
    private fun series(provider: String, feature: String, r: Result, values: List<JsonObject>, timeKey: String, target: Int=48,
                       history: Boolean=false, valid: (JsonObject)->Boolean) = result(provider,feature,r) {
        val times=values.filter(valid).mapNotNull { time(it.str(timeKey)) }.map { it/hour*hour }.distinct().sorted()
        val start=if(history) currentHour-24*hour else if(times.contains(currentHour)) currentHour else currentHour+hour
        val counted=times.filter { it>=start && it<start+target*hour }
        val all=times.filter { if(history) it<currentHour && it>=currentHour-24*hour else it>=currentHour }
        val state=when { counted.isEmpty()->ApiSupport.MISSING;counted.size>=target->ApiSupport.SUPPORTED;else->ApiSupport.PARTIAL }
        add(provider,feature,state,"${counted.size}/$target 小时；本次有效 ${all.size} 个小时点"+
            if(all.isEmpty()) "（本次未返回，不等于永久不支持）" else "\n${dateFormat.format(Instant.ofEpochMilli(all.first()))}—${dateFormat.format(Instant.ofEpochMilli(all.last()))}")
    }
    private fun caiyun(c: UserApiConfiguration.Config, progress: (Int,String)->Unit) {
        val p="彩云 ${c.caiyunVersion}"
        val base="https://api.caiyunapp.com"
        val route="$base/v${c.caiyunVersion}/${c.caiyunToken}/116.4,39.9/weather"
        val query=if(c.caiyunVersion=="2.6") "alert=true&dailysteps=15&hourlysteps=360&lang=zh_CN" else "alert=false&span=15&dailystart=0&hourlysteps=360&lang=zh_CN&version=7.59.0"
        val main=request(ApiProbeRequest(p,"综合天气", "$route?$query"),progress)
        val nowRoot=main.root.obj("result.realtime")
        fields(p,"首页实况",main,listOf("气温" to nowRoot.number("temperature"),"天气" to nowRoot.str("skycon").isNotBlank()))
        val metrics=listOf("体感" to "apparent_temperature","湿度" to "humidity","气压" to "pressure","能见度" to "visibility","风速" to "wind.speed","紫外线" to "life_index.ultraviolet.index")
        fields(p,"六项实时指标",main,metrics.map { (name,key)->name to nowRoot.number(key) })
        series(p,"小时气温 / 48小时页",main,main.root.objects("result.hourly.temperature"),"datetime") { it.number("value") }
        series(p,"多日页逐小时范围",main,main.root.objects("result.hourly.temperature"),"datetime",360) { it.number("value") }
        series(p,"小时天气",main,main.root.objects("result.hourly.skycon"),"datetime") { it.str("value").isNotBlank() }
        (metrics.filter { it.first!="风速" && it.first!="紫外线" }.map { Triple(it.first,it.second,"value") }+
            listOf(Triple("风速","wind","speed"),Triple("紫外线","life_index.ultraviolet","index"))).forEach { (name,key,value) ->
            series(p,"小时$name",main,main.root.objects("result.hourly.$key"),"datetime") { it.number(value) }
        }
        series(p,"小时降水概率",main,main.root.objects("result.hourly.precipitation"),"datetime") { it.number("probability") }
        val air=nowRoot.obj("air_quality")
        fields(p,"实时空气质量",main,listOf("中国 AQI" to (air.number("aqi.chn") || nowRoot.number("aqi.chn"))))
        fields(p,"六项污染物",main,pollutants.map { it to air.number(it) })
        series(p,"小时 AQI",main,main.root.objects("result.hourly.air_quality.aqi"),"datetime") { it.number("value.chn") }
        pollutants.forEach { key -> series(p,"小时污染物 · $key",main,main.root.objects("result.hourly.air_quality.$key"),"datetime") { it.number("value") } }
        days(p,"多日预报（App 请求15日）",main,main.root.objects("result.daily.temperature"),"date",15) { it.number("min") && it.number("max") }
        days(p,"逐日天气",main,main.root.objects("result.daily.skycon"),"date",15) { it.str("value").isNotBlank() }
        days(p,"逐日降水概率",main,main.root.objects("result.daily.precipitation"),"date",15) { it.number("probability") }
        result(p,"彩云原生生活指数",main) { root ->
            val keys=listOf("ultraviolet","comfort","carWashing","coldRisk","dressing").filter { root.objects("result.daily.life_index.$it").any { row->row.str("index").isNotBlank() } }
            add(p,"彩云原生生活指数",if(keys.isEmpty()) ApiSupport.MISSING else ApiSupport.PARTIAL,"返回 ${keys.size} 类；App 生活建议仍使用和风，不能据此视为已覆盖生活建议页")
        }
        val minute=request(ApiProbeRequest(p,"2.6 分钟降水","$base/v2.6/${c.caiyunToken}/116.4,39.9/minutely?unit=metric:v2&lang=zh_CN"),progress)
        result(p,"分钟级降水卡",minute) { root ->
            val data=root.array("result.minutely.precipitation_2h").ifEmpty { root.array("result.minutely.precipitation") }.count { finite(it) }
            add(p,"分钟级降水卡",when {data==0->ApiSupport.MISSING;data<120->ApiSupport.PARTIAL;else->ApiSupport.SUPPORTED},"本次 $data 个逐分钟点，目标120分钟；0降水值同样算有效数据")
        }
        val astro=request(ApiProbeRequest(p,"2.6 日出日落","$base/v2.6/${c.caiyunToken}/116.4,39.9/daily?dailystart=-1&dailysteps=3&lang=zh_CN"),progress)
        days(p,"日出日落（2.6）",astro,astro.root.objects("result.daily.astro"),"date",2) { it.str("sunrise.time").isNotBlank() && it.str("sunset.time").isNotBlank() }
        val begin=(currentHour-24*hour)/1000
        val history=request(ApiProbeRequest(p,"2.6 过去一天","$base/v2.6/${c.caiyunToken}/116.4,39.9/weather?begin=$begin&alert=false&dailysteps=2&hourlysteps=48&lang=zh_CN"),progress)
        (listOf(Triple("气温","temperature","value")) + metrics.filter { it.first!="风速" && it.first!="紫外线" }.map { Triple(it.first,it.second,"value") }+
            listOf(Triple("风速","wind","speed"),Triple("紫外线","life_index.ultraviolet","index"),Triple("AQI","air_quality.aqi","value.chn"))).forEach { (name,key,value)->
            series(p,"历史24小时 · $name",history,history.root.objects("result.hourly.$key"),"datetime",24,true) { it.number(value) }
        }
    }
    private fun qweather(c: UserApiConfiguration.Config, progress: (Int,String)->Unit) {
        val p="和风"
        val base="https://"+c.qweatherHost.removePrefix("https://").trimEnd('/')
        val headers=mapOf("X-QW-Api-Key" to c.qweatherApiKey)
        fun get(name: String, path: String)=request(ApiProbeRequest(p,name,base+path,headers),progress)
        val n=get("实况","/v7/weather/now?location=116.40,39.90&lang=zh&unit=m")
        val nr=n.root.obj("now")
        fields(p,"首页实况",n,listOf("气温" to nr.number("temp"),"天气" to nr.str("icon").isNotBlank(),"气压" to nr.number("pressure")))
        fields(p,"实时指标（紫外线另检）",n,listOf("体感" to nr.number("feelsLike"),"风速" to nr.number("windSpeed"),"湿度" to nr.number("humidity"),"能见度" to nr.number("vis"),"气压" to nr.number("pressure")))
        val h=get("小时预报","/v7/weather/24h?location=116.40,39.90&lang=zh&unit=m")
        val hours=h.root.objects("hourly")
        series(p,"多日页逐小时范围",h,hours,"fxTime",168) { it.number("temp") }
        listOf("气温" to "temp","体感" to "feelsLike","湿度" to "humidity","气压" to "pressure","能见度" to "vis","风速" to "windSpeed","紫外线" to "uvIndex","降水概率" to "pop").forEach { (name,key)->
            series(p,"小时$name / 48小时页",h,hours,"fxTime") { it.number(key) }
        }
        val d=get("多日预报与天文","/weather/v1/daily/39.90/116.40?days=7&lang=zh&localTime=true")
        val dayRows=d.root.objects("days").map { raw -> raw.deepCopy().apply { addProperty("probeDate",raw.str("forecastStartTime").ifBlank { raw.str("forecastEndTime") }) } }
        days(p,"多日预报（当前请求7日）",d,dayRows,"probeDate",7) { it.number("temperatureMax.value") && it.number("temperatureMin.value") }
        days(p,"逐日天气",d,dayRows,"probeDate",7) { it.str("daytime.condition.code").isNotBlank() }
        days(p,"逐日降水概率",d,dayRows,"probeDate",7) { it.number("daytime.precipitation.probability") }
        days(p,"日出日落",d,dayRows,"probeDate",7) { it.str("astro.sunrise").isNotBlank() && it.str("astro.sunset").isNotBlank() }
        val a=get("当前空气质量","/airquality/v1/current/39.90/116.40?lang=zh")
        fields(p,"实时空气质量",a,listOf("AQI" to a.root.objects("indexes").any { it.number("aqi") }))
        fields(p,"六项污染物",a,pollutants.map { it to hasPollutant(a.root,it) })
        val ah=get("小时空气质量","/airquality/v1/hourly/39.90/116.40?lang=zh")
        val airHours=ah.root.objects("hours").ifEmpty { ah.root.objects("hourly") }.map { raw ->
            raw.deepCopy().apply { addProperty("probeTime",raw.str("forecastTime").ifBlank { raw.str("fxTime").ifBlank { raw.str("time") } }) }
        }
        series(p,"小时 AQI（目标24小时）",ah,airHours,"probeTime",24) { it.number("aqi") || it.objects("indexes").any { index-> index.str("code") in listOf("cn-mee","cn-mee-1h","qaqi") && index.number("aqi") } }
        pollutants.forEach { key -> series(p,"小时污染物 · $key",ah,airHours,"probeTime",24) { hasPollutant(it,key) } }
        val m=get("分钟降水","/v7/minutely/5m?location=116.40,39.90&lang=zh")
        result(p,"分钟级降水卡",m) { root ->
            val count=root.objects("minutely").filter { it.number("precip") && time(it.str("fxTime"))!=null }.map { it.str("fxTime") }.distinct().size
            add(p,"分钟级降水卡",when {count==0->ApiSupport.MISSING;count<24->ApiSupport.PARTIAL;else->ApiSupport.SUPPORTED},"本次 $count 个5分钟点（约${count*5}分钟）；0降水值算有效")
        }
        val uv=get("当日紫外线","/v7/indices/1d?type=5&location=116.40,39.90&lang=zh")
        fields(p,"当日紫外线指数",uv,listOf("级别" to uv.root.objects("daily").any { it.str("level").isNotBlank() }))
        val life=get("全部生活建议","/v7/indices/1d?type=0&location=116.40,39.90&lang=zh")
        result(p,"生活建议页",life) { root ->
            val count=root.objects("daily").filter { it.str("type").isNotBlank() && it.str("name").isNotBlank() }.map { it.str("type") }.distinct().size
            add(p,"生活建议页",if(count==0) ApiSupport.MISSING else ApiSupport.SUPPORTED,"本次返回 $count 类指数；以实际返回项目展示，不凭套餐名称推断")
        }
        val alert=get("当前灾害预警","/weatheralert/v1/current/39.90/116.40?lang=zh&localTime=true")
        result(p,"预警卡与通知数据",alert) { root ->
            val zero=root.v("metadata.zeroResult")?.let { runCatching { it.asBoolean }.getOrDefault(false) } == true
            val returned=root.v("alerts")?.isJsonArray==true
            add(p,"预警卡与通知数据",if(zero||returned) ApiSupport.SUPPORTED else ApiSupport.MISSING,
                if(zero || returned && root.array("alerts").isEmpty()) "接口可用，当前无生效预警（不是不支持）；不代表系统通知必达" else if(returned) "返回${root.array("alerts").size}条；通知还依赖本机权限/后台调度" else "本次未返回预警结构")
        }
        add(p,"昨日/历史对比",ApiSupport.LOCAL,"App 尚未调用和风原生历史路径；目前依赖同源快照缓存或明确的补充源。本轮未探测未接入的时光机权限")
    }
    private fun amap(c: UserApiConfiguration.Config, progress: (Int,String)->Unit) {
        listOf("城市输入提示" to "assistant/inputtips?keywords=%E5%8C%97%E4%BA%AC", "行政区中心点" to "config/district?keywords=%E5%8C%97%E4%BA%AC&subdistrict=0").forEach { (name,path)->
            val r=request(ApiProbeRequest("高德",name,"https://restapi.amap.com/v3/$path&key=${c.amapKey}"),progress)
            result("高德",name,r) { root ->
                add("高德",name,if(root.str("status")=="1") ApiSupport.SUPPORTED else ApiSupport.RESTRICTED,
                    if(root.str("status")=="1") "请求成功；高德仅用于城市检索，不提供本 App 天气数据" else "请求被拒绝，请核对 Web 服务 Key、额度及访问限制")
            }
        }
    }
    private fun days(p: String, feature: String, r: Result, data: List<JsonObject>, key: String, target: Int, valid: (JsonObject)->Boolean) = result(p,feature,r) {
        val dates=data.filter(valid).mapNotNull { runCatching { LocalDate.parse(it.str(key).take(10)) }.getOrNull() }.filter { !it.isBefore(today) }.distinct().sorted()
        add(p,feature,when {dates.isEmpty()->ApiSupport.MISSING;dates.size<target->ApiSupport.PARTIAL;else->ApiSupport.SUPPORTED},
            "本次 ${dates.size}/$target 日" + if(dates.isEmpty()) "，未返回有效数据" else "\n${dates.first()}—${dates.last()}")
    }
    private fun hasPollutant(root: JsonObject?, code: String)=root.objects("pollutants").any {
        val same=it.str("code").lowercase() in if(code=="pm25") listOf("pm25","pm2p5","pm2.5") else listOf(code)
        same && (it.number("concentration.value") || it.number("concentration") || it.number("value"))
    }
    private fun time(text: String): Long? = runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
    private fun JsonObject?.v(path: String): JsonElement? {
        var value: JsonElement=this ?: return null
        path.split('.').forEach { value=if(value.isJsonObject) value.asJsonObject.get(it) ?: return null else return null }
        return value.takeUnless { it.isJsonNull }
    }
    private fun JsonObject?.str(path: String): String = v(path)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    private fun JsonObject?.obj(path: String): JsonObject? = v(path)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject?.array(path: String): List<JsonElement> = v(path)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
    private fun JsonObject?.objects(path: String)=array(path).filter { it.isJsonObject }.map { it.asJsonObject }
    private fun JsonObject?.number(path: String)=finite(v(path))
    private fun finite(value: JsonElement?)=value?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull()?.isFinite()==true
    companion object { private val pollutants=listOf("pm25","pm10","so2","no2","o3","co") }
}
