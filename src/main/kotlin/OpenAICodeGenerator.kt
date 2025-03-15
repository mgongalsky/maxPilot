import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Properties

// Data class для изменений одного файла
@Serializable
data class FileChange(
    val file_name: String,    // Имя файла, например "snake.py"
    val description: String,  // Краткое описание изменений или программы
    val code: String,         // Новый или изменённый код
    val user_message: String  // Сообщение для пользователя
)

// Контейнер, содержащий список изменений для нескольких файлов
@Serializable
data class MultiCodeResponse(
    val files: List<FileChange>
)

// Функция для загрузки API-ключа из файла ресурсов (openai.properties)
fun loadApiKey(): String {
    val inputStream = object {}.javaClass.getResourceAsStream("/openai.properties")
        ?: throw Exception("Файл openai.properties не найден в ресурсах")
    val properties = Properties()
    properties.load(inputStream)
    return properties.getProperty("api.key") ?: throw Exception("Свойство api.key не найдено в openai.properties")
}

// Класс для генерации MultiCodeResponse по пользовательскому запросу с возможностью передачи контекста
class OpenAiCodeGenerator(private val apiKey: String) {
    private val client: HttpClient = HttpClient.newBuilder().build()

    // JSON-схема для ответа, которая содержит список изменений для нескольких файлов
    private val schema = """
        {
          "type": "object",
          "properties": {
            "files": {
              "type": "array",
              "items": {
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
            }
          },
          "required": ["files"],
          "additionalProperties": false
        }
    """.trimIndent()

    /**
     * Генерирует объект MultiCodeResponse по данному запросу.
     * @param userQuery Текст запроса от пользователя.
     * @param context Дополнительный контекст (например, содержимое текущего файла), может быть null.
     * @return MultiCodeResponse, содержащий список изменений для файлов.
     */
    fun generateCodeResponse(userQuery: String, context: String? = null): MultiCodeResponse {
        // Формируем массив сообщений
        val messagesArray = buildJsonArray {
            add(buildJsonObject {
                put("role", "system")
                put("content", "Твоя задача — сгенерировать изменения для одного или нескольких файлов на питоне по запросу пользователя. " +
                        "Если пользователь просит доработать существующий код, используй предоставленный контекст. " +
                        "Ответ должен быть в формате JSON с ключом 'files', содержащим массив объектов, " +
                        "каждый из которых имеет следующие поля: file_name, description, code, user_message. " +
                        "Не меняй file_name без необходимости. Если требуется создать новый файл, используй новое file_name." +
                        "Пиши описание в коде ко всем функциям - в виде комментариев, чтобы IDE смогла их использовать." +
                        "В начале каждого файла тоже внутрикодовыми комментариями пиши краткое описание файла.")
            })
            context?.let {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", "Текущий контекст файла:\n$it")
                })
            }
            add(buildJsonObject {
                put("role", "user")
                put("content", userQuery)
            })
        }

        // Формируем пейлоад запроса
        val payload = buildJsonObject {
            put("model", "gpt-4o-2024-08-06")
            put("input", messagesArray)
            put("text", buildJsonObject {
                put("format", buildJsonObject {
                    put("type", "json_schema")
                    put("name", "code_response")
                    put("schema", Json.parseToJsonElement(schema))
                    put("strict", true)
                })
            })
        }

        // Сериализуем пейлоад в JSON-строку
        val jsonPayload = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), payload)
        println("Request payload:\n$jsonPayload")

        // Создаем HTTP POST-запрос к Responses API
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/responses"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
            .build()

        // Отправляем запрос и получаем ответ
        val httpResponse = client.send(request, HttpResponse.BodyHandlers.ofString())
        println("HTTP response code: ${httpResponse.statusCode()}")
        if (httpResponse.statusCode() != 200) {
            throw Exception("Ошибка: ${httpResponse.statusCode()} ${httpResponse.body()}")
        }

        // Разбираем JSON-ответ
        val responseJson = Json.parseToJsonElement(httpResponse.body()).jsonObject
        println("Response JSON:\n$responseJson")

        // Извлекаем outputText: сначала пробуем output_text, если нет – ищем в массиве output -> content -> text
        val outputText = responseJson["output_text"]?.jsonPrimitive?.content ?: run {
            val outputArray = responseJson["output"]?.jsonArray ?: throw Exception("Ответ не содержит output массива")
            if (outputArray.isEmpty()) throw Exception("Output массив пуст")
            val firstOutput = outputArray[0].jsonObject
            val contentArray = firstOutput["content"]?.jsonArray ?: throw Exception("Поле content отсутствует в output")
            if (contentArray.isEmpty()) throw Exception("Content массив пуст")
            contentArray[0].jsonObject["text"]?.jsonPrimitive?.content
                ?: throw Exception("Поле text отсутствует в content")
        }

        println("Output text:\n$outputText")
        return Json.decodeFromString(outputText)
    }
}

// Точка входа: если файл запущен напрямую, выполняется блок main
fun main() {
    val defaultQuery = if (System.getenv("USER_QUERY").isNullOrEmpty()) {
        "Обнови код змейки: добавь возможность съедать яблоки и увеличивать длину змейки"
    } else {
        System.getenv("USER_QUERY")
    }
    val apiKey = loadApiKey()
    val generator = OpenAiCodeGenerator(apiKey)
    val multiResponse = generator.generateCodeResponse(defaultQuery)
    println("Extracted file changes:")
    multiResponse.files.forEach { fileChange ->
        println("File: ${fileChange.file_name}")
        println("Description: ${fileChange.description}")
        println("User Message: ${fileChange.user_message}")
        println("Code:\n${fileChange.code}\n")
    }
}
