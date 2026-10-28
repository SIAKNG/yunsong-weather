package com.example.weatherapp

import org.junit.Assert.*
import org.junit.Test

class ProgressiveWeatherLoaderTest {
    @Test fun primaryDoesNotWaitForSlowAirAndDuplicateEntriesShareRequests() {
        val pending=HashMap<String,(String?)->Unit>()
        var calls=0
        val loader=ProgressiveWeatherLoader({url,callback -> calls++;pending[url]=callback})
        val primary=ArrayList<Pair<String?,String?>>()
        val extra=ArrayList<Pair<String,String>>()
        repeat(2) {loader.load("beijing","weather","air",{w,a->primary.add(w to a)},{w,a->extra.add(w to a)})}
        assertEquals(2,calls)
        pending.getValue("weather")("weather-data")
        assertEquals(listOf("weather-data" to null,"weather-data" to null),primary)
        assertTrue(extra.isEmpty())
        pending.getValue("air")("air-data")
        assertEquals(2,extra.size)
        assertEquals(2,primary.size)
    }

    @Test fun airFirstIsIncludedInSinglePrimaryCommit() {
        val pending=HashMap<String,(String?)->Unit>()
        val loader=ProgressiveWeatherLoader({url,cb->pending[url]=cb})
        var weather:String?=null;var aq:String?=null;var supplements=0
        loader.load("bj","w","a",{w,a->weather=w;aq=a},{_,_->supplements++})
        pending.getValue("a")("air");assertNull(weather)
        pending.getValue("w")("weather")
        assertEquals("weather",weather);assertEquals("air",aq);assertEquals(0,supplements)
    }

    @Test fun shortMemoryCacheExpiresAndCitiesStaySeparate() {
        var now=100L;var requests=0
        val loader=ProgressiveWeatherLoader({url,cb->requests++;cb(url)},clock={now})
        var received=0
        fun load(city:String)=loader.load(city,"$city-w","$city-a",{_,_->received++},{_,_->})
        load("bj");load("bj");assertEquals(2,requests);assertEquals(2,received)
        load("sh");assertEquals(4,requests)
        now+=30_001;load("bj");assertEquals(6,requests)
    }

    @Test fun airFailureCannotDiscardWeatherAndWeatherFailureRetries() {
        val pending=HashMap<String,(String?)->Unit>();var count=0
        val loader=ProgressiveWeatherLoader({url,cb->count++;pending[url]=cb})
        var result:String?="unset";var air:String?="unset"
        loader.load("bj","w","a",{w,a->result=w;air=a},{_,_->fail()})
        pending.getValue("a")(null);pending.getValue("w")("valid")
        assertEquals("valid",result);assertNull(air)
        loader.load("sh","sw","sa",{w,_->result=w},{_,_->fail()})
        pending.getValue("sw")(null);pending.getValue("sa")(null);assertNull(result)
        loader.load("sh","sw","sa",{_,_->},{_,_->});assertEquals(6,count)
    }
}
