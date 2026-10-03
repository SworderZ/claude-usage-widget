package space.megaworld.claudeusage.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Клиент Open-Meteo: общий прогноз осадков для выбранных каналов Glyph.
 *
 * Выбран за то, что у него нет ни ключа, ни регистрации, ни обязательной
 * геолокации — место пользователь задаёт названием, координаты один раз
 * достаёт геокодер того же сервиса.
 *
 * Клиент здесь свой, отдельный от [ApiClient], и это осознанно: туда credentials
 * подставляются вручную заголовком `Cookie`, и cookie claude.ai не должны даже
 * теоретически уехать на сторонний хост.
 */
class WeatherClient(
    private val client: OkHttpClient = defaultClient(),
) {

    /** Название места → координаты. Вызывается один раз, при выборе места в настройках. */
    suspend fun geocode(query: String): ApiResult<WeatherPlace> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return ApiResult.Failure("Пустой запрос")
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        return get("$GEOCODE_URL?name=$encoded&count=1&language=ru&format=json") { body ->
            val results = (json.parseToJsonElement(body) as? JsonObject)
                ?.get("results") as? JsonArray
            val first = results?.firstOrNull() as? JsonObject
                ?: return@get ApiResult.Failure("Место не найдено")
            val latitude = first.number("latitude")
            val longitude = first.number("longitude")
            if (latitude == null || longitude == null) {
                return@get ApiResult.Failure("Сервис не вернул координаты")
            }
            ApiResult.Success(
                WeatherPlace(
                    name = first.text("name") ?: trimmed,
                    latitude = latitude,
                    longitude = longitude,
                )
            )
        }
    }

    /**
     * Максимальная вероятность осадков на ближайшие [FORECAST_HOURS] часа.
     *
     * Берём максимум, а не текущий час: смысл канала — «брать ли зонт», а для
     * этого важнее, что дождь будет через два часа, чем что сейчас сухо.
     */
    suspend fun fetchRain(place: WeatherPlace): ApiResult<RainForecast> {
        val url = FORECAST_URL +
            "?latitude=" + place.latitude +
            "&longitude=" + place.longitude +
            "&hourly=precipitation_probability" +
            "&forecast_hours=" + FORECAST_HOURS +
            "&timezone=auto"
        return get(url) { body ->
            val hourly = (json.parseToJsonElement(body) as? JsonObject)
                ?.get("hourly") as? JsonObject
            val values = hourly?.get("precipitation_probability") as? JsonArray
                ?: return@get ApiResult.Failure("Неожиданный формат прогноза")
            val probability = values.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.maxOrNull()
                ?: return@get ApiResult.Failure("Прогноз без вероятности осадков")
            ApiResult.Success(
                RainForecast(
                    probabilityPercent = probability.coerceIn(0, 100),
                    fetchedAtMillis = System.currentTimeMillis(),
                )
            )
        }
    }

    private suspend fun <T> get(
        url: String,
        parse: (String) -> ApiResult<T>,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext ApiResult.Failure("Сервис погоды вернул ${response.code}")
                }
                runCatching { parse(body) }
                    .getOrElse { ApiResult.Failure("Не удалось разобрать ответ погоды") }
            }
        } catch (e: IOException) {
            ApiResult.Failure(e.message ?: "Сеть недоступна")
        }
    }

    private companion object {
        const val GEOCODE_URL = "https://geocoding-api.open-meteo.com/v1/search"
        const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"

        /** Горизонт «успею ли вернуться до дождя». */
        const val FORECAST_HOURS = 3

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        fun JsonObject.number(key: String): Double? = (get(key) as? JsonPrimitive)?.doubleOrNull

        fun JsonObject.text(key: String): String? =
            (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

        // Погода не критична: ждём её заметно меньше, чем данные о лимитах.
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
