package com.example.weatherapp

import org.junit.Assert.*
import org.junit.Test

class IconAttributionTest {
    @Test fun pointsToTheMaintainerProvidedOfficialCollection() {
        assertEquals("https://www.iconfont.cn/collections/detail?cid=53861", IconAttribution.COLLECTION_URL)
    }
    @Test fun preservesTheConfirmedAuthorName() {
        assertEquals("桑榆菲碗", IconAttribution.AUTHOR)
    }
}
