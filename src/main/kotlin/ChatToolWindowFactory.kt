package com.example.plugin

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.ui.content.ContentFactory
import com.intellij.lang.Language
import java.awt.BorderLayout
import java.util.Properties
import javax.swing.*

class ChatToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Основная панель с BorderLayout
        val mainPanel = JPanel(BorderLayout())

        // Текстовая область для вывода истории диалога
        val chatArea = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }
        val scrollPane = JScrollPane(chatArea)

        // Панель ввода с текстовым полем и кнопкой "Сгенерировать"
        val inputPanel = JPanel(BorderLayout())
        val inputField = JTextField()
        val generateButton = JButton("Сгенерировать")
        inputPanel.add(inputField, BorderLayout.CENTER)
        inputPanel.add(generateButton, BorderLayout.EAST)

        mainPanel.add(scrollPane, BorderLayout.CENTER)
        mainPanel.add(inputPanel, BorderLayout.SOUTH)

        // Функция для обработки запроса и генерации кода
        fun generateCode() {
            val userMessage = inputField.text.trim()
            if (userMessage.isEmpty()) {
                JOptionPane.showMessageDialog(mainPanel, "Пожалуйста, введите описание программы.", "Ошибка", JOptionPane.ERROR_MESSAGE)
                return
            }
            chatArea.append("Вы: $userMessage\n")
            // Выводим сообщение, что идёт генерация кода
            chatArea.append("Генерация кода идет...\n")

            // Загружаем настройки из файла openai.properties
            val props = loadOpenAiProperties()
            if (props == null) {
                JOptionPane.showMessageDialog(mainPanel, "Не удалось загрузить openai.properties из ресурсов.", "Ошибка", JOptionPane.ERROR_MESSAGE)
                return
            }
            val apiKey = props.getProperty("api.key")
            if (apiKey.isNullOrEmpty()) {
                JOptionPane.showMessageDialog(mainPanel, "Свойство api.key не найдено или пустое.", "Ошибка", JOptionPane.ERROR_MESSAGE)
                return
            }
            // Модель можно указать в настройках, по умолчанию используем "gpt-4o-mini-2024-08-06"
            val selectedModel = props.getProperty("model") ?: "gpt-4o-mini-2024-08-06"

            try {
                // Создаем экземпляр генератора и вызываем функцию для генерации ответа
                val generator = OpenAiCodeGenerator(apiKey)
                val codeResponse: CodeResponse = generator.generateCodeResponse(userMessage)
                // Выводим сообщение для пользователя из CodeResponse.user_message вместо полного кода
                chatArea.append("Сообщение: ${codeResponse.user_message}\n\n")
                // Создаем или обновляем файл в проекте
                createOrUpdateFile(project, codeResponse.file_name, codeResponse.code)
                chatArea.append("Файл '${codeResponse.file_name}' успешно создан/обновлён.\n")
                // Выводим список файлов в корневой директории проекта
                val baseDir = project.baseDir
                val psiManager = PsiManager.getInstance(project)
                val psiDirectory = psiManager.findDirectory(baseDir)
                if (psiDirectory != null) {
                    val fileNames = psiDirectory.files.map { it.name }
                    chatArea.append("Список файлов в проекте:\n")
                    fileNames.forEach { chatArea.append("$it\n") }
                }
            } catch (e: Exception) {
                chatArea.append("Ошибка генерации: ${e.message}\n")
            }
            inputField.text = ""
        }

        // Добавляем обработчики событий для кнопки и текстового поля
        generateButton.addActionListener { generateCode() }
        inputField.addActionListener { generateCode() }

        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(mainPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    /**
     * Загружает настройки из файла openai.properties, расположенного в resources.
     */
    private fun loadOpenAiProperties(): Properties? {
        val props = Properties()
        val inputStream = this::class.java.classLoader.getResourceAsStream("openai.properties") ?: return null
        props.load(inputStream)
        return props
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
