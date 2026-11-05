package com.example.weatherapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope

/** One failed route cannot cancel or postpone publishing another successful route. */
internal object WeatherRiskBranches {
    suspend fun run(isCurrent: () -> Boolean, branches: List<suspend () -> Unit>): RefreshOutcome = supervisorScope {
        if (!isCurrent()) return@supervisorScope RefreshOutcome.NO_DATA
        val outcomes = branches.map { branch ->
            async(Dispatchers.IO) {
                try {
                    currentCoroutineContext().ensureActive()
                    if (!isCurrent()) return@async BranchOutcome.SUCCESS
                    branch()
                    BranchOutcome.SUCCESS
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (WeatherRiskAuthorizationFailure.isAuthorizationFailure(failure)) BranchOutcome.AUTHORIZATION_DENIED
                    else BranchOutcome.RETRYABLE_FAILURE
                }
            }
        }.awaitAll()
        when {
            !isCurrent() -> RefreshOutcome.NO_DATA
            // Successful sibling publications already happened. A denied route must
            // not replay this entire batch on every short retry; check next cycle.
            BranchOutcome.AUTHORIZATION_DENIED in outcomes -> RefreshOutcome.NO_DATA
            BranchOutcome.RETRYABLE_FAILURE in outcomes -> RefreshOutcome.TRANSIENT_FAILURE
            outcomes.isEmpty() -> RefreshOutcome.NO_DATA
            else -> RefreshOutcome.SUCCESS
        }
    }

    private enum class BranchOutcome { SUCCESS, AUTHORIZATION_DENIED, RETRYABLE_FAILURE }

    /** Check again after the blocking request, before any cache or ledger mutation. */
    suspend fun <T> fetchAndPublish(isCurrent: () -> Boolean, fetch: () -> T, publish: (T) -> Unit) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return
        val value = fetch()
        currentCoroutineContext().ensureActive()
        if (isCurrent()) publish(value)
    }
}

internal object WeatherRiskRetryPolicy {
    const val BACKOFF_MINUTES = 1L
    const val MAX_RETRIES = 3
    fun shouldRetry(runAttemptCount: Int): Boolean = runAttemptCount < MAX_RETRIES
}

/** Risk-only identity: location movement, first-city changes or removal discard old responses. */
internal object WeatherRiskFreshness {
    fun isCurrent(capturedEpoch: String, currentEpoch: String, expectedCity: CityPage, currentCity: CityPage?): Boolean =
        capturedEpoch == currentEpoch && expectedCity == currentCity
}

/** No response bodies, URLs or credentials are retained in this classification. */
internal class WeatherRiskAuthorizationFailure : java.io.IOException("Risk route authorization denied") {
    companion object {
        fun isAuthorizationFailure(failure: Throwable): Boolean =
            generateSequence(failure) { it.cause }.take(8).any {
                it is WeatherRiskAuthorizationFailure || Regex("^HTTP (401|403)(?:\\D|$)").containsMatchIn(it.message.orEmpty())
            }
    }
}
