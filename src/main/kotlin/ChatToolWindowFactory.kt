package com.example.plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.awt.BorderLayout
import javax.swing.*
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiDocumentManager
import com.intellij.lang.Language
import java.util.*
import java.util.concurrent.TimeUnit

class ChatToolWindowFactory : ToolWindowFactory {

    // Создаем HTTP-клиент с увеличенным таймаутом
    private val client = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Основная панель
        val mainPanel = JPanel(BorderLayout())

        // Текстовая область для диалога с прокруткой
        val chatArea = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }
        val scrollPane = JScrollPane(chatArea)

        // Панель ввода сообщения
        val inputPanel = JPanel(BorderLayout())
        val inputField = JTextField()
        val sendButton = JButton("Отправить")
        inputPanel.add(inputField, BorderLayout.CENTER)
        inputPanel.add(sendButton, BorderLayout.EAST)

        mainPanel.add(scrollPane, BorderLayout.CENTER)
        mainPanel.add(inputPanel, BorderLayout.SOUTH)

        fun sendMessage() {
            val userMessage = inputField.text.trim()
            val props = loadOpenAiProperties()
            if (props == null) {
                JOptionPane.showMessageDialog(mainPanel, "Не удалось загрузить openai.properties из ресурсов.", "Ошибка", JOptionPane.ERROR_MESSAGE)
                return
            }
            val apiKey = props.getProperty("api.key")
            // В файле openai.properties укажите модель, поддерживающую Structured Outputs, например:
            // model=gpt-4o-mini-2024-08-06
            val selectedModel = props.getProperty("model") ?: "gpt-4o-mini-2024-08-06"
            if (userMessage.isNotEmpty()) {
                chatArea.append("Вы: $userMessage\n")

                // Формируем JSON-схему для Structured Outputs
                val schema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("fileName", JSONObject().apply {
                            put("type", "string")
                            put("description", "Имя файла, который нужно создать")
                        })
                        put("code", JSONObject().apply {
                            put("type", "string")
                            put("description", "Содержимое файла с кодом")
                        })
                    })
                    put("required", JSONArray().put("fileName").put("code"))
                    put("additionalProperties", false)
                }

                // Параметры для Structured Outputs
                val responseFormat = JSONObject().apply {
                    put("type", "json_schema")
                    put("json_schema", JSONObject().apply {
                        put("strict", true)
                        put("schema", schema)
                    })
                }

                // Формируем тело запроса с параметром response_format
                val jsonBody = JSONObject().apply {
                    put("model", selectedModel)
                    put("messages", JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", userMessage)
                        }
                    ))
                    put("response_format", responseFormat)
                }.toString()

                // Выводим запрос для отладки
                chatArea.append("Полный запрос:\n$jsonBody\n\n")

                // Отправляем запрос и получаем ответ
                val responseText = sendChatMessage(apiKey, jsonBody)

                // Выводим полный ответ сервера для отладки
                chatArea.append("Полный ответ от сервера:\n$responseText\n\n")
                inputField.text = ""

                // Пытаемся обработать ответ как JSON со структурированными данными
                try {
                    val jsonResponse = JSONObject(responseText)
                    if (!jsonResponse.has("fileName") || !jsonResponse.has("code")) {
                        chatArea.append("Ошибка: ответ не соответствует ожидаемой структуре.\n")
                        return
                    }
                    val fileName = jsonResponse.getString("fileName")
                    val codeText = jsonResponse.getString("code")
                    createOrUpdateFile(project, fileName, codeText)
                    chatArea.append("Файл '$fileName' успешно создан/обновлён.\n")
                } catch (e: Exception) {
                    chatArea.append("Ошибка обработки ответа: ${e.message}\n")
                    chatArea.append("Полный ответ для отладки:\n$responseText\n")
                }
            } else {
                JOptionPane.showMessageDialog(mainPanel, "Пожалуйста, введите описание программы.", "Ошибка", JOptionPane.ERROR_MESSAGE)
            }
        }

        sendButton.addActionListener { sendMessage() }
        inputField.addActionListener { sendMessage() }

        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(mainPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    /**
     * Загружает настройки из файла openai.properties, расположенного в resources.
     */
    private fun loadOpenAiProperties(): Properties? {
        val props = Properties()
        val inputStream = this::class.java.classLoader.getResourceAsStream("openai.properties")
            ?: return null
        props.load(inputStream)
        return props
    }

    /**
     * Отправляет запрос к OpenAI ChatGPT API и возвращает ответ в виде строки.
     * jsonBody – готовое тело запроса в формате JSON, включая параметр response_format.
     */
    private fun sendChatMessage(apiKey: String, jsonBody: String): String {
        return try {
            val url = "https://api.openai.com/v1/chat/completions"
            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val body = jsonBody.toRequestBody(mediaType)
            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return "Ошибка при запросе: ${response.code} ${response.message}"
                }
                response.body?.string() ?: "Пустой ответ от сервера"
            }
        } catch (e: Exception) {
            "Ошибка: ${e.message}"
        }
    }

    /**
     * Создает или обновляет Python-файл с заданным именем и содержимым кода через PSI.
     */
    private fun createOrUpdateFile(project: Project, fileName: String, code: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            val baseDir = project.baseDir
            val psiManager = PsiManager.getInstance(project)
            val psiDirectory = psiManager.findDirectory(baseDir) ?: return@runWriteCommandAction
            val pythonLanguage = Language.findLanguageByID("Python") ?: return@runWriteCommandAction
            var psiFile = psiDirectory.findFile(fileName)
            if (psiFile == null) {
                psiFile = PsiFileFactory.getInstance(project)
                    .createFileFromText(fileName, pythonLanguage, code)
                psiDirectory.add(psiFile)
            } else {
                val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                if (document != null) {
                    document.setText(code)
                    PsiDocumentManager.getInstance(project).commitDocument(document)
                }
            }
        }
    }
}
