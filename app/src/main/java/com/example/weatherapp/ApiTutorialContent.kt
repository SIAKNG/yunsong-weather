package com.example.weatherapp

/** Reviewed against official public documentation on 2026-11-12. No real credential or account data. */
object ApiTutorialContent {
    data class Step(val title: String,val body: String,val path: List<String> = emptyList(),val url: String? = null,val link: String = "打开官方页面", val image: Int? = null)
    const val CAIYUN_PLATFORM = "https://platform.caiyunapp.com/"
    const val CAIYUN_AUTH = "https://docs.caiyunapp.com/weather-api/auth.html"
    const val Q_ACCOUNT = "https://id.qweather.com/"
    const val Q_PROJECT = "https://dev.qweather.com/docs/configuration/project-and-key/"
    const val Q_HOST = "https://dev.qweather.com/docs/configuration/api-host/"
    fun steps(qweather: Boolean): List<Step> = if(qweather) listOf(
        Step("打开和风开发者官网", "这是开发者服务，不是手机天气 App 的登录入口。点击下方按钮，用浏览器打开官网；网址应属于 qweather.com。申请完成再回到云松填写，不要在云松填写官网账号密码。",listOf("开发者官网","用户中心 / 注册登录"),"https://dev.qweather.com/","打开和风开发者官网", R.drawable.guide_q_home),
        Step("免费注册和风账号", "选择“免费注册”，输入你能收信的邮箱并设置独立密码。点击“下一步”，按网页提示验证邮箱、手机号或其他资料。账号和验证码只在官网填写。", listOf("免费注册", "填写邮箱与密码", "完成官网验证"), "https://id.qweather.com/#/register", "打开和风免费注册", R.drawable.guide_q_signup),
        Step("登录和风控制台", "用刚注册的账号登录，已注册用户可以直接从本步开始。登录后打开控制台。", listOf("登录", "进入控制台"), Q_ACCOUNT, "打开和风登录页", R.drawable.guide_q_login),
        Step("创建一个项目", "登录后进入开发者控制台，打开“项目管理”，点击“创建项目”。名称可填写“云松个人天气”，然后保存；再点击刚创建的项目进入详情。项目相当于一个装天气服务配置的文件夹，不是 API Key。",listOf("控制台 → 项目管理","创建项目 → 保存","点击项目名称"),"https://console.qweather.com/project","打开和风项目管理",R.drawable.guide_q_create_docs),
        Step("创建并复制 API Key", "在项目详情的凭据区域点击“添加凭据”或“创建凭据”，填写便于识别的名称，认证方式选 API KEY，保存后复制生成的 API Key。不要复制项目 ID 或凭据 ID。本版本只使用 API Key，不需要生成公钥、私钥。",listOf("项目详情 → 添加凭据","认证方式：API KEY","保存 → 复制 API Key"),Q_PROJECT,"查看官方 API Key 创建说明",R.drawable.guide_q_key_docs),
        Step("再找到专属 API Host", "回到控制台的“设置”，找到 API Host 并完整复制。它是你账号专属的请求域名，可能包含多段子域名。填域名即可，不要加 /v7/weather 等路径，也不要使用旧公共域名、示例域名或其他人的 Host。Host 与 API Key 必须属于同一账号。",listOf("控制台 → 设置","复制自己的 API Host","本 App：和风 API Host"),Q_HOST,"查看 API Host 在哪里",R.drawable.guide_q_host_docs),
        Step("检查服务权限与费用", "和风官方将天气和基础服务合计每月前 50,000 次请求列为 0 元。超过后可按量计费。官网已预告自 2027 年 2 月 1 日起逐步限制 API Key 日请求量；详情以官网和账号规则为准。",listOf("控制台 → 服务 / 费用","检查权限、额度与安全限制"),"https://dev.qweather.com/docs/finance/pricing/","查看和风官方计费说明",R.drawable.guide_q_free_docs),
        Step("回到云松，粘贴两项后保存", "返回“天气设置 → 天气数据”，点“和风天气”标签，将专属域名和 Key 分别填入对应框。希望用和风作为主源时，将天气源优先级选为“和风天气主源”，再点“保存并使用”。若使用彩云主源，也可填写这两项作补充。查看能力检测结果，再回首页刷新。",listOf("天气源：和风天气主源","API Host ＋ API Key","保存并使用 → 首页刷新")),
        Step("遇到问题先核对这几项", "仍提示未配置：检查 Host 和 API Key 是否都填写。认证失败：确认复制的是 Key 而不是 ID，且 Host 与 Key 同属一个账号。权限不足：到官网检查服务和凭据限制。限流或额度不足：查看用量，稍后再试，不要连续刷新。网络故障：先用浏览器检查连接。不要把真实 Key 发到群聊或公开截图。")
    ) else listOf(
        Step("打开彩云开放平台", "点击下方按钮，用浏览器打开彩云开放平台。确认网址是 platform.caiyunapp.com。这里申请的是天气接口，不是购买彩云天气 App 会员；本教程适用于云松支持的 v2 Token 方式。",listOf("彩云开放平台","注册 / 登录"),CAIYUN_PLATFORM,"打开彩云开放平台",R.drawable.guide_cy_home),
        Step("用邮箱注册彩云账号", "在登录页下方点“注册账号”。填写邮箱、密码和确认密码，点击“获取邮箱验证码”，再把邮件收到的验证码填回官网。阅读并同意条款后点击“注册”。",listOf("注册账号","邮箱与密码","填写邮箱验证码并注册"),"https://platform.caiyunapp.com/regist","打开彩云注册页",R.drawable.guide_cy_signup),
        Step("登录彩云开放平台", "用注册的邮箱和密码登录。已有账号可以直接从这里开始。登录后进入控制台，查看天气 API。",listOf("输入邮箱与密码","登录并进入控制台"),CAIYUN_PLATFORM,"打开彩云登录页",R.drawable.guide_cy_login),
        Step("确认申请的是天气服务", "在控制台查看天气 API 的开通、套餐和额度；若需要申请或完善信息，请按官网流程操作。天气会员不等于开发者接口权限，小译/翻译 Token 也不能当成天气 Token。是否有试用、有效期多久和支持哪些数据，以你的控制台为准，不承诺免费或完整字段。",listOf("控制台 → 天气 API","查看开通状态、套餐与额度"),"https://docs.caiyunapp.com/weather-api/billing.html","查看彩云天气 API 计费与服务"),
        Step("进入访问控制 → Token 管理", "彩云官方将“API 凭证管理”和“Token 管理”分开。打开访问控制，再选择 Token 管理，按页面入口申请、创建或查看你有权限使用的天气 Token。若账号只提供 App Key + App Secret，不要把它们拼接或当成 Token；本 App 目前没有该签名方式，可咨询平台或使用和风。",listOf("访问控制","Token 管理（不是 API 凭证管理）","申请 / 创建 / 查看天气 Token"),CAIYUN_AUTH,"查看官方 Token 入口说明",R.drawable.guide_cy_control_docs),
        Step("复制 Token，留意权限", "复制你自己生成的完整天气 Token，不要带空格、引号或换行，不要复制官网文档里的示例值。确认它未过期，并已获所需天气权限；分钟降雨、历史和预报长度等可能受账号套餐限制。Token 像一把钥匙，请勿公开，也不要发给本 App 作者。",listOf("自己的天气 Token","复制完整内容","不复制示例、不公开密钥")),
        Step("回到云松，填写后刷新", "返回“天气设置 → 天气数据”，点“彩云天气”标签，把 Token 粘贴到“彩云 API Token”。如果还填写了和风并希望彩云优先，可在天气源优先级里选彩云主源。接口版本先保留 2.6；只有确认账号有 2.7 权限时才选 2.7。点“保存并使用”后返回首页刷新。和风补充、高德 Key 均非彩云主源的必填项。",listOf("彩云主源 → 填自己的 Token","版本先选 2.6","保存并使用 → 首页刷新")),
        Step("保存成功但没有天气？", "保存成功只表示本地格式通过，不代表官网已开通接口。认证失败先检查 Token、有效期和版本；数据缺项先查套餐权限；额度不足或限流时检查用量，不要反复刷新。若页面只有 App Key / Secret 而没有可用 Token，请勿随意尝试，把获取问题交给彩云官方支持。")
    )
}
