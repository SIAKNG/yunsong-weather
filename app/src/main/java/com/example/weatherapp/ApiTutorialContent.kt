package com.example.weatherapp

/** Reviewed against official public documentation on 2026-10-20. No real credential or account data. */
object ApiTutorialContent {
    data class Step(val title: String,val body: String,val path: List<String> = emptyList(),val url: String? = null,val link: String = "打开官方页面")
    const val CAIYUN_PLATFORM = "https://platform.caiyunapp.com/"
    const val CAIYUN_AUTH = "https://docs.caiyunapp.com/weather-api/auth.html"
    const val Q_ACCOUNT = "https://id.qweather.com/"
    const val Q_PROJECT = "https://dev.qweather.com/docs/configuration/project-and-key/"
    const val Q_HOST = "https://dev.qweather.com/docs/configuration/api-host/"
    fun steps(qweather: Boolean): List<Step> = if(qweather) listOf(
        Step("打开和风开发者官网", "这是开发者服务，不是手机天气 App 的登录入口。点击下方按钮，用浏览器打开官网；网址应属于 qweather.com。申请完成再回到云松填写，不要在云松填写官网账号密码。",listOf("开发者官网","用户中心 / 注册登录"),"https://dev.qweather.com/","打开和风开发者官网"),
        Step("注册账号，再登录", "没有账号：在用户中心选择注册，按网页要求填写本人可用的电子邮箱和手机号，完成验证码等验证并设置独立密码。已有账号：选择登录。验证码只填在官网；忘记密码请在官网找回。具体验证步骤以当前页面为准。",listOf("用户中心","注册 / 已有账号登录","完成官网验证"),Q_ACCOUNT,"打开和风注册 / 登录"),
        Step("创建一个项目", "登录后进入开发者控制台，打开“项目管理”，点击“创建项目”。名称可填写“云松个人天气”，然后保存；再点击刚创建的项目进入详情。项目相当于一个装天气服务配置的文件夹，不是 API Key。",listOf("控制台 → 项目管理","创建项目 → 保存","点击项目名称"),"https://console.qweather.com/","打开和风控制台"),
        Step("创建并复制 API Key", "在项目详情的凭据区域点击“添加凭据”或“创建凭据”，填写便于识别的名称，认证方式选 API KEY，保存后复制生成的 API Key。不要复制项目 ID 或凭据 ID。本版本只使用 API Key，不需要生成公钥、私钥。",listOf("项目详情 → 添加凭据","认证方式：API KEY","保存 → 复制 API Key"),Q_PROJECT,"查看官方 API Key 创建说明"),
        Step("再找到专属 API Host", "回到控制台的“设置”，找到 API Host 并完整复制。它是你账号专属的请求域名，可能包含多段子域名。填域名即可，不要加 /v7/weather 等路径，也不要使用旧公共域名、示例域名或其他人的 Host。Host 与 API Key 必须属于同一账号。",listOf("控制台 → 设置","复制自己的 API Host","本 App：和风 API Host"),Q_HOST,"查看 API Host 在哪里"),
        Step("检查服务权限与费用", "确认账号已开通需要的天气数据服务，并查看额度、计费与凭据的应用/API 限制。实况、预报、空气质量、生活指数等权限可能不同。不要把“能创建 Key”理解为所有数据永久免费；本 App 不会替你购买或开通服务。官网已预告 2027 年起限制 API Key 日请求量，时间和额度以最新公告为准。",listOf("控制台 → 服务 / 费用","检查权限、额度与安全限制"),"https://dev.qweather.com/docs/finance/pricing/","查看和风官方计费说明"),
        Step("回到云松，粘贴两项后保存", "返回“天气源 · 自行配置”：先选择“和风天气主源”，再将刚复制的域名填到“和风 API Host”，将 Key 填到“和风 API Key”，点击“保存并使用”。若使用彩云主源，也可填写这两项作补充。保存只是本机格式校验；返回首页刷新才能确认账号实际可用。",listOf("天气源：和风天气主源","API Host ＋ API Key","保存并使用 → 首页刷新")),
        Step("遇到问题先核对这几项", "仍提示未配置：检查 Host 和 API Key 是否都填写。认证失败：确认复制的是 Key 而不是 ID，且 Host 与 Key 同属一个账号。权限不足：到官网检查服务和凭据限制。限流或额度不足：查看用量，稍后再试，不要连续刷新。网络故障：先用浏览器检查连接。不要把真实 Key 发到群聊或公开截图。")
    ) else listOf(
        Step("打开彩云开放平台", "点击下方按钮，用浏览器打开彩云开放平台。确认网址是 platform.caiyunapp.com。这里申请的是天气接口，不是购买彩云天气 App 会员；本教程适用于云松支持的 v2 Token 方式。",listOf("彩云开放平台","注册 / 登录"),CAIYUN_PLATFORM,"打开彩云开放平台"),
        Step("注册并登录你的账号", "新用户选择注册，已有账号选择登录；按当前官网提供的手机号、邮箱、验证码或其他登录入口完成操作。若网页要求补充资料、验证或同意协议，请在读清楚后自行完成。不要把官网密码、短信验证码当成 Token 填进云松。手机页面拥挤时可尝试浏览器的桌面版网站。",listOf("官网注册 / 登录","按页面完成身份验证","进入开放平台控制台"),CAIYUN_PLATFORM,"打开注册 / 登录页面"),
        Step("确认申请的是天气服务", "在控制台查看天气 API 的开通、套餐和额度；若需要申请或完善信息，请按官网流程操作。天气会员不等于开发者接口权限，小译/翻译 Token 也不能当成天气 Token。是否有试用、有效期多久和支持哪些数据，以你的控制台为准，不承诺免费或完整字段。",listOf("控制台 → 天气 API","查看开通状态、套餐与额度"),"https://caiyunapp.com/api/weather","查看彩云天气 API 服务"),
        Step("进入访问控制 → Token 管理", "彩云官方将“API 凭证管理”和“Token 管理”分开。打开访问控制，再选择 Token 管理，按页面入口申请、创建或查看你有权限使用的天气 Token。若账号只提供 App Key + App Secret，不要把它们拼接或当成 Token；本 App 目前没有该签名方式，可咨询平台或使用和风。",listOf("访问控制","Token 管理（不是 API 凭证管理）","申请 / 创建 / 查看天气 Token"),CAIYUN_AUTH,"查看官方 Token 入口说明"),
        Step("复制 Token，留意权限", "复制你自己生成的完整天气 Token，不要带空格、引号或换行，不要复制官网文档里的示例值。确认它未过期，并已获所需天气权限；分钟降雨、历史和预报长度等可能受账号套餐限制。Token 像一把钥匙，请勿公开，也不要发给本 App 作者。",listOf("自己的天气 Token","复制完整内容","不复制示例、不公开密钥")),
        Step("回到云松，填写后刷新", "返回“天气源 · 自行配置”，选择“彩云主源 · 和风按字段补充”，把 Token 粘贴到“彩云 API Token”。接口版本先保留 2.6；只有确认账号有 2.7 权限时才选 2.7。点“保存并使用”后返回首页刷新。和风补充、高德 Key 均非彩云主源的必填项。",listOf("彩云主源 → 填自己的 Token","版本先选 2.6","保存并使用 → 首页刷新")),
        Step("保存成功但没有天气？", "保存成功只表示本地格式通过，不代表官网已开通接口。认证失败先检查 Token、有效期和版本；数据缺项先查套餐权限；额度不足或限流时检查用量，不要反复刷新。若页面只有 App Key / Secret 而没有可用 Token，请勿随意尝试，把获取问题交给彩云官方支持。")
    )
}
