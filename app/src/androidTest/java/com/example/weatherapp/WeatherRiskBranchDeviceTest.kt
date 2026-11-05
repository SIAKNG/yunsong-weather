package com.example.weatherapp

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Deterministic injected routes; does not use network, preferences, notifications or location. */
class WeatherRiskBranchDeviceTest {
    @Test fun injectedSlowMinuteRequestCannotHoldBackOfficialAlert() = runBlocking {
        val minuteStarted = CompletableDeferred<Unit>()
        val releaseMinute = CompletableDeferred<Unit>()
        val alertPublished = CompletableDeferred<Unit>()
        val result = async {
            WeatherRiskBranches.run({ true }, listOf(
                { minuteStarted.complete(Unit); releaseMinute.await(); throw IOException("simulated minute timeout") },
                {
                    minuteStarted.await()
                    WeatherRiskBranches.fetchAndPublish({ true }, { "synthetic official alert" }, {
                        alertPublished.complete(Unit)
                        Unit
                    })
                }
            ))
        }
        try {
            withTimeout(3_000) { alertPublished.await() }
            assertFalse(result.isCompleted)
        } finally { releaseMinute.complete(Unit) }
        assertEquals(RefreshOutcome.TRANSIENT_FAILURE, result.await())
    }

    @Test fun injectedAlertDenialStillAllowsRainAndPreservesAlertState() = runBlocking {
        var oldAlert = "keep existing alert"
        val rainPublished = AtomicBoolean(false)
        val result = WeatherRiskBranches.run({ true }, listOf(
            { WeatherRiskBranches.fetchAndPublish<String>({ true }, { throw IOException("simulated 403") }, { oldAlert = it }) },
            { WeatherRiskBranches.fetchAndPublish({ true }, { "synthetic rain" }, { rainPublished.set(true) }) }
        ))
        assertEquals(RefreshOutcome.TRANSIENT_FAILURE, result)
        assertEquals("keep existing alert", oldAlert)
        assertTrue(rainPublished.get())
    }

    @Test fun injectedSourceChangeDuringRequestPreventsPublication() = runBlocking {
        var current = true
        var published = false
        val result = WeatherRiskBranches.run({ current }, listOf({
            WeatherRiskBranches.fetchAndPublish({ current }, { current = false; "obsolete response" }, { published = true })
        }))
        assertEquals(RefreshOutcome.NO_DATA, result)
        assertFalse(published)
    }
}
