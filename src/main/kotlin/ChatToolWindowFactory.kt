package com.example.plugin

import OpenAiCodeGenerator
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.ui.content.ContentFactory
import com.intellij.lang.Language
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.util.Properties
import javax.swing.*

class ChatToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Панель для вывода диалога (JTextArea) и панели для списка файлов
        val chatArea = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }
        val scrollPane = JScrollPane(chatArea)

        // Панель для вывода списка созданных файлов с кнопками "Открыть"
        val filesPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        filesPanel.border = BorderFactory.createTitledBorder("Созданные файлы")

        // Объединяем чат и панель файлов в центральную панель
        val centerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(scrollPane)
            add(filesPanel)
        }

        // Панель ввода с текстовым полем и кнопкой "Сгенерировать"
        val inputPanel = JPanel(BorderLayout())
        val inputField = JTextField()
        val generateButton = JButton("Сгенерировать")
        inputPanel.add(inputField, BorderLayout.CENTER)
        inputPanel.add(generateButton, BorderLayout.EAST)

        // Основная панель
        val mainPanel = JPanel(BorderLayout())
        mainPanel.add(centerPanel, BorderLayout.CENTER)
        mainPanel.add(inputPanel, BorderLayout.SOUTH)

        // Функция для добавления сообщения в чат (простой append для JTextArea)
        fun appendToChat(message: String) {
            chatArea.append(message + "\n")
        }

        // Функция для получения контекста текущего открытого файла
        fun getCurrentFileContext(project: Project): String? {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
            val document = editor.document
            val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
            val fileName = file?.name ?: "Unknown"
            return "File: $fileName\nContent:\n${document.text}"
        }

        // Функция для обновления панели файлов – добавляет кнопку "Открыть" для нового файла
        fun addFileButton(project: Project, fileName: String) {
            val filePath = "${project.baseDir.path}/$fileName"
            val openButton = JButton("Открыть $fileName")
            openButton.addActionListener {
                val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
                if (virtualFile != null) {
                    FileEditorManager.getInstance(project).openFile(virtualFile, true)
                } else {
                    JOptionPane.showMessageDialog(null, "Не удалось найти файл: $filePath", "Ошибка", JOptionPane.ERROR_MESSAGE)
                }
            }
            filesPanel.add(openButton)
            filesPanel.revalidate()
            filesPanel.repaint()
        }

        // Функция для обработки запроса и генерации кода для нескольких файлов
        fun generateCode() {
            val userMessage = inputField.text.trim()
            if (userMessage.isEmpty()) {
                JOptionPane.showMessageDialog(mainPanel, "Пожалуйста, введите описание программы.", "Ошибка", JOptionPane.ERROR_MESSAGE)
                return
            }
            appendToChat("Вы: $userMessage")
            appendToChat("Генерация кода идет...")

            // Получаем контекст текущего файла (если имеется)
            val context = getCurrentFileContext(project)
            if (context != null) {
                appendToChat("Контекст текущего файла загружен.")
            }

            // Загружаем настройки из openai.properties
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
            val selectedModel = props.getProperty("model") ?: "gpt-4o-mini-2024-08-06"

            try {
                val generator = OpenAiCodeGenerator(apiKey)
                // Передаем контекст, если есть
                val multiResponse = generator.generateCodeResponse(userMessage, context)
                // Для каждого изменения (файла) выводим информацию в чат и добавляем кнопку "Открыть"
                multiResponse.files.forEach { fileChange ->
                    appendToChat("Сообщение: ${fileChange.user_message}")
                    createOrUpdateFile(project, fileChange.file_name, fileChange.code)
                    appendToChat("Файл '${fileChange.file_name}' успешно создан/обновлён по пути: ${project.baseDir.path}")
                    addFileButton(project, fileChange.file_name)
                }
            } catch (e: Exception) {
                appendToChat("Ошибка генерации: ${e.message}")
            }
            inputField.text = ""
        }

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
