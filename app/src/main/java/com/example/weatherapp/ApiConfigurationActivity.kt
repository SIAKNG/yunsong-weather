package com.example.weatherapp

import android.os.Bundle
import android.text.InputType
import android.graphics.Rect
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.ViewModelProvider
import androidx.appcompat.app.AlertDialog

/** Only reachable in userApi; no prefilled built-in secret, clipboard read or credential logging. */
class ApiConfigurationActivity : AppCompatActivity() {
    private val checker by lazy { ViewModelProvider(this)[ApiCapabilityViewModel::class.java] }
    private var progressDialog: AlertDialog? = null
    private var reportDialog: AlertDialog? = null
    private var progressText: TextView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.USER_CONFIGURABLE) { finish(); return }
        if (savedInstanceState == null) FreeWeatherNotice.configurationOpened(this)
        val dark=WeatherSettings.isDarkTheme(this)
        val bars=if(dark) androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle=bars,navigationBarStyle=bars)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val old = checker.editingConfiguration() ?: UserApiConfiguration.read(this)
        val layout = LinearLayout(this).apply {
            id = R.id.apiConfigRoot
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(ContextCompat.getColor(this@ApiConfigurationActivity, R.color.page_bg))
            isFocusableInTouchMode=true
        }
        val scroll = ScrollView(this).apply {
            id=R.id.apiConfigScroll;isFillViewport=true;isVerticalScrollBarEnabled = false
            setBackgroundColor(ContextCompat.getColor(this@ApiConfigurationActivity,R.color.page_bg));addView(layout)
        }
        setContentView(scroll)
        fun revealInput() {
            scroll.doOnLayout {
                (currentFocus as? EditText)?.let { field ->
                    // ScrollView's child-rectangle helper does not consistently account
                    // for an IME-sized bottom padding on edge-to-edge ColorOS windows.
                    val fieldPos=IntArray(2);val scrollPos=IntArray(2)
                    field.getLocationOnScreen(fieldPos);scroll.getLocationOnScreen(scrollPos)
                    val top=scrollPos[1]+scroll.paddingTop+dp(12)
                    val bottom=scrollPos[1]+scroll.height-scroll.paddingBottom-dp(12)
                    val delta=when {
                        fieldPos[1]+field.height>bottom -> fieldPos[1]+field.height-bottom
                        fieldPos[1]<top -> fieldPos[1]-top
                        else -> 0
                    }
                    if(delta!=0) scroll.scrollBy(0,delta)
                }
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, i ->
            val bars=i.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime=i.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left,bars.top,bars.right,maxOf(bars.bottom,ime.bottom))
            revealInput();i
        }
        ViewCompat.requestApplyInsets(scroll)
        fun text(value: String, size: Float = 15f) = TextView(this).apply {
            text = value; textSize = size; setPadding(0, dp(10), 0, dp(6));setLineSpacing(dp(3).toFloat(),1f)
            setTextColor(ContextCompat.getColor(this@ApiConfigurationActivity, R.color.text_primary)); layout.addView(this)
        }
        text("天气源 · 自行配置", 22f)
        text("不填接口也能使用 Open-Meteo 免费天气。填写自己的彩云或和风接口后优先使用个人接口，不再请求 Open-Meteo。凭证仅加密保存在本机。")
        fun action(value: String, callback: () -> Unit) = TextView(this).apply {
            text=value; textSize=15f; gravity=android.view.Gravity.CENTER
            setPadding(20,20,20,20); minHeight=(48*resources.displayMetrics.density).toInt()
            isClickable=true; isFocusable=true; setOnClickListener { callback() }
            layout.addView(this,LinearLayout.LayoutParams(-1,-2).apply { topMargin=(12*resources.displayMetrics.density).toInt() })
            SettingsGlassStyle.decorate(this,16f,true)
        }
        action("如何获取与填写 API") { startActivity(android.content.Intent(this,ApiTutorialActivity::class.java)) }.apply {
            id = R.id.btnApiTutorial
        }
        if(old.qweatherApiKey.isBlank() && (old.qweatherPem.isNotBlank() || old.qweatherKid.isNotBlank()))
            text("旧配置使用私钥方式。本版改为仅支持 API Key，请按教程填写自己的 Host + API Key；旧加密内容在你主动保存前不会被清除。")
        val source = Spinner(this).apply {
            adapter = ArrayAdapter(this@ApiConfigurationActivity, android.R.layout.simple_spinner_dropdown_item, listOf("自动 · 个人 API 优先，无配置时免费天气", "彩云主源 · 已填和风可补充", "和风天气主源"))
            setSelection(when(old.source) {WeatherSettings.SOURCE_QWEATHER->2;WeatherSettings.SOURCE_CAIYUN->1;else->0}); layout.addView(this)
        }
        val controls=mutableListOf<View>(source)
        fun field(idValue: Int,label: String, value: String, secret: Boolean = false): EditText {
            text(label)
            return EditText(this).apply {
                id=idValue;setText(value); textSize = 15f; isSaveEnabled = false
                if (android.os.Build.VERSION.SDK_INT >= 26)
                    importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                isSingleLine=true;setHorizontallyScrolling(true)
                if(secret) transformationMethod=android.text.method.PasswordTransformationMethod.getInstance()
                imeOptions=EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                setTextColor(ContextCompat.getColor(this@ApiConfigurationActivity, R.color.text_primary))
                layout.addView(this, LinearLayout.LayoutParams(-1, -2))
                setBackgroundResource(R.drawable.search_bg);backgroundTintList=null
                foreground=null;elevation=0f;minHeight=dp(48);setPadding(dp(16),dp(10),dp(16),dp(10))
                setHintTextColor(ContextCompat.getColor(context,R.color.text_tertiary));hint="点击填写"
                highlightColor=androidx.core.graphics.ColorUtils.setAlphaComponent(ContextCompat.getColor(context,R.color.text_tertiary),112)
                setOnFocusChangeListener { _,focused->if(focused) revealInput() }
                controls.add(this)
            }
        }
        val caiyun = field(R.id.apiCaiyunToken,"彩云 API Token", old.caiyunToken, true)
        text("彩云接口版本（默认 2.6；有 2.7 权限时可选择 2.7）")
        val version = Spinner(this).apply {
            adapter = ArrayAdapter(this@ApiConfigurationActivity, android.R.layout.simple_spinner_dropdown_item, listOf("2.6", "2.7"))
            setSelection(if (old.caiyunVersion == "2.7") 1 else 0); layout.addView(this)
        }
        controls.add(version)
        val host = field(R.id.apiQweatherHost,"和风 API Host（复制控制台的完整专属域名）", old.qweatherHost)
        val apiKey = field(R.id.apiQweatherKey,"和风 API Key", old.qweatherApiKey, true)
        val amap = field(R.id.apiAmapKey,"高德 Web 服务 Key（可选；留空使用本地城市搜索）", old.amapKey, true)
        caiyun.nextFocusForwardId=host.id;host.nextFocusForwardId=apiKey.id;apiKey.nextFocusForwardId=amap.id
        amap.imeOptions=EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        amap.setOnEditorActionListener { v,actionId,_->
            if(actionId==EditorInfo.IME_ACTION_DONE) { WindowInsetsControllerCompat(window,v).hide(WindowInsetsCompat.Type.ime());v.clearFocus();true } else false
        }
        fun formConfiguration() = UserApiConfiguration.Config(
                    when(source.selectedItemPosition) {2->WeatherSettings.SOURCE_QWEATHER;1->WeatherSettings.SOURCE_CAIYUN;else->"auto"},
                    caiyun.text.toString().trim(), version.selectedItem.toString(), host.text.toString().trim(),
                    apiKey.text.toString().trim(), "", "", "", amap.text.toString().trim())
        fun checkForm(config: UserApiConfiguration.Config,saved: Boolean) {
            currentFocus?.clearFocus();WindowInsetsControllerCompat(window,scroll).hide(WindowInsetsCompat.Type.ime())
            checker.start(config,saved)
        }
        text("可以先检测再保存。检测使用北京示例地点，最多15次请求，可能消耗调用额度；不会读取定位。",13f)
        action("保存并使用") {
                val config=formConfiguration()
                val error = UserApiConfiguration.validate(config)
                if (error != null) Toast.makeText(this@ApiConfigurationActivity, error, Toast.LENGTH_LONG).show()
                else runCatching { UserApiConfiguration.save(this@ApiConfigurationActivity, config) }.fold(
                    onSuccess = {
                        checkForm(config,true)
                    },
                    onFailure = { Toast.makeText(this@ApiConfigurationActivity, "保存失败，请重试", Toast.LENGTH_SHORT).show() })
        }.apply { id=R.id.apiSave;controls.add(this) }
        action("检测 API 支持情况（不保存）") {
            val config=formConfiguration()
            val error=UserApiConfiguration.validate(config)
            if(error!=null) Toast.makeText(this,error,Toast.LENGTH_LONG).show() else checkForm(config,false)
        }.apply {id=R.id.apiCheck;controls.add(this)}
        action("清除所有接口配置") {
            androidx.appcompat.app.AlertDialog.Builder(this@ApiConfigurationActivity)
                .setMessage("清除本机 API 配置？清除后将使用 Open-Meteo 免费天气，不影响已添加的城市。")
                .setNegativeButton("取消", null).setPositiveButton("清除") { _, _ ->
                    UserApiConfiguration.clear(this@ApiConfigurationActivity); finish()
                }.show()
        }.also { controls.add(it) }
        action("返回") { finish() }
        layout.requestFocus()
        checker.state.observe(this) { state ->
            controls.forEach { it.isEnabled=!state.running }
            if(state.running) {
                if(progressDialog==null) {
                    val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(12),dp(24),dp(8)) }
                    body.addView(ProgressBar(this))
                    progressText=TextView(this).apply { textSize=14f;setTextColor(ContextCompat.getColor(this@ApiConfigurationActivity,R.color.text_primary));setPadding(0,dp(14),0,dp(8)) }
                    body.addView(progressText)
                    progressDialog=AlertDialog.Builder(this).setTitle(if(state.savedConfiguration) "已保存 · 正在检测" else "正在检测 · 不保存修改")
                        .setView(body).setCancelable(false).setNegativeButton("停止检测") {_,_->checker.cancel()}.create().also {
                            it.show();it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        }
                }
                progressText?.text="已发起${state.requests}次请求\n${state.progress}\n${if(state.savedConfiguration) "停止检测不会删除已保存的配置。" else "检测不会修改正在使用的配置。"}"
            } else {
                progressDialog?.dismiss();progressDialog=null;progressText=null
                if(state.report!=null && reportDialog==null) reportDialog=ApiCapabilityDialog.show(this,state.report,
                    done={reportDialog=null;checker.dismissReport();if(state.savedConfiguration) finish()},
                    edit={reportDialog=null;checker.dismissReport()},
                    retry={reportDialog=null;checker.retry()},savedConfiguration=state.savedConfiguration)
                if(state.stopped) Toast.makeText(this,if(state.savedConfiguration) "配置已保存，检测已停止" else "检测已停止，原配置未改变",Toast.LENGTH_LONG).show()
                if(state.failed) Toast.makeText(this,if(state.savedConfiguration) "配置已保存，但本次检测未完成；请稍后重试" else "本次检测未完成，原配置未改变；请稍后重试",Toast.LENGTH_LONG).show()
            }
        }
    }
    private fun dp(value: Int)=(value*resources.displayMetrics.density).toInt()
    override fun onDestroy() {
        progressDialog?.dismiss();reportDialog?.dismiss();progressDialog=null;reportDialog=null
        super.onDestroy()
    }
}
