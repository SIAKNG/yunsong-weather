package com.example.weatherapp

import org.junit.Assert.*
import org.junit.Test

class AppUpdatePolicyTest {
    private fun release(tag: String = "v1.1", code: Int = 4, flags: String = "\"draft\":false,\"prerelease\":false", asset: String = "app.apk") =
        """{"tag_name":"$tag",$flags,"body":"<!-- yunsong-version-code: $code -->","assets":[{"name":"$asset","size":100,"state":"uploaded","browser_download_url":"https://github.com/SIAKNG/yunsong-weather/releases/download/$tag/$asset"}]}"""
    private fun status(json: String, code: Int = 4) = AppUpdatePolicy.evaluate(200,json,"1.1-userapi",code).status
    @Test fun newerSemanticVersion() { assertEquals(AppUpdatePolicy.Status.NEW_VERSION,status(release("v1.2",1))) }
    @Test fun sameVersionHigherBuild() { assertEquals(AppUpdatePolicy.Status.NEW_VERSION,status(release(),3)) }
    @Test fun sameBuildIsCurrent() { assertEquals(AppUpdatePolicy.Status.CURRENT,status(release())) }
    @Test fun olderBuildIsCurrent() { assertEquals(AppUpdatePolicy.Status.CURRENT,status(release(code=3))) }
    @Test fun olderVersionCannotUpgradeEvenWithHigherCode() { assertEquals(AppUpdatePolicy.Status.CURRENT,status(release("v1.0",999))) }
    @Test fun userApiSuffixAndPatchSupported() { assertEquals(AppUpdatePolicy.Status.NEW_VERSION,status(release("v1.1.1-userapi"))) }
    @Test fun invalidVersionFailsHonestly() { assertEquals(AppUpdatePolicy.Status.FAILED,status(release("preview"))) }
    @Test fun draftIsNotAnUpdate() { assertEquals(AppUpdatePolicy.Status.FAILED,status(release(flags="\"draft\":true,\"prerelease\":false"))) }
    @Test fun prereleaseIsNotAnUpdate() { assertEquals(AppUpdatePolicy.Status.FAILED,status(release(flags="\"draft\":false,\"prerelease\":true"))) }
    @Test fun noApkDoesNotCountAsInstallableRelease() { assertEquals(AppUpdatePolicy.Status.FAILED,status(release(asset="source.zip"))) }
    @Test fun foreignDownloadHostRejected() { assertEquals(AppUpdatePolicy.Status.FAILED,status(release().replace("https://github.com/","https://example.com/"))) }
    @Test fun zeroByteApkRejected() { assertEquals(AppUpdatePolicy.Status.FAILED,status(release().replace("\"size\":100","\"size\":0"))) }
    @Test fun privateOrMissingIsNotReportedAsCurrent() { assertEquals(AppUpdatePolicy.Status.UNAVAILABLE,AppUpdatePolicy.evaluate(404,"","1.1",4).status) }
    @Test fun rateLimitAndServerErrorDoNotReportCurrent() { for(code in listOf(403,429,500)) assertEquals(AppUpdatePolicy.Status.FAILED,AppUpdatePolicy.evaluate(code,"","1.1",4).status) }
    @Test fun malformedResponseDoesNotCrash() { for(json in listOf("{}","broken","null","[]")) assertEquals(AppUpdatePolicy.Status.FAILED,status(json)) }
    @Test fun badgeClearlyShowsNewVersion() { assertEquals("有新版本 1.2",AppUpdatePolicy.evaluate(200,release("v1.2"),"1.1-userapi",4).label) }
}
