package space.megaworld.claudeusage.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val OPENAI_DEVICE_URL = "https://auth.openai.com/codex/device"
private const val ISSUER = "https://auth.openai.com"
// Public Codex OAuth client; this integration uses its device-code flow.
private const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"

internal data class DeviceAuthorization(
    val deviceAuthId: String,
    val userCode: String,
    val intervalMillis: Long,
    val expiresAtMillis: Long,
) {
    override fun toString() = "DeviceAuthorization(expiresAtMillis=$expiresAtMillis)"
}

internal sealed interface DevicePoll {
    data object Pending : DevicePoll
    data class Authorized(val credentials: Credentials) : DevicePoll
}

internal class OpenAiAuthException(message: String) : IOException(message)

/** No browser cookies or desktop refresh tokens are involved in this login. */
internal class OpenAiAuthClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).followRedirects(false).build(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun startDeviceLogin(): DeviceAuthorization {
        val response = postJson("/api/accounts/deviceauth/usercode", buildJsonObject { put("client_id", CLIENT_ID) })
        checkResponse(response)
        return try {
            val data = Json.parseToJsonElement(response.body).jsonObject
            val id = data.text("device_auth_id").checkedToken()
            val code = (data.text("user_code") ?: data.text("usercode")).checkedToken()
            val interval = data.text("interval")?.toLongOrNull()?.coerceIn(1, 30) ?: 5
            val expires = data.text("expires_at")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                ?: (now() + 15 * 60_000L)
            if (expires <= now()) throw OpenAiAuthException("Код уже истёк. Начните вход заново.")
            DeviceAuthorization(id, code, interval * 1000, expires.coerceAtMost(now() + 15 * 60_000L))
        } catch (e: IllegalArgumentException) {
            throw OpenAiAuthException("OpenAI не передал код входа. Попробуйте ещё раз.")
        }
    }

    suspend fun pollDeviceLogin(session: DeviceAuthorization): DevicePoll {
        if (now() >= session.expiresAtMillis) throw OpenAiAuthException("Время действия кода закончилось. Начните вход заново.")
        val response = postJson("/api/accounts/deviceauth/token", buildJsonObject {
            put("device_auth_id", session.deviceAuthId)
            put("user_code", session.userCode)
        })
        val code = response.errorCode()
        if (!response.isChallenge() && response.status in listOf(403, 404) &&
            code in listOf("deviceauth_authorization_pending", "authorization_pending")) return DevicePoll.Pending
        checkResponse(response)
        val data = try { Json.parseToJsonElement(response.body).jsonObject }
        catch (e: IllegalArgumentException) { throw OpenAiAuthException("OpenAI не подтвердил вход. Попробуйте ещё раз.") }
        val grant = FormBody.Builder().add("grant_type", "authorization_code")
            .add("client_id", CLIENT_ID).add("code", data.text("authorization_code").checkedToken())
            .add("code_verifier", data.text("code_verifier").checkedToken())
            .add("redirect_uri", "$ISSUER/deviceauth/callback").build()
        val tokens = post("/oauth/token", grant)
        checkResponse(tokens)
        return DevicePoll.Authorized(parseOpenAiTokenResponse(tokens.body, now()))
    }

    suspend fun refresh(credentials: Credentials): ApiResult<Credentials> {
        val refreshToken = credentials.refreshToken ?: return ApiResult.Unauthorized
        return try {
            val response = post("/oauth/token", FormBody.Builder()
                .add("grant_type", "refresh_token").add("client_id", CLIENT_ID)
                .add("refresh_token", refreshToken).build())
            if (!response.isChallenge() && (response.status == 401 || response.errorCode() in
                listOf("invalid_grant", "refresh_token_expired", "refresh_token_reused", "refresh_token_invalidated"))) {
                return ApiResult.Unauthorized
            }
            checkResponse(response)
            val updated = parseOpenAiTokenResponse(response.body, now(), credentials)
            if (credentials.userAgent.isNotBlank() && updated.userAgent != credentials.userAgent) {
                return ApiResult.Failure("OpenAI вернул другую учётную запись. Подключите аккаунт заново.")
            }
            ApiResult.Success(updated)
        } catch (e: OpenAiAuthException) { ApiResult.Failure(e.message.orEmpty())
        } catch (e: IOException) { ApiResult.Failure("Не удалось обновить сессию GPT. Проверьте интернет и VPN для tinyGlyph.")
        } catch (e: IllegalArgumentException) { ApiResult.Failure("Не удалось прочитать обновлённую сессию GPT. Повторите попытку.") }
    }

    private suspend fun postJson(path: String, data: JsonObject) =
        post(path, data.toString().toRequestBody("application/json".toMediaType()))

    private suspend fun post(path: String, body: RequestBody): AuthResponse {
        val request = Request.Builder().url(ISSUER + path).post(body)
            .header("Accept", "application/json").header("User-Agent", "tinyGlyph/0.16.0")
            .header("originator", "tinyglyph").build()
        return client.newCall(request).awaitResponse().use {
            AuthResponse(it.code, it.body?.string().orEmpty(), it.header("Content-Type"), it.header("cf-mitigated"))
        }
    }

    private fun checkResponse(response: AuthResponse) {
        if (response.isChallenge()) throw OpenAiAuthException("OpenAI запросил проверку подключения. Проверьте VPN для tinyGlyph и попробуйте другой узел.")
        if (response.status in 200..299) return
        val message = when (response.errorCode()) {
            "deviceauth_expired", "expired_token", "deviceauth_code_expired" -> "Код входа истёк. Начните вход заново."
            "access_denied", "deviceauth_access_denied" -> "Вход отменён в браузере. Можно попробовать снова."
            "deviceauth_disabled", "device_code_login_disabled" -> "Включите вход по коду устройства в настройках ChatGPT: Безопасность."
            "unsupported_country_region_territory" -> "OpenAI недоступен через текущий узел VPN. Выберите другой узел."
            else -> when (response.status) {
                404 -> "Вход по коду устройства недоступен. Проверьте настройки безопасности ChatGPT или используйте импорт файла."
                429 -> "Слишком много попыток входа. Подождите немного и повторите."
                else -> "Не удалось выполнить вход: OpenAI ответил HTTP ${response.status}. Повторите попытку."
            }
        }
        throw OpenAiAuthException(message)
    }
}

