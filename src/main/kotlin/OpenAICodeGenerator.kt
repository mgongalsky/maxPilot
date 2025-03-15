package com.example.plugin

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
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
    val inputStream = object {}.javaClass.getResourceAsStream("/openai.properties")
        ?: throw Exception("Файл openai.properties не найден в ресурсах")
    val properties = Properties()
    properties.load(inputStream)
    return properties.getProperty("api.key") ?: throw Exception("Свойство api.key не найдено в openai.properties")
}

// Класс для генерации CodeResponse по пользовательскому запросу с возможностью передачи контекста
class OpenAiCodeGenerator(private val apiKey: String) {
    private val client: HttpClient = HttpClient.newBuilder().build()

    // JSON-схема для ответа, заданная вручную
    private val schema = """
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

    /**
     * Генерирует объект CodeResponse по данному запросу.
     * @param userQuery Текст запроса от пользователя.
     * @param context Дополнительный контекст (например, содержимое текущего файла), может быть null.
     * @return CodeResponse, содержащий сгенерированные данные.
     */
    fun generateCodeResponse(userQuery: String, context: String? = null): CodeResponse {
        // Формируем массив сообщений
        val messagesArray = buildJsonArray {
            // Системное сообщение с инструкцией
            add(buildJsonObject {
                put("role", "system")
                put("content", "Твоя задача - сгенерировать код на питоне по запросу пользователя, его нужно положить в поле code" +
                        "Пользователь может попросить доработать существующий код (явно или неявно). В этом случае старайся не создавать новых файлов, а исправлять те, которые тебе переданы в качестве контекста. Не меняй названия файлов file_name без надобности, но если логика подразумевает создание нового файла - то используй новое file_name, которое тебе кажется адекватным программе. " +
                        "В поле user_message напиши свой ответ пользователю, чтобы он понял, что ты сделал - кратко. Куски кода вставлять - не надо, это будет сделано автоматически. " +
                        "В поле description напиши краткое описание программы или файла" +
                        "Ответ должен быть в формате JSON с ключами: file_name, description, code, user_message.")
            })
            // Если передан контекст, добавляем дополнительное сообщение
            context?.let {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", "Текущий контекст файла:\n$it")
                })
            }
            // Пользовательское сообщение – запрос от пользователя
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

        // Десериализуем outputText в объект CodeResponse
        return Json.decodeFromString(outputText)
    }
}
