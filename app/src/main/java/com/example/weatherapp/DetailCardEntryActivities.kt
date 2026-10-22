package com.example.weatherapp

/**
 * 首页卡片展开的专用不透明入口。
 *
 * 详情父类本身采用透明窗口，让详情之间横滑时能够同时绘制新旧内容。ColorOS 的
 * View Seamless 会跳过透明目标窗口，因而首页必须实际启动这些子 Activity，而不是
 * 仅通过 activity-alias 试图替换主题。子类不改动任何页面逻辑；页内横滑仍启动父类。
 */
class MetricDetailCardEntryActivity : MetricDetailActivity()

class AirQualityDetailCardEntryActivity : AirQualityDetailActivity()

class SunDetailCardEntryActivity : SunDetailActivity()
