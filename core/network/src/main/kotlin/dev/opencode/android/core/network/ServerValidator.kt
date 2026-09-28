package dev.opencode.android.core.network

import dev.opencode.android.core.model.ServerInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.HttpException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** The outcome of `GET /api/info`, with every failure already classified. */
sealed interface ServerValidationResult {
    data class Success(
        val serverInfo: ServerInfo,
        val versionStatus: VersionStatus,
    ) : ServerValidationResult

    /**
     * A classified failure. [technicalDetail] is for the developer log and the event inspector;
     * the message the user reads comes from a string resource chosen by [errorType], so this
     * module stays free of user-facing text.
     */
    data class Failure(
        val errorType: ValidationErrorType,
        val technicalDetail: String? = null,
    ) : ServerValidationResult
}

/** How the server version relates to the release this client was tested against (plan §5.1). */
enum class VersionStatus {
    /** The version this client is tested against, or an older one. */
    TESTED,

    /** A newer release. The connection is allowed, and the UI says the version is untested. */
    NEWER_UNTESTED,
}

enum class ValidationErrorType {
    /** Nothing answered on that address. Usually the server still listens on localhost only. */
    CONNECTION_REFUSED,
    /** The password or token was rejected: re-pair. */
    UNAUTHORIZED,
    /** A certificate could not be validated. */
    TLS_ERROR,
    /** The connection or the response timed out. */
    TIMEOUT,
    /** Not an OpenCode V2 server (a V1 server, or something else entirely on that port). */
    UNSUPPORTED_VERSION,
    /** The host name could not be resolved. */
    UNKNOWN_HOST,
    /** The server answered with an error status. */
    SERVER_ERROR,
    /** The server answered with something that is not an OpenCode server response. */
    MALFORMED_RESPONSE,
    /** Any other failure. */
    UNKNOWN,
}

/**
 * Checks that an address is a reachable OpenCode V2 server, and gates on its version (plan §5.1).
 *
 * The same call is the app's "test connection" and its pairing check, so it reports the failure
 * classes the onboarding help maps to: connection refused, 401, TLS and timeout.
 */
class ServerValidator(
    private val serverApiFactory: ServerApiFactory,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun validate(
        baseUrl: String,
        credential: String? = null,
        trustUserCertificates: Boolean = false,
    ): ServerValidationResult = withContext(ioDispatcher) {
        val host = baseUrl.toHttpUrlOrNull()?.host
        val api = try {
            serverApiFactory.create(baseUrl, trustUserCertificates = trustUserCertificates)
        } catch (e: IllegalArgumentException) {
            return@withContext ServerValidationResult.Failure(
                errorType = ValidationErrorType.UNKNOWN_HOST,
                technicalDetail = e.message,
            )
        }

        try {
            val info = api.getServerInfo(ServerAuthCredential(credential?.takeIf { it.isNotBlank() }))
            val status = versionStatusOf(info)
            if (status == null) {
                ServerValidationResult.Failure(
                    errorType = ValidationErrorType.UNSUPPORTED_VERSION,
                    technicalDetail = "Unrecognized version '${info.version}'",
                )
            } else {
                ServerValidationResult.Success(info, status)
            }
        } catch (e: Throwable) {
            failureFor(e, host)
        }
    }

    private fun failureFor(e: Throwable, host: String?): ServerValidationResult.Failure {
        val detail = e.message ?: e.javaClass.simpleName
        val type = when (e) {
            is HttpException -> when (e.code()) {
                401, 403 -> ValidationErrorType.UNAUTHORIZED
                404 -> ValidationErrorType.UNSUPPORTED_VERSION
                in 500..599 -> ValidationErrorType.SERVER_ERROR
                else -> ValidationErrorType.SERVER_ERROR
            }

            is SerializationException -> ValidationErrorType.MALFORMED_RESPONSE
            is ConnectException,
            is NoRouteToHostException,
            is PortUnreachableException,
            -> ValidationErrorType.CONNECTION_REFUSED

            is SocketTimeoutException,
            is InterruptedIOException,
            -> ValidationErrorType.TIMEOUT

            is SSLException -> ValidationErrorType.TLS_ERROR
            is UnknownHostException -> ValidationErrorType.UNKNOWN_HOST
            else -> ValidationErrorType.UNKNOWN
        }
        return ServerValidationResult.Failure(
            errorType = type,
            technicalDetail = buildString {
                append(e.javaClass.simpleName)
                if (detail.isNotBlank()) append(": ").append(detail)
                if (host != null) append(" (host ").append(host).append(')')
            },
        )
    }

    companion object {
        /** The vendored spec and fixtures come from this release (plan §5.1). */
        const val TESTED_VERSION = "2.0.18"
        private const val TESTED_MAJOR = 2

        /**
         * `null` when the server is not an OpenCode V2 release, [VersionStatus.TESTED] for the
         * tested range and older, and [VersionStatus.NEWER_UNTESTED] above it.
         *
         * The version string decides, and a string that does not parse is not a recognized V2
         * release: an unparsable version is exactly the case that must not be trusted, so it is
         * reported as unsupported rather than connected to. The reported [ServerInfo.majorVersion]
         * is checked as well, and a server that says it is not a major version 2 is rejected even
         * when the string would have passed.
         */
        fun versionStatusOf(info: ServerInfo): VersionStatus? {
            val parsed = versionStatusOf(info.version) ?: return null
            val major = info.majorVersion ?: return null
            return if (major != TESTED_MAJOR) null else parsed
        }

        /**
         * `null` when the version is not an OpenCode V2 release, [VersionStatus.TESTED] for the
         * tested range and older, and [VersionStatus.NEWER_UNTESTED] above it.
         */
        fun versionStatusOf(version: String): VersionStatus? {
            val parts = version.trim().removePrefix("v").split('.')
            val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
            if (major != TESTED_MAJOR) return null
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: return null
            val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
            val tested = TESTED_VERSION.removePrefix("v").split('.').map { it.toInt() }
            val testedMinor = tested.getOrElse(1) { 0 }
            val testedPatch = tested.getOrElse(2) { 0 }
            return if (minor > testedMinor || (minor == testedMinor && patch > testedPatch)) {
                VersionStatus.NEWER_UNTESTED
            } else {
                VersionStatus.TESTED
            }
        }
    }
}
