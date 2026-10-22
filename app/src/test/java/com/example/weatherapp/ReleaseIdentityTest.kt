package com.example.weatherapp

import org.junit.Assert.*
import org.junit.Test

class ReleaseIdentityTest {
    @Test fun userApiIsIdentifiedInVersionNotLauncherBrand() {
        assertEquals("1.1-userapi",BuildConfig.VERSION_NAME)
        assertEquals(3,BuildConfig.VERSION_CODE)
        assertEquals("com.example.weatherapp.userapi",BuildConfig.APPLICATION_ID)
    }
}
