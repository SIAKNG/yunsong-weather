package com.example.weatherapp

import org.junit.Assert.*
import org.junit.Test

class October20TutorialPolicyTest {
    @Test fun bothTutorialsCoverEntryAccountCredentialAndReturn() {
        listOf(false,true).forEach { q ->
            val steps=ApiTutorialContent.steps(q)
            assertTrue(steps.size >= 7)
            val text=steps.joinToString { it.title+it.body }
            listOf("注册","登录","复制","保存","刷新").forEach { assertTrue(text.contains(it)) }
            assertTrue(steps.any { it.path.size >= 3 })
        }
    }
    @Test fun tutorialLinksAreExplicitOfficialHttpsOnly() {
        listOf(false,true).flatMap(ApiTutorialContent::steps).mapNotNull { it.url }.forEach {
            val uri=java.net.URI(it)
            assertEquals("https",uri.scheme)
            assertTrue(listOf("caiyunapp.com","qweather.com").any { domain -> uri.host==domain || uri.host.endsWith(".$domain") })
        }
    }
    @Test fun qweatherOnlyUsesKeyAndNeverLegacyPrivateCredentials() {
        val c=UserApiConfiguration.Config(source=WeatherSettings.SOURCE_QWEATHER,qweatherHost="fixture.xy.qweatherapi.com",qweatherKid="old",qweatherSub="old",qweatherPem="fixture_old_pem")
        assertFalse(c.hasQWeather()); assertFalse(c.hasPrimary())
        assertNotNull(UserApiConfiguration.validate(c))
        assertEquals("",c.jwtConfig().privateKeyPem)
    }
    @Test fun accountHostsAllowMultipleValidLabelsButNoUrlInjection() {
        val config=UserApiConfiguration.Config(source=WeatherSettings.SOURCE_QWEATHER,qweatherApiKey="fixture")
        listOf("test.qweatherapi.com","account.xy.qweatherapi.com","https://account.xy.qweatherapi.com/").forEach {
            assertNull(UserApiConfiguration.validate(config.copy(qweatherHost=it)))
        }
        listOf("-bad.qweatherapi.com","a..qweatherapi.com","host.qweatherapi.com.attacker.test","qweatherapi.com","a.qweatherapi.com/path","a.qweatherapi.com:443").forEach {
            assertNotNull(UserApiConfiguration.validate(config.copy(qweatherHost=it)))
        }
    }
    @Test fun instructionsWarnAboutExamplesPermissionsAndFutureKeyLimits() {
        val caiyun=ApiTutorialContent.steps(false).joinToString { it.body }
        assertTrue(caiyun.contains("App Key + App Secret")); assertTrue(caiyun.contains("示例"))
        val q=ApiTutorialContent.steps(true).joinToString { it.body }
        assertTrue(q.contains("2027")); assertTrue(q.contains("项目 ID")); assertTrue(q.contains("不是"))
    }
}
