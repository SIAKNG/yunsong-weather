package com.example.weatherapp

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import org.junit.Assert.*
import org.junit.Test

class CityIndicatorDeviceTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    @Test fun foregroundEntryResetsCityButInternalScreenReturnDoesNot() {
        val main=ins.startActivitySync(android.content.Intent(ins.targetContext,MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        fun awaitStage(expected: androidx.test.runner.lifecycle.Stage) {
            val deadline=SystemClock.elapsedRealtime()+8000
            var actual: androidx.test.runner.lifecycle.Stage?=null
            do {
                ins.runOnMainSync {actual=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(main)}
                if(actual==expected) return
                SystemClock.sleep(50)
            } while(SystemClock.elapsedRealtime()<deadline)
            assertEquals("Physical task lifecycle",expected,actual)
        }
        try {
            awaitStage(androidx.test.runner.lifecycle.Stage.RESUMED)
            SystemClock.sleep(500)
            var target=0
            val monitor=ins.addMonitor(WeatherWidgetActivity::class.java.name,null,false)
            ins.runOnMainSync {
                assertTrue(main.cityList().size>=2)
                target=main.cityList().lastIndex
                main.findViewById<ViewPager2>(R.id.main).setCurrentItem(target,false)
                main.startActivity(android.content.Intent(main,WeatherWidgetActivity::class.java))
            }
            val editor=ins.waitForMonitorWithTimeout(monitor,6000) ?: error("Internal screen did not open")
            ins.waitForIdleSync()
            ins.runOnMainSync {editor.finish()}
            ins.removeMonitor(monitor)
            awaitStage(androidx.test.runner.lifecycle.Stage.RESUMED)
            ins.runOnMainSync {assertEquals("Internal return preserves reading city",target,main.currentPage())}
            ins.runOnMainSync {assertTrue(main.moveTaskToBack(true))}
            awaitStage(androidx.test.runner.lifecycle.Stage.STOPPED)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(
                "am start -W -n ${ins.targetContext.packageName}/com.example.weatherapp.MainActivity -f 0x10020000"
            )).use {it.readBytes()}
            awaitStage(androidx.test.runner.lifecycle.Stage.RESUMED)
            ins.runOnMainSync {assertEquals("New entry uses location or leftmost city",WeatherHomeSelection.defaultIndex(main.cityList()),main.currentPage())}
        } finally {
            ins.runOnMainSync {main.finish()}
            ins.waitForIdleSync()
        }
    }
    @Test fun indicatorMatchesVisibleAddressDuringDragAndReversal() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            SystemClock.sleep(1400)
            var samples = 0
            var mismatches = 0
            scenario.onActivity { activity ->
                assertTrue("At least two saved cities required for physical pager verification", activity.cityList().size >= 2)
                activity.findViewById<ViewPager2>(R.id.main).setCurrentItem(0, false)
            }
            ins.waitForIdleSync()
            repeat(3) {
                scenario.onActivity { activity ->
                    val pager = activity.findViewById<ViewPager2>(R.id.main)
                    assertTrue(pager.beginFakeDrag())
                }
                for (delta in listOf(-.3f, -.35f, .3f, -.4f)) {
                    scenario.onActivity { activity ->
                        val pager = activity.findViewById<ViewPager2>(R.id.main)
                        pager.fakeDragBy(pager.width * delta)
                    }
                    SystemClock.sleep(80)
                    scenario.onActivity { activity ->
                        for (fragment in activity.supportFragmentManager.fragments.filterIsInstance<WeatherFragment>()) {
                            val root = fragment.view ?: continue
                            val overlay = root.findViewById<View>(R.id.topOverlay) ?: continue
                            if (!overlay.isShown || overlay.alpha < .99f) continue
                            val own = activity.cityList().indexOfFirst(fragment::matchesPage)
                            val container = root.findViewById<LinearLayout>(R.id.indicatorContainer)
                            val selected = (0 until container.childCount).firstOrNull { index ->
                                val child = container.getChildAt(index)
                                if (child.background is GradientDrawable)
                                    (child.background as GradientDrawable).color?.defaultColor == Color.WHITE
                                else child.alpha > .9f
                            }
                            samples++
                            if (selected != own) mismatches++
                        }
                    }
                }
                scenario.onActivity { it.findViewById<ViewPager2>(R.id.main).endFakeDrag() }
                SystemClock.sleep(400)
                scenario.onActivity { it.findViewById<ViewPager2>(R.id.main).setCurrentItem(0, false) }
                ins.waitForIdleSync()
            }
            java.io.File(ins.targetContext.getExternalFilesDir(null),"widget-verification/city-indicator.json").apply {
                parentFile?.mkdirs();writeText("{\"samples\":$samples,\"mismatches\":$mismatches}")
            }
            assertTrue(samples > 0)
            assertEquals("Indicator must describe its visible address throughout partial/reversed swipes", 0, mismatches)
        }
    }
}
