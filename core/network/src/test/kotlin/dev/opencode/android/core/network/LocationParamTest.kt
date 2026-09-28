package dev.opencode.android.core.network

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

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
    fun appendsLocationQueryParamToUrl() {
        val result = LocationParam.appendToUrl("http://example.com/api/session", "/home/user/project")
        val decoded = URLDecoder.decode(result, StandardCharsets.UTF_8.name())
        assertEquals("http://example.com/api/session?location[directory]=/home/user/project", decoded)
    }

    @Test
    fun appendsLocationQueryParamToUrlWithExistingQuery() {
        val result = LocationParam.appendToUrl("http://example.com/api/session?limit=10", "/tmp/repo")
        val decoded = URLDecoder.decode(result, StandardCharsets.UTF_8.name())
        assertEquals("http://example.com/api/session?limit=10&location[directory]=/tmp/repo", decoded)
    }

    @Test
    fun doesNotModifyUrlWhenDirectoryIsBlank() {
        val result = LocationParam.appendToUrl("http://example.com/api/session", "")
        assertEquals("http://example.com/api/session", result)
    }

    @Test
    fun locationInterceptorAddsQueryParam() {
        server.enqueue(MockResponse.Builder().code(200).build())

        val client = OkHttpClient.Builder()
            .addInterceptor(LocationInterceptor(directoryProvider = { "/var/workspace" }))
            .build()

        client.newCall(Request.Builder().url(server.url("/api/session")).build()).execute().close()

        val recorded = server.takeRequest()
        val requestedUrl = recorded.url.toString()
        val decodedUrl = URLDecoder.decode(requestedUrl, StandardCharsets.UTF_8.name())
        assertTrue(decodedUrl.contains("location[directory]=/var/workspace"))
    }

    @Test
    fun locationInterceptorAddsHeaderWhenConfigured() {
        server.enqueue(MockResponse.Builder().code(200).build())

        val client = OkHttpClient.Builder()
            .addInterceptor(LocationInterceptor(directoryProvider = { "/var/workspace" }, useHeader = true))
            .build()

        client.newCall(Request.Builder().url(server.url("/api/session")).build()).execute().close()

        val recorded = server.takeRequest()
        val header = recorded.headers[LocationParam.HEADER_KEY] ?: ""
        val decodedHeader = URLDecoder.decode(header, StandardCharsets.UTF_8.name())
        assertEquals("/var/workspace", decodedHeader)
    }
}
