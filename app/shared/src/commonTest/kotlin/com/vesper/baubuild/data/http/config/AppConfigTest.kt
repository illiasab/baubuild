package com.vesper.baubuild.data.http.config

import com.vesper.baubuild.core.network.AppJson
import com.vesper.baubuild.core.network.jsonResponse
import com.vesper.baubuild.core.session.DeviceInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppConfigTest {
    private val sample = """{
        "min_supported": {"ios": "1.4.0", "macos": "1.0.0", "android": "2.0", "windows": 7, "linux": null},
        "features": {"billing": false, "mentor": true, "github": false, "push": "yes", "new_thing": true},
        "maintenance": {"message": "  Deploy in progress — saving may be slow.  "},
        "unknown": [1, 2]
    }"""

    @Test
    fun parsesLeniently() {
        val config = AppConfig.parse(sample)!!
        assertEquals(mapOf("ios" to "1.4.0", "macos" to "1.0.0", "android" to "2.0", "windows" to "7"), config.minSupported)
        assertEquals("Deploy in progress — saving may be slow.", config.maintenanceMessage)
        assertFalse(config.features.github)
        assertTrue(config.features.mentor)
        assertTrue(config.features.push) // non-boolean → default
        assertTrue(config.features["new_thing"])
        assertFalse(config.features["unknown_flag"])
        assertNull(AppConfig.parse("""{"maintenance": null}""")!!.maintenanceMessage)
        assertNull(AppConfig.parse("not json"))
        assertNull(AppConfig.parse("[1]"))
    }

    @Test
    fun comparesVersions() {
        assertTrue(isBelow("1.0.0", "1.0.1"))
        assertTrue(isBelow("1.9", "1.10"))
        assertTrue(isBelow("1.2.3", "2"))
        assertFalse(isBelow("1.2", "1.2.0"))
        assertFalse(isBelow("1.2.0-beta", "1.2.0"))
        assertFalse(isBelow("v2.0.0+42", "1.9.9"))
        assertFalse(isBelow("garbage", "1.0.0")) // unparseable → never block
        assertFalse(isBelow("1.0.0", ""))
    }

    @Test
    fun statusBlocksOnlyBelowThisPlatformsMinimum() {
        val config = AppConfig.parse(sample)!!
        assertTrue(AppStatus.from(config, "ios", "1.3.9").updateRequired)
        assertFalse(AppStatus.from(config, "ios", "1.4.0").updateRequired)
        assertFalse(AppStatus.from(config, "linux", "0.0.1").updateRequired) // no minimum for linux
        assertEquals("1.4.0", AppStatus.from(config, "ios", "1.0").minimumVersion)
    }

    private fun client(handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = HttpClient(MockEngine { handler(it) }) {
        install(ContentNegotiation) { json(AppJson) }
        defaultRequest { url("https://api.example.test/") }
    }

    @Test
    fun repositoryFetchesAndExposesStatusAndFlags() = runTest {
        var calls = 0
        val repo = HttpAppConfigRepository(
            client { calls++; assertEquals("/v1/config", it.url.encodedPath); jsonResponse(sample) },
            DeviceInfo("ios", "1.0.0"), clock = { 0L },
        )
        assertEquals(AppStatus.Open, repo.status.value)
        repo.refresh()
        assertTrue(repo.status.value.updateRequired)
        assertEquals("Deploy in progress — saving may be slow.", repo.status.value.maintenanceMessage)
        assertFalse(repo.features.value.github)
        repo.refresh() // throttled
        assertEquals(1, calls)
        repo.refresh(force = true)
        assertEquals(2, calls)
    }

    @Test
    fun failsOpenAndKeepsLastGoodValue() = runTest {
        var now = 0L
        var mode = "error"
        val repo = HttpAppConfigRepository(
            client {
                when (mode) {
                    "error" -> jsonResponse("""{"error":{"code":"internal"}}""", HttpStatusCode.InternalServerError)
                    "garbage" -> jsonResponse("<html>")
                    "throw" -> throw IllegalStateException("offline")
                    else -> jsonResponse("""{"min_supported":{"android":"1.0"},"maintenance":{"message":"Hi"}}""")
                }
            },
            DeviceInfo("android", "1.0"), clock = { now },
        )
        repo.refresh()
        assertEquals(AppStatus.Open, repo.status.value)
        mode = "throw"; now += 60_000; repo.refresh()
        assertEquals(AppStatus.Open, repo.status.value)

        mode = "ok"; now += 60_000; repo.refresh()
        assertEquals("Hi", repo.status.value.maintenanceMessage)
        assertFalse(repo.status.value.updateRequired)

        mode = "garbage"; repo.refresh(force = true)
        assertEquals("Hi", repo.status.value.maintenanceMessage) // last good value kept
    }

    @Test
    fun failedFetchRetriesSoonerThanTheSuccessInterval() = runTest {
        var now = 0L
        var calls = 0
        val repo = HttpAppConfigRepository(
            client { calls++; throw IllegalStateException("offline") },
            DeviceInfo("ios", "1.0.0"), clock = { now }, minIntervalMillis = 300_000, failureRetryMillis = 30_000,
        )
        repo.refresh(); repo.refresh()
        assertEquals(1, calls)
        now += 31_000; repo.refresh()
        assertEquals(2, calls)
    }
}
