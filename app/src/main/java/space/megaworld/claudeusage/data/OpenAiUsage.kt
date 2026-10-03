package space.megaworld.claudeusage.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

enum class UsageProvider(val label: String) { CLAUDE("Claude"), CODEX("Codex") }

/** Import only the access token: never rotate the desktop's shared refresh token. */
internal fun parseOpenAiCredentials(raw: String): Credentials {
    val root = Json.parseToJsonElement(raw).jsonObject
    val tokens = root["tokens"] as? JsonObject ?: root
    val access = tokens["access_token"]?.jsonPrimitive?.contentOrNull
        ?: tokens["accessToken"]?.jsonPrimitive?.contentOrNull
    require(!access.isNullOrBlank() && !access.contains('\n') && !access.contains('\r')) { "В файле нет access_token" }
    val account = tokens["account_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
    require(!account.contains('\n') && !account.contains('\r')) { "Некорректный аккаунт" }
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

class OpenAiUsageClient {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).build()

    suspend fun fetch(credentials: Credentials): ApiResult<List<UsageWindow>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("https://chatgpt.com/backend-api/wham/usage")
                .header("Authorization", "Bearer ${credentials.cookieHeader}")
                .header("Accept", "application/json")
                .apply { if (credentials.userAgent.isNotBlank()) header("ChatGPT-Account-Id", credentials.userAgent) }
                .build()
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 -> ApiResult.Unauthorized
                    !response.isSuccessful -> ApiResult.Failure("OpenAI: HTTP ${response.code}")
                    else -> ApiResult.Success(parseCodexUsage(response.body?.string().orEmpty()))
                }
            }
        } catch (e: java.io.IOException) {
            ApiResult.Failure("Не удалось связаться с OpenAI")
        } catch (e: IllegalArgumentException) {
            ApiResult.Failure("Не удалось прочитать лимиты OpenAI")
        }
    }
}
