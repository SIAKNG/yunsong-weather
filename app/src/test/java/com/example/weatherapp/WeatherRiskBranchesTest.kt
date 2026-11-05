package com.example.weatherapp

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class WeatherRiskBranchesTest {
    @Test fun slowMinuteRouteCannotDelayAlertPublication() = runBlocking {
        val minuteStarted = CompletableDeferred<Unit>()
        val releaseMinute = CompletableDeferred<Unit>()
        val alertPosted = CompletableDeferred<Unit>()
        val job = async {
            WeatherRiskBranches.run({ true }, listOf(
                { minuteStarted.complete(Unit); releaseMinute.await() },
                { minuteStarted.await(); alertPosted.complete(Unit); Unit }
            ))
        }
        try {
            withTimeout(3_000) { alertPosted.await() }
            assertFalse(job.isCompleted)
        } finally { releaseMinute.complete(Unit) }
        assertEquals(RefreshOutcome.SUCCESS, job.await())
    }

    @Test fun failedAlertRouteDoesNotBlockRainAndStillRequestsRetry() = runBlocking {
        val rainPosted = AtomicBoolean(false)
        val result = WeatherRiskBranches.run({ true }, listOf(
            { throw IOException("simulated unauthorized alert route") },
            { WeatherRiskBranches.fetchAndPublish({ true }, { listOf(0.0, 1.0) }, { rainPosted.set(true) }) }
        ))
        assertEquals(RefreshOutcome.TRANSIENT_FAILURE, result)
        assertTrue(rainPosted.get())
    }

    @Test fun failedMinuteFetchCannotClearExistingRainState() = runBlocking {
        var rainState = "existing rain event"
        val result = WeatherRiskBranches.run({ true }, listOf({
            WeatherRiskBranches.fetchAndPublish<List<Double>>({ true },
                { throw IOException("simulated unavailable minute permission") },
                { rainState = "cleared" })
        }))
        assertEquals(RefreshOutcome.TRANSIENT_FAILURE, result)
        assertEquals("existing rain event", rainState)
    }

    @Test fun verifiedDryMinutesMayClearRainState() = runBlocking {
        var cleared = false
        WeatherRiskBranches.fetchAndPublish({ true }, { listOf(0.0, 0.0) }, { cleared = it.all { value -> value == 0.0 } })
        assertTrue(cleared)
    }

    @Test fun sourceChangeDuringFetchDiscardsResultBeforeMutation() = runBlocking {
        var epoch = 1
        var posted = false
        val result = WeatherRiskBranches.run({ epoch == 1 }, listOf({
            WeatherRiskBranches.fetchAndPublish({ epoch == 1 }, { epoch = 2; "old response" }, { posted = true })
        }))
        assertFalse(posted)
        assertEquals(RefreshOutcome.NO_DATA, result)
    }

    @Test fun staleSourceDoesNotStartNetworkRequest() = runBlocking {
        var requested = false
        val result = WeatherRiskBranches.run({ false }, listOf({ requested = true }))
        assertFalse(requested)
        assertEquals(RefreshOutcome.NO_DATA, result)
    }

    @Test fun unsupportedCapabilitiesDoNotPretendToBeSuccessfulEmptyData() = runBlocking {
        assertEquals(RefreshOutcome.NO_DATA, WeatherRiskBranches.run({ true }, emptyList()))
    }

    @Test fun workerCancellationIsNotSwallowedAsNetworkFailure() = runBlocking {
        val job = async {
            WeatherRiskBranches.run({ true }, listOf({ throw CancellationException("worker stopped") }))
        }
        try { job.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
    }

    @Test fun shortRetriesStopAfterThreeRetries() {
        assertEquals(1L, WeatherRiskRetryPolicy.BACKOFF_MINUTES)
        assertTrue(WeatherRiskRetryPolicy.shouldRetry(0))
        assertTrue(WeatherRiskRetryPolicy.shouldRetry(2))
        assertFalse(WeatherRiskRetryPolicy.shouldRetry(3))
        assertFalse(WeatherRiskRetryPolicy.shouldRetry(12))
    }

    @Test fun absentUnauthorizedOrMalformedCaiyunMinutesAreNotDryForecasts() {
        assertFalse(WeatherRiskRefresh.validCaiyunMinutes(null))
        assertFalse(WeatherRiskRefresh.validCaiyunMinutes(Minutely(status = "ok")))
        assertFalse(WeatherRiskRefresh.validCaiyunMinutes(Minutely(status = "error", precipitation2h = listOf(0.0))))
        assertFalse(WeatherRiskRefresh.validCaiyunMinutes(Minutely(status = "ok", precipitation2h = emptyList())))
        assertFalse(WeatherRiskRefresh.validCaiyunMinutes(Minutely(status = "ok", precipitation2h = listOf(Double.NaN))))
        assertFalse(WeatherRiskRefresh.validCaiyunMinutes(Minutely(status = "ok", precipitation2h = listOf(-0.1))))
        assertTrue(WeatherRiskRefresh.validCaiyunMinutes(Minutely(status = "ok", precipitation2h = listOf(0.0, 0.0))))
    }
    @Test fun cityMovementOrFirstCityChangeInvalidatesRiskResponse() = runBlocking {
        val requested = CityPage("synthetic A", 0.0, 0.0, true)
        var current: CityPage? = requested
        var published = false
        val isCurrent = { WeatherRiskFreshness.isCurrent("source_r1", "source_r1", requested, current) }
        val result = WeatherRiskBranches.run(isCurrent, listOf({
            WeatherRiskBranches.fetchAndPublish(isCurrent, {
                current = requested.copy(lat = 0.01)
                "old location response"
            }, { published = true })
        }))
        assertFalse(published)
        assertEquals(RefreshOutcome.NO_DATA, result)
        assertFalse(WeatherRiskFreshness.isCurrent("r1", "r1", requested, requested.copy(isCurrentLocation = false)))
        assertFalse(WeatherRiskFreshness.isCurrent("r1", "r1", requested, null))
        assertTrue(WeatherRiskFreshness.isCurrent("r1", "r1", requested, requested.copy()))
    }

    @Test fun authorizationDenialSuppressesBatchShortRetriesButAllowsSiblingPublication() = runBlocking {
        var published = false
        val result = WeatherRiskBranches.run({ true }, listOf(
            { throw IOException("HTTP 401") },
            { throw IOException("simulated timeout") },
            { published = true }
        ))
        assertTrue(published)
        assertEquals(RefreshOutcome.NO_DATA, result)
    }

    @Test fun authorizationClassificationUsesOnlyExplicitStatusAndDoesNotMistakeServerFailure() {
        assertTrue(WeatherRiskAuthorizationFailure.isAuthorizationFailure(IOException("HTTP 403")))
        assertTrue(WeatherRiskAuthorizationFailure.isAuthorizationFailure(WeatherRiskAuthorizationFailure()))
        assertTrue(WeatherRiskAuthorizationFailure.isAuthorizationFailure(IOException("wrapper", IOException("HTTP 401"))))
        assertFalse(WeatherRiskAuthorizationFailure.isAuthorizationFailure(IOException("HTTP 500")))
        assertFalse(WeatherRiskAuthorizationFailure.isAuthorizationFailure(IOException("HTTP 4010")))
    }

}
