package com.example.plugin

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Properties

// Data class для представления одного узла PSI, релевантного для задачи
@Serializable
data class CodeNode(
    val file: String,       // Имя или путь файла
    val nodeType: String,   // Тип узла, например "Класс" или "Функция"
    val name: String,       // Имя узла
    val description: String, // Краткое описание, почему этот узел полезен
    val parent_signature: String // Родительский элемент; если отсутствует, передавайте пустую строку
)

// Контейнер для ответа с узлами
@Serializable
data class CodeNodesResponse(
    val nodes: List<CodeNode>
)

// Класс, который отправляет задание вместе с деревом кода и получает релевантные узлы
class OpenAiContextFilter(private val apiKey: String) {
    private val client: HttpClient = HttpClient.newBuilder().build()

    // Обновлённая JSON-схема: теперь в "required" перечислены все ключи, включая "parent_signature"
    private val schema = """
        {
          "type": "object",
          "properties": {
            "nodes": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "file": { "type": "string" },
                  "nodeType": { "type": "string" },
                  "name": { "type": "string" },
                  "description": { "type": "string" },
                  "parent_signature": { "type": "string" }
                },
                "required": ["file", "nodeType", "name", "description", "parent_signature"],
                "additionalProperties": false
              }
            }
          },
          "required": ["nodes"],
          "additionalProperties": false
        }
    """.trimIndent()

    /**
     * Отправляет задание и дерево контекста в OpenAI API и возвращает список релевантных узлов.
     *
     * @param userTask Задание, которое нужно выполнить.
     * @param tree Текстовое представление дерева PSI (например, список файлов с классами и функциями).
     * @return CodeNodesResponse, содержащий список узлов, полезных для выполнения задачи.
     */
    fun filterNodes(userTask: String, tree: String): CodeNodesResponse {
        // Формируем массив сообщений для запроса
        val messagesArray = buildJsonArray {
            add(buildJsonObject {
                put("role", "system")
                put("content", "Ты ассистент, который помогает определить, какие узлы из данного PSI-дерева полезны для выполнения задачи. " +
                        "Тебе предоставлены задание и дерево проекта. Верни только те узлы, которые имеют отношение к задаче. " +
                        "Если узел является функцией, укажи его родительский элемент (например, класс), если он существует. " +
                        "Ответ должен быть в формате JSON с ключом 'nodes', содержащим массив объектов, где каждый объект имеет поля: " +
                        "'file' (имя или путь файла), 'nodeType' (например, 'Класс' или 'Функция'), 'name' (имя узла), " +
                        "'description' (краткое описание, почему данный узел полезен) и 'parent_signature' (пустая строка, если родителя нет).")
            })
            // Передаём дерево проекта как системное сообщение
            add(buildJsonObject {
                put("role", "system")
                put("content", "Дерево проекта:\n$tree")
            })
            // Сообщение пользователя с заданием
            add(buildJsonObject {
                put("role", "user")
                put("content", userTask)
            })
        }

        // Формируем пейлоад запроса с указанием модели и схемы ответа
        val payload = buildJsonObject {
            put("model", "gpt-4o-2024-08-06")
            put("input", messagesArray)
            put("text", buildJsonObject {
                put("format", buildJsonObject {
                    put("type", "json_schema")
                    put("name", "context_filter")
                    put("schema", Json.parseToJsonElement(schema))
                    put("strict", true)
                })
            })
        }

        // Сериализуем пейлоад в JSON-строку
        val jsonPayload = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), payload)
        println("Request payload for context filtering:\n$jsonPayload")

        // Создаем HTTP POST-запрос к OpenAI API
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
        println("Response JSON for context filtering:\n$responseJson")

        // Извлекаем outputText
        val outputText = responseJson["output_text"]?.jsonPrimitive?.content ?: run {
            val outputArray = responseJson["output"]?.jsonArray ?: throw Exception("Ответ не содержит output массива")
            if (outputArray.isEmpty()) throw Exception("Output массив пуст")
            val firstOutput = outputArray[0].jsonObject
            val contentArray = firstOutput["content"]?.jsonArray ?: throw Exception("Поле content отсутствует в output")
            if (contentArray.isEmpty()) throw Exception("Content массив пуст")
            contentArray[0].jsonObject["text"]?.jsonPrimitive?.content
                ?: throw Exception("Поле text отсутствует в content")
        }

        println("Output text for context filtering:\n$outputText")
        return Json.decodeFromString(outputText)
    }
}
