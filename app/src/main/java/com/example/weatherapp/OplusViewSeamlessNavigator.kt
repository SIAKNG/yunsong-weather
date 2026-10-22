package com.example.weatherapp

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.fragment.app.Fragment
import com.oplus.animation.OplusViewSeamless

/**
 * 将主页的玻璃卡片安全接入 OPPO View 无缝转场。
 *
 * SDK 的实现由支持该能力的 ColorOS 系统提供，因此依赖保持 compileOnly；不支持的
 * ROM、动效等级或窗口模式下 setSeamlessView 会返回 false，并自动使用普通 Activity 转场。
 */
object OplusViewSeamlessNavigator {
    internal const val EXTRA_ENTRY_STARTED = "weather_seamless_entry_started"
    private const val TAG = "ViewSeamless"
    private const val DEFAULT_CARD_RADIUS_DP = 16f

    fun startFromCard(
        fragment: Fragment,
        source: View,
        intent: Intent,
        cornerRadiusDp: Float = DEFAULT_CARD_RADIUS_DP
    ): Boolean {
        val activity = fragment.activity
        if (activity == null || !source.isAttachedToWindow) {
            fragment.startActivity(intent)
            return false
        }
        return startFromCard(activity, source, intent, cornerRadiusDp)
    }

    fun startFromCard(
        activity: Activity,
        source: View,
        intent: Intent,
        cornerRadiusDp: Float = DEFAULT_CARD_RADIUS_DP
    ): Boolean = start(
            activity = activity,
            source = source,
            intent = intent,
            cornerRadiusDp = cornerRadiusDp,
            sourceHasAlpha = true,
            requestCode = null
        )

    fun startForResultFromView(
        activity: Activity,
        source: View,
        intent: Intent,
        requestCode: Int,
        cornerRadiusDp: Float = 0f,
        sourceHasAlpha: Boolean = false
    ): Boolean = start(
            activity = activity,
            source = source,
            intent = intent,
            cornerRadiusDp = cornerRadiusDp,
            sourceHasAlpha = sourceHasAlpha,
            requestCode = requestCode
        )

    private fun start(
        activity: Activity,
        source: View,
        intent: Intent,
        cornerRadiusDp: Float,
        sourceHasAlpha: Boolean,
        requestCode: Int?
    ): Boolean {
        (activity as? MainActivity)?.markWeatherCardOpened()
        if (!source.isAttachedToWindow) {
            if (requestCode == null) activity.startActivity(intent)
            else activity.startActivityForResult(intent, requestCode)
            return false
        }
        val options = Bundle().apply {
            // SDK 要求：声明这是一次由卡片 View 发起的无缝打开动画。
            putBoolean(OplusViewSeamless.VIEW_SEAMLESS_OPEN, true)
            // 文档要求圆角卡片显式传入，getDimension 对应像素值而不是 dp 值。
            putFloat(
                OplusViewSeamless.BUNDLE_RADIUS,
                cornerRadiusDp * source.resources.displayMetrics.density
            )
            // 首页卡片是半透明的实时毛玻璃，按 SDK 提示声明以避免过渡期间错误取色。
            putBoolean(OplusViewSeamless.BUNDLE_VIEW_WITH_ALPHA, sourceHasAlpha)
            // 系统会单独绘制源卡片的 transition leash，源 View 本身必须保持隐藏；
            // 否则会与系统卡片叠加，导致返回时看到双重文字、图标或边框。显式传入
            // false，确保打开和返回始终由同一份系统动画画面接管。
            putBoolean(OplusViewSeamless.BUNDLE_VIEW_VISIBLE, false)
            // 返回时若卡片因页面滚动而改变位置，SDK 自动降级为淡出，避免回弹到错误位置。
            putBoolean(OplusViewSeamless.BUNDLE_ALPHA_OUT_ON_POSITION_CHANGE, true)
        }

        val supported = try {
            OplusViewSeamless.setSeamlessView(source, activity, options, null)
        } catch (error: RuntimeException) {
            Log.w(TAG, "View seamless unavailable; fallback to normal transition", error)
            false
        } catch (error: NoSuchMethodError) {
            Log.w(TAG, "View seamless API mismatch; fallback to normal transition", error)
            false
        } catch (error: LinkageError) {
            Log.w(TAG, "View seamless runtime is absent; fallback to normal transition", error)
            false
        }

        if (supported) {
            intent.putExtra(EXTRA_ENTRY_STARTED, android.os.SystemClock.uptimeMillis())
            if (requestCode == null) activity.startActivity(intent, options)
            else activity.startActivityForResult(intent, requestCode, options)
        } else {
            // 不支持 OPPO 无缝动画的 ROM 之前会直接跳页，因而看起来像“卡片动效丢失”。
            // 这里使用系统的卡片裁剪展开作为稳定降级；支持无缝动画的设备仍走上面的原生路径。
            val fallback = ActivityOptions.makeClipRevealAnimation(
                source,
                0,
                0,
                source.width.coerceAtLeast(1),
                source.height.coerceAtLeast(1)
            ).toBundle()
            if (requestCode == null) activity.startActivity(intent, fallback)
            else activity.startActivityForResult(intent, requestCode, fallback)
        }
        Log.i(TAG, "${if (supported) "Started seamless" else "Started clip-reveal fallback"}: ${intent.component?.className.orEmpty()}")
        return supported
    }

    /**
     * 详情页刚返回时若用户立即开始滚动，主动停止尚未结束的系统过渡，保证列表优先跟手。
     */
    fun finishRunningAnimation() {
        try {
            OplusViewSeamless.finishCurrentAnimation()
        } catch (_: RuntimeException) {
            // 非 OPPO / 不支持系统不需要处理。
        } catch (_: LinkageError) {
            // 系统接口版本较旧时安全降级。
        }
    }

    fun finishWhenUserInteracts(root: View) {
        val slop = android.view.ViewConfiguration.get(root.context).scaledTouchSlop.toFloat()
        var downX = 0f
        var downY = 0f
        var cancelledForGesture = false
        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    cancelledForGesture = false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!cancelledForGesture &&
                        kotlin.math.hypot((event.rawX - downX).toDouble(), (event.rawY - downY).toDouble()) > slop
                    ) {
                        // 只有用户确认开始拖动时才停止过渡；普通点击不再打断来源卡的回位动画。
                        finishRunningAnimation()
                        cancelledForGesture = true
                    }
                }
            }
            false
        }
    }
}
