package space.megaworld.claudeusage.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

enum class UsageProvider(val label: String) { CLAUDE("Claude"), CODEX("Codex") }

class OpenAiImportException(message: String) : IllegalArgumentException(message)

/** Import only the access token: never rotate the desktop's shared refresh token. */
internal fun parseOpenAiCredentials(raw: String): Credentials {
    val root = try {
        Json.parseToJsonElement(raw.trim().removePrefix("\uFEFF").trim()).jsonObject
    } catch (e: IllegalArgumentException) {
        throw OpenAiImportException("Файл не является JSON-файлом входа Codex. Выберите auth.json из папки .codex.")
    }
    val tokens = root["tokens"] as? JsonObject ?: root
    fun field(name: String) = (tokens[name] as? JsonPrimitive)?.contentOrNull
    val access = field("access_token") ?: field("accessToken")
    if (access.isNullOrBlank() || access.any { it <= ' ' || it == '\u007f' }) {
        throw OpenAiImportException("В файле нет корректного access_token. Нужен вход Codex через подписку ChatGPT, а не API-ключ.")
    }
    val account = field("account_id").orEmpty()
    if (account.any { it < ' ' || it == '\u007f' }) {
        throw OpenAiImportException("В файле некорректный account_id. Выберите исходный auth.json без редактирования.")
    }
    return Credentials(access, account, System.currentTimeMillis())
}

/** Unofficial endpoint used by Codex; missing windows must remain missing, never 0%. */
internal fun parseCodexUsage(raw: String): List<UsageWindow> {
    val root = Json.parseToJsonElement(raw).jsonObject
    val windows = mutableListOf<UsageWindow>()
    fun bucket(value: JsonElement?, prefix: String) {
        val limit = value as? JsonObject ?: return
        for (slot in listOf("primary_window", "secondary_window")) {
            val window = limit[slot] as? JsonObject ?: continue
            val used = (window["used_percent"] as? JsonPrimitive)?.doubleOrNull ?: continue
            require(used.isFinite() && used in 0.0..100.0) { "Некорректный расход" }
            val duration = (window["limit_window_seconds"] as? JsonPrimitive)?.longOrNull
            val key = when (duration) {
                18000L -> UsageSnapshot.KEY_FIVE_HOUR
                604800L -> UsageSnapshot.KEY_SEVEN_DAY
                else -> "${duration ?: 0}s"
            }
            val reset = (window["reset_at"] as? JsonPrimitive)?.longOrNull
            windows += UsageWindow(prefix + key, used, reset?.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 }?.times(1000))
        }
    }
    bucket(root["rate_limit"], "")
    bucket(root["code_review_rate_limit"], "review_")
    (root["additional_rate_limits"] as? JsonArray)?.forEachIndexed { index, value ->
        val obj = value as? JsonObject ?: return@forEachIndexed
        val name = (obj["limit_name"] as? JsonPrimitive)?.contentOrNull ?: "extra_${index + 1}"
        bucket(obj["rate_limit"], "${name}_")
    }
    require(windows.isNotEmpty()) { "Сервер не передал числовые лимиты Codex" }
    return windows
}

/** Distinguishes rejected credentials from network/edge failures without exposing response bodies. */
internal fun classifyOpenAiUsageResponse(
    code: Int,
    body: String,
    contentType: String? = null,
    mitigation: String? = null,
): ApiResult<List<UsageWindow>> {
    val html = body.trimStart().startsWith("<") || contentType?.contains("text/html", ignoreCase = true) == true
    if (html || mitigation.equals("challenge", ignoreCase = true)) {
        return ApiResult.Failure(
            "OpenAI: HTTP $code — вместо лимитов пришла страница блокировки или проверки Cloudflare. " +
                "Это не доказательство истёкшей сессии. Проверьте маршрут VPN для AI Usage " +
                "(space.megaworld.claudeusage) и попробуйте другой узел."
        )
    }
    if (code == 401) return ApiResult.Unauthorized
    if (code == 403) {
        val errorCode = runCatching {
            val root = Json.parseToJsonElement(body).jsonObject
            val error = root["error"] as? JsonObject
            (error?.get("code") as? JsonPrimitive)?.contentOrNull
                ?: (root["code"] as? JsonPrimitive)?.contentOrNull
        }.getOrNull()
        val hint = when (errorCode) {
            "unsupported_country_region_territory" -> "сервер заблокировал регион подключения. Смените узел VPN."
            "token_expired", "invalid_token", "invalid_access_token", "token_revoked" -> return ApiResult.Unauthorized
            else -> "сервер отказал в доступе. Проверьте VPN-маршрут приложения и выбранный аккаунт; смените узел VPN."
        }
        return ApiResult.Failure("OpenAI: HTTP 403 — $hint")
    }
    if (code == 429) return ApiResult.Failure("OpenAI: HTTP 429 — слишком частые запросы. Подождите и повторите импорт.")
    if (code !in 200..299) return ApiResult.Failure("OpenAI: HTTP $code. Повторите запрос позже.")
    return try {
        ApiResult.Success(parseCodexUsage(body))
    } catch (e: IllegalArgumentException) {
        ApiResult.Failure("OpenAI: HTTP $code — сервер не передал распознаваемые числовые лимиты Codex.")
    }
}

class OpenAiUsageClient(
    private val client: OkHttpClient = defaultOpenAiClient(),
) {
    /** Uses the same route as import, without reading or sending any credentials. */
    suspend fun checkConnectivity(): String = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("https://chatgpt.com/backend-api/wham/usage")
                .header("Accept", "application/json").build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val result = classifyOpenAiUsageResponse(response.code, body,
                    response.header("Content-Type"), response.header("cf-mitigated"))
                when {
                    response.code == 401 && result == ApiResult.Unauthorized ->
                        "Доступ к серверу есть: HTTP 401 на запрос без токена — ожидаемый ответ. Теперь импортируйте auth.json. Эта проверка не подтверждает действительность токена."
                    result is ApiResult.Failure -> result.message
                    else -> "Сервер ответил HTTP ${response.code}. Проверка выполнена без токена; попробуйте импорт файла."
                }
            }
        } catch (e: java.io.IOException) {
            "Не удалось связаться с OpenAI. Проверьте интернет и VPN-маршрут приложения AI Usage."
        }
    }

    suspend fun fetch(credentials: Credentials): ApiResult<List<UsageWindow>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("https://chatgpt.com/backend-api/wham/usage")
                .header("Authorization", "Bearer ${credentials.cookieHeader}")
                .header("Accept", "application/json")
                .apply { if (credentials.userAgent.isNotBlank()) header("ChatGPT-Account-Id", credentials.userAgent) }
                .build()
            client.newCall(request).execute().use { response ->
                classifyOpenAiUsageResponse(response.code, response.body?.string().orEmpty(),
                    response.header("Content-Type"), response.header("cf-mitigated"))
            }
        } catch (e: java.io.IOException) {
            ApiResult.Failure("Не удалось связаться с OpenAI. Проверьте интернет и VPN-маршрут приложения AI Usage.")
        } catch (e: IllegalArgumentException) {
            ApiResult.Failure("Не удалось прочитать лимиты OpenAI. Проверьте формат файла входа.")
        }
    }
}

private fun defaultOpenAiClient() = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS).followRedirects(false).build()
