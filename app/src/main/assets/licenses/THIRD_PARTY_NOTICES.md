# 第三方声明与素材核查

## 项目与服务

本仓库仅为自填 API 版本。彩云天气、和风天气、Android、ColorOS 等名称属于其相应权利人；本应用不代表这些服务的官方客户端。
API 使用权、套餐与数据再展示条件由相应服务决定，软件许可证不授予第三方 API 使用权。
个人版使用过的七张天气源品牌字标 PNG 未纳入本仓库；自填版本使用文字标注数据来源。

## 直接依赖

以下说明为直接依赖索引，不代替各版本及其传递依赖的完整许可证；原始著作权和许可文本仍适用。
通用许可证文本随仓库保存在 `third-party/licenses/`。上游特有的 NOTICE/著作权声明仍须保留，不以通用许可文本替代。

| 依赖 | 上游 | 许可索引 |
| --- | --- | --- |
| AndroidX Activity / AppCompat / ConstraintLayout / Core / ViewPager2 / SwipeRefreshLayout / WorkManager / DataStore | https://android.googlesource.com/platform/frameworks/support/ | Apache-2.0 |
| Material Components for Android | https://github.com/material-components/material-components-android | Apache-2.0 |
| Retrofit / converter-gson | https://github.com/square/retrofit | Apache-2.0；Gson 保留自身声明 |
| AndroidSVG | https://github.com/BigBadaboom/androidsvg | Apache-2.0 |
| kotlinx.coroutines | https://github.com/Kotlin/kotlinx.coroutines | Apache-2.0 |
| EdDSA-Java | https://github.com/str4d/ed25519-java | CC0-1.0 |
| JUnit 4（测试） | https://github.com/junit-team/junit4 | EPL-1.0 |
| Gradle Wrapper | https://github.com/gradle/gradle | Apache-2.0 |
| OPPO View Seamless（仅 compileOnly） | Maven 坐标 com.oplus.animation:viewseamless:1.0.0 | 仅编译引用，运行实现由支持设备提供；不在仓库分发 AAR 或系统实现 |

## 图像和 SVG

维护者提供的天气图标来源：iconfont（阿里巴巴矢量图标库），作者 **桑榆菲碗**，图标集 **天气图标**：
https://www.iconfont.cn/collections/detail?cid=53861 。作者与图标集名称已于 2026-10-23 通过该网站公开接口核对。

维护者说明原作者允许免费非商用使用；目前未取得可核对的原始授权文本，也未确认是否包含将原 SVG 放入公开源码仓库、修改及再分发的权限。因此 87 个原 SVG 已从本公开工程及其 Git 历史排除。
**这批图标不在本项目 GPL-3.0-only 的重新授权范围内，保留原作者版权及适用限制。署名和不收费不能代替完整再分发许可。**

默认公开构建使用 `PublicWeatherIconRenderer.kt` 内新编写的基础几何图形，不含原 SVG 路径或像素。生活建议当前由程序绘制，并不读取旧的 life_advice SVG。
可选私有素材仅从仓库外指定目录合并，本机带素材构建仍需遵守原作者许可；不声称加密、混淆或不提供原文件就可免除署名/许可要求。
应用图标及其他派生资源的完整来源核验仍不等同于已完成全面权利审计。
更完整的核对状态见 [docs/ICON_SOURCES.md](docs/ICON_SOURCES.md)。本记录不是法律合规结论。
