plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.21"
    id("org.jetbrains.intellij") version "1.16.1"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.10.0")
    implementation("org.json:json:20230227")
}

intellij {
    // PyCharm Community. Если Gradle не найдёт конкретно "2023.1.5",
    // используйте более раннюю или актуальную версию (например, "2023.2").
    version.set("2023.1.5")
    type.set("PC") // PC = PyCharm Community

    // Список плагинов-зависимостей, если нужны:
    plugins.set(listOf(/* "python" */))
}

tasks {
    // Настраиваем Java и Kotlin под 17 (PyCharm 2023.1.5 поддерживает 17)
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    // Автоматически патчим plugin.xml (если в проекте есть plugin.xml)
    patchPluginXml {
        // Минимальная сборка IDE (для 2023.1 может быть 231.*)
        sinceBuild.set("231")
        // Максимальная сборка
        untilBuild.set("241.*")

        // Подхватим версию из project.version
        version.set("${project.version}")

        // Описание плагина
        pluginDescription.set(
            """
            Пример плагина MaxPilot для PyCharm Community.
            Добавляет окно чата справа, отвечает эхо-сообщениями.
            """.trimIndent()
        )
        // Что нового в этой версии
        changeNotes.set(
            """
            Initial version of the plugin.
            """.trimIndent()
        )
    }

    // Сборка плагина (ZIP) в папку build/distributions
    buildPlugin {
        // Доп. настройки, если нужны
    }

    // Запуск PyCharm в "песочнице" для тестирования плагина
    runIde {
        // Можете явно указать локальную установку PyCharm:
        // ideDir.set(file("/путь/к/pycharm-community-2023.1.5"))
    }

    // Подпись плагина (необязательно, если не публикуете)
    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    // Публикация на Marketplace (необязательно)
    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}
