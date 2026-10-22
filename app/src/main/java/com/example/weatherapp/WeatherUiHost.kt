package com.example.weatherapp

// 天气页宿主动画接口：主页（MainActivity）和城市预览页都实现，
// WeatherFragment 通过它上报天气背景与状态栏配色，避免强依赖 MainActivity
interface WeatherUiHost {
    fun hostWeatherBackground(skycon: String?, night: Boolean)
    fun hostStatusBarStyle(lightIcons: Boolean)

    /**
     * 背景底部的地形只在天气内容真正读到底时出现。由当前城市页上报，
     * 防止 ViewPager 中不可见页面的滚动状态误把它提前显示出来。
     */
    fun hostBackgroundFooterVisible(visible: Boolean) {
    }

    /**
     * 页面底部信息会随 ScrollView 实际上推进入视野。背景底部插画同步接收同一进度，
     * 以位移进入而非独立淡入；默认实现保留给旧宿主使用。
     */
    fun hostBackgroundFooterProgress(progress: Float) {
        hostBackgroundFooterVisible(progress > 0.01f)
    }

    /** 顶部城市/操作栏的下边界；所有云朵必须在此安全区以下绘制。 */
    fun hostBackgroundCloudSafeTop(safeTopPx: Int) {
    }

    // 城市预览页没有滚动蒙版；改为按相同阈值淡出中间城市名。
    fun hostPreviewCityTitleAlpha(alpha: Float) {
    }

    // 预览页复用主页顶部蒙版的滚动进度；默认宿主无需处理。
    fun hostPreviewTopMaskProgress(progress: Float) {
    }

    // 预览页由宿主承载顶部蒙版；把其实际下边界回传给天气页，
    // 使主页与预览页共用同一套滚动显隐阈值。
    fun hostTopMaskBottom(): Int? = null

    // 天气数据加载后上报城市快照，供城市管理页进入前直接显示（默认空实现，预览页可忽略）
    fun hostWeatherSnapshot(
        name: String,
        temp: String?,
        desc: String?,
        skycon: String?,
        night: Boolean
    ) {
    }
}
