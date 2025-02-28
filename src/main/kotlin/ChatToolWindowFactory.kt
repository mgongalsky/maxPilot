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
import java.awt.FlowLayout
import javax.swing.*

class ChatToolWindowFactory : ToolWindowFactory {

    private val client = OkHttpClient()

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Основная панель с BorderLayout
        val mainPanel = JPanel(BorderLayout())

        // Верхняя панель для ввода API-ключа и выбора модели
        val topPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        val apiKeyField = JTextField(25).apply {
            toolTipText = "Введите ваш OpenAI API Key"
        }
        val modelSelector = JComboBox(arrayOf("gpt-3.5-turbo", "gpt-4")).apply {
            selectedIndex = 0
        }
        topPanel.add(JLabel("API Key:"))
        topPanel.add(apiKeyField)
        topPanel.add(JLabel("Model:"))
        topPanel.add(modelSelector)

        // Область для отображения истории чата с прокруткой
        val chatArea = JTextArea().apply {
            isEditable = false
        }
        val scrollPane = JScrollPane(chatArea)

        // Панель для ввода сообщения и кнопки отправки
        val inputPanel = JPanel(BorderLayout())
        val inputField = JTextField()
        val sendButton = JButton("Отправить")
        inputPanel.add(inputField, BorderLayout.CENTER)
        inputPanel.add(sendButton, BorderLayout.EAST)

        // Собираем основной интерфейс
        mainPanel.add(topPanel, BorderLayout.NORTH)
        mainPanel.add(scrollPane, BorderLayout.CENTER)
        mainPanel.add(inputPanel, BorderLayout.SOUTH)

        // Обработка нажатия кнопки "Отправить"
        sendButton.addActionListener {
            val userMessage = inputField.text.trim()
            val apiKey = apiKeyField.text.trim()
            val selectedModel = modelSelector.selectedItem?.toString() ?: "gpt-3.5-turbo"
            if (userMessage.isNotEmpty() && apiKey.isNotEmpty()) {
                chatArea.append("Вы: $userMessage\n")
                val responseText = sendChatMessage(apiKey, selectedModel, userMessage)
                chatArea.append("ChatGPT: $responseText\n\n")
                inputField.text = ""
            } else {
                JOptionPane.showMessageDialog(
                    mainPanel,
                    "Пожалуйста, введите API-ключ и сообщение.",
                    "Ошибка",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        }

        // Создаем контент для ToolWindow и регистрируем его
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(mainPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    /**
     * Отправка запроса к OpenAI ChatGPT API и возврат ответа.
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
