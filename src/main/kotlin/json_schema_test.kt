import kotlinx.serialization.Serializable
import com.github.victools.jsonschema.generator.*
import com.fasterxml.jackson.databind.ObjectMapper

@Serializable
data class CodeResponse3(
    val file_name: String,
    val description: String,
    val code: String,
    val user_message: String
)

fun main() {
    val config = SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
        .without(Option.SCHEMA_VERSION_INDICATOR) // Убираем $schema
        .with(Option.DEFINITIONS_FOR_ALL_OBJECTS) // Создаём явные определения для всех объектов
        .build()

    val generator = SchemaGenerator(config)
    val schemaNode = generator.generateSchema(CodeResponse3::class.java)

    // Преобразуем JSON Schema в строку
    val jsonSchema = ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(schemaNode)

    // Добавляем `required` вручную
    val updatedSchema = jsonSchema.replaceFirst("{", """
    {
      "required": ["file_name", "description", "code", "user_message"],
    """.trimIndent())

    println(updatedSchema)
}
