<h1 align="center">云松天气</h1>
<p align="center">把天气、趋势与出行建议，放进一张清晰的天气画面。</p>
<p align="center"><b>Android 原生界面 · 动态天气 · 毛玻璃卡片 · 交互式预报</b></p>
<p align="center">
  <a href="https://github.com/SIAKNG/yunsong-weather/releases/latest">下载安装包</a> ·
  <a href="#界面预览">界面预览</a> · <a href="#天气源与数据边界">天气源</a> ·
  <a href="#第一次使用">使用方法</a> · <a href="#项目组成与实现">工程组成</a> ·
  <a href="CHANGELOG.md">版本更新</a>
</p>

云松天气是一款使用 **Kotlin、XML 与自定义 View / Canvas** 构建的 Android 天气应用。
首页用动态天空和毛玻璃卡片组织实况、短时降雨、48 小时趋势、多日预报及生活建议；
点开卡片，可在详情页横向拖动图表，对照不同时刻的气象变化。

本仓库仅提供**用户 API 配置版**，不包含维护者的天气密钥、个人服务账户或签名私钥。
应用名为“云松天气”，版本标识 `1.3-userapi`，构建号 `8`。维护者：SIAKNG。

未填写个人 API 时默认使用 Open-Meteo 免费天气源；填写完整的彩云或和风接口后优先使用个人来源。
实际数据范围见 [Open-Meteo 覆盖说明](docs/OPEN_METEO.md)。

> 截图采用北京、上海等示例城市；**气象数值、预报、建议和预警均为模拟数据**，不是实时天气或真实预警。
> 它们用于展示界面，并不代表任意 API 都具有图片中的全部权限。

## 版本更新

版本记录按发布时间由新到旧排列；每次更新新增一条记录，保留已有版本的介绍与下载入口。

### 1.3 · 2026-10-29（当前版本）

- 多日预报按日期直达：点击首页列表或折线中的某一天，即可打开对应日期的详情；每一天都有独立摘要、逐小时图表和“返回今日”入口。
- 重构多日详情图表：由原先在同一列表或图表中集中展示所有日期，改为每一天独立显示当天的天气摘要及下方可切换指标的图表。图表统一渐变与高亮样式，温度、体感合并对照并显示温差。
- 完善当天与历史展示：小时图按 00:00–23:00 组织；跨天保留已取得的同源小时预报，未获取的时段保留空缺。
- 加快免费天气加载：Open-Meteo 天气与空气质量并行请求，天气先展示，空气质量随后补齐。
- 改善返回主页时偶发内容不显示的问题，修复卡片进入、退出详情时毛玻璃短暂变清晰的问题。
- 优化检查更新：点击后显示检查进度；仅在确认有新版本时打开下载页，无更新或检查失败时留在设置页显示结果。

