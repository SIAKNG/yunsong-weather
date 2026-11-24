package com.example.weatherapp

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.core.view.doOnAttach
import okhttp3.Interceptor
import org.json.JSONObject
import java.util.UUID
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** App-owned signals only. No process-wide logcat, request payloads or displayed text. */
internal object WeatherDiagnosticMonitor : Application.ActivityLifecycleCallbacks {
    private lateinit var app: Application
    @Volatile private var recording = false
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val stopAtDeadline = Runnable {
        recording = false
        detach()
    }
    private var resumed: Activity? = null
    private var attached: Window? = null
    private var frameThread: HandlerThread? = null
    private var frameHandler: Handler? = null
    private var samples: FrameSamples? = null
    private var frameListener: Window.OnFrameMetricsAvailableListener? = null

    fun initialize(application: Application) {
        app = application
        sessionChanged(WeatherDiagnostics.enabled(app))
        WeatherDiagnostics.event(app, "记录", "应用进程启动")
        app.registerActivityLifecycleCallbacks(this)
        val delegate = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            try {
                WeatherDiagnostics.saveCrash(app, failure, thread === Looper.getMainLooper().thread)
            } catch (_: Throwable) {
                // Crash reporting must never prevent Android from handling the original exception.
            } finally {
                if (delegate != null) delegate.uncaughtException(thread, failure)
                else Process.killProcess(Process.myPid())
            }
        }
    }

    fun sessionChanged(enabled: Boolean) {
        if (!::app.isInitialized) return
        recording = enabled
        main.post {
            main.removeCallbacks(stopAtDeadline)
            val remaining = WeatherDiagnostics.sessionRemainingMs(app)
            if (recording && remaining > 0L) {
                main.postDelayed(stopAtDeadline, remaining)
                resumed?.let(::attach)
            } else {
                detach()
            }
        }
    }

    enum class Source(val label: String) {
        CAIYUN("彩云"), QWEATHER("和风"), OPEN_METEO("Open-Meteo"), GATEWAY("天气网关"), AIR_RANK("空气排名")
    }

    data class Request(
        val source: Source,
        val endpoint: String?,
        val session: Long,
        val id: String,
        val startedAt: Long
    ) {
        fun data(phase: String): JSONObject = JSONObject().put("phase", phase)
            .put("request_id", id)
            .put("source", source.name.lowercase(java.util.Locale.ROOT))
            .put("endpoint", endpoint ?: JSONObject.NULL)
    }

    fun requestStart(source: Source, endpoint: String? = null): Request? {
        if (!recording || !::app.isInitialized) return null
        val session = WeatherDiagnostics.activeSession(app)
        if (session == 0L) return null
        val request = Request(source, endpoint, session, UUID.randomUUID().toString(), SystemClock.elapsedRealtime())
        WeatherDiagnostics.event(app, "天气请求", "${source.label} · 请求开始", request.data("start"))
        return request
    }

    fun requestFinished(request: Request?, status: Int?, failure: IOException? = null) {
        if (request == null || request.session != WeatherDiagnostics.activeSession(app)) return
        val failureKind = when (failure) {
            is SocketTimeoutException -> "timeout"
            is UnknownHostException -> "dns"
            is SSLException -> "tls"
            is InterruptedIOException -> "interrupted"
            null -> null
            else -> "io"
        }
        val details = request.data("finish").put("duration_ms", SystemClock.elapsedRealtime() - request.startedAt)
            .put("http_status", status ?: JSONObject.NULL).put("failure", failureKind ?: JSONObject.NULL)
        WeatherDiagnostics.event(app, "天气请求", "${request.source.label} · 请求结束", details)
    }

    fun network(source: Source): Interceptor = Interceptor { chain ->
        val request = chain.request()
        val endpoint = if (!recording) null else when (request.url().pathSegments().lastOrNull()) {
            "weather", "forecast", "bundle" -> "forecast"
            "air-quality" -> "air_quality"
            "minutely" -> "minutely"
            "daily" -> "daily"
            "astronomy" -> "astronomy"
            else -> null
        }
        val tracked = requestStart(source, endpoint)
        try {
            chain.proceed(request).also { requestFinished(tracked, it.code()) }
        } catch (failure: IOException) {
            requestFinished(tracked, null, failure)
            throw failure
        }
    }

    private fun page(activity: Activity): String = when (activity) {
        is MainActivity -> "天气首页"
        is WeatherSettingsActivity -> "天气设置"
        is HourlyForecastDetailActivity -> "48 小时预报"
        is ForecastDetailActivity -> "多日预报"
        is MetricDetailActivity -> "气象指标"
        is AirQualityDetailActivity -> "空气质量"
        is SunDetailActivity -> "日出日落"
        is LifeAdviceActivity -> "生活建议"
        is AlertActivity -> "气象预警"
        is CityManageActivity -> "城市管理"
        is CityPreviewActivity -> "城市预览"
        is SearchActivity -> "城市搜索"
        is WeatherWidgetActivity -> "桌面小组件设置"
        is WeatherAboutActivity -> "关于"
        is ApiConfigurationActivity -> "天气数据配置"
        is ApiTutorialActivity -> "API 使用教程"
        else -> "应用页面"
    }

    override fun onActivityResumed(activity: Activity) {
        resumed = activity
        if (WeatherDiagnostics.enabled(app)) {
            WeatherDiagnostics.event(app, "界面", "进入 ${page(activity)}")
            attach(activity)
        } else {
            detach()
        }
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed === activity) {
            resumed = null
            detach()
            WeatherDiagnostics.event(app, "界面", "离开 ${page(activity)}")
        }
    }

    private fun attach(activity: Activity) {
        if (attached === activity.window) return
        detach()
        if (!WeatherDiagnostics.enabled(app)) return
        val decor = activity.window.decorView
        if (!decor.isAttachedToWindow) {
            decor.doOnAttach { if (resumed === activity) attach(activity) }
            return
        }
        if (!decor.isHardwareAccelerated) return
        @Suppress("DEPRECATION")
        val refreshRate = activity.windowManager.defaultDisplay.refreshRate.takeIf { it > 0f } ?: 60f
        val accumulator = FrameSamples(page(activity), (1_000_000_000.0 / refreshRate).toLong())
        val dispatcher = frameHandler ?: HandlerThread("weather-diagnostic-frames", Process.THREAD_PRIORITY_BACKGROUND).let {
            it.start()
            frameThread = it
            Handler(it.looper).also { handler -> frameHandler = handler }
        }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, missed ->
            val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            val deadline = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else -1L
            accumulator.add(duration, deadline, missed)
        }
        runCatching {
            activity.window.addOnFrameMetricsAvailableListener(listener, dispatcher)
            attached = activity.window
            frameListener = listener
            samples = accumulator
        }
    }

    private fun detach() {
        val window = attached
        frameListener?.let { listener -> runCatching { window?.removeOnFrameMetricsAvailableListener(listener) } }
        frameListener = null
        attached = null
        samples?.finish()
        samples = null
        frameThread?.quitSafely()
        frameThread = null
        frameHandler = null
    }

    fun flushCurrentWindow() {
        if (Looper.myLooper() == Looper.getMainLooper()) samples?.flush()
    }

    private class FrameSamples(private val page: String, private val budget: Long) {
        private var frames = 0
        private var slow = 0
        private var longest = 0L
        private var missedReports = 0
        private var windowStart = SystemClock.elapsedRealtime()
        private var closed = false

        @Synchronized fun add(duration: Long, deadline: Long, missed: Int) {
            if (closed || duration < 0L) return
            frames++
            if (duration > (if (deadline > 0L) deadline else budget)) slow++
            longest = maxOf(longest, duration)
            missedReports += missed
            if (SystemClock.elapsedRealtime() - windowStart >= 5_000L) flush()
        }

        @Synchronized fun flush() {
            if (frames == 0) return
            val now = SystemClock.elapsedRealtime()
            val details = JSONObject().put("page", page).put("sample_count", frames)
                .put("over_budget", slow).put("longest_frame_ms", longest / 1_000_000.0)
                .put("missing_samples", missedReports).put("window_ms", now - windowStart)
            WeatherDiagnostics.event(app, "流畅度", "$page · 帧耗时统计", details)
            windowStart = SystemClock.elapsedRealtime()
            frames = 0
            slow = 0
            longest = 0L
            missedReports = 0
        }

        @Synchronized fun finish() {
            closed = true
            flush()
        }
    }

    fun recentSystemExits(context: android.content.Context, after: Long): List<Pair<Long, String>> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        return runCatching {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 12)
                .asSequence()
                .filter { it.timestamp > after }
                .mapNotNull {
                    val reason = when (it.reason) {
                        ApplicationExitInfo.REASON_CRASH -> "未捕获异常"
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生组件崩溃"
                        ApplicationExitInfo.REASON_ANR -> "应用无响应（ANR）"
                        else -> null
                    }
                    reason?.let { reason -> it.timestamp to reason }
                }.take(5).toList()
        }.getOrDefault(emptyList())
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
