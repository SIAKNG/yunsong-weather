package com.example.weatherapp

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import android.widget.CompoundButton
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

// 天气设置页：数据源、深浅色模式、温度/风力/气压/能见度单位、关于天气。
// 每个分区是一行“标题 + 当前值 + 箭头”，点击后弹出悬浮小面板进行选择（参考 shezhi 效果）；
// 主题色跟随手机默认的动态主题色（Material You）。
class WeatherSettingsActivity : WeatherActivity() {
    companion object {
        internal const val RESULT_RETRY_LOCATION = RESULT_FIRST_USER
    }

    private val saveProblemDiagnostics = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            val result = runCatching {
                requireNotNull(contentResolver.openOutputStream(uri)).use { output ->
                    output.write(WeatherDiagnostics.report(this).toByteArray(Charsets.UTF_8))
                }
            }
            Toast.makeText(this, if (result.isSuccess) "诊断报告已保存" else "诊断报告保存失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showProblemDiagnostics() {
        val recording = WeatherDiagnostics.enabled(this)
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += (if (recording) "停止记录" else "开始记录") to {
            WeatherDiagnostics.setEnabled(this, !recording)
            showProblemDiagnostics()
        }
        if (recording) actions += "标记问题发生" to {
            WeatherDiagnostics.record(this, WeatherDiagnostics.Event.USER_MARK)
            Toast.makeText(this, "已标记问题时间", Toast.LENGTH_SHORT).show()
        }
        actions += "重试系统定位" to {
            setResult(RESULT_RETRY_LOCATION)
            finish()
        }
        actions += "查看记录（${WeatherDiagnostics.entries(this).size} 条）" to {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("诊断记录")
                .setMessage(WeatherDiagnostics.report(this))
                .setPositiveButton("返回") { _, _ -> showProblemDiagnostics() }
                .show()
        }
        actions += "保存 TXT 报告" to { exportProblemDiagnostics() }
        actions += "分享 TXT 报告" to { shareProblemDiagnostics() }
        actions += "清除记录" to {
            WeatherDiagnostics.clear(this)
            showProblemDiagnostics()
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(if (recording) "问题诊断 · 正在记录" else "问题诊断与定位")
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun shareProblemDiagnostics() {
        val appContext = applicationContext
        updateExecutor.execute {
            val report = runCatching { WeatherDiagnostics.shareIntent(appContext) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val intent = report.getOrNull()
                if (intent == null) {
                    Toast.makeText(this, "诊断报告生成失败", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                try {
                    startActivity(Intent.createChooser(intent, "分享诊断报告"))
                } catch (_: android.content.ActivityNotFoundException) {
                    Toast.makeText(this, "找不到可分享报告的应用，请使用保存功能", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun exportProblemDiagnostics() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            saveProblemDiagnostics.launch("云松天气-诊断记录.txt")
            return
        }
        val appContext = applicationContext
        updateExecutor.execute {
            val saved = runCatching { WeatherDiagnostics.saveToDownloads(appContext) }.isSuccess
            runOnUiThread {
                Toast.makeText(appContext, if (saved) "诊断报告已保存到下载文件夹" else "诊断报告保存失败",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    private class Option(val id: Int, val label: String)
    private class Section(
        val row: View,
        val arrow: ImageView,
        val value: TextView,
        val options: List<Option>,
        val currentId: () -> Int,
        val onSelect: (Int) -> Unit
    )

    private val sections = ArrayList<Section>()
    private var popup: PopupWindow? = null
    private lateinit var permissionCoordinator: WeatherPermissionCoordinator
    private lateinit var rowCaiyunAccess: View
    private lateinit var dividerCaiyunAccess: View
    private lateinit var rowCaiyunToken: View
    private lateinit var dividerCaiyunToken: View
    private lateinit var tvCaiyunAccess: TextView
    private lateinit var tvCaiyunToken: TextView
    private lateinit var tvNotificationDiagnostics: TextView
    private val updateExecutor = Executors.newFixedThreadPool(2)
    private val updateUi = android.os.Handler(android.os.Looper.getMainLooper())
    private var updateGeneration = 0
    private var checkingUpdate = false
    private var manualUpdatePending = false
    private var lastUpdateCompletedAt = 0L
    private var updateRequest: (Boolean) -> AppUpdatePolicy.Result = { force -> AppUpdateChecker(applicationContext).check(force) }
    private val updateDownload by lazy { ViewModelProvider(this)[AppUpdateDownloadModel::class.java] }
    private var updateConfirmDialog: AlertDialog? = null
    private var updateProgressDialog: AlertDialog? = null
    private var updateProgressBar: android.widget.ProgressBar? = null
    private val updateInstallPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (canInstallUpdate()) installReadyUpdate()
        else Toast.makeText(this, "可在检查更新处继续安装", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionCoordinator = WeatherPermissionCoordinator(this)
        // 设置页使用手机默认（Material You）动态主题色
        DynamicColors.applyToActivityIfAvailable(this)
        // 边到边：标题栏在状态栏之下开始，不再与状态栏文字重叠
        val darkTheme = WeatherSettings.isDarkTheme(this)
        enableEdgeToEdge(
            statusBarStyle = if (darkTheme) SystemBarStyle.dark(Color.TRANSPARENT)
            else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        setContentView(R.layout.activity_weather_settings)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settingsRoot)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val c = this
        // 状态栏图标颜色跟随页面深浅色（浅色页用深色图标，深色页用浅色图标）
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = !WeatherSettings.isDarkTheme(this)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        val primary = MaterialColors.getColor(
            findViewById(R.id.settingsRoot), com.google.android.material.R.attr.colorPrimary
        )
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)
        val divider = ContextCompat.getColor(this, R.color.divider)

        // —— 固定数据链路 ——
        val tvSource = findViewById<TextView>(R.id.tvSourceValue)
        rowCaiyunAccess = findViewById(R.id.rowCaiyunAccess)
        dividerCaiyunAccess = findViewById(R.id.dividerCaiyunAccess)
        rowCaiyunToken = findViewById(R.id.rowCaiyunToken)
        dividerCaiyunToken = findViewById(R.id.dividerCaiyunToken)
        tvCaiyunAccess = findViewById(R.id.tvCaiyunAccessValue)
        tvCaiyunToken = findViewById(R.id.tvCaiyunTokenValue)
        tvSource.text = "彩云主源 · 和风补充"
        findViewById<View>(R.id.rowSource).isEnabled = false
        findViewById<View>(R.id.ivSourceArrow).visibility = View.GONE
        if (BuildConfig.USER_CONFIGURABLE) {
            findViewById<View>(R.id.rowSource).isEnabled = true
            findViewById<View>(R.id.ivSourceArrow).visibility = View.VISIBLE
            findViewById<View>(R.id.rowSource).setOnClickListener {
                startActivity(android.content.Intent(this, ApiConfigurationActivity::class.java))
            }
            tvSource.text = "自行填写 API 配置"
        }
        // 旧版接口模式与 Token 输入永久隐藏，既不会再读取也不会修改构建内凭证。
        rowCaiyunAccess.visibility = View.GONE
        dividerCaiyunAccess.visibility = View.GONE
        rowCaiyunToken.visibility = View.GONE
        dividerCaiyunToken.visibility = View.GONE

        // —— 深浅色模式 ——
        val tvTheme = findViewById<TextView>(R.id.tvThemeValue)
        addSection(
            R.id.rowTheme, R.id.ivThemeArrow, tvTheme,
            listOf(Option(0, "跟随系统"), Option(1, "固定深色"), Option(2, "固定浅色")),
            currentId = {
                when (WeatherSettings.themeMode(c)) {
                    WeatherSettings.THEME_DARK -> 1
                    WeatherSettings.THEME_LIGHT -> 2
                    else -> 0
                }
            },
            onSelect = { id ->
                val mode = when (id) {
                    1 -> WeatherSettings.THEME_DARK
                    2 -> WeatherSettings.THEME_LIGHT
                    else -> WeatherSettings.THEME_SYSTEM
                }
                WeatherSettings.setThemeMode(c, mode)
                WeatherSettings.applyNightMode(c)
                recreate()
            },
            primary, textPrimary, divider
        )

        // —— 温度单位 ——
        val tvTemp = findViewById<TextView>(R.id.tvTempValue)
        addSection(
            R.id.rowTemp, R.id.ivTempArrow, tvTemp,
            listOf(Option(0, "摄氏度 (°C)"), Option(1, "华氏度 (°F)")),
            currentId = { if (WeatherSettings.tempUnit(c) == WeatherSettings.TEMP_F) 1 else 0 },
            onSelect = { id ->
                WeatherSettings.setTempUnit(c, if (id == 1) WeatherSettings.TEMP_F else WeatherSettings.TEMP_C)
                tvTemp.text = if (id == 1) "华氏度 (°F)" else "摄氏度 (°C)"
            },
            primary, textPrimary, divider
        )

        // —— 风力单位 ——
        val tvWind = findViewById<TextView>(R.id.tvWindValue)
        addSection(
            R.id.rowWind, R.id.ivWindArrow, tvWind,
            listOf(
                Option(0, "公里/小时"), Option(1, "米/秒"),
                Option(2, "英里/小时"), Option(3, "风级")
            ),
            currentId = {
                when (WeatherSettings.windUnit(c)) {
                    WeatherSettings.WIND_MS -> 1
                    WeatherSettings.WIND_MPH -> 2
                    WeatherSettings.WIND_BF -> 3
                    else -> 0
                }
            },
            onSelect = { id ->
                WeatherSettings.setWindUnit(
                    c,
                    when (id) {
                        1 -> WeatherSettings.WIND_MS
                        2 -> WeatherSettings.WIND_MPH
                        3 -> WeatherSettings.WIND_BF
                        else -> WeatherSettings.WIND_KMH
                    }
                )
                tvWind.text = windLabel(id)
            },
            primary, textPrimary, divider
        )

        // —— 气压单位 ——
        val tvPressure = findViewById<TextView>(R.id.tvPressureValue)
        addSection(
            R.id.rowPressure, R.id.ivPressureArrow, tvPressure,
            listOf(Option(0, "百帕 (hPa)"), Option(1, "毫米汞柱"), Option(2, "英寸汞柱")),
            currentId = {
                when (WeatherSettings.pressureUnit(c)) {
                    WeatherSettings.PRESSURE_MMHG -> 1
                    WeatherSettings.PRESSURE_INHG -> 2
                    else -> 0
                }
            },
            onSelect = { id ->
                WeatherSettings.setPressureUnit(
                    c,
                    when (id) {
                        1 -> WeatherSettings.PRESSURE_MMHG
                        2 -> WeatherSettings.PRESSURE_INHG
                        else -> WeatherSettings.PRESSURE_HPA
                    }
                )
                tvPressure.text = pressureLabel(id)
            },
            primary, textPrimary, divider
        )

        // —— 能见度单位 ——
        val tvVis = findViewById<TextView>(R.id.tvVisValue)
        addSection(
            R.id.rowVis, R.id.ivVisArrow, tvVis,
            listOf(Option(0, "公里 (km)"), Option(1, "英里 (mi)"), Option(2, "米 (m)")),
            currentId = {
                when (WeatherSettings.visibilityUnit(c)) {
                    WeatherSettings.VIS_MI -> 1
                    WeatherSettings.VIS_M -> 2
                    else -> 0
                }
            },
            onSelect = { id ->
                WeatherSettings.setVisibilityUnit(
                    c,
                    when (id) {
                        1 -> WeatherSettings.VIS_MI
                        2 -> WeatherSettings.VIS_M
                        else -> WeatherSettings.VIS_KM
                    }
                )
                tvVis.text = visLabel(id)
            },
            primary, textPrimary, divider
        )

        // 初始显示当前值
        for (s in sections) {
            s.value.text = s.options.firstOrNull { it.id == s.currentId() }?.label
                ?: s.options.firstOrNull()?.label.orEmpty()
        }

        setupReminderSwitches()
        tvNotificationDiagnostics = findViewById(R.id.tvNotificationDiagnostics)
        findViewById<View>(R.id.btnSendNotificationTest).setOnClickListener {
            if (WeatherNotificationCenter.postTestNotifications(this)) {
                Toast.makeText(this, "已发送三类测试通知，请查看通知栏", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "请先在系统设置中允许云松天气发送通知", Toast.LENGTH_LONG).show()
            }
            refreshNotificationDiagnostics()
        }
        findViewById<View>(R.id.btnOpenNotificationSystemSettings).setOnClickListener {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !WeatherPermissionCoordinator.canScheduleExactAlarms(this)
            ) {
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            }
            startActivity(intent)
        }
        refreshNotificationDiagnostics()

        findViewById<TextView>(R.id.btnUsageGuide).apply {
            text = if (BuildConfig.USER_CONFIGURABLE) "使用帮助与 API 指引" else "使用帮助"
            setOnClickListener { FirstRunGuide.show(this@WeatherSettingsActivity, force = true) { configure ->
                if (configure && BuildConfig.USER_CONFIGURABLE) startActivity(Intent(this@WeatherSettingsActivity, ApiConfigurationActivity::class.java))
            } }
        }

        findViewById<View>(R.id.btnWeatherWidgets).setOnClickListener {
            startActivity(Intent(this, WeatherWidgetActivity::class.java))
        }
        // —— 关于天气 ——
        findViewById<View>(R.id.btnProblemDiagnostics).setOnClickListener { showProblemDiagnostics() }
        findViewById<View>(R.id.btnAbout).setOnClickListener {
            startActivity(Intent(this, WeatherAboutActivity::class.java))
        }
        findViewById<View>(R.id.btnCheckUpdate).setOnClickListener {
            when (updateDownload.state.value) {
                is AppUpdateDownloadModel.State.Ready -> installReadyUpdate()
                is AppUpdateDownloadModel.State.Downloading -> Unit
                else -> requestUpdateCheck(manual = true)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                updateDownload.state.collect(::renderUpdateDownload)
            }
        }
        SettingsGlassStyle.applyTree(findViewById(R.id.settingsRoot))
    }

    private fun updateCaiyunControls() {
        if (!::rowCaiyunAccess.isInitialized) return
        val isCaiyun = WeatherSettings.dataSource(this) == WeatherSettings.SOURCE_CAIYUN
        val ownToken = isCaiyun && WeatherSettings.usesOwnCaiyunToken(this)
        rowCaiyunAccess.visibility = if (isCaiyun) View.VISIBLE else View.GONE
        dividerCaiyunAccess.visibility = if (isCaiyun) View.VISIBLE else View.GONE
        rowCaiyunToken.visibility = if (isCaiyun) View.VISIBLE else View.GONE
        dividerCaiyunToken.visibility = if (isCaiyun) View.VISIBLE else View.GONE
        if (isCaiyun) {
            tvCaiyunAccess.text = when {
                ownToken -> "本机加密 Token"
                WeatherSettings.usesSharedCaiyunGateway(this) -> "官方共享接口"
                else -> "本机加密 Token"
            }
            tvCaiyunToken.text = when {
                !ownToken -> "可设置后切换使用"
                WeatherSettings.caiyunMinutelyCapability(this) == WeatherSettings.CAIYUN_MINUTELY_AVAILABLE ->
                    "已加密保存 · 分钟降雨可用"
                WeatherSettings.caiyunMinutelyCapability(this) == WeatherSettings.CAIYUN_MINUTELY_UNAVAILABLE ->
                    "已加密保存 · 当前凭证未开通分钟降雨"
                WeatherSettings.caiyunMinutelyCapability(this) == WeatherSettings.CAIYUN_MINUTELY_CHECKING ->
                    "已加密保存 · 分钟降雨检测中"
                else -> "已加密保存 · 分钟降雨待检测"
            }
        }
    }

    private fun showCaiyunTokenDialog(restoreModeOnCancel: String? = null) {
        val input = EditText(this).apply {
            hint = "粘贴彩云 API Token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(false)
            setPadding(dp(20), dp(6), dp(20), dp(6))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("我的彩云 Token")
            .setMessage("Token 将使用 Android Keystore 加密后仅保存在本机，不会发送到共享天气服务。保存前会以当前城市验证。")
            .setView(input)
            .setNegativeButton("取消") { _, _ ->
                if (restoreModeOnCancel != null) {
                    WeatherSettings.setCaiyunAccessMode(this, restoreModeOnCancel)
                    updateCaiyunControls()
                }
            }
            .setNeutralButton("清除我的 Token") { _, _ ->
                Thread {
                    runBlocking { CaiyunTokenStore.clear(this@WeatherSettingsActivity) }
                    runOnUiThread {
                        WeatherSettings.setCaiyunAccessMode(this, WeatherSettings.CAIYUN_ACCESS_OWN_TOKEN)
                        WeatherSettings.resetCaiyunMinutelyCapability(this)
                        WeatherCache.clearCaiyun(this)
                        updateCaiyunControls()
                        Toast.makeText(this, "已清除本机 Token", Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
            .setPositiveButton("保存并验证", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val token = input.text?.toString()?.trim().orEmpty()
                if (token.isEmpty()) {
                    input.error = "请输入 Token"
                    return@setOnClickListener
                }
                val city = WeatherCityStore.currentLocationCity(this)
                    ?: CityPage("北京", 116.4074, 39.9042, false)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                Thread {
                    // 必须同时尝试 v2.7、v2.6、过去一天和日出日落；Token 本身只在该线程
                    // 暂存，探测结果不带凭证、坐标和原始响应。
                    val probe = WeatherDataRepository.probeOwnCaiyunToken(token, city.lng, city.lat)
                    if (probe.canActivate) runBlocking { CaiyunTokenStore.save(this@WeatherSettingsActivity, token) }
                    runOnUiThread {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        if (probe.canActivate) {
                            WeatherSettings.setCaiyunAccessMode(this, WeatherSettings.CAIYUN_ACCESS_OWN_TOKEN)
                            WeatherSettings.setCaiyunMinutelyCapability(
                                this,
                                if (probe.v26MinutelyData) WeatherSettings.CAIYUN_MINUTELY_AVAILABLE
                                else WeatherSettings.CAIYUN_MINUTELY_UNAVAILABLE
                            )
                            WeatherCache.clearCaiyun(this)
                            updateCaiyunControls()
                            Toast.makeText(
                                this,
                                "Token 已验证并安全保存：${probe.compactSummary()}",
                                Toast.LENGTH_LONG
                            ).show()
                            dialog.dismiss()
                        } else {
                            input.error = "主天气接口验证失败；未切换数据源（仅检测到：${probe.compactSummary()}）"
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }

    private fun setupReminderSwitches() {
        bindReminder(
            findViewById(R.id.switchDisasterReminder),
            { WeatherSettings.disasterReminder(this) },
            { WeatherSettings.setDisasterReminder(this, it) },
            WeatherPermissionCoordinator.ReminderType.DISASTER
        )
        bindReminder(
            findViewById(R.id.switchTemperatureReminder),
            { WeatherSettings.temperatureChangeReminder(this) },
            { WeatherSettings.setTemperatureChangeReminder(this, it) },
            WeatherPermissionCoordinator.ReminderType.TEMPERATURE
        )
        bindReminder(
            findViewById(R.id.switchWindReminder),
            { WeatherSettings.strongWindReminder(this) },
            { WeatherSettings.setStrongWindReminder(this, it) },
            WeatherPermissionCoordinator.ReminderType.WIND
        )
        bindReminder(
            findViewById(R.id.switchRainReminder),
            { WeatherSettings.rainReminder(this) },
            { WeatherSettings.setRainReminder(this, it) },
            WeatherPermissionCoordinator.ReminderType.RAIN
        )
        bindReminder(
            findViewById(R.id.switchDailyReminder),
            { WeatherSettings.dailyForecastReminder(this) },
            { WeatherSettings.setDailyForecastReminder(this, it) },
            WeatherPermissionCoordinator.ReminderType.DAILY
        )
    }

    private fun bindReminder(
        control: CompoundButton,
        saved: () -> Boolean,
        persist: (Boolean) -> Unit,
        reminderType: WeatherPermissionCoordinator.ReminderType
    ) {
        control.setOnCheckedChangeListener(null)
        // 权限被系统回收后不能把开关伪装成已生效；保存值也同步回关闭状态。
        val initialEnabled = saved()
        control.isChecked = initialEnabled
        control.setOnCheckedChangeListener { view, enabled ->
            if (enabled && !permissionCoordinator.canEnableReminder(reminderType)) {
                view.isChecked = false
                permissionCoordinator.requestForReminder(reminderType) { granted ->
                    if (granted && !isFinishing && !isDestroyed) {
                        // 权限已齐全后重新触发本开关；这一次会进入下方持久化分支。
                        view.isChecked = true
                    }
                }
                return@setOnCheckedChangeListener
            }
            persist(enabled)
            WeatherReminderPreparation.request(this)
            WeatherNotificationCenter.updateSchedules(this)
            WeatherBackgroundScheduler.schedule(this)
            if (::tvNotificationDiagnostics.isInitialized) refreshNotificationDiagnostics()
        }
    }

    override fun onResume() {
        super.onResume()
        if (updateDownload.state.value == AppUpdateDownloadModel.State.Idle &&
            (lastUpdateCompletedAt == 0L || android.os.SystemClock.uptimeMillis() - lastUpdateCompletedAt >= 60_000L)) {
            requestUpdateCheck(manual = false)
        }
        if (BuildConfig.USER_CONFIGURABLE) {
            val config = UserApiConfiguration.read(this)
            findViewById<TextView>(R.id.tvSourceValue).text = when {
                config.effectiveSource() == WeatherSettings.SOURCE_OPEN_METEO -> "Open-Meteo · 免费默认"
                config.effectiveSource() == WeatherSettings.SOURCE_QWEATHER -> "和风 · 自行配置"
                else -> "彩云 ${config.caiyunVersion} · 自行配置"
            }
        }
        if (::permissionCoordinator.isInitialized) permissionCoordinator.onResume()
        if (::tvNotificationDiagnostics.isInitialized) {
            // 从精确闹钟或通知设置返回后立即恢复计划并刷新可见状态。
            WeatherNotificationCenter.updateSchedules(this)
            WeatherBackgroundScheduler.schedule(this)
            refreshNotificationDiagnostics()
        }
    }

    private fun requestUpdateCheck(manual: Boolean) {
        if(updateExecutor.isShutdown || isFinishing || isDestroyed) return
        if(checkingUpdate && (!manual || manualUpdatePending)) return
        checkingUpdate=true
        manualUpdatePending=manual
        val generation=++updateGeneration
        val started=android.os.SystemClock.uptimeMillis()
        findViewById<android.widget.ProgressBar>(R.id.updateCheckProgress).apply {
            indeterminateTintList=android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@WeatherSettingsActivity,R.color.accent))
            visibility=View.VISIBLE
        }
        findViewById<TextView>(R.id.tvUpdateStatus).apply {
            text="正在检查更新…";setTextColor(ContextCompat.getColor(this@WeatherSettingsActivity,R.color.text_secondary))
            setTypeface(null,Typeface.NORMAL)
        }
        findViewById<View>(R.id.updateDownloadArrow).visibility=View.GONE
        findViewById<View>(R.id.btnCheckUpdate).contentDescription="正在检查更新，请稍候"
        val request=updateRequest
        updateExecutor.execute {
            val result=runCatching {request(manual)}.getOrElse {AppUpdatePolicy.Result(AppUpdatePolicy.Status.FAILED)}
            val remaining=(400L-(android.os.SystemClock.uptimeMillis()-started)).coerceAtLeast(0L)
            updateUi.postDelayed({
                if(!isFinishing && !isDestroyed && generation==updateGeneration) {
                    checkingUpdate=false;manualUpdatePending=false
                    lastUpdateCompletedAt=android.os.SystemClock.uptimeMillis()
                    renderUpdateStatus(result)
                    if(manual && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                        if(result.status==AppUpdatePolicy.Status.NEW_VERSION) confirmUpdateDownload(result)
                        else Toast.makeText(this,when(result.status) {
                            AppUpdatePolicy.Status.CURRENT -> "已是最新版本"
                            AppUpdatePolicy.Status.UNAVAILABLE -> "暂时无法获取版本信息，请稍后重试"
                            else -> "检查更新失败，请稍后重试"
                        },Toast.LENGTH_SHORT).show()
                    }
                }
            },remaining)
        }
    }

    private fun confirmUpdateDownload(result: AppUpdatePolicy.Result) {
        val apk = result.apk ?: return
        if (updateConfirmDialog?.isShowing == true) return
        val size = String.format(java.util.Locale.getDefault(), "%.1f", apk.size / (1024.0 * 1024))
        updateConfirmDialog = AlertDialog.Builder(this)
            .setTitle("发现新版本 " + result.version)
            .setMessage("下载约 " + size + " MB。下载完成后，将打开 Android 安装确认。")
            .setNegativeButton("稍后", null)
            .setPositiveButton("下载更新") { _, _ -> updateDownload.start(result.version, apk) }
            .show()
    }

    private fun renderUpdateDownload(state: AppUpdateDownloadModel.State) {
        val button = findViewById<View>(R.id.btnCheckUpdate)
        val label = findViewById<TextView>(R.id.tvUpdateStatus)
        val arrow = findViewById<View>(R.id.updateDownloadArrow)
        if (state !is AppUpdateDownloadModel.State.Downloading) {
            updateProgressDialog?.dismiss()
            updateProgressDialog = null
            updateProgressBar = null
        }
        when (state) {
            is AppUpdateDownloadModel.State.Downloading -> {
                label.text = "正在下载 " + state.percent + "%"
                button.contentDescription = label.text
                arrow.visibility = View.GONE
                if (updateProgressDialog == null) {
                    val padding = (20 * resources.displayMetrics.density).toInt()
                    val layout = LinearLayout(this).apply { setPadding(padding, padding, padding, 0) }
                    updateProgressBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                        isIndeterminate = false
                        max = 100
                        layout.addView(this, LinearLayout.LayoutParams(-1, -2))
                    }
                    updateProgressDialog = AlertDialog.Builder(this).setTitle("正在下载 " + state.version)
                        .setView(layout).setCancelable(false)
                        .setNegativeButton("取消下载") { _, _ -> updateDownload.cancel() }.show()
                }
                updateProgressBar?.progress = state.percent
            }
            is AppUpdateDownloadModel.State.Ready -> {
                label.text = "下载完成 · 安装 " + state.version
                button.contentDescription = label.text
                arrow.visibility = View.VISIBLE
                if (updateDownload.takeAutoInstall()) installReadyUpdate()
            }
            AppUpdateDownloadModel.State.Failed -> {
                label.text = "下载未完成 · 点击重试"
                button.contentDescription = label.text
                Toast.makeText(this, "更新下载或校验失败，请重试", Toast.LENGTH_LONG).show()
                updateDownload.clearFailure()
            }
            AppUpdateDownloadModel.State.Idle -> {
                if (!checkingUpdate && label.text.startsWith("正在下载")) {
                    label.text = "下载已取消 · 点击重试"
                    button.contentDescription = label.text
                }
            }
        }
    }

    private fun canInstallUpdate(): Boolean =
        Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

    private fun installReadyUpdate() {
        val update = updateDownload.state.value as? AppUpdateDownloadModel.State.Ready ?: return
        if (!update.file.isFile) {
            updateDownload.clearMissingPackage()
            requestUpdateCheck(manual = true)
            return
        }
        if (!canInstallUpdate()) {
            AlertDialog.Builder(this).setTitle("允许安装更新")
                .setMessage("首次安装更新时，请在系统设置中允许云松天气安装应用。返回后即可继续安装。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("前往授权") { _, _ ->
                    runCatching {
                        updateInstallPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.fromParts("package", packageName, null)))
                    }.onFailure {
                        Toast.makeText(this, "请在系统设置中允许云松天气安装应用", Toast.LENGTH_LONG).show()
                    }
                }.show()
            return
        }
        runCatching { startActivity(AppUpdateInstaller.installationIntent(this, update.file)) }
            .onFailure { Toast.makeText(this, "无法打开系统安装器，请稍后重试", Toast.LENGTH_LONG).show() }
    }

    internal fun renderUpdateStatus(result: AppUpdatePolicy.Result) {
        findViewById<View>(R.id.updateCheckProgress).visibility=View.GONE
        findViewById<View>(R.id.updateDownloadArrow).visibility=if(result.status==AppUpdatePolicy.Status.NEW_VERSION) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.tvUpdateStatus).apply {
            text = result.label
            setTextColor(ContextCompat.getColor(this@WeatherSettingsActivity,
                if (result.status == AppUpdatePolicy.Status.NEW_VERSION) R.color.accent else R.color.text_secondary))
            setTypeface(null, if (result.status == AppUpdatePolicy.Status.NEW_VERSION) Typeface.BOLD else Typeface.NORMAL)
        }
        findViewById<View>(R.id.btnCheckUpdate).contentDescription = if(result.status==AppUpdatePolicy.Status.NEW_VERSION) "检查更新，${result.label}，点击查看更新" else "检查更新，${result.label}，点击重新检查"
    }

    override fun onDestroy() {
        updateGeneration++
        updateUi.removeCallbacksAndMessages(null)
        updateExecutor.shutdownNow()
        updateConfirmDialog?.dismiss()
        updateProgressDialog?.dismiss()
        super.onDestroy()
    }

    private fun refreshNotificationDiagnostics() {
        tvNotificationDiagnostics.text = WeatherNotificationCenter.diagnosticsText(this)
    }

    private fun addSection(
        rowId: Int,
        arrowId: Int,
        value: TextView,
        options: List<Option>,
        currentId: () -> Int,
        onSelect: (Int) -> Unit,
        primary: Int,
        textPrimary: Int,
        divider: Int
    ) {
        val row = findViewById<View>(rowId)
        val arrow = findViewById<ImageView>(arrowId)
        val section = Section(row, arrow, value, options, currentId, onSelect)
        sections.add(section)
        row.setOnClickListener { showPopup(section, primary, textPrimary, divider) }
    }

    // 参考 shezhi：点击行后弹出一个白色圆角小面板，当前选中项带主题色文字与对勾
    private fun showPopup(section: Section, primary: Int, textPrimary: Int, divider: Int) {
        popup?.dismiss()
        val c = this
        val container = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.settings_popup_bg)
            setPadding(0, dp(5), 0, dp(5))
        }
        val selected = section.currentId()
        section.options.forEachIndexed { idx, opt ->
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                isClickable = true
                isFocusable = true
            }
            row.addView(
                TextView(c).apply {
                    text = opt.label
                    textSize = 14f
                    if (opt.id == selected) {
                        setTextColor(primary)
                        setTypeface(null, Typeface.BOLD)
                    } else {
                        setTextColor(textPrimary)
                    }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(
                ImageView(c).apply {
                    setImageResource(R.drawable.ic_check)
                    setColorFilter(primary)
                    visibility = if (opt.id == selected) View.VISIBLE else View.INVISIBLE
                    layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                        marginStart = dp(12)
                    }
                }
            )
            row.setOnClickListener {
                section.onSelect(opt.id)
                section.value.text = opt.label
                section.arrow.animate().rotation(0f).setDuration(160).start()
                popup?.dismiss()
            }
            container.addView(row)
            if (idx != section.options.lastIndex) {
                container.addView(
                    View(c).apply {
                        setBackgroundColor(divider)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                        ).apply {
                            leftMargin = dp(16)
                            rightMargin = dp(16)
                        }
                    }
                )
            }
        }
        val width = dp(240)
        SettingsGlassStyle.decoratePopup(container, window.decorView)
        val wnd = PopupWindow(
            container, width, ViewGroup.LayoutParams.WRAP_CONTENT, true
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // 先关闭外部点击，避免“弹出时同一次点按的抬起事件落在面板外”把它立刻关掉；
            // 等面板显示完成后再开启外部点击关闭。
            isOutsideTouchable = false
            elevation = dp(10).toFloat()
            setOnDismissListener {
                section.arrow.animate().rotation(0f).setDuration(160).start()
            }
        }
        popup = wnd
        section.arrow.animate().rotation(180f).setDuration(160).start()
        // 面板贴行右下弹出，与参考图一致
        wnd.showAsDropDown(section.row, section.row.width - width - dp(4), -dp(6))
        section.row.postDelayed({ if (popup === wnd) wnd.isOutsideTouchable = true }, 120)
    }

    private fun windLabel(id: Int): String = when (id) {
        1 -> "米/秒"
        2 -> "英里/小时"
        3 -> "风级"
        else -> "公里/小时"
    }

    private fun pressureLabel(id: Int): String = when (id) {
        1 -> "毫米汞柱"
        2 -> "英寸汞柱"
        else -> "百帕 (hPa)"
    }

    private fun visLabel(id: Int): String = when (id) {
        1 -> "英里 (mi)"
        2 -> "米 (m)"
        else -> "公里 (km)"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
