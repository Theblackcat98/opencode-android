package dev.opencode.android.core.network

import dev.opencode.android.core.model.ServerInfo
import dev.opencode.android.core.model.json.OpenCodeJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

sealed interface ServerValidationResult {
    data class Success(val serverInfo: ServerInfo) : ServerValidationResult
    data class Failure(
        val errorType: ValidationErrorType,
        val userMessage: String,
        val technicalDetail: String? = null,
    ) : ServerValidationResult
}

enum class ValidationErrorType {
    CONNECTION_REFUSED_LOCALHOST,
    UNAUTHORIZED,
    TLS_ERROR,
    TIMEOUT,
    UNSUPPORTED_VERSION,
    UNKNOWN_HOST,
    OTHER_NETWORK_ERROR,
}

class ServerValidator(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun validate(
        baseUrl: String,
        credential: String? = null,
    ): ServerValidationResult = withContext(ioDispatcher) {
        val cleanBase = baseUrl.trimEnd('/')
        val url = "$cleanBase/api/info"

        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")

        if (!credential.isNullOrBlank()) {
            requestBuilder.header("Authorization", Credentials.basic(AuthInterceptor.AUTH_USER, credential))
        }

        val request = requestBuilder.build()

        try {
            val response = okHttpClient.newCall(request).execute()
            response.use { resp ->
                if (resp.code == 401) {
                    return@withContext ServerValidationResult.Failure(
                        errorType = ValidationErrorType.UNAUTHORIZED,
                        userMessage = "Authentication failed (401). Please check your password or re-pair this server.",
                        technicalDetail = "HTTP 401 Unauthorized",
                    )
                }

                val body = resp.body
                if (!resp.isSuccessful) {
                    val errorBody = body.string()
                    return@withContext ServerValidationResult.Failure(
                        errorType = ValidationErrorType.OTHER_NETWORK_ERROR,
                        userMessage = "Server returned error: HTTP ${resp.code}",
                        technicalDetail = errorBody,
                    )
                }

                val bodyString = body.string()
                val info = OpenCodeJson.decodeFromString<ServerInfo>(bodyString)

                if (info.majorVersion != 2) {
                    return@withContext ServerValidationResult.Failure(
                        errorType = ValidationErrorType.UNSUPPORTED_VERSION,
                        userMessage = "Server version ${info.version} is not supported. This app requires OpenCode V2.x.",
                        technicalDetail = "Major version: ${info.majorVersion}",
                    )
                }

                return@withContext ServerValidationResult.Success(info)
            }
        } catch (e: ConnectException) {
            val isLocalhost = cleanBase.contains("localhost") || cleanBase.contains("127.0.0.1")
            val message = if (isLocalhost) {
                "Connection refused. On an Android device or emulator, 'localhost' refers to the phone itself. To connect to your computer, use your computer's LAN IP address or 10.0.2.2 (on Android emulator)."
            } else {
                "The server only listens on localhost. On the computer, run `opencode service set hostname 0.0.0.0`, then `opencode service start` and `opencode pair`."
            }
            ServerValidationResult.Failure(
                errorType = ValidationErrorType.CONNECTION_REFUSED_LOCALHOST,
                userMessage = message,
                technicalDetail = e.message ?: "ConnectException",
            )
        } catch (e: SocketTimeoutException) {
            ServerValidationResult.Failure(
                errorType = ValidationErrorType.TIMEOUT,
                userMessage = "Connection timed out. Check that the server is running, the host and port are correct, and your device is on the same Wi-Fi or VPN (e.g. Tailscale).",
                technicalDetail = e.message ?: "SocketTimeoutException",
            )
        } catch (e: SSLException) {
            ServerValidationResult.Failure(
                errorType = ValidationErrorType.TLS_ERROR,
                userMessage = "TLS / SSL certificate error. If using a self-signed certificate, ensure the user CA is trusted in Android Settings or use an SSH tunnel / Tailscale.",
                technicalDetail = e.message ?: "SSLException",
            )
        } catch (e: UnknownHostException) {
            ServerValidationResult.Failure(
                errorType = ValidationErrorType.UNKNOWN_HOST,
                userMessage = "Hostname could not be resolved. Please verify the server address.",
                technicalDetail = e.message ?: "UnknownHostException",
            )
        } catch (e: Exception) {
            ServerValidationResult.Failure(
                errorType = ValidationErrorType.OTHER_NETWORK_ERROR,
                userMessage = "Connection failed: ${e.message ?: e.javaClass.simpleName}",
                technicalDetail = e.stackTraceToString(),
            )
        }
    }
}
