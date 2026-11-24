package com.example.weatherapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import java.util.Locale
import com.example.weatherapp.WeatherDiagnostics.Event

/** Foreground location uses Android providers and is independent of weather service credentials. */
internal class ForegroundWeatherLocation(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val cancellations = mutableSetOf<() -> Unit>()

    fun isEnabled(): Boolean = runCatching { LocationManagerCompat.isLocationEnabled(manager) }.getOrDefault(false)

    fun cancelRequests() {
        cancellations.toList().forEach { it() }
    }

    private fun isRecent(location: Location): Boolean {
        val age = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        return location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0 &&
            location.elapsedRealtimeNanos > 0L && age in 0..(WeatherRefreshPolicy.LOCATION_CACHE_MS * 1_000_000L)
    }

    @SuppressLint("MissingPermission")
    fun request(preferCache: Boolean, timeoutMillis: Long, onResult: (Location?, Event) -> Unit) {
        WeatherDiagnostics.record(context, Event.LOCATION_START)
        fun fail(reason: Event) {
            WeatherDiagnostics.record(context, reason)
            onResult(null, reason)
        }
        if (!WeatherPermissionCoordinator.hasForegroundLocation(context)) {
            fail(Event.LOCATION_PERMISSION)
            return
        }
        if (!isEnabled()) {
            fail(Event.LOCATION_OFF)
            return
        }
        val precise = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        WeatherDiagnostics.record(context, if (precise) Event.LOCATION_PRECISE else Event.LOCATION_APPROXIMATE)
        // Modern Android can deliver an approximate fix from GNSS under coarse permission.
        // A vendor that rejects it is handled the same way as any unavailable provider.
        val providers = WeatherDiagnostics.locationProviders
            .filter { it != LocationManager.GPS_PROVIDER || precise || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

        fun recentCache() = (providers + LocationManager.PASSIVE_PROVIDER).mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.filter(::isRecent).maxByOrNull { it.elapsedRealtimeNanos }
        if (preferCache) {
            recentCache()?.let { cached ->
                WeatherDiagnostics.record(context, Event.LOCATION_CACHED, cached.provider)
                onResult(cached, Event.LOCATION_CACHED)
                return
            }
        }

        var finished = false
        lateinit var timeout: Runnable
        lateinit var cancel: () -> Unit
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (finished || !isRecent(location)) return
                finished = true
                cancellations.remove(cancel)
                main.removeCallbacks(timeout)
                runCatching { manager.removeUpdates(this) }
                WeatherDiagnostics.record(context, Event.LOCATION_RECEIVED, location.provider)
                onResult(location, Event.LOCATION_RECEIVED)
            }

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Legacy Android callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        fun stop() {
            finished = true
            cancellations.remove(cancel)
            main.removeCallbacks(timeout)
            runCatching { manager.removeUpdates(listener) }
        }
        cancel = {
            stop()
            fail(Event.LOCATION_CANCELLED)
        }
        fun fallback(reason: Event) {
            stop()
            val cached = if (isEnabled() && WeatherPermissionCoordinator.hasForegroundLocation(context)) recentCache() else null
            if (cached == null) {
                fail(reason)
            } else {
                WeatherDiagnostics.record(context, reason)
                WeatherDiagnostics.record(context, Event.LOCATION_CACHED, cached.provider)
                onResult(cached, Event.LOCATION_CACHED)
            }
        }
        timeout = Runnable {
            fallback(Event.LOCATION_TIMEOUT)
        }
        var registered = false
        for (provider in providers) {
            try {
                manager.requestLocationUpdates(provider, 0L, 0f, listener, main.looper)
                registered = true
                WeatherDiagnostics.record(context, Event.LOCATION_PROVIDER_STARTED, provider)
            } catch (_: RuntimeException) {
                WeatherDiagnostics.record(context, Event.LOCATION_PROVIDER_FAILED, provider)
            }
        }
        if (registered) {
            cancellations.add(cancel)
            main.postDelayed(timeout, timeoutMillis)
        } else {
            fallback(Event.LOCATION_NO_PROVIDER)
        }
    }

    @Suppress("DEPRECATION")
    fun reverseGeocode(location: Location, onResult: (String?, String?) -> Unit) {
        if (!runCatching { Geocoder.isPresent() }.getOrDefault(false)) {
            WeatherDiagnostics.record(context, Event.ADDRESS_UNAVAILABLE)
            onResult(null, null)
            return
        }
        var finished = false
        lateinit var cancel: () -> Unit
        val timeout = Runnable {
            if (finished) return@Runnable
            finished = true
            cancellations.remove(cancel)
            WeatherDiagnostics.record(context, Event.ADDRESS_TIMEOUT)
            onResult(null, null)
        }
        cancel = {
            finished = true
            cancellations.remove(cancel)
            main.removeCallbacks(timeout)
            onResult(null, null)
        }
        cancellations.add(cancel)
        main.postDelayed(timeout, 2_500L)
        Thread {
            val address = runCatching {
                Geocoder(context, Locale.CHINA).getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
            }.getOrNull()
            main.post {
                if (finished) return@post
                finished = true
                cancellations.remove(cancel)
                main.removeCallbacks(timeout)
                val name = listOf(address?.locality, address?.subAdminArea, address?.adminArea)
                    .firstOrNull { !it.isNullOrBlank() }
                val detail = address?.let {
                    val district = it.subLocality ?: it.subAdminArea
                    if (listOf(name, district, it.thoroughfare, it.featureName).all { part -> part.isNullOrBlank() }) {
                        null
                    } else {
                        WeatherAddressFormatter.fromParts(name, district, it.thoroughfare, it.featureName)
                    }
                }
                WeatherDiagnostics.record(context, if (name == null && detail == null) Event.ADDRESS_UNAVAILABLE else Event.ADDRESS_RESOLVED)
                onResult(name, detail)
            }
        }.start()
    }
}
