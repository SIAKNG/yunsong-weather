package com.example.weatherapp

import org.junit.Assert.*
import org.junit.Test

class RainReminderPolicyTest {
    private val t = 1_800_000_000_000L
    private val minute = 60_000L
    private fun forecast(now: Long = t, duration: Int = 120, rate: (Long) -> Double = { 1.0 }) =
        RainForecast((0 until duration).map { now + it * minute },
            (0 until duration).map { rate(now + it * minute) }, minute)
    private fun state(f: RainForecast = forecast()) = RainReminderPolicy.decide(f, null, t).state!!

    @Test fun unchangedRainDoesNotRepeatAcrossHoursOfRollingForecasts() {
        var old = state()
        for (m in 15..360 step 15) {
            val next = RainReminderPolicy.decide(forecast(t + m * minute), old, t + m * minute)
            assertNull("Unchanged rain at minute $m", next.delivery)
            old = next.state!!
        }
    }
    @Test fun unchangedRainDoesNotRepeatAfterALongBackgroundGap() {
        assertNull(RainReminderPolicy.decide(forecast(t + 8 * 60 * minute), state(),
            t + 8 * 60 * minute).delivery)
    }
    @Test fun countdownAndDryHorizonExtensionDoNotCountAsForecastChanges() {
        val at: (Long) -> Double = { if (it in (t + 60 * minute) until (t + 90 * minute)) 1.0 else 0.0 }
        assertNull(RainReminderPolicy.decide(forecast(t + 30 * minute, rate = at),
            state(forecast(rate = at)), t + 30 * minute).delivery)
    }
    @Test fun ordinaryChangeWaitsForExactHalfHourFromSuccessfulDelivery() {
        val old = state()
        assertNull(RainReminderPolicy.decide(forecast(t + 29 * minute) { 1.5 }, old, t + 29 * minute).delivery)
        assertEquals(RainDelivery.UPDATED, RainReminderPolicy.decide(
            forecast(t + 30 * minute) { 1.5 }, old, t + 30 * minute).delivery)
    }
    @Test fun withheldChangesAreNotConsumedAndRevertedChangesDoNotNotify() {
        val pending = RainReminderPolicy.decide(forecast(t + 15 * minute) { 1.5 }, state(), t + 15 * minute)
        assertNull(pending.delivery)
        assertEquals(RainDelivery.UPDATED, RainReminderPolicy.decide(
            forecast(t + 30 * minute) { 1.5 }, pending.state, t + 30 * minute).delivery)
        assertNull(RainReminderPolicy.decide(forecast(t + 30 * minute), pending.state, t + 30 * minute).delivery)
    }
    @Test fun drizzleToLightRainDoesNotBypassNormalStep() {
        val old = state(forecast { 0.08 })
        assertNull(RainReminderPolicy.decide(forecast(t + minute) { 1.0 }, old, t + minute).delivery)
    }
    @Test fun suddenMediumHeavyAndStormEscalationsEachNotifyOnceWithoutWaiting() {
        var old = state()
        for ((m, rate) in listOf(1 to 3.0, 2 to 9.0, 3 to 20.0)) {
            val result = RainReminderPolicy.decide(forecast(t + m * minute) { rate }, old, t + m * minute)
            assertEquals(RainDelivery.ESCALATED, result.delivery)
            assertNull(RainReminderPolicy.decide(forecast(t + (m + 1) * minute) { rate },
                result.state, t + (m + 1) * minute).delivery)
            old = result.state!!
        }
    }
    @Test fun knownFutureStormApproachingDoesNotBecomeANewEmergency() {
        val at: (Long) -> Double = { if (it >= t + 40 * minute) 20.0 else 1.0 }
        assertNull(RainReminderPolicy.decide(forecast(t + 15 * minute, rate = at),
            state(forecast(rate = at)), t + 15 * minute).delivery)
    }
    @Test fun aShortSuddenStormTakesPriorityOverRainEndingAdvice() {
        val burst = forecast(t + minute) { if (it < t + 20 * minute) 20.0 else 0.0 }
        assertEquals(RainDelivery.ESCALATED, RainReminderPolicy.decide(burst, state(), t + minute).delivery)
        assertEquals(RainDelivery.NEW, RainReminderPolicy.decide(burst, null, t + minute).delivery)
    }
    @Test fun rainEndingWithinHalfHourNotifiesOnceThenDryDoesNotRepeat() {
        val at: (Long) -> Double = { if (it < t + 20 * minute) 1.0 else 0.0 }
        val first = RainReminderPolicy.decide(forecast(rate = at), state(), t)
        assertEquals(RainDelivery.ENDING, first.delivery)
        assertNull(RainReminderPolicy.decide(forecast(t + 10 * minute, rate = at), first.state, t + 10 * minute).delivery)
        val dry = RainReminderPolicy.decide(forecast(t + 20 * minute) { 0.0 }, first.state, t + 20 * minute)
        assertNull(dry.delivery)
        assertFalse(dry.state!!.active)
    }
    @Test fun alreadyEndedRainNotifiesOnceAndNewRainCanStartANewEpisode() {
        val dry = RainReminderPolicy.decide(forecast(t + minute) { 0.0 }, state(), t + minute)
        assertEquals(RainDelivery.ENDING, dry.delivery)
        assertNull(RainReminderPolicy.decide(forecast(t + 2 * minute) { 0.0 }, dry.state, t + 2 * minute).delivery)
        assertEquals(RainDelivery.NEW, RainReminderPolicy.decide(forecast(t + 3 * minute), dry.state, t + 3 * minute).delivery)
    }
    @Test fun dryFirstCheckDoesNotAnnounceAnUnobservedRainEnding() {
        assertNull(RainReminderPolicy.decide(forecast { 0.0 }, null, t).delivery)
    }
    @Test fun cancelledFutureRainMustNotBeDescribedAsRainAlreadyEnded() {
        val upcoming = forecast { if (it >= t + 60 * minute) 1.0 else 0.0 }
        val dry = RainReminderPolicy.decide(forecast(t + minute) { 0.0 }, state(upcoming), t + minute)
        assertNull(dry.delivery)
        assertFalse(dry.state!!.active)
    }
    @Test fun suddenIncreaseAfterHoursStillUsesLatestObservedForecast() {
        var old = state()
        for (m in 15..180 step 15) old = RainReminderPolicy.decide(
            forecast(t + m * minute), old, t + m * minute).state!!
        assertEquals(RainDelivery.ESCALATED, RainReminderPolicy.decide(
            forecast(t + 181 * minute) { 20.0 }, old, t + 181 * minute).delivery)
    }
    @Test fun temporaryPauseWithLaterRainMustNotSuggestNormalTravel() {
        val at: (Long) -> Double = { if (it < t + 10 * minute || it >= t + 70 * minute) 1.0 else 0.0 }
        assertEquals(RainDelivery.NEW, RainReminderPolicy.decide(forecast(rate = at), null, t).delivery)
    }
    @Test fun dryTailMustCoverAtLeastHalfHourBeforeEndingAdvice() {
        val f = forecast(duration = 35) { if (it < t + 20 * minute) 1.0 else 0.0 }
        assertEquals(RainDelivery.NEW, RainReminderPolicy.decide(f, null, t).delivery)
        assertNull(RainReminderPolicy.decide(forecast(t + minute, 10) { 0.0 }, state(), t + minute).delivery)
    }
    @Test fun ancientRainDoesNotGenerateAnEndingToday() {
        assertNull(RainReminderPolicy.decide(forecast(t + 24 * 60 * minute) { 0.0 },
            state(), t + 24 * 60 * minute).delivery)
    }
    @Test fun genuineStartAndEndTimeChangesAreDetectedAtSameAbsoluteTimes() {
        val original: (Long) -> Double = { if (it in (t + 60 * minute) until (t + 90 * minute)) 1.0 else 0.0 }
        val revised: (Long) -> Double = { if (it in (t + 45 * minute) until (t + 80 * minute)) 1.0 else 0.0 }
        assertEquals(RainDelivery.UPDATED, RainReminderPolicy.decide(forecast(t + 30 * minute, rate = revised),
            state(forecast(rate = original)), t + 30 * minute).delivery)
    }
    @Test fun newlyRevealedRainAfterADryHorizonCountsAsChange() {
        val original: (Long) -> Double = { if (it < t + 60 * minute) 1.0 else 0.0 }
        val revised: (Long) -> Double = { if (it < t + 60 * minute || it >= t + 130 * minute) 1.0 else 0.0 }
        assertEquals(RainDelivery.UPDATED, RainReminderPolicy.decide(forecast(t + 30 * minute, rate = revised),
            state(forecast(rate = original)), t + 30 * minute).delivery)
    }
    @Test fun roundingNoiseDoesNotCountAsRainDataChange() {
        assertNull(RainReminderPolicy.decide(forecast(t + 30 * minute) { 1.001 }, state(), t + 30 * minute).delivery)
    }
    @Test fun invalidOrGappedForecastsAreRejected() {
        for (build in listOf<() -> RainForecast>(
            { RainForecast(emptyList(), emptyList(), minute) },
            { forecast { Double.NaN } }, { forecast { -1.0 } },
            { RainForecast(listOf(t, t + 3 * minute), listOf(0.0, 0.0), minute) })) {
            try { build(); fail("Invalid data must not become an all-clear") } catch (_: IllegalArgumentException) {}
        }
    }
}

