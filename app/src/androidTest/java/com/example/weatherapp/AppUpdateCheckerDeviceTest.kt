package com.example.weatherapp

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class AppUpdateCheckerDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun json(tag: String)="""{"draft":false,"prerelease":false,"tag_name":"$tag","assets":[{"name":"app.apk","size":100,"state":"uploaded","browser_download_url":"https://github.com/SIAKNG/yunsong-weather/releases/download/$tag/app.apk"}]}"""
    private fun fixture(block: (Context)->Unit) {
        val prefs=context.getSharedPreferences("update_checker_test_fixture",0)
        val scoped=object : ContextWrapper(context) {
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(name:String,mode:Int)=prefs
        }
        ActivityScenario.launch(WeatherAboutActivity::class.java).use {
            prefs.edit().clear().commit()
            try {block(scoped)} finally {prefs.edit().clear().commit()}
        }
    }

    @Test fun manualCheckBypassesSuccessAndFailureCaches() = fixture { c ->
        var now=1000L;var calls=0;var response=200 to json("v9.9")
        val checker=AppUpdateChecker(c,{calls++;response},{now})
        assertEquals(AppUpdatePolicy.Status.NEW_VERSION,checker.check().status)
        response=200 to json("v1.3")
        assertEquals(AppUpdatePolicy.Status.NEW_VERSION,checker.check().status)
        assertEquals(1,calls)
        now++
        assertEquals(AppUpdatePolicy.Status.CURRENT,checker.check(true).status);assertEquals(2,calls)
        now++;response=503 to ""
        assertEquals(AppUpdatePolicy.Status.FAILED,checker.check(true).status)
        response=200 to json("v1.3")
        assertEquals(AppUpdatePolicy.Status.FAILED,checker.check().status)
        now++
        assertEquals(AppUpdatePolicy.Status.CURRENT,checker.check(true).status);assertEquals(4,calls)
    }

    @Test fun olderAutomaticCompletionCannotReplaceNewerCache() = fixture { c ->
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val executor=Executors.newSingleThreadExecutor()
        try {
            val older=executor.submit<AppUpdatePolicy.Result> {
                AppUpdateChecker(c,{entered.countDown();release.await(3,TimeUnit.SECONDS);200 to json("v1.3")},{1000L}).check(true)
            }
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            assertEquals(AppUpdatePolicy.Status.NEW_VERSION,AppUpdateChecker(c,{200 to json("v9.9")},{2000L}).check(true).status)
            release.countDown();older.get(2,TimeUnit.SECONDS)
            assertEquals(AppUpdatePolicy.Status.NEW_VERSION,AppUpdateChecker(c,{fail("Fresh cache should be retained");500 to ""},{2500L}).check().status)
        } finally {release.countDown();executor.shutdownNow()}
    }
}
