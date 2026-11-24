package com.example.weatherapp

/** Independent requests start together; AQ never holds the first weather frame hostage. */
internal class ProgressiveWeatherLoader(
    private val request: (String, (String?)->Unit)->Unit,
    private val clock: ()->Long = { System.nanoTime()/1_000_000 },
    private val freshnessMs: Long = 30_000L
) {
    private data class Listener(val primary: (String?,String?)->Unit, val supplement: (String,String)->Unit,
        var delivered: Boolean=false, var withAir: Boolean=false)
    private class Flight(val started: Long) {
        var weather: String?=null; var air: String?=null
        var weatherDone=false; var airDone=false
        val listeners=ArrayList<Listener>()
    }
    private val flights=LinkedHashMap<String,Flight>()

    fun load(key: String, weatherUrl: String, airUrl: String?,
             primary: (String?,String?)->Unit, supplement: (String,String)->Unit) {
        val flight: Flight
        val start: Boolean
        synchronized(this) {
            val old=flights[key]
            start=old==null || (old.weatherDone && (old.weather == null || old.airDone && clock() - old.started > freshnessMs))
            flight=if(start) Flight(clock()).also {it.airDone = airUrl == null; flights[key]=it} else old!!
            flight.listeners.add(Listener(primary,supplement))
            deliver(flight)
            if(flights.size>12) flights.entries.removeAll {it.key!=key && it.value.weatherDone && it.value.airDone}
        }
        if(!start) return
        request(weatherUrl) { body -> synchronized(this) {flight.weather=body;flight.weatherDone=true;deliver(flight)} }
        if (airUrl != null) request(airUrl) { body -> synchronized(this) {flight.air=body;flight.airDone=true;deliver(flight)} }
    }

    @Synchronized
    fun discardWeather(key: String, weather: String) {
        val flight = flights[key] ?: return
        if (flight.weather == weather) flights.remove(key)
    }

    private fun deliver(f: Flight) {
        if(!f.weatherDone) return
        f.listeners.forEach {listener ->
            if(!listener.delivered) {
                listener.delivered=true;listener.withAir=f.air!=null
                listener.primary(f.weather,f.air)
            } else if(!listener.withAir && f.air!=null && f.weather!=null) {
                listener.withAir=true;listener.supplement(f.weather!!,f.air!!)
            }
        }
        if (f.airDone || f.weather == null) f.listeners.clear()
    }
}
