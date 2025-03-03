import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.*
import java.util.concurrent.TimeUnit

// Простой data class для календарного события (пример)
data class CalendarEvent(val name: String, val date: String, val participants: List<String>)

// Функция для загрузки настроек из файла openai.properties, который должен быть в src/main/resources
fun loadOpenAiProperties(): Properties? {
    val props = Properties()
    val inputStream = object {}.javaClass.classLoader.getResourceAsStream("openai.properties") ?: return null
    props.load(inputStream)
    return props
}

suspend fun main() {
    // Загружаем настройки
    val props = loadOpenAiProperties() ?: run {
        println("Не удалось загрузить openai.properties")
        return
    }
    val apiKey = props.getProperty("api.key")
    // Если нужно, модель можно тоже вынести в настройки. Здесь используем значение по умолчанию.
    val model = props.getProperty("model") ?: "gpt-4o-mini-2024-08-06"

    // Определяем JSON-схему для Structured Outputs.
    // В этом примере ожидается, что ответ будет содержать объект с полем "response" (строка).
    val schemaJson = """
        {
            "type": "object",
            "properties": {
                "response": {
                    "type": "string"
                }
            },
            "required": ["response"],
            "additionalProperties": false
        }
    """.trimIndent()

    // Формируем объект response_format как JSON-объект
    val responseFormat = buildJsonObject {
        put("type", JsonPrimitive("json_schema"))
        put("json_schema", Json.parseToJsonElement(schemaJson))
    }

    // Формируем тело запроса с использованием kotlinx.serialization JSON API
    val requestBodyJson = buildJsonObject {
        put("model", model)
        put("messages", buildJsonArray {
            add(
                buildJsonObject {
                    put("role", "system")
                    put("content", "You are a helpful assistant. Please respond with structured output in JSON.")
                }
            )
            add(
                buildJsonObject {
                    put("role", "user")
                    put("content", "Tell me a simple message.")
                }
            )
        })
        put("response_format", responseFormat)
    }

    val requestBodyStr = requestBodyJson.toString()
    println("Полный запрос:\n$requestBodyStr\n")

    val client = OkHttpClient.Builder()
        .callTimeout(120, TimeUnit.SECONDS)
        .build()
    val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
    val body = requestBodyStr.toRequestBody(mediaType)
    val request = Request.Builder()
        .url("https://api.openai.com/v1/chat/completions")
        .addHeader("Content-Type", "application/json")
        .addHeader("Authorization", "Bearer $apiKey")
        .post(body)
        .build()

    try {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                println("Ошибка при запросе: ${response.code} ${response.message}")
                return
            }
            val responseStr = response.body?.string() ?: "Пустой ответ от сервера"
            println("Полный ответ от сервера:\n$responseStr\n")

            // Пытаемся распарсить ответ как JSON
            val jsonResponse = Json.parseToJsonElement(responseStr).jsonObject
            // Извлекаем поле "response" из первого выбора (если такое имеется)
            // Предполагаем, что ответ модели будет выглядеть примерно так:
            // { "response": "Your structured output text here" }
            val choices = jsonResponse["choices"]?.jsonArray
            if (choices != null && choices.isNotEmpty()) {
                val message = choices[0].jsonObject["message"]?.jsonObject
                if (message != null && message.containsKey("content")) {
                    val content = message["content"]?.jsonPrimitive?.content
                    println("Распарсенный ответ: $content")
                } else {
                    println("Поле content не найдено в ответе.")
                }
            } else {
                println("Массив choices пуст или отсутствует.")
            }
        }
    } catch (e: Exception) {
        println("Ошибка: ${e.message}")
    }
}
