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
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import android.widget.CompoundButton
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

// 天气设置页：数据源、深浅色模式、温度/风力/气压/能见度单位、关于天气。
// 每个分区是一行“标题 + 当前值 + 箭头”，点击后弹出悬浮小面板进行选择（参考 shezhi 效果）；
// 主题色跟随手机默认的动态主题色（Material You）。
class WeatherSettingsActivity : AppCompatActivity() {

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
    private val updateExecutor = Executors.newSingleThreadExecutor()

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

        // —— 关于天气 ——
        findViewById<View>(R.id.btnAbout).setOnClickListener {
            startActivity(Intent(this, WeatherAboutActivity::class.java))
        }
        findViewById<View>(R.id.btnCheckUpdate).setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppUpdatePolicy.DOWNLOAD_PAGE)))
            }.onFailure {
                Toast.makeText(this, "未找到浏览器，请在 GitHub 查看 SIAKNG/yunsong-weather 的 Releases", Toast.LENGTH_LONG).show()
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
            WeatherNotificationCenter.updateSchedules(this)
            WeatherBackgroundScheduler.schedule(this)
            if (::tvNotificationDiagnostics.isInitialized) refreshNotificationDiagnostics()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!updateExecutor.isShutdown) updateExecutor.execute {
            val result = AppUpdateChecker(applicationContext).check()
            runOnUiThread { if (!isFinishing && !isDestroyed) renderUpdateStatus(result) }
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

    internal fun renderUpdateStatus(result: AppUpdatePolicy.Result) {
        findViewById<TextView>(R.id.tvUpdateStatus).apply {
            text = result.label
            setTextColor(ContextCompat.getColor(this@WeatherSettingsActivity,
                if (result.status == AppUpdatePolicy.Status.NEW_VERSION) R.color.accent else R.color.text_secondary))
            setTypeface(null, if (result.status == AppUpdatePolicy.Status.NEW_VERSION) Typeface.BOLD else Typeface.NORMAL)
        }
        findViewById<View>(R.id.btnCheckUpdate).contentDescription = "检查更新，${result.label}，打开 GitHub 安装包下载页"
    }

    override fun onDestroy() {
        updateExecutor.shutdownNow()
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
