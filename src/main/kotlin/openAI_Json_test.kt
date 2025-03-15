import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Properties

// Data class, соответствующий ожидаемому JSON-ответу
@Serializable
data class CodeResponse(
    val file_name: String,    // Имя файла, например "snake.py"
    val description: String,  // Краткое описание игры
    val code: String,         // Код игры
    val user_message: String  // Сообщение для пользователя
)

// Функция для загрузки API-ключа из файла ресурсов (openai.properties)
fun loadApiKey(): String {
    val inputStream: InputStream = object {}.javaClass.getResourceAsStream("/openai.properties")
        ?: throw Exception("Файл openai.properties не найден в ресурсах")
    val properties = Properties()
    properties.load(inputStream)
    return properties.getProperty("api.key") ?: throw Exception("Свойство api.key не найдено в openai.properties")
}

fun main() {
    // 1. Загружаем API-ключ
    val apiKey = loadApiKey()

    // 2. Инициализируем HTTP клиент для отправки запросов
    val client = HttpClient.newBuilder().build()

    // 3. Определяем JSON-схему для CodeResponse (с обязательным полем "additionalProperties": false)
    val schema = """
        {
          "type": "object",
          "properties": {
              "file_name": { "type": "string" },
              "description": { "type": "string" },
              "code": { "type": "string" },
              "user_message": { "type": "string" }
          },
          "required": ["file_name", "description", "code", "user_message"],
          "additionalProperties": false
        }
    """.trimIndent()

    // 4. Формируем JSON-пейлоад для запроса к Responses API
    val payload = buildJsonObject {
        put("model", "gpt-4o-2024-08-06")
        put("input", buildJsonArray {
            add(buildJsonObject {
                put("role", "system")
                put("content", "Выполни задачу: напиши простую игру 'змейка' на Python. " +
                        "Игра должна управляться стрелками и не содержать врагов. " +
                        "Ответ должен быть в формате JSON с ключами: file_name, description, code, user_message.")
            })
            add(buildJsonObject {
                put("role", "user")
                put("content", "напиши нам змейку на питоне. управление стрелками, врага не нужно")
            })
        })
        put("text", buildJsonObject {
            put("format", buildJsonObject {
                put("type", "json_schema")
                put("name", "code_response")
                // Преобразуем строку схемы в JsonElement и передаём в поле "schema"
                put("schema", Json.parseToJsonElement(schema))
                put("strict", true)
            })
        })
    }

    // 5. Сериализуем пейлоад в JSON-строку
    val jsonPayload = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), payload)
    println("Request payload:\n$jsonPayload")

    // 6. Создаем HTTP POST-запрос к конечной точке Responses API
    val request = HttpRequest.newBuilder()
        .uri(URI.create("https://api.openai.com/v1/responses"))
        .header("Authorization", "Bearer $apiKey")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
        .build()

    // 7. Отправляем запрос и получаем ответ
    val httpResponse = client.send(request, HttpResponse.BodyHandlers.ofString())
    println("HTTP response code: ${httpResponse.statusCode()}")
    if (httpResponse.statusCode() != 200) {
        println("Ошибка: ${httpResponse.body()}")
        return
    }

    // 8. Разбираем полученный JSON-ответ
    val responseJson = Json.parseToJsonElement(httpResponse.body()).jsonObject
    println("Response JSON:\n$responseJson")

    // 9. Извлекаем поле output_text, если оно есть, иначе ищем в массиве output:
    val outputText = responseJson["output_text"]?.jsonPrimitive?.content ?: run {
        // Если поле output_text отсутствует, берем массив output
        val outputArray = responseJson["output"]?.jsonArray ?: throw Exception("Ответ не содержит output массива")
        if (outputArray.isEmpty()) throw Exception("Output массив пуст")
        val firstOutput = outputArray[0].jsonObject
        val contentArray = firstOutput["content"]?.jsonArray ?: throw Exception("Поле content отсутствует в первом элементе output")
        if (contentArray.isEmpty()) throw Exception("Content массив пуст")
        contentArray[0].jsonObject["text"]?.jsonPrimitive?.content ?: throw Exception("Поле text отсутствует в content")
    }

    println("Output text:\n$outputText")

    // 10. Десериализуем outputText (которое является JSON-строкой) в объект CodeResponse
    val codeResponse = Json.decodeFromString<CodeResponse>(outputText)

    // 11. Выводим только код игры (поле code) с сохранением переносов строк
    println("Extracted game code:")
    println(codeResponse.code)
}
