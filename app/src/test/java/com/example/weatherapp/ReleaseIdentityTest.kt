package com.example.weatherapp

import org.junit.Assert.*
import org.junit.Test

class ReleaseIdentityTest {
    @Test fun userApiIsIdentifiedInVersionNotLauncherBrand() {
        assertEquals("1.3-userapi",BuildConfig.VERSION_NAME)
        assertEquals(8,BuildConfig.VERSION_CODE)
        assertEquals("com.example.weatherapp.userapi",BuildConfig.APPLICATION_ID)
    }
}
