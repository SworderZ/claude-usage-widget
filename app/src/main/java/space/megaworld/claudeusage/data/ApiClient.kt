package space.megaworld.claudeusage.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>

    /** 401/403 либо HTML-челлендж Cloudflare вместо JSON. */
    data object Unauthorized : ApiResult<Nothing>

    data class Failure(val message: String) : ApiResult<Nothing>
}

/**
 * Клиент неофициального API claude.ai.
 *
 * Схема ответов не документирована и может измениться в любой момент, поэтому
 * разбор нестрогий: неизвестные ключи игнорируются, любое окно лимита распознаётся
 * по наличию поля `utilization`, отсутствие полей не считается ошибкой.
 */
class ApiClient(
    private val client: OkHttpClient = defaultClient(),
) {

    suspend fun fetchOrganizations(credentials: Credentials): ApiResult<List<Organization>> =
        get(ORGANIZATIONS_URL, credentials) { body ->
            val root = json.parseToJsonElement(body)
            val array = root as? JsonArray
                ?: (root as? JsonObject)?.get("organizations") as? JsonArray
                ?: return@get ApiResult.Failure("Неожиданный формат списка организаций")
            val orgs = array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val uuid = obj["uuid"]?.asStringOrNull() ?: return@mapNotNull null
                Organization(uuid = uuid, name = obj["name"]?.asStringOrNull())
            }
            if (orgs.isEmpty()) {
                ApiResult.Failure("Список организаций пуст")
            } else {
                ApiResult.Success(orgs)
            }
        }

    suspend fun fetchUsage(
        credentials: Credentials,
        organizationUuid: String,
    ): ApiResult<List<UsageWindow>> =
        get(usageUrl(organizationUuid), credentials) { body ->
            val windows = parseWindows(json.parseToJsonElement(body))
            if (windows.isEmpty()) {
                ApiResult.Failure("В ответе нет ни одного окна лимита")
            } else {
                ApiResult.Success(windows)
            }
        }

    private suspend fun <T> get(
        url: String,
        credentials: Credentials,
        parse: (String) -> ApiResult<T>,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Cookie", credentials.cookieHeader)
            .header("User-Agent", credentials.userAgent)
            .header("Accept", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "https://claude.ai/")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) return@withContext ApiResult.Unauthorized
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext ApiResult.Failure("HTTP ${response.code}")
                }
                // Cloudflare отдаёт 200 + HTML-страницу проверки — для нас это тот же
                // «нужен перелогин», а не сетевая ошибка.
                if (body.trimStart().startsWith("<")) return@withContext ApiResult.Unauthorized
                try {
                    parse(body)
                } catch (e: Exception) {
                    ApiResult.Failure("Не удалось разобрать ответ: ${e.message}")
                }
            }
        } catch (e: IOException) {
            ApiResult.Failure(e.message ?: "Сеть недоступна")
        }
    }

    private companion object {
        const val ORGANIZATIONS_URL = "https://claude.ai/api/organizations"

        fun usageUrl(uuid: String) = "https://claude.ai/api/organizations/$uuid/usage"

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}

/**
 * Собирает окна лимитов из произвольного JSON: любой вложенный объект с полем
 * `utilization` считается окном. Так новые ключи (`seven_day_opus` и подобные)
 * подхватываются без правок кода.
 */
internal fun parseWindows(element: kotlinx.serialization.json.JsonElement): List<UsageWindow> {
    val root = element as? JsonObject ?: return emptyList()
    val direct = root.collectWindows()
    if (direct.isNotEmpty()) return direct.sortedWith(windowOrder)
    // Данные могли уехать на уровень глубже (например, под ключом "usage").
    return root.values
        .filterIsInstance<JsonObject>()
        .flatMap { it.collectWindows() }
        .sortedWith(windowOrder)
}

private fun JsonObject.collectWindows(): List<UsageWindow> = entries.mapNotNull { (key, value) ->
    val obj = value as? JsonObject ?: return@mapNotNull null
    val utilization = obj["utilization"]?.asDoubleOrNull() ?: return@mapNotNull null
    UsageWindow(
        key = key,
        utilization = utilization.coerceIn(0.0, 100.0),
        resetsAtMillis = obj["resets_at"]?.asStringOrNull()?.let(::parseIsoMillis),
    )
}

/** Сначала окна из спецификации, остальные — по алфавиту, чтобы порядок был стабилен. */
private val windowOrder = compareBy<UsageWindow>(
    {
        when (it.key) {
            UsageSnapshot.KEY_FIVE_HOUR -> 0
            UsageSnapshot.KEY_SEVEN_DAY -> 1
            else -> 2
        }
    },
    { it.key },
)

internal fun parseIsoMillis(raw: String): Long? {
    if (raw.isBlank()) return null
    return try {
        Instant.parse(raw).toEpochMilli()
    } catch (e: DateTimeParseException) {
        try {
            OffsetDateTime.parse(raw).toInstant().toEpochMilli()
        } catch (e2: DateTimeParseException) {
            null
        }
    }
}

private fun kotlinx.serialization.json.JsonElement.asStringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun kotlinx.serialization.json.JsonElement.asDoubleOrNull(): Double? =
    (this as? JsonPrimitive)?.doubleOrNull
