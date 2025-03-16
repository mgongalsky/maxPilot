package com.example.plugin

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.ui.content.ContentFactory
import com.intellij.lang.Language
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.util.Properties
import javax.swing.*

// Топ-левел функция для извлечения сигнатуры PSI-элемента
fun extractSignature(element: PsiElement): String {
    val text = element.text.trim()
    return text.substringBefore("(").trim()
}

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

        // Добавление кнопки для открытия файла (отображается только имя файла)
        fun addFileButton(project: Project, fileName: String) {
            val basePath = project.baseDir.path
            val filePath = if (fileName.startsWith(basePath) || fileName.matches(Regex("^[A-Za-z]:.*"))) {
                fileName
            } else {
                "$basePath/$fileName"
            }
            val displayName = fileName.replace("\\", "/").substringAfterLast("/")
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

        // Рекурсивное построение PSI-дерева проекта для файлов .py,
        // исключая системные папки ("venv", ".idea", "build", "out", "dist")
        fun buildProjectTree(project: Project): String {
            val psiManager = PsiManager.getInstance(project)
            val baseDir = project.baseDir
            val structureBuilder = StringBuilder()
            structureBuilder.append("Структура проекта:\n")
            val excludedDirs = setOf("venv", ".idea", "build", "out", "dist")
            fun processDirectory(directory: VirtualFile) {
                for (child in directory.children) {
                    if (child.isDirectory) {
                        if (child.name in excludedDirs) continue
                        processDirectory(child)
                    } else if (child.extension == "py") {
                        structureBuilder.append("Файл: ${child.path}\n")
                        val psiFile = psiManager.findFile(child)
                        if (psiFile != null) {
                            psiFile.children.forEach { element ->
                                val text = element.text.trim()
                                if (text.startsWith("class ") || text.startsWith("def ")) {
                                    val signature = text.substringBefore("(").trim()
                                    structureBuilder.append("    $signature\n")
                                }
                            }
                        }
                    }
                }
            }
            processDirectory(baseDir)
            return structureBuilder.toString()
        }

        // Основная функция: отправка запроса к OpenAI, получение ответа и обновление PSI
        fun generateAndUpdateNodes() {
            val userTask = inputField.text.trim()
            if (userTask.isEmpty()) {
                JOptionPane.showMessageDialog(mainPanel, "Пожалуйста, введите задание.", "Ошибка", JOptionPane.ERROR_MESSAGE)
                return
            }
            appendToChat("Вы: $userTask")
            appendToChat("Анализ PSI-дерева проекта идет...")

            // Шаг 1: Собираем PSI-дерево проекта, ограничиваем длину контекста
            val fullProjectTree = buildProjectTree(project)
            val maxContextLength = 250000
            val projectTree = if (fullProjectTree.length > maxContextLength)
                fullProjectTree.substring(0, maxContextLength)
            else
                fullProjectTree
            println("Полное PSI-дерево проекта:\n$projectTree")

            val props = loadApiProperties()
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
                // Шаг 2: Формируем контекст доработки из PSI-элементов
                val nodesContextBuilder = StringBuilder()
                codeNodesResponse.nodes.forEach { node ->
                    if (nodesContextBuilder.length >= maxContextLength) return@forEach
                    val psiManager = PsiManager.getInstance(project)
                    val virtualFile = LocalFileSystem.getInstance().findFileByPath(node.file)
                    val psiFile = if (virtualFile != null) psiManager.findFile(virtualFile) else null
                    if (psiFile != null) {
                        val expectedSignature = "${node.nodeType.toLowerCase()} ${node.name}"
                        val targetElement = psiFile.children.firstOrNull { extractSignature(it).toLowerCase() == expectedSignature }
                        if (targetElement != null) {
                            nodesContextBuilder.append("File: ${node.file}\n")
                            nodesContextBuilder.append("${node.nodeType}: ${node.name}\n")
                            nodesContextBuilder.append("Content:\n${targetElement.text}\n")
                            nodesContextBuilder.append("-----\n")
                        }
                    }
                }
                val nodesContext = nodesContextBuilder.toString()
                val combinedQuery = "$userTask\n\nИспользуй следующий контекст узлов для доработки кода:"
                val codeGenerator = OpenAiCodeGenerator(apiKey)
                appendToChat("Отправляем запрос на доработку кода с учетом узлов...")
                val multiResponse = codeGenerator.generateCodeResponse(combinedQuery, nodesContext)
                // Обрабатываем каждое изменение согласно update_mode
                multiResponse.files.forEach { fileChange ->
                    appendToChat("Изменения для файла ${fileChange.file_name}: ${fileChange.user_message}")
                    createOrUpdateFile(project, fileChange)
                    appendToChat("Файл '${fileChange.file_name}' успешно обновлён.")
                    addFileButton(project, fileChange.file_name)
                }
            } catch (e: Exception) {
                appendToChat("Ошибка в процессе обновления узлов: ${e.message}")
            }
            inputField.text = ""
        }

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
    private fun loadApiProperties(): Properties? {
        val props = Properties()
        val inputStream = this::class.java.classLoader.getResourceAsStream("openai.properties") ?: return null
        props.load(inputStream)
        return props
    }

    /**
     * Создает или обновляет Python-файл с изменениями, полученными от нейросети.
     * Обновление происходит в зависимости от update_mode:
     * - "update_file": полная замена содержимого файла.
     * - "update_element": поиск PSI-элемента по сигнатуре и его замена.
     * - "create_element": поиск родительского элемента по parent_signature и добавление нового элемента.
     */
    private fun createOrUpdateFile(project: Project, fileChange: FileChange) {
        WriteCommandAction.runWriteCommandAction(project) {
            val psiManager = PsiManager.getInstance(project)
            val pythonLanguage = Language.findLanguageByID("Python") ?: return@runWriteCommandAction
            val targetFileName = if (fileChange.target_file.isNullOrBlank()) fileChange.file_name else fileChange.target_file
            val existingVirtualFile = findVirtualFileByAbsolutePath(project.baseDir, targetFileName)
            if (existingVirtualFile != null) {
                val psiFile = psiManager.findFile(existingVirtualFile)
                if (psiFile != null) {
                    when (fileChange.update_mode?.toLowerCase()) {
                        "update_element" -> {
                            val tempPsiFile = PsiFileFactory.getInstance(project)
                                .createFileFromText("temp.py", pythonLanguage, fileChange.code)
                            val newElement = tempPsiFile.firstChild ?: return@runWriteCommandAction
                            val newSignature = extractSignature(newElement)
                            val targetElement = psiFile.children.firstOrNull { extractSignature(it) == newSignature }
                            if (targetElement != null) {
                                targetElement.replace(newElement)
                            } else if (!fileChange.parent_signature.isNullOrBlank()) {
                                val parentElement = psiFile.children.firstOrNull { extractSignature(it) == fileChange.parent_signature }
                                if (parentElement != null) {
                                    parentElement.add(newElement)
                                } else {
                                    psiFile.viewProvider.document?.setText(psiFile.text + "\n" + fileChange.code)
                                }
                            } else {
                                psiFile.viewProvider.document?.setText(fileChange.code)
                            }
                        }
                        "create_element" -> {
                            val tempPsiFile = PsiFileFactory.getInstance(project)
                                .createFileFromText("temp.py", pythonLanguage, fileChange.code)
                            val newElement = tempPsiFile.firstChild ?: return@runWriteCommandAction
                            if (!fileChange.parent_signature.isNullOrBlank()) {
                                val parentElement = psiFile.children.firstOrNull { extractSignature(it) == fileChange.parent_signature }
                                if (parentElement != null) {
                                    parentElement.add(newElement)
                                } else {
                                    psiFile.viewProvider.document?.setText(psiFile.text + "\n" + fileChange.code)
                                }
                            } else {
                                psiFile.viewProvider.document?.setText(psiFile.text + "\n" + fileChange.code)
                            }
                        }
                        else -> {
                            // "update_file" или update_mode не указан – полная замена файла
                            psiFile.viewProvider.document?.setText(fileChange.code)
                        }
                    }
                    PsiDocumentManager.getInstance(project).commitDocument(psiFile.viewProvider.document)
                }
            } else {
                val basePath = project.baseDir.path
                val relativePath = if (targetFileName.startsWith(basePath)) targetFileName.substring(basePath.length + 1) else targetFileName
                val psiDirectory = PsiManager.getInstance(project).findDirectory(project.baseDir)
                    ?: return@runWriteCommandAction
                val existingFile = psiDirectory.findFile(relativePath)
                if (existingFile != null) {
                    existingFile.viewProvider.document?.setText(fileChange.code)
                    PsiDocumentManager.getInstance(project).commitDocument(existingFile.viewProvider.document)
                } else {
                    val psiFile = PsiFileFactory.getInstance(project)
                        .createFileFromText(relativePath, pythonLanguage, fileChange.code)
                    psiDirectory.add(psiFile)
                }
            }
        }
    }

    // Рекурсивная функция для поиска виртуального файла по абсолютному пути, начиная с указанной директории
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
