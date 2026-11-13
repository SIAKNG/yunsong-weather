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
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import java.util.Locale
import com.example.weatherapp.WeatherDiagnostics.Event

/** Foreground location uses Android providers and is independent of weather service credentials. */
internal class ForegroundWeatherLocation(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission")
    fun request(preferCache: Boolean, timeoutMillis: Long, onResult: (Location?, Event) -> Unit) {
        fun fail(reason: Event) {
            WeatherDiagnostics.record(context, reason)
            onResult(null, reason)
        }
        if (!WeatherPermissionCoordinator.hasForegroundLocation(context)) {
            fail(Event.LOCATION_PERMISSION)
            return
        }
        WeatherDiagnostics.record(context, Event.LOCATION_START)
        if (!LocationManagerCompat.isLocationEnabled(manager)) {
            fail(Event.LOCATION_OFF)
            return
        }
        val precise = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        WeatherDiagnostics.record(context, if (precise) Event.LOCATION_PRECISE else Event.LOCATION_APPROXIMATE)
        val providers = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { it != LocationManager.GPS_PROVIDER || precise }
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

        val cached = (providers + LocationManager.PASSIVE_PROVIDER).mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.filter { System.currentTimeMillis() - it.time in 0..WeatherRefreshPolicy.LOCATION_CACHE_MS }
            .maxByOrNull { it.time }
        if (preferCache && cached != null) {
            WeatherDiagnostics.record(context, Event.LOCATION_CACHED)
            onResult(cached, Event.LOCATION_CACHED)
            return
        }

        var finished = false
        lateinit var timeout: Runnable
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (finished) return
                finished = true
                main.removeCallbacks(timeout)
                runCatching { manager.removeUpdates(this) }
                WeatherDiagnostics.record(context, Event.LOCATION_RECEIVED)
                onResult(location, Event.LOCATION_RECEIVED)
            }

            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Legacy Android callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        timeout = Runnable {
            if (finished) return@Runnable
            finished = true
            runCatching { manager.removeUpdates(listener) }
            fail(Event.LOCATION_TIMEOUT)
        }
        var registered = false
        for (provider in providers) {
            try {
                manager.requestLocationUpdates(provider, 0L, 0f, listener, main.looper)
                registered = true
            } catch (_: RuntimeException) {
                WeatherDiagnostics.record(context, Event.LOCATION_PROVIDER_FAILED)
            }
        }
        if (registered) main.postDelayed(timeout, timeoutMillis) else fail(Event.LOCATION_NO_PROVIDER)
    }

    @Suppress("DEPRECATION")
    fun reverseGeocode(location: Location, onResult: (String?, String?) -> Unit) {
        var finished = false
        val timeout = Runnable {
            if (finished) return@Runnable
            finished = true
            WeatherDiagnostics.record(context, Event.ADDRESS_TIMEOUT)
            onResult(null, null)
        }
        main.postDelayed(timeout, 2_500L)
        Thread {
            val address = runCatching {
                Geocoder(context, Locale.CHINA).getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
            }.getOrNull()
            main.post {
                if (finished) return@post
                finished = true
                main.removeCallbacks(timeout)
                val name = address?.locality
                val detail = address?.let {
                    WeatherAddressFormatter.fromParts(name, it.subLocality ?: it.subAdminArea, it.thoroughfare, it.featureName)
                        .ifEmpty { null }
                }
                WeatherDiagnostics.record(context, if (address == null) Event.ADDRESS_UNAVAILABLE else Event.ADDRESS_RESOLVED)
                onResult(name, detail)
            }
        }.start()
    }
}
