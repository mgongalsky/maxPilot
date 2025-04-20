package com.example.plugin

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
    val file_name: String,    // Имя файла, например "arkanoid.py"
    val description: String,  // Краткое описание изменений или программы
    val code: String,         // Новый или изменённый код
    val user_message: String, // Сообщение для пользователя
    // Дополнительные поля для управления обновлением PSI
    val update_mode: String? = null,       // "update_element", "create_element", "update_file"
    val parent_signature: String? = null,  // Сигнатура родительского элемента, если нужно добавить новый элемент
    val target_file: String? = null        // Имя файла, в который нужно добавить элемент, если это новый файл
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

// Класс для генерации MultiCodeResponse по запросу
class OpenAiCodeGenerator(private val apiKey: String) {
    private val client: HttpClient = HttpClient.newBuilder().build()

    // JSON-схема для проверки формата ответа
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
              "user_message": { "type": "string" },
              "update_mode": { "type": "string" },
              "parent_signature": { "type": "string" },
              "target_file": { "type": "string" }
            },
            "required": ["file_name", "description", "code", "user_message", "update_mode", "parent_signature", "target_file"],
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
     */
    fun generateCodeResponse(userQuery: String, context: String? = null): MultiCodeResponse {
        // 1) строим messages
        val messagesArray = buildJsonArray {
            add(buildJsonObject {
                put("role", "system")
                put("content", "Твоя задача — сгенерировать изменения для одного или нескольких файлов на питоне по запросу пользователя. " +
                        "Если пользователь просит доработать существующий код, используй предоставленный контекст. " +
                        "Ответ должен быть в формате JSON с ключом 'files', содержащим массив объектов, " +
                        "каждый из которых имеет следующие поля: file_name, description, code, user_message. " +
                        "Если необходимо, добавь опциональные поля update_mode, parent_signature и target_file, " +
                        "чтобы указать, обновлять ли отдельный PSI-элемент, создавать новый элемент или обновлять весь файл. " +
                        "Не меняй file_name без необходимости. Если требуется создать новый файл, используй новое file_name, но это поле обязательно не должно быть пустым. " +
                        "Пиши описание в коде ко всем функциям в виде комментариев, чтобы IDE могла их использовать. " +
                        "В начале каждого файла также пиши краткое описание файла внутрикодовыми комментариями." +
                        "Не используй патчи и диффы, давай сразу полноценные куски на уровне элементов PSI")
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

        // 2) пейлоад
        val payload = buildJsonObject {
            //put("model", "o4-mini")
            put("model", "o3-mini")
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
        val jsonPayload = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), payload)
        println("Request payload:\n$jsonPayload")

        // 3) запрос
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/responses"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
            .build()

        val httpResponse = client.send(request, HttpResponse.BodyHandlers.ofString())
        println("HTTP response code: ${httpResponse.statusCode()}")
        if (httpResponse.statusCode() != 200) {
            throw Exception("Ошибка: ${httpResponse.statusCode()} ${httpResponse.body()}")
        }

        // 4) парсим ответ
        val responseJson = Json.parseToJsonElement(httpResponse.body()).jsonObject
        println("Response JSON:\n$responseJson")

        // 5) извлечение текста с JSON из блока output[type=message]
        val outputText = responseJson["output_text"]?.jsonPrimitive?.content ?: run {
            // находим элемент output с type="message"
            val outputArray = responseJson["output"]?.jsonArray
                ?: throw Exception("Ответ не содержит output массива")
            // ищем самый релевантный message
            val messageObj = outputArray
                .firstOrNull { elem ->
                    elem.jsonObject["type"]?.jsonPrimitive?.content == "message"
                }
                ?.jsonObject
                ?: throw Exception("В output нет элемента с type=\"message\"")
            // извлекаем content -> text
            val contentArr = messageObj["content"]?.jsonArray
                ?: throw Exception("Поле content отсутствует в элементе output[type=message]")
            if (contentArr.isEmpty()) throw Exception("Content массив пуст")
            val textField = contentArr[0].jsonObject["text"]?.jsonPrimitive?.content
                ?: throw Exception("В content отсутствует поле text")
            textField
        }

        println("Output text:\n$outputText")

        // 6) десериализуем его в MultiCodeResponse
        return Json.decodeFromString(outputText)
    }
}

// Точка входа
fun main() {
    val defaultQuery = System.getenv("USER_QUERY").takeUnless { it.isNullOrEmpty() }
        ?: "Давай напишем игру арканоид"
    val apiKey = loadApiKey()
    val generator = OpenAiCodeGenerator(apiKey)
    val multiResponse = generator.generateCodeResponse(defaultQuery)
    println("Extracted file changes:")
    multiResponse.files.forEach { fileChange ->
        println("File: ${fileChange.file_name}")
        println("Description: ${fileChange.description}")
        println("User Message: ${fileChange.user_message}")
        println("Code:\n${fileChange.code}\n")
        fileChange.update_mode?.let { println("Update Mode: $it") }
        fileChange.parent_signature?.let { println("Parent Signature: $it") }
        fileChange.target_file?.let { println("Target File: $it") }
    }
}
