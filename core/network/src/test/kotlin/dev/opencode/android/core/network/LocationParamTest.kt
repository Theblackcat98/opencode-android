package dev.opencode.android.core.network

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocationParamTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun encodesAccordingToRfc3986SoASpaceIsPercentTwenty() {
        // The whole path is percent-encoded, which is what the query form and the header form
        // both send, and what the server decodes back into an absolute path.
        assertEquals("%2Fhome%2Fuser%2FMy%20Project", LocationParam.encode("/home/user/My Project"))
        assertEquals("%23notes", LocationParam.encode("#notes"))
        assertEquals("a%2Bb", LocationParam.encode("a+b"))
        assertEquals("/home/user/code", java.net.URLDecoder.decode(LocationParam.encode("/home/user/code"), "UTF-8"))
    }

    @Test
    fun appendsTheParameterToAPlainUrl() {
        val url = LocationParam.appendToUrl("http://box:4096/api/session", "/home/user/code")

        assertEquals("http://box:4096/api/session?location%5Bdirectory%5D=%2Fhome%2Fuser%2Fcode", url)
    }

    @Test
    fun appendsTheParameterToAUrlThatAlreadyHasAQuery() {
        val url = LocationParam.appendToUrl("http://box:4096/api/session?limit=5", "/work")

        assertTrue(url.startsWith("http://box:4096/api/session?"))
        assertTrue(url.contains("limit=5"))
        assertTrue(url.contains("location%5Bdirectory%5D=%2Fwork"))
    }

    @Test
    fun leavesTheUrlAloneWithoutADirectory() {
        assertEquals("http://box:4096/api/session", LocationParam.appendToUrl("http://box:4096/api/session", null))
        assertEquals("http://box:4096/api/session", LocationParam.appendToUrl("http://box:4096/api/session", "  "))
    }

    @Test
    fun doesNotAddTheParameterTwice() {
        val once = "http://box:4096/api/session".toHttpUrl().newBuilder()
            .addQueryParameter(LocationParam.QUERY_KEY, "/first")
            .build()

        val twice = LocationParam.applyToHttpUrl(once.newBuilder(), "/second").build()

        assertEquals(listOf("/first"), twice.queryParameterValues(LocationParam.QUERY_KEY))
    }

    @Test
    fun theHeaderFormIsPercentEncoded() {
        val request = LocationParam.applyToRequest(
            Request.Builder().url("http://box:4096/api/session"),
            "/home/user/My Project",
        ).build()

        assertEquals("%2Fhome%2Fuser%2FMy%20Project", request.header(LocationParam.HEADER_KEY))
    }

    @Test
    fun theInterceptorAddsTheParameterToRequests() {
        server.enqueue(MockResponse.Builder().code(200).body("[]").build())

        val client = OkHttpClient.Builder()
            .addInterceptor(LocationInterceptor(directoryProvider = { "/home/user/code" }))
            .build()
        client.newCall(Request.Builder().url(server.url("/api/session")).build()).execute().close()

        val recorded = server.takeRequest()
        assertEquals(listOf("/home/user/code"), recorded.url.queryParameterValues(LocationParam.QUERY_KEY))
    }

    @Test
    fun theInterceptorCanUseTheHeaderForm() {
        server.enqueue(MockResponse.Builder().code(200).body("[]").build())

        val client = OkHttpClient.Builder()
            .addInterceptor(LocationInterceptor(directoryProvider = { "/home/user/code" }, useHeader = true))
            .build()
        client.newCall(Request.Builder().url(server.url("/api/session")).build()).execute().close()

        val recorded = server.takeRequest()
        assertEquals("%2Fhome%2Fuser%2Fcode", recorded.headers[LocationParam.HEADER_KEY])
        assertNull(recorded.url.queryParameter(LocationParam.QUERY_KEY))
    }

    @Test
    fun theInterceptorAddsNothingWithoutADirectory() {
        server.enqueue(MockResponse.Builder().code(200).body("[]").build())

        val client = OkHttpClient.Builder()
            .addInterceptor(LocationInterceptor(directoryProvider = { null }))
            .build()
        client.newCall(Request.Builder().url(server.url("/api/session")).build()).execute().close()

        val recorded = server.takeRequest()
        assertNull(recorded.url.queryParameter(LocationParam.QUERY_KEY))
        assertNull(recorded.headers[LocationParam.HEADER_KEY])
    }

    @Test
    fun theServerApiFactoryScopesEveryRequestToTheDirectory() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(SERVER_INFO_JSON).build())

        val api = ServerApiFactory(OkHttpClient()).create(
            baseUrl = server.url("/").toString(),
            directory = "/home/user/My Project",
        )
        api.getServerInfo()

        val recorded = server.takeRequest()
        assertEquals(listOf("/home/user/My Project"), recorded.url.queryParameterValues(LocationParam.QUERY_KEY))
    }

    @Test
    fun theServerApiFactoryLeavesRequestsUnscopedWithoutADirectory() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(SERVER_INFO_JSON).build())

        val api = ServerApiFactory(OkHttpClient()).create(server.url("/").toString())
        api.getServerInfo()

        val recorded = server.takeRequest()
        assertNull(recorded.url.queryParameter(LocationParam.QUERY_KEY))
    }

    private companion object {
        val SERVER_INFO_JSON =
            """{"version":"2.0.18","pid":1,"urls":["http://box:4096"],"paths":{"tmp":"/tmp"}}"""
    }
}