[下载 1.3 安装包](https://github.com/SIAKNG/yunsong-weather/releases/download/v1.3/yunsong-weather-1.3.apk) ·
[查看 1.3 发布说明](https://github.com/SIAKNG/yunsong-weather/releases/tag/v1.3) · [完整更新记录](CHANGELOG.md)

### 1.2 · 2026-10-25

- 新增 Open-Meteo 免费天气源，无需填写 API 即可查看天气；配置个人彩云或和风 API 后使用个人天气源。
- 修复空气质量详情仪表盘的显示错误，恢复统一的渐变仪表盘效果。

[下载 1.2 安装包](https://github.com/SIAKNG/yunsong-weather/releases/download/v1.2/yunsong-weather-1.2.apk) ·
[查看 1.2 发布说明](https://github.com/SIAKNG/yunsong-weather/releases/tag/v1.2) ·
[完整更新记录](CHANGELOG.md)

### 1.1 · 2026-10-23（首次发布）

云松天气是一款原生 Android 天气应用，以动态天空和毛玻璃卡片展示当前天气与未来变化。
首次公开发布的是用户自行配置 API 的版本，支持彩云天气和和风天气，不含维护者的天气凭据。

**首次发布功能**

- 天气与城市：实况、体感、高低温、空气质量、多城市管理、定位及新城市天气预览。
- 动态场景：晴、阴、雨、雪、雾霾等背景，昼夜变化、毛玻璃卡片及深浅色模式。
- 分钟降雨：在接口支持时展示短时雨量曲线、雨势变化及出行提醒。
- 48 小时与多日：图示摘要、逐小时趋势、联动时间轴、拖动选择及回到当前。
- 详细气象：空气质量和污染物，以及紫外线、体感温度、风力、气压、能见度、湿度详情。
- 天文与生活：日出日落、太阳轨迹、晨昏参考时段及生活指数建议。
- 预警与提醒：天气预警详情，以及本机降雨、大风、变温和每日天气提醒。
- 上手与配置：交互式首次引导、API 申请教程、加密配置及接口支持情况检测。
- 日常使用：天气缓存、主题与单位设置，以及 GitHub 检查更新。

**1.1 更新内容**

- 整理自填 API 独立工程、正式签名安装包及不含维护者凭证的源码。
- 集中展示检查更新、使用帮助与 API 指引、关于云松天气；图标来源和使用说明保留在关于页。
- 完善图表高亮、时间选择、详情切换、缓存和通知设置等已有体验。
- 提供详细介绍及 26 张原生界面展示图，涵盖首页、详情、预警、降雨、设置与教程。

1.1 需要填写自己的彩云 Token 或和风 API Host + API Key；无需配置的免费天气源由 1.2 新增。

[下载 1.1 安装包](https://github.com/SIAKNG/yunsong-weather/releases/download/v1.1/yunsong-weather-1.1.apk) ·
[查看 1.1 发布说明](https://github.com/SIAKNG/yunsong-weather/releases/tag/v1.1)

后续每次发布将在本节顶部新增版本条目，同时在 [完整更新记录](CHANGELOG.md) 和对应 Releases 页面保留历史内容。

## 功能一览

| 功能 | 可以做什么 |
| --- | --- |
| 实时天气 | 查看气温、天气现象、体感、高低温、空气质量等内容 |
| 动态场景与玻璃卡片 | 按天气及昼夜展示晴、多云、阴、雨、雪、雾霾等背景，并实时模糊卡片背后的内容 |
| 城市与定位 | 顶部地址进入城市管理，搜索、添加、切换城市，查看新城市天气预览；定位可按需授权 |
| 分钟级降雨 | 展示有数据的短时降雨强度、变化曲线、峰值与雨势说明 |
| 48 小时详情 | 图示摘要概括温度、降水、风、紫外线和 AQI；多个图表共享时间轴，支持横滑与回到当前 |
| 多日天气 | 首页按日期直达；逐日摘要与小时图表，温度/体感双线及温差、八项指标胶囊切换、返回今日 |
| 六项指标详情 | 紫外线、体感温度、风力、气压、能见度、湿度分别提供趋势、状态和相关生活建议 |
| 空气质量 | AQI 仪表、等级、污染物浓度和小时趋势；排名数据有效且覆盖城市时显示全国 AQI 排名 |
| 日出日落 | 日出、日落、太阳轨迹、昼长，以及本地计算的晨昏和霞光参考时段 |
| 生活建议 | 展示接口提供的穿衣、运动、防晒、洗车等指数，用统一图标和语义颜色提示 |
| 天气预警 | 预警入口、等级、发布时间、影响区域、详情和防御建议 |
| 本机提醒 | 降雨、大风、明显变温、预警、每日天气提醒；提供权限诊断和测试入口 |
| 主题与单位 | 深色、浅色、系统主题，以及温度、风、气压、能见度单位调整 |
| 引导与 API 检测 | 交互式首次引导、两种天气源申请教程、接口可用性和支持范围表格 |
| 缓存与更新 | 优先恢复已有天气内容，按数据类别判断新鲜度；设置中提供 GitHub 下载及新版本提示 |

**数据时长不是固定承诺。** 48 小时和多日布局按实际返回的数据展示，多日卡布局最多组织 15 日预报。
历史/昨日对比、分钟降水、空气质量、预警、生活指数可能需要额外配置或权限；不是每个套餐都能提供。

## 界面预览

可点击图片查看大图。以下是本项目原生页面在真机上的渲染，不是其他软件截图，也不是另画的概念图。
来源、模拟情景与隐私处理详见 [截图说明](docs/SCREENSHOTS.md)。

### 天气页面与动态场景

| 晴天布局 | 夜间场景 | 降雨与预警入口 |
| :---: | :---: | :---: |
| <img src="docs/screenshots/showcase/weather-home-day.jpg" width="240" alt="北京模拟晴天天气页面"> | <img src="docs/screenshots/showcase/weather-home-night.jpg" width="240" alt="北京模拟夜间场景"> | <img src="docs/screenshots/showcase/weather-home-rain.jpg" width="240" alt="降雨与模拟预警入口"> |

### 指标卡片、分钟降雨与城市管理

| 首页气象卡片 | 分钟级降雨曲线 | 多城市管理 |
| :---: | :---: | :---: |
| <img src="docs/screenshots/showcase/weather-metrics-cards.jpg" width="240" alt="空气质量与气象卡片"> | <img src="docs/screenshots/showcase/minute-rain-card.jpg" width="240" alt="模拟分钟降雨强度与峰值"> | <img src="docs/screenshots/showcase/cities.jpg" width="240" alt="北京上海演示城市列表"> |

分钟降雨卡在有有效降水曲线时出现；接口未返回、未开通权限或未来窗口无降水时，卡片可能不会显示。
图片中的雨势及预警特意构造为演示情景，不是对北京实际天气的播报。

| 多日预报与日出日落卡片 | 生活建议与页面底部 |
| :---: | :---: |
| <img src="docs/screenshots/showcase/weather-daily-card.jpg" width="300" alt="首页多日预报和太阳时刻卡片"> | <img src="docs/screenshots/showcase/weather-footer-life.jpg" width="300" alt="首页生活建议与数据支持说明"> |

### 48 小时天气详情

| 浅色模式 | 深色模式 |
| :---: | :---: |
| <img src="docs/screenshots/showcase/forecast-48h-light.jpg" width="300" alt="浅色48小时详情与图示摘要"> | <img src="docs/screenshots/showcase/forecast-48h-dark.jpg" width="300" alt="深色48小时详情与趋势图"> |

温度范围条与四组图示概括天气变化，并保留穿衣、雨具、防晒等建议。
多个图表共享时间轴，可横滑选中时刻，通过“回到当前”返回当前附近；历史部分采用淡化样式。

### 多日天气详情

| 浅色模式 | 深色模式 |
| :---: | :---: |
| <img src="docs/screenshots/v1.3/forecast-multiday-light.png" width="300" alt="浅色多日天气详情"> | <img src="docs/screenshots/v1.3/forecast-multiday-dark.png" width="300" alt="深色多日天气详情"> |

点击首页列表的一行或折线的一列即可打开对应日期；顶部日期条切换每天的独立摘要与小时图。
下方整体胶囊可切换温度/体感、降水、风力、紫外线、湿度、气压、能见度和空气质量。
温度/体感使用彩色体感主线与灰色气温参考线，显示所选小时的双读数及温差；摘要右侧可返回今日。
图表按当天 00:00–23:00 组织，已缓存的同源预报跨天保留，没有取得的小时保持空缺。
上图为单个模拟日期的页面；实际可切换日期数量和字段覆盖由天气源决定。

### 空气质量与六项指标

| 空气质量 | 紫外线 | 体感温度 |
| :---: | :---: | :---: |
| <img src="docs/screenshots/showcase/air-quality.jpg" width="240" alt="AQI污染物和趋势"> | <img src="docs/screenshots/showcase/metric-uv.jpg" width="240" alt="紫外线趋势与防晒建议"> | <img src="docs/screenshots/showcase/metric-feels_like.jpg" width="240" alt="体感与实际温度对照"> |

| 风力 | 气压 | 能见度 |
| :---: | :---: | :---: |
| <img src="docs/screenshots/showcase/metric-wind.jpg" width="240" alt="风向风力详情"> | <img src="docs/screenshots/showcase/metric-pressure.jpg" width="240" alt="气压详情"> | <img src="docs/screenshots/showcase/metric-visibility.jpg" width="240" alt="能见度详情"> |

| 湿度 | 日出日落 | 生活建议 |
| :---: | :---: | :---: |
| <img src="docs/screenshots/showcase/metric-humidity.jpg" width="240" alt="湿度趋势与建议"> | <img src="docs/screenshots/showcase/sunrise-sunset.jpg" width="240" alt="太阳轨迹和晨昏时段"> | <img src="docs/screenshots/showcase/life-advice.jpg" width="240" alt="彩色生活指数与建议"> |

八类详情支持左右箭头和非图表区域横滑，图表自身继续处理时刻选择。
排名数据不可用时明确提示，不把示例 AQI 拼成真实全国排名。
晨昏/霞光是本地参考计算，并非供应商的“实时霞光”观测。

### 预警、引导与设置

| 预警详情（模拟） | 首次交互引导 | 天气设置 |
| :---: | :---: | :---: |
| <img src="docs/screenshots/showcase/weather-alerts.jpg" width="240" alt="明确标注模拟的预警详情"> | <img src="docs/screenshots/showcase/first-run-guide.jpg" width="240" alt="首次使用交互演示"> | <img src="docs/screenshots/showcase/settings.jpg" width="240" alt="设置帮助关于更新入口"> |

### API 申请教程

| 彩云 Token | 和风 API Key |
| :---: | :---: |
| <img src="docs/screenshots/showcase/api-guide-caiyun.jpg" width="300" alt="彩云Token申请教程"> | <img src="docs/screenshots/showcase/api-guide-qweather.jpg" width="300" alt="和风Host及API Key申请教程"> |

API 填写页保留防截图保护，文档不展示真实 Token、API Key 或个人 API Host。
图标作者、来源及使用说明集中保留在“关于云松天气”，设置页不再单独显示来源按钮。

## 天气源与数据边界

### 可配置服务

| 服务 | 如何配置 | 应用用途 |
| --- | --- | --- |
| Open-Meteo | 无需注册或 Key；无完整个人接口时自动使用 | 模型天气、小时/日预报、历史、日出日落和模型污染物；不提供中国雷达分钟预报、国内官方预警或完整生活指数 |
| 彩云天气 | 用户自己的 v2 Token，兼容 v2.6 / v2.7 路径 | 实况、小时/日预报、可用的分钟降水、AQI 及相关字段；部分天文和历史使用兼容路径 |
| 和风天气 | 自己的 **API Host + API Key** | 可作为天气来源，亦可在配置及权限可用时提供预警、生活指数、空气质量等补充内容 |

申请入口：[彩云开放平台](https://platform.caiyunapp.com/) · [和风开发平台](https://dev.qweather.com/)。
和风在用户配置中仅使用 API Key，不要求普通用户制作 JWT 私钥。
供应商的注册、套餐、免费额度与权限可能变化，请以官方控制台和应用内检测为准。

### 补充数据与本地计算

| 组成 | 用途与限制 |
| --- | --- |
| 来源隔离 | 使用个人彩云/和风接口时不请求 Open-Meteo；请求失败不会自动改用免费数据冒充所选来源 |
| 生态环境部公开 AQI 数据 | 同批次、数据完整且覆盖城市时计算排名，异常时显示不可用 |
| Android 定位/地址解析 | 经授权后获取位置；也可手动添加城市使用 |
| 本地计算 | 太阳轨迹、晨昏、摘要和部分说明；计算结果不冒充原始观测 |

这些接口不是一个统一免费套餐。**App 支持某项展示，不等于你填写的 API 已开通该数据。**
历史、分钟降雨、预警等可用性可能各不相同，请在配置页点击“检测 API 支持情况”。
当前不把小米、心知、墨迹列为可选天气源。

## 第一次使用

1. 在 [Releases](https://github.com/SIAKNG/yunsong-weather/releases/latest) 下载 `yunsong-weather-1.3.apk` 并安装，后续安装包也在文件名中标明版本号。
2. 完成交互引导，认识地址、城市切换、卡片详情和刷新。
3. 可直接使用免费天气；如需增强数据，在“天气设置 → 天气源/API 配置”按教程选填自己的接口信息。
4. 彩云填 v2 Token；和风填 API Host 和 API Key，不要使用他人的密钥。
5. 点击“检测 API 支持情况”查看表格，或“保存并使用”验证后使用。
6. 点击地址管理城市，按需授权定位；点击卡片进入详情，拖动图表查看其他时刻。
7. 需要本机提醒时，再开启通知并检查后台、电池优化和精确闹钟权限。

未配置个人 API 时使用 Open-Meteo；清除个人配置后恢复免费来源。自动模式下彩云优先，其次为完整的和风配置；两者都填写时可指定主源。
本项目不提供维护者的服务配额，不保证任意免费凭据覆盖全部功能。

### 更新与安装

检查更新、使用帮助与 API 指引、关于云松天气集中在设置底部的一张毛玻璃面板中。
点击“检查更新”先显示转圈和“正在检查更新…”；只有确认存在较新正式版本，才打开 GitHub 下载页。
无更新、网络失败或暂不可用时留在设置页显示结果；自动发现新版只显示提醒，不自动打开浏览器。
自动检查成功缓存六小时，失败后十分钟再试；手动点击会重新检查，检查中重复点击不会重复请求。
应用不自动后台下载或安装，错误或限流也不会误报“已是最新版本”。

更新信息通过 GitHub 公开 Release 获取，无需登录 GitHub，也不需要填写 GitHub Token。
网络受限或触发限流时可能暂时无法检查，可直接打开下载页查看最新版本。

正式包使用专用发布证书，**不能直接覆盖旧调试签名版**。请勿贸然卸载，以免丢失城市和 API 配置。
更多规则见 [更新说明](docs/UPDATES.md)。

## 项目组成与实现

本项目是以 **`:app` 为主体的单模块 Android 工程**。下表按实际文件职责说明各部分组成。

| 逻辑组成 | 主要实现 | 职责 |
| --- | --- | --- |
| 首页与城市 | `MainActivity`、`WeatherFragment`、`CityManageActivity`、`CityPreviewActivity` | 城市分页、地址、卡片和预览 |
| 背景与玻璃 | `WeatherBackgroundView`、`WeatherBackgroundPalette`、`BlurCardView`、`DetailGlassSurface` | 动态场景、调色、模糊、描边和阴影 |
| 预报图表 | `HourlyForecastDetailActivity`、`ForecastDetailActivity`、`DailyForecastMetricPanel`、`HourlyMetricChartView` | 48 小时、逐日分页、指标胶囊、温差、高亮和返回今日 |
| 指标与导航 | `MetricDetailActivity`、`AirQualityDetailActivity`、`SunDetailActivity`、`DetailContentPager` | 八类详情、跟手切换和天文展示 |
| 数据接入 | `CaiyunApi.kt`、`QWeatherApi.kt`、`OpenMeteo.kt`、`AirQualityTimeline` | 请求、解析、来源与时间对齐 |
| 缓存与历史 | `WeatherCache`、`CitySummaryCache`、`LifeAdviceCache`、历史时间线/快照文件 | 启动恢复、分组新鲜度和历史对照 |
| 建议与预警 | `LifeAdvice`、`LifeAdviceActivity`、`AlertActivity` | 指数、建议分组、预警等级和正文 |
| 提醒与调度 | `WeatherNotificationCenter`、`WeatherNotificationLedger`、`WeatherBackgroundScheduler` | 本机任务、闹钟、诊断和去重 |
| 配置与帮助 | `UserApiConfiguration`、`ApiCapabilityViewModel`、`ApiTutorialActivity`、`AppUpdateChecker` | 加密配置、支持表格、教程和更新 |

### 工程目录

```text
app/
  src/main/          页面、图表、天气接入与业务逻辑
  src/userApi/       自填接口版本资源与隐私/配置差异
  src/test/          逻辑单元测试
  src/androidTest/   真机回归及显式启用的文档截图夹具
gradle/              依赖版本目录与 Wrapper
docs/                更新、素材来源、截图说明和图片
third-party/         第三方许可
tools/               源码上传边界检查
.github/workflows/   单测与 Release 构建
```

### 技术栈

| 类别 | 当前采用 |
| --- | --- |
| 语言/UI | Kotlin、XML、View / Canvas、AndroidX Activity / AppCompat、Material Components |
| 分页与刷新 | ViewPager2、SwipeRefreshLayout 及项目手势/动画封装 |
| 网络与解析 | Retrofit、Gson、协程及已有网络访问封装 |
| 本地保存 | SharedPreferences、DataStore；用户配置通过 Android Keystore / AES-GCM 加密 |
| 后台任务 | WorkManager、AlarmManager 及系统启动/升级恢复入口 |
| 图标/系统兼容 | AndroidSVG、程序绘制；OPPO 动画使用可选适配，其他设备使用回退效果 |
| 测试 | JUnit 4、AndroidX Test、真机截图与配置保留检查 |
| 构建 | JDK 25、Gradle 9.5.0、AGP 9.3.1（内置 Kotlin 支持）、SDK 37 |

依赖版本以 [版本目录](gradle/libs.versions.toml) 和 [构建文件](app/build.gradle.kts) 为准。

## 构建与验证

最低运行版本 **Android 7.0（API 24）**；相关真机检查使用 Android 16。
不同版本、厂商的模糊、转场和后台调度可能有差异，不承诺全部设备完全一致。

1. 安装兼容的 Android Studio、JDK 25 和 SDK 37，克隆并打开工程。
2. 本机生成 `local.properties`，不要提交它。
3. SDK 命令行包名是 `platforms;android-37.0`，不是 `platforms;android-37`。

```powershell
.\gradlew.bat :app:testUserApiDebugUnitTest
.\gradlew.bat :app:assembleUserApiDebug
.\gradlew.bat :app:assembleUserApiRelease
```

macOS / Linux 使用 `./gradlew`，默认 APK 输出在 `app/build/outputs/apk/`。
Release 默认**未签名**，开发者须自行保管发布证书；仓库没有维护者的签名私钥。
测试覆盖解析、时间线、缓存、导航、图表策略、更新和部分通知逻辑；真机另查 UI、深浅色和配置保留。
测试通过不等于所有天气源/设备/权限组合都已穷尽。不要在日用手机直接运行可能卸载应用的测试任务。

## 隐私、缓存与提醒限制

- 查天气时，所需坐标、IP 和认证信息会发送给对应服务；手动城市也需查询该城市坐标。
- 用户 API 在设备端加密保存，不进代码、截图或 Release；自填版本禁用应用备份。
- 启动优先恢复已有内容，再按观测时间更新；实况/预警/分钟降雨、小时预报、日/天文采用不同新鲜度策略。
- 历史/昨日对比只在有可用数据时展示；缓存或本地推导不冒充实时观测。
- 通知为**本机调度**，受省电、自启动、后台定位和通知权限影响，不是云端推送，不能保证分钟级预警必达。
- 本机提示不能替代气象台预警或防灾指引，重要出行/防灾决定请同时关注官方信息。
- 更新请求不含天气密钥或坐标；GitHub 仍会接收请求 IP、网络元数据和应用版本信息。

## 许可证与素材

维护者有权授权的代码按 **GPL-3.0-only** 提供，见 [LICENSE](LICENSE)。
第三方依赖、图标和商标保留原作者权利及许可，不因代码开源自动重新授权为 GPL。

原天气图标来自 iconfont 用户“桑榆菲碗”的[天气图标集](https://www.iconfont.cn/collections/detail?cid=53861)。
按维护者提供的许可信息，该图标限免费非商用使用；原 SVG 的源码再分发权限尚未明确，
因此本仓库不包含原 SVG，源码默认使用独立绘制的简化图形。

维护者本机构建可从仓库外加载有相应许可的素材：

```powershell
.\gradlew.bat :app:assembleUserApiRelease -PprivateIconAssetsDir="D:/private-assets/weather-app"
```

展示截图和维护者安装包可能采用独立许可素材，默认源码构建的图标细节可能不同。
APK 素材仍可被提取或截图，不保证不可逆向，也不扩大素材使用/分发许可。
详见 [素材构建说明](docs/PRIVATE_ARTWORK.md)、[图标来源](docs/ICON_SOURCES.md)、[第三方声明](THIRD_PARTY_NOTICES.md)。
