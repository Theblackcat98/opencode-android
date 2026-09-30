package dev.opencode.android.core.data.action

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * What a screen may say the *server* said.
 *
 * [ActionError.message] is never empty: a body with no text becomes `HTTP 500`. That is right for a log and
 * wrong for a sentence, because a row that reads "The server said: HTTP 500" attributes the client's own
 * placeholder to the server. [ActionError.serverMessage] is `null` exactly when the server said nothing, and
 * a screen then says what it does know.
 */
class ActionErrorMessageTest {

    private fun failure(code: Int, body: String): ActionError = HttpException(
        Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())),
    ).toActionError()

    @Test
    fun `a bare 500 has a status and no message of the server's`() = runTest {
        val error = failure(500, "")

        assertEquals(500, error.httpStatus)
        assertEquals("HTTP 500", error.message)
        assertNull("the placeholder is not the server's words", error.serverMessage)
        assertNull(error.serverReference)
    }

    @Test
    fun `an UnknownError with text is the server's message and carries the reference it logged`() = runTest {
        val error = failure(
            500,
            """{"_tag":"UnknownError","message":"EACCES: permission denied, open '/work/a.jsonc'","ref":"err_1a2b3c4d"}""",
        )

        assertEquals("EACCES: permission denied, open '/work/a.jsonc'", error.serverMessage)
        assertEquals("err_1a2b3c4d", error.serverReference)
    }

    @Test
    fun `a body that only names its tag or its status says nothing`() = runTest {
        // The parsed fallbacks: no `message` on an unrecognised body is the tag, and an HTML error page from
        // a proxy is neither JSON nor a message.
        assertNull(failure(502, """{"_tag":"UnknownError","message":"Unknown error"}""").serverMessage)
        assertNull(failure(502, "<html><body>Bad gateway</body></html>").serverMessage)
        assertNull(failure(500, """{"_tag":"UnknownError","message":"  "}""").serverMessage)
    }

    @Test
    fun `a message is trimmed`() = runTest {
        assertEquals(
            "path is outside the location",
            failure(400, """{"_tag":"InvalidRequestError","message":" path is outside the location "}""").serverMessage,
        )
    }
}
