package com.example.plugin

import OpenAiCodeGenerator
import OpenAiContextFilter
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
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
        // Панель для истории диалога и панель для файлов
        val chatArea = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }
        val scrollPane = JScrollPane(chatArea)
        val filesPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            border = BorderFactory.createTitledBorder("Созданные файлы")
        }
        val centerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(scrollPane)
            add(filesPanel)
        }

        // Поле ввода и панель кнопок
        val inputField = JTextArea().apply {
            lineWrap = true
            wrapStyleWord = true
            rows = 3
        }
        val inputScrollPane = JScrollPane(inputField)
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        // Одна кнопка объединяет два шага
        val generateButton = JButton("Сгенерировать")
        buttonPanel.add(generateButton)
        val inputPanel = JPanel(BorderLayout())
        inputPanel.add(inputScrollPane, BorderLayout.CENTER)
        inputPanel.add(buttonPanel, BorderLayout.EAST)
        val mainPanel = JPanel(BorderLayout())
        mainPanel.add(centerPanel, BorderLayout.CENTER)
        mainPanel.add(inputPanel, BorderLayout.SOUTH)

        // Функция для добавления сообщений в чат
        fun appendToChat(message: String) {
            chatArea.append(message + "\n")
        }

        // Получение контекста текущего открытого файла (если требуется)
        fun getCurrentFileContext(project: Project): String? {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
            val document = editor.document
            val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
            val fileName = file?.name ?: "Unknown"
            return "File: $fileName\nContent:\n${document.text}"
        }

        // Добавление кнопки для открытия файла
        fun addFileButton(project: Project, fileName: String) {
            val basePath = project.baseDir.path
            // Если fileName уже содержит basePath или начинается с буквы диска (Windows), считаем путь абсолютным
            val filePath = if (fileName.startsWith(basePath) || fileName.matches(Regex("^[A-Za-z]:.*"))) {
                fileName
            } else {
                "$basePath/$fileName"
            }

            // Извлекаем только название файла для отображения на кнопке
            val displayName = fileName
                .replace("\\", "/")      // заменяем обратные слеши на прямые
                .substringAfterLast("/") // берём всё, что после последнего '/'

            val openButton = JButton(displayName)
            openButton.addActionListener {
                val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
                if (virtualFile != null) {
                    FileEditorManager.getInstance(project).openFile(virtualFile, true)
                } else {
                    JOptionPane.showMessageDialog(
                        null,
                        "Не удалось найти файл: $filePath",
                        "Ошибка",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
            filesPanel.add(openButton)
            filesPanel.revalidate()
            filesPanel.repaint()
        }

        // Рекурсивное построение дерева проекта: файлы с расширением .py и их узлы (объявления классов и функций)
        fun buildProjectTree(project: Project): String {
            val psiManager = PsiManager.getInstance(project)
            val baseDir = project.baseDir
            val structureBuilder = StringBuilder()
            structureBuilder.append("Структура проекта:\n")
            fun processDirectory(directory: VirtualFile) {
                for (child in directory.children) {
                    if (child.isDirectory) {
                        processDirectory(child)
                    } else if (child.extension == "py") {
                        val psiFile = psiManager.findFile(child)
                        if (psiFile != null) {
                            structureBuilder.append("Файл: ${child.path}\n")
                            psiFile.children.forEach { element ->
                                val text = element.text.trim()
                                if (text.startsWith("class ")) {
                                    val name = text.substringAfter("class ").substringBefore("(").substringBefore(" ")
                                    structureBuilder.append("    Класс: $name\n")
                                } else if (text.startsWith("def ")) {
                                    val name = text.substringAfter("def ").substringBefore("(").substringBefore(" ")
                                    structureBuilder.append("    Функция: $name\n")
                                }
                            }
                        }
                    }
                }
            }
            processDirectory(baseDir)
            return structureBuilder.toString()
        }

        // Простейший метод для извлечения содержимого узла (объявления и несколько следующих строк)
        fun extractNodeContent(project: Project, filePath: String, nodeName: String, nodeType: String): String {
            val psiManager = PsiManager.getInstance(project)
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)
                ?: return "Файл не найден: $filePath"
            val psiFile = psiManager.findFile(virtualFile)
                ?: return "PSI файл не найден для: $filePath"
            val lines = psiFile.text.lines()
            val resultLines = mutableListOf<String>()
            val prefix = if (nodeType.equals("Класс", ignoreCase = true)) "class $nodeName" else "def $nodeName"
            for (i in lines.indices) {
                if (lines[i].trim().startsWith(prefix)) {
                    // Возьмем найденную строку и следующие 5 строк (если есть)
                    for (j in i until minOf(i + 6, lines.size)) {
                        resultLines.add(lines[j])
                    }
                    break
                }
            }
            return if (resultLines.isNotEmpty()) resultLines.joinToString("\n")
            else "Не найден узел $nodeName в файле $filePath"
        }

        // Основная функция, объединяющая два шага:
        // 1. Отправляем задание и дерево узлов в OpenAiContextFilter для получения релевантных узлов.
        // 2. На основе содержимого этих узлов формируем новый контекст и отправляем задание в OpenAiCodeGenerator для доработки.
        fun generateAndUpdateNodes() {
            val userTask = inputField.text.trim()
            if (userTask.isEmpty()) {
                JOptionPane.showMessageDialog(
                    mainPanel,
                    "Пожалуйста, введите задание.",
                    "Ошибка",
                    JOptionPane.ERROR_MESSAGE
                )
                return
            }
            appendToChat("Вы: $userTask")
            appendToChat("Анализ узлов проекта идет...")

            // Шаг 1: Собираем дерево проекта и отправляем задание в OpenAiContextFilter
            val projectTree = buildProjectTree(project)
            println("Полное PSI-дерево проекта:\n$projectTree")
            val props = loadOpenAiProperties()
            if (props == null) {
                appendToChat("Не удалось загрузить openai.properties из ресурсов.")
                return
            }
            val apiKey = props.getProperty("api.key")
            if (apiKey.isNullOrEmpty()) {
                appendToChat("Свойство api.key не найдено или пустое.")
                return
            }
            val contextFilter = OpenAiContextFilter(apiKey)
            try {
                val codeNodesResponse = contextFilter.filterNodes(userTask, projectTree)
                appendToChat("Релевантные узлы для задачи '$userTask':")
                codeNodesResponse.nodes.forEach { node ->
                    appendToChat("Файл: ${node.file}, ${node.nodeType}: ${node.name} (${node.description})")
                }
                // Шаг 2: Извлекаем содержимое каждого узла для формирования контекста доработки
                val nodesContextBuilder = StringBuilder()
                codeNodesResponse.nodes.forEach { node ->
                    val nodeContent = extractNodeContent(project, node.file, node.name, node.nodeType)
                    nodesContextBuilder.append("File: ${node.file}\n")
                    nodesContextBuilder.append("${node.nodeType}: ${node.name}\n")
                    nodesContextBuilder.append("Content:\n$nodeContent\n")
                    nodesContextBuilder.append("-----\n")
                }
                val nodesContext = nodesContextBuilder.toString()
                // Отправляем задание с контекстом узлов в OpenAiCodeGenerator
                val combinedQuery = "$userTask\n\nИспользуй следующий контекст узлов для доработки кода:"
                val codeGenerator = OpenAiCodeGenerator(apiKey)
                appendToChat("Отправляем запрос на доработку кода с учетом узлов...")
                val multiResponse = codeGenerator.generateCodeResponse(combinedQuery, nodesContext)
                // Обновляем файлы согласно полученным изменениям
                multiResponse.files.forEach { fileChange ->
                    appendToChat("Изменения для файла ${fileChange.file_name}: ${fileChange.user_message}")
                    createOrUpdateFile(project, fileChange.file_name, fileChange.code)
                    appendToChat("Файл '${fileChange.file_name}' успешно обновлён.")
                    addFileButton(project, fileChange.file_name)
                }
            } catch (e: Exception) {
                appendToChat("Ошибка в процессе обновления узлов: ${e.message}")
            }
            inputField.text = ""
        }

        // Привязываем действие кнопки "Сгенерировать" к объединенной функции
        generateButton.addActionListener { generateAndUpdateNodes() }
        inputField.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyReleased(e: java.awt.event.KeyEvent?) {
                if (e?.keyCode == java.awt.event.KeyEvent.VK_ENTER) {
                    generateAndUpdateNodes()
                }
            }
        })

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
            val psiManager = PsiManager.getInstance(project)
            // Пытаемся найти файл по абсолютному пути рекурсивно начиная от project.baseDir
            val existingVirtualFile = findVirtualFileByAbsolutePath(project.baseDir, fileName)
            if (existingVirtualFile != null) {
                val psiFile = psiManager.findFile(existingVirtualFile)
                if (psiFile != null) {
                    val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                    if (document != null) {
                        document.setText(code)
                        PsiDocumentManager.getInstance(project).commitDocument(document)
                    }
                }
            } else {
                // Если файл не найден, пытаемся создать его.
                // Если fileName начинается с базового пути, извлекаем относительный путь.
                val basePath = project.baseDir.path
                val relativePath =
                    if (fileName.startsWith(basePath)) fileName.substring(basePath.length + 1) else fileName
                // Если относительный путь содержит поддиректории, можно добавить их создание.
                val psiDirectory = PsiManager.getInstance(project).findDirectory(project.baseDir)
                    ?: return@runWriteCommandAction
                val pythonLanguage = Language.findLanguageByID("Python") ?: return@runWriteCommandAction
                val psiFile = PsiFileFactory.getInstance(project)
                    .createFileFromText(relativePath, pythonLanguage, code)
                psiDirectory.add(psiFile)
            }
        }
    }

    // Рекурсивная функция для поиска виртуального файла по абсолютному пути, начиная с данной директории
    private fun findVirtualFileByAbsolutePath(directory: VirtualFile, targetPath: String): VirtualFile? {
        for (child in directory.children) {
            if (child.isDirectory) {
                val found = findVirtualFileByAbsolutePath(child, targetPath)
                if (found != null) return found
            } else if (child.path == targetPath) {
                return child
            }
        }
        return null
    }
}
