package dev.opencode.android.core.network

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException

/** The outcome of redeeming a pairing link. */
sealed interface PairingRedemptionResult {
    data class Success(val token: String) : PairingRedemptionResult

    /** [errorType] selects the help text; [technicalDetail] is for the developer log. */
    data class Failure(
        val errorType: PairingErrorType,
        val technicalDetail: String? = null,
    ) : PairingRedemptionResult
}

enum class PairingErrorType {
    /** The code was already used, expired (they last 5 minutes), or never existed. */
    CODE_REJECTED,

    /** The server answered with an error status other than 401. */
    SERVER_ERROR,

    /** The server answered with a body that is not a pairing session. */
    MALFORMED_RESPONSE,

    /** The address is not usable at all. */
    UNREACHABLE,
}

/**
 * Redeems an `opencode pair` link for a 30-day session token (features doc §2.3).
 *
 * The route needs no authentication, so this deliberately does not send a credential: a stale one
 * must not turn a valid code into a failure.
 */
class PairingClient(
    private val serverApiFactory: ServerApiFactory,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun redeem(link: PairingLink): PairingRedemptionResult = withContext(ioDispatcher) {
        val api = try {
            serverApiFactory.create(link.baseUrl)
        } catch (e: IllegalArgumentException) {
            return@withContext PairingRedemptionResult.Failure(
                errorType = PairingErrorType.UNREACHABLE,
                technicalDetail = e.message,
            )
        }

        try {
            val session = api.redeemPairingCode(link.code)
            if (session.token.isBlank()) {
                PairingRedemptionResult.Failure(
                    errorType = PairingErrorType.MALFORMED_RESPONSE,
                    technicalDetail = "Pairing session carried an empty token",
                )
            } else {
                PairingRedemptionResult.Success(session.token)
            }
        } catch (e: Throwable) {
            val type = when (e) {
                is HttpException -> when (e.code()) {
                    401, 403, 404, 410 -> PairingErrorType.CODE_REJECTED
                    else -> PairingErrorType.SERVER_ERROR
                }

                is SerializationException -> PairingErrorType.MALFORMED_RESPONSE

                else -> if (e is IOException) PairingErrorType.UNREACHABLE else PairingErrorType.SERVER_ERROR
            }
            val detail = e.message ?: e.javaClass.simpleName
            PairingRedemptionResult.Failure(
                errorType = type,
                technicalDetail = "$type: $detail",
            )
        }
    }
}