private data class AuthResponse(val status: Int, val body: String, val contentType: String?, val mitigation: String?) {
    fun isChallenge() = body.trimStart().startsWith("<") || contentType?.contains("text/html", true) == true || mitigation == "challenge"
    fun errorCode(): String? = runCatching {
        val root = Json.parseToJsonElement(body).jsonObject
        (root["error"] as? JsonObject)?.text("code") ?: (root["error"] as? JsonPrimitive)?.contentOrNull
    }.getOrNull()
}

internal fun parseOpenAiTokenResponse(raw: String, now: Long, previous: Credentials? = null): Credentials {
    val data = Json.parseToJsonElement(raw).jsonObject
    val access = data.text("access_token").checkedToken()
    val refresh = (data.text("refresh_token") ?: previous?.refreshToken).checkedToken()
    val claims = jwtClaims(data.text("id_token")) ?: jwtClaims(access)
    val auth = claims?.get("https://api.openai.com/auth") as? JsonObject
    val account = (auth?.text("chatgpt_account_id") ?: previous?.userAgent).orEmpty()
    require(account.none { it <= ' ' || it == '\u007f' })
    val lifetime = data.text("expires_in")?.toLongOrNull()?.takeIf { it in 1..31_536_000 }
    val expiration = lifetime?.let { now + it * 1000 } ?: jwtClaims(access)?.text("exp")?.toLongOrNull()
        ?.takeIf { it in 1..Long.MAX_VALUE / 1000 }?.times(1000)
    return Credentials(access, account, now, refresh, expiration)
}

private fun jwtClaims(token: String?): JsonObject? = runCatching {
    val payload = token?.split('.')?.getOrNull(1) ?: return null
    Json.parseToJsonElement(Base64.getUrlDecoder().decode(payload).decodeToString()).jsonObject
}.getOrNull()

private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
private fun String?.checkedToken(): String {
    require(!isNullOrBlank() && length <= 32_768 && none { it <= ' ' || it == '\u007f' })
    return this
}

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}

/** The caller serializes writes; save a rotated token before retrying the quota request. */
internal class OpenAiSessionClient(
    private val usage: OpenAiUsageClient = OpenAiUsageClient(),
    private val auth: OpenAiAuthClient = OpenAiAuthClient(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun fetch(initial: Credentials, save: suspend (Credentials) -> Unit): ApiResult<List<UsageWindow>> {
        var credentials = initial
        var refreshed = false
        suspend fun renew(): ApiResult<Credentials> {
            val result = auth.refresh(credentials)
            if (result is ApiResult.Success) {
                // Cancellation must not discard a refresh token already rotated by the server.
                withContext(NonCancellable) { save(result.value) }
                credentials = result.value
                refreshed = true
            }
            return result
        }
        if (credentials.refreshToken != null && credentials.expiresAtMillis?.let { it <= now() + 60_000 } == true) {
            when (val renewal = renew()) {
                ApiResult.Unauthorized -> return ApiResult.Unauthorized
                is ApiResult.Failure -> return renewal
                is ApiResult.Success -> Unit
            }
        }
        val result = usage.fetch(credentials)
        if (result != ApiResult.Unauthorized || credentials.refreshToken == null || refreshed) return result
        return when (val renewal = renew()) {
            ApiResult.Unauthorized -> ApiResult.Unauthorized
            is ApiResult.Failure -> renewal
            is ApiResult.Success -> usage.fetch(credentials)
        }
    }
}
