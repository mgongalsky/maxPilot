package com.example.plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.awt.BorderLayout
import javax.swing.*
import java.util.*

class ChatToolWindowFactory : ToolWindowFactory {

    private val client = OkHttpClient()

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Основная панель с BorderLayout
        val mainPanel = JPanel(BorderLayout())

        // Область для отображения истории чата с прокруткой
        val chatArea = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }
        val scrollPane = JScrollPane(chatArea)

        // Панель для ввода сообщения и кнопки отправки
        val inputPanel = JPanel(BorderLayout())
        val inputField = JTextField()
        val sendButton = JButton("Отправить")
        inputPanel.add(inputField, BorderLayout.CENTER)
        inputPanel.add(sendButton, BorderLayout.EAST)

        // Собираем интерфейс: область чата и панель ввода внизу
        mainPanel.add(scrollPane, BorderLayout.CENTER)
        mainPanel.add(inputPanel, BorderLayout.SOUTH)

        // Функция отправки сообщения
        fun sendMessage() {
            val userMessage = inputField.text.trim()
            // Получаем API-ключ из openai.properties (файл должен лежать в resources)
            val props = loadOpenAiProperties()
            if (props == null) {
                JOptionPane.showMessageDialog(
                    mainPanel,
                    "Не удалось загрузить openai.properties из ресурсов.",
                    "Ошибка",
                    JOptionPane.ERROR_MESSAGE
                )
                return
            }
            val apiKey = props.getProperty("api.key")
            val selectedModel = "o1-mini" // или другая модель по вашему выбору
            if (userMessage.isNotEmpty()) {
                chatArea.append("Вы: $userMessage\n")
                val responseText = sendChatMessage(apiKey, selectedModel, userMessage)
                chatArea.append("ChatGPT: $responseText\n\n")
                inputField.text = ""
            } else {
                JOptionPane.showMessageDialog(
                    mainPanel,
                    "Пожалуйста, введите сообщение.",
                    "Ошибка",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        }

        // Обработка нажатия кнопки "Отправить" и клавиши Enter
        sendButton.addActionListener { sendMessage() }
        inputField.addActionListener { sendMessage() }

        // Создаем контент для ToolWindow и регистрируем его
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(mainPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    /**
     * Загружает настройки из файла openai.properties, расположенного в папке ресурсов.
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
     */
    private fun sendChatMessage(apiKey: String, model: String, userMessage: String): String {
        return try {
            val url = "https://api.openai.com/v1/chat/completions"
            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()

            // Формирование JSON-тела запроса
            val jsonBody = JSONObject().apply {
                put("model", model)
                put("messages", listOf(
                    mapOf("role" to "user", "content" to userMessage)
                ))
            }.toString()

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
                val responseString = response.body?.string() ?: return "Пустой ответ от сервера"
                val json = JSONObject(responseString)
                val choices = json.getJSONArray("choices")
                if (choices.length() > 0) {
                    choices.getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()
                } else {
                    "Не удалось получить ответ от ChatGPT"
                }
            }
        } catch (e: Exception) {
            "Ошибка: ${e.message}"
        }
    }
}
