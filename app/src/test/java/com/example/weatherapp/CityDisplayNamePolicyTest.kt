package com.example.weatherapp

import org.junit.Assert.*
import org.junit.Test

class CityDisplayNamePolicyTest {
    @Test fun standaloneCitiesDropOnlyTheAdministrativeSuffix() {
        for((name,display) in listOf("上海市" to "上海","西安市" to "西安","渭南市" to "渭南","乌鲁木齐市" to "乌鲁木齐","津市市" to "津市","上海" to "上海","纽约" to "纽约"))
            assertEquals(display,WeatherAddressFormatter.city(name))
    }
    @Test fun cityNamesContainingTownWordsAndOtherAdministrativeLevelsStayValid() {
        for(name in listOf("镇江","新乡","桐乡","清镇","北镇")) assertEquals(name,WeatherAddressFormatter.city("${name}市"))
        for(name in listOf("雁塔区","蓝田县","阿拉善左旗","香港特别行政区")) assertEquals(name,WeatherAddressFormatter.city(name))
    }
    @Test fun detailedAddressesKeepTheirFullAdministrativeSpelling() {
        for(name in listOf("陕西省西安市","西安市 雁塔区 科技路","上海市浦东新区","镇江市 京口区")) assertEquals(name,WeatherAddressFormatter.city(name))
        assertEquals("西安",WeatherAddressFormatter.fromParts("西安市",null,null,null))
    }
    @Test fun displayFormattingPreservesStoredIdentityAndLocationDetail() {
        val page=CityPage("西安市",108.9,34.2,false)
        val original=page.copy()
        assertEquals("西安",WeatherAddressFormatter.label(page));assertEquals(original,page)
        assertEquals("西安",WeatherAddressFormatter.label(page.copy(isCurrentLocation=true,locationDetail="西安市")))
        assertEquals("西安市 雁塔区 科技路",WeatherAddressFormatter.label(page.copy(isCurrentLocation=true,locationDetail="西安市 雁塔区 科技路")))
    }
}
