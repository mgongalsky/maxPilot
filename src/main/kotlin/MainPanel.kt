package com.example.plugin

import com.intellij.ui.components.JBPanel
import java.awt.BorderLayout

// Создаем собственный подкласс, наследуемый от JBPanel, с параметром типа MainPanel
class MainPanel : JBPanel<MainPanel>(BorderLayout())
